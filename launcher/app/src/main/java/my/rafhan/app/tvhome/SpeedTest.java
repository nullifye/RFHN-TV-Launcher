package my.rafhan.app.tvhome;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Settings → Speed test: measures download speed and latency with plain HTTP, so it needs no USB
 * debugging. The source is chosen in Settings:
 * <ul>
 *   <li>Cloudflare — the nearest Cloudflare edge (usually Kuala Lumpur).</li>
 *   <li>Unifi Malaysia — TM's own Klang speedtest server, over HTTPS on 8080.</li>
 *   <li>Netflix — fast.com's servers, which on a Malaysian line sit inside the ISP (a Netflix
 *       cache), so it reflects streaming speed.</li>
 * </ul>
 * It downloads over a few connections for ~10 s, reporting the running speed; latency is the
 * quickest of a few tiny requests. Only byte counts leave the TV. Callbacks come on the main thread.
 */
final class SpeedTest {
    enum Source {
        CLOUDFLARE("Cloudflare"), NETFLIX("Netflix"), UNIFI("Unifi Malaysia");
        final String label;

        Source(String label) {
            this.label = label;
        }

        static Source of(int i) {
            Source[] v = values();
            return i >= 0 && i < v.length ? v[i] : CLOUDFLARE;
        }
    }

    interface Listener {
        /** Latency measured (ms), before the download phase begins. */
        void onLatency(int ms);

        /** The download speed so far, Mbps, a few times a second. */
        void onProgress(double mbps);

        /** The run finished: its download speed (Mbps) and latency (ms). */
        void onResult(double downMbps, int pingMs);

        /** The run couldn't finish. */
        void onError(String message);
    }

    private static final String CF = "https://speed.cloudflare.com/__down?bytes=";
    /** Unifi's own Ookla server list; the nearest (smallest distance) is used. */
    private static final String TM_SERVERS = "https://unifi-my.speedtestcustom.com/api/js/servers?engine=js";
    private static final String TM_FALLBACK = "speedtest-klg.tm.com.my:8080"; // if the list can't be fetched
    private static final String FAST = "https://api.fast.com/netflix/speedtest/v2?https=true&urlCount=5"
            + "&token=YXNkZmFzZGxmbnNkYWZoYXNkZmhrYWxm"; // fast.com's long-standing public token
    private static final int STREAMS = 3, TIMEOUT_MS = 15_000;
    private static final long DOWN_MS = 10_000, WARMUP_MS = 1_500, CF_BYTES = 50_000_000; // Cloudflare caps under 1e8

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    void cancel() {
        cancelled = true;
    }

    void start(Source src, Listener l) {
        Thread t = new Thread(() -> runAll(src, l), "speedtest");
        t.setDaemon(true);
        t.start();
    }

    private void runAll(Source src, Listener l) {
        try {
            String pingUrl;
            String[] down;
            long nc = System.currentTimeMillis();
            switch (src) {
                case UNIFI:
                    String host = nearestTmHost();
                    pingUrl = "https://" + host + "/hello";
                    down = new String[] {"https://" + host + "/download?nocache=" + nc + "&size=50000000"};
                    break;
                case NETFLIX:
                    down = netflixTargets();
                    pingUrl = down[0];
                    break;
                case CLOUDFLARE:
                default:
                    pingUrl = CF + "0";
                    down = new String[] {CF + CF_BYTES};
            }
            int ping = measurePing(pingUrl);
            if (cancelled) return;
            main.post(() -> l.onLatency(ping));
            double mbps = measureDown(down, l);
            if (cancelled) return;
            if (mbps <= 0) {
                error(l, "No data from the test server");
                return;
            }
            main.post(() -> l.onResult(mbps, ping));
        } catch (Exception e) {
            error(l, "Couldn't reach the test server");
        }
    }

    private void error(Listener l, String msg) {
        if (!cancelled) main.post(() -> l.onError(msg));
    }

    /** fast.com's measurement URLs (Netflix servers, inside the ISP on a Malaysian line). */
    private String[] netflixTargets() throws Exception {
        JSONArray t = new JSONObject(readBody(open(FAST))).getJSONArray("targets");
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < t.length(); i++) urls.add(t.getJSONObject(i).getString("url"));
        if (urls.isEmpty()) throw new java.io.IOException("no targets");
        return urls.toArray(new String[0]);
    }

    /** The nearest TM server's host:port from Unifi's list (smallest distance), else the Klang one. */
    private String nearestTmHost() {
        try {
            JSONArray a = new JSONArray(readBody(open(TM_SERVERS)));
            String best = null;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.getJSONObject(i);
                String h = s.optString("host", "");
                double d = s.optDouble("distance", Double.MAX_VALUE);
                if (!h.isEmpty() && d < bestD) {
                    bestD = d;
                    best = h;
                }
            }
            if (best != null) return best;
        } catch (Exception ignored) {
        }
        return TM_FALLBACK;
    }

    /** Reads a small response body (capped at 1 MB) as a UTF-8 string, then disconnects. */
    private String readBody(HttpURLConnection c) throws Exception {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0 && b.size() < 1_000_000; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** The quickest of a few tiny round-trips, in ms (the first, cold, one is dropped). */
    private int measurePing(String url) throws Exception {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5 && !cancelled; i++) {
            long t0 = System.nanoTime();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setUseCaches(false);
            c.setRequestProperty("Range", "bytes=0-1"); // keep the body tiny on a big target
            try {
                int code = c.getResponseCode();
                if (code != 200 && code != 206) throw new java.io.IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    byte[] b = new byte[64];
                    while (in.read(b) > 0) { /* drain the little there is */ }
                }
            } finally {
                c.disconnect();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (i > 0) best = Math.min(best, ms);
        }
        return best == Long.MAX_VALUE ? 0 : (int) best;
    }

    /** Downloads over several connections for DOWN_MS; returns Mbps measured after a short warm-up. */
    private double measureDown(String[] urls, Listener l) throws Exception {
        AtomicLong bytes = new AtomicLong();
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < STREAMS; i++) {
            String url = urls[i % urls.length];
            Thread t = new Thread(() -> stream(url, bytes, stop), "speedtest-" + i);
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        long t0 = System.nanoTime(), markNs = 0, markBytes = 0;
        while (!cancelled) {
            Thread.sleep(250);
            long elapsed = System.nanoTime() - t0, b = bytes.get();
            if (markNs == 0 && elapsed >= WARMUP_MS * 1_000_000L) {
                markNs = elapsed;
                markBytes = b;
            }
            double mbps = b * 8.0 / 1e6 / (elapsed / 1e9);
            main.post(() -> l.onProgress(mbps));
            if (elapsed >= DOWN_MS * 1_000_000L) break;
        }
        stop.set(true);
        for (Thread t : threads) t.join(500);
        long elapsed = System.nanoTime() - t0, b = bytes.get();
        if (markNs > 0 && elapsed > markNs) return (b - markBytes) * 8.0 / 1e6 / ((elapsed - markNs) / 1e9);
        return elapsed > 0 ? b * 8.0 / 1e6 / (elapsed / 1e9) : 0;
    }

    /** Pulls bytes and discards them, reconnecting if a download ends before time's up. */
    private void stream(String url, AtomicLong bytes, AtomicBoolean stop) {
        while (!stop.get() && !cancelled) {
            HttpURLConnection c = null;
            try {
                c = open(url);
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[32 * 1024];
                    for (int n; !stop.get() && !cancelled && (n = in.read(buf)) > 0; ) bytes.addAndGet(n);
                }
            } catch (Exception ignored) {
                return; // a broken stream: the others carry the measurement
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setUseCaches(false);
        int code = c.getResponseCode();
        if (code != 200 && code != 206) throw new java.io.IOException("HTTP " + code);
        return c;
    }
}
