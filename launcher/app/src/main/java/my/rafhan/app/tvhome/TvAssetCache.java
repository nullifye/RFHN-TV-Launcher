package my.rafhan.app.tvhome;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Live TV's own copy of RTMKlik's player scripts. WebView's HTTP cache didn't keep them (the
 * server revalidates them cheaply, yet every RTMKlik page load downloaded the 1.3 MB player in
 * full; most likely the live video's segments, which share that small cache, push it out).
 * Served from here, the page skips that download.
 *
 * The player's file name carries its content hash (a new version comes under a new name), so its
 * copy is used while that name is; the player UI's name isn't versioned, so its copy is fetched
 * again after a day. Only these scripts are kept; never pages, APIs, licences or streams.
 * Called on WebView's network threads, where blocking is expected.
 */
final class TvAssetCache {
    private TvAssetCache() {}

    static final long FOREVER = Long.MAX_VALUE;
    static final long UNVERSIONED_MS = 24 * 60 * 60_000L;
    // A copy not fetched again in this long is deleted (a versioned one is fetched again if still used).
    static final long PRUNE_MS = 14 * 24 * 60 * 60_000L;
    private static final long MAX_BYTES = 16 * 1024 * 1024;
    private static final Pattern HASHED_PLAYER =
            Pattern.compile("/vendor/glueplayer/[A-Za-z0-9._-]+-[0-9a-f]{8,}\\.js");
    private static final Pattern PLAYER_UI = Pattern.compile("/[A-Za-z0-9_-]+/bitmovinplayer-ui\\.js");
    // Request headers passed on, so the CDN sees the same browser asking.
    private static final String[] FORWARD = {"user-agent", "referer", "accept", "accept-language"};

    /** How long a copy of this script may be used: 0 if it isn't kept, {@link #FOREVER} while its name stays. */
    static long keepFor(String host, String path, String query) {
        if (host == null || path == null || query != null) return 0;
        switch (host.toLowerCase(Locale.ROOT)) {
            case "rtmklik.rtm.gov.my":
                return HASHED_PLAYER.matcher(path).matches() ? FOREVER : 0;
            case "rtmklik-merdeka360.s3.ap-southeast-1.amazonaws.com":
                return PLAYER_UI.matcher(path).matches() ? UNVERSIONED_MS : 0;
            default:
                return 0;
        }
    }

    /** True while a copy fetched at {@code fetchedAt} may still be used. */
    static boolean fresh(long keep, long fetchedAt, long now) {
        return keep == FOREVER || (fetchedAt <= now && now - fetchedAt < keep);
    }

    /** The cache file's name for {@code url}: its SHA-256, so any URL makes a safe, fixed-length name. */
    static String fileName(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(d.length * 2 + 3);
            for (byte x : d) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.append(".js").toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The script from the app's copy (fetched first if there's none, or it's too old), or null to
     * let WebView load it as usual: not a kept script, or the fetch failed.
     */
    static WebResourceResponse serve(File dir, Uri u, Map<String, String> requestHeaders, String userAgent) {
        long keep = keepFor(u.getHost(), u.getPath(), u.getQuery());
        if (keep == 0) return null;
        String url = u.toString();
        File f = new File(dir, fileName(url));
        long now = System.currentTimeMillis();
        try {
            if (!f.isFile() || !fresh(keep, f.lastModified(), now)) {
                if (!fetch(url, f, requestHeaders, userAgent)) return null;
                prune(dir, f, now);
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*"); // public scripts, possibly loaded with CORS
            return new WebResourceResponse("application/javascript", "utf-8", 200, "OK", headers,
                    new FileInputStream(f));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean fetch(String url, File to, Map<String, String> requestHeaders, String userAgent)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(15_000);
            boolean ua = false;
            for (Map.Entry<String, String> h : (requestHeaders != null ? requestHeaders
                    : Collections.<String, String>emptyMap()).entrySet()) {
                String name = h.getKey() == null ? "" : h.getKey().toLowerCase(Locale.ROOT);
                for (String keep : FORWARD) {
                    if (keep.equals(name) && h.getValue() != null) {
                        c.setRequestProperty(h.getKey(), h.getValue());
                        ua |= name.equals("user-agent");
                    }
                }
            }
            if (!ua && userAgent != null) c.setRequestProperty("User-Agent", userAgent);
            if (c.getResponseCode() != 200) return false;
            String type = c.getContentType();
            if (type == null || !type.toLowerCase(Locale.ROOT).contains("javascript")) return false;
            File dir = to.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
            File tmp = File.createTempFile(to.getName(), ".part", dir);
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("too large");
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw e;
            }
            if (total == 0 || !tmp.renameTo(to)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            return true;
        } finally {
            c.disconnect();
        }
    }

    /** Deletes copies not fetched in {@link #PRUNE_MS}, and leftovers of interrupted fetches. */
    private static void prune(File dir, File keep, long now) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.equals(keep)) continue;
            boolean partial = f.getName().contains(".part") && now - f.lastModified() > 60_000;
            if (partial || now - f.lastModified() > PRUNE_MS) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
