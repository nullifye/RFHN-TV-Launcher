package my.rafhan.app.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Net traffic monitor's native engine (libzdtun, the forwarding library, and libtraffic, its
 * bridge), kept out of the APK so TV Home stays small. The build records each file's SHA-256 in
 * assets/netengine.txt; the first time the monitor starts, the files for this TV's CPU are taken
 * from where build-and-install.sh pushed them (a local build) or downloaded from this version's
 * GitHub release, checked against those fingerprints, kept in files/netengine/ and loaded. A file
 * that doesn't match is never loaded. Blocking (network): call off the main thread.
 */
final class TrafficEngine {
    static final String[] LIBS = {"libzdtun.so", "libtraffic.so"}; // zdtun first: traffic links to it
    private static final int MAX_BYTES = 1 << 20;
    private static volatile boolean loaded;

    private TrafficEngine() {}

    static boolean loaded() {
        return loaded;
    }

    /** Makes sure the engine is here and checked, then loads it. Throws with a reason to show. */
    // Loading from a file is the point: every file is checked against the APK's fingerprints first.
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    static synchronized void load(Context ctx) throws IOException {
        if (loaded) return;
        Map<String, String> pins = pins(read(ctx.getAssets().open("netengine.txt")));
        String abi = abi(pins);
        if (abi == null) throw new IOException("no engine for this TV's processor");
        File dir = new File(ctx.getFilesDir(), "netengine/" + abi);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create the engine folder");
        for (String lib : LIBS) {
            File f = new File(dir, lib);
            String want = pins.get(abi + " " + lib);
            if (f.isFile() && want.equals(sha256(Files.readAllBytes(f.toPath())))) continue;
            byte[] got = pushed(ctx, abi, lib, want);
            if (got == null) {
                TrafficMonitor.status = "Downloading the traffic engine…";
                got = download(ctx, abi, lib);
                if (!want.equals(sha256(got))) throw new IOException("the downloaded engine didn't match; not loaded");
            }
            File tmp = new File(dir, lib + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(got);
            }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("can't save the engine");
        }
        for (String lib : LIBS) {
            File f = new File(dir, lib);
            //noinspection ResultOfMethodCallIgnored
            f.setReadOnly(); // never changed in place once checked
            System.load(f.getAbsolutePath());
        }
        loaded = true;
    }

    /** "<abi> <lib>" → sha256, from netengine.txt's "<abi> <lib> <sha256>" lines. */
    static Map<String, String> pins(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length == 3 && p[2].matches("[0-9a-f]{64}")) out.put(p[0] + " " + p[1], p[2]);
        }
        return out;
    }

    /** The first CPU type this TV runs that has every engine file pinned, or null. */
    private static String abi(Map<String, String> pins) {
        for (String abi : Build.SUPPORTED_ABIS) {
            boolean all = true;
            for (String lib : LIBS) all &= pins.containsKey(abi + " " + lib);
            if (all) return abi;
        }
        return null;
    }

    /** A local build's engine, pushed by build-and-install.sh next to the app; only if it matches. */
    private static byte[] pushed(Context ctx, String abi, String lib, String want) {
        File ext = ctx.getExternalFilesDir(null);
        File f = ext == null ? null : new File(ext, "netengine/netengine-" + abi + "-" + lib);
        try {
            if (f == null || !f.isFile() || f.length() > MAX_BYTES) return null;
            byte[] b = Files.readAllBytes(f.toPath());
            return want.equals(sha256(b)) ? b : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    /** This version's release asset: netengine-<abi>-<lib>. */
    private static byte[] download(Context ctx, String abi, String lib) throws IOException {
        String version = UpdateCheck.installedVersion(ctx);
        String url = "https://github.com/" + UpdateCheck.REPO + "/releases/download/v" + version
                + "/netengine-" + abi + "-" + lib;
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", Wallpapers.UA);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("engine download HTTP " + code
                    + (code == 404 ? " (this version's release doesn't have it)" : ""));
            return read(c.getInputStream()).getBytes(StandardCharsets.ISO_8859_1);
        } finally {
            c.disconnect();
        }
    }

    /** Bytes as a Latin-1 string (one char a byte), at most MAX_BYTES. */
    private static String read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16 * 1024];
            for (int n; (n = s.read(b)) > 0; ) {
                buf.write(b, 0, n);
                if (buf.size() > MAX_BYTES) throw new IOException("engine file too large");
            }
            return buf.toString("ISO-8859-1");
        }
    }

    static String sha256(byte[] b) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format(Locale.ROOT, "%02x", x));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
