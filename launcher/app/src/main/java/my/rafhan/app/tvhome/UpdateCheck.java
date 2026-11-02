package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Is there a newer TV Home on GitHub? Reads the repository's latest release (drafts and
 * pre-releases are never "latest") and its tvhome-<version>.apk. Releases are signed with the
 * same key as the installed app, so UpdateActivity can install one over it, settings kept.
 * Debug builds don't look: they're signed with another key and couldn't take the update.
 */
final class UpdateCheck {
    static final String REPO = "nullifye/RFHN-TV-Launcher";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** A release newer than the installed app. */
    static final class Release {
        final String version, url, sha256; // sha256: null when GitHub doesn't give one
        final long size;

        Release(String version, String url, long size, String sha256) {
            this.version = version;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    private UpdateCheck() {}

    // The last check's answer, whichever ran it (the bell's 6-hourly one or Settings → About), so
    // About shows it. In memory only: a launcher restart forgets it until the next check.
    private static volatile Release lastFound;
    private static volatile long lastAt;
    private static volatile boolean lastFailed;

    /** The newer release the last check found, or null. */
    static Release lastFound() {
        return lastFound;
    }

    /** When the last check finished (epoch ms), or 0 if none has since the launcher started. */
    static long lastCheckedAt() {
        return lastAt;
    }

    /** The last check couldn't reach GitHub. */
    static boolean lastFailed() {
        return lastFailed;
    }

    /** The newer release, or null (up to date, offline, a debug build, or no APK in it). Blocks. */
    static Release newer(Context ctx) {
        try {
            return check(ctx);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Settings → About → Check for updates: the newer release, or null when this is the latest;
     * throws when GitHub can't be reached (or for a debug build, which couldn't take it). Blocks.
     */
    static Release check(Context ctx) throws Exception {
        try {
            Release r = fetch(ctx);
            lastFound = r;
            lastFailed = false;
            return r;
        } catch (Exception e) {
            lastFailed = true; // the last one found (if any) stays: it's still out there
            throw e;
        } finally {
            lastAt = System.currentTimeMillis();
        }
    }

    private static Release fetch(Context ctx) throws Exception {
        if ((ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            throw new IllegalStateException("a debug build can't take updates");
        }
        String current = installedVersion(ctx);
        if (current == null) throw new IllegalStateException("no version");
        JSONObject r = new JSONObject(get(LATEST));
        String version = r.optString("tag_name").replaceFirst("^[vV]", "");
        if (version.isEmpty()) throw new IllegalStateException("no release");
        if (compare(version, current) <= 0) return null;
        JSONArray assets = r.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String name = a.optString("name");
            if (!name.startsWith("tvhome-") || !name.endsWith(".apk")) continue;
            String digest = a.optString("digest", "");
            String sha = digest.startsWith("sha256:") ? digest.substring(7).toLowerCase(java.util.Locale.ROOT) : null;
            return new Release(version, a.optString("browser_download_url"), a.optLong("size"), sha);
        }
        return null; // a newer release without its APK yet (CI still building): nothing to install
    }

    static String installedVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Dotted versions compared number by number: 1.10.0 > 1.9.1, 1.10.1 > 1.10. */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "tvhome");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                for (int r; (r = in.read(buf)) > 0; ) {
                    out.write(buf, 0, r);
                    if (out.size() > 2_000_000) throw new IllegalStateException("response too large");
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }
}
