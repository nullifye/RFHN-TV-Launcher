package my.rafhan.app.tvhome;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The phone remote's HTTP server: socket lifecycle, request parsing, the security gates (IP-only
 * Host, no cross-site requests, a per-phone rate limit) and dispatch to {@link RemoteApi}. The
 * routes and their actions live in {@link RemoteApi}; pairing state in {@link RemotePairing}.
 *
 * No ADB: D-pad keys only reach the launcher (RemoteBridge), Back/Home go through
 * RemoteAccessibilityService, media keys and volume through AudioManager, apps and inputs are
 * plain intents (SYSTEM_ALERT_WINDOW exempts us from Android 10's background-start block).
 *
 * Requests whose Host isn't an IP address are refused (DNS rebinding), and cross-site requests are
 * refused too, so a web page can't drive the remote.
 */
final class RemoteServer {
    static final int PORT = 8486;
    private static final int MAX_BODY = 4096;
    private static final String IP_HOST = "(\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9a-fA-F:]+\\])(:\\d+)?";
    /** Very generous per-phone fixed window, so a flood is refused but a screenful of banner
     *  images isn't. */
    private static final int RATE_MAX = 600;
    private static final long RATE_WINDOW_MS = 10_000;
    private static final String TAG = "TVHomeRemote";

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    // Uploads, downloads and screenshots run here, so a slow transfer can't hold up key presses.
    private final ExecutorService ioPool = Executors.newFixedThreadPool(2);
    private final RemoteApi api;
    // Per-phone request counts (fixed window), guarded by this.
    private final Map<String, long[]> rates = new HashMap<>();
    private volatile boolean running;
    private volatile ServerSocket socket;

    RemoteServer(Context ctx, SleepTimer sleepTimer) {
        api = new RemoteApi(ctx, sleepTimer);
    }

    void start() {
        running = true;
        Thread t = new Thread(this::acceptLoop, "remote-http");
        t.setDaemon(true);
        t.start();
    }

    /** Drops cached app banners and the app list (rebuilt on the next phone request). */
    void trim() {
        api.trim();
    }

    void stop() {
        running = false;
        ServerSocket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        pool.shutdownNow();
        ioPool.shutdownNow();
        api.hide();
    }

    private void acceptLoop() {
        while (running) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                socket = s;
                while (running) {
                    Socket c = s.accept();
                    pool.execute(() -> handle(c));
                }
            } catch (IOException e) {
                if (running) SystemClock.sleep(5000); // port busy or network change: retry
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(Socket c) {
        InputStream in;
        OutputStream out;
        try {
            c.setSoTimeout(10_000);
            in = new BufferedInputStream(c.getInputStream());
            out = c.getOutputStream();
        } catch (IOException e) {
            Http.closeQuietly(c);
            return;
        }

        String summary = null;
        try {
            String requestLine = Http.readLine(in);
            if (requestLine == null) {
                c.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                c.close();
                return;
            }
            String method = parts[0], target = parts[1];
            int q = target.indexOf('?');
            String path = q < 0 ? target : target.substring(0, q);
            summary = method + " " + path; // for the failure log; no query, so no token

            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < 64; i++) {
                String line = Http.readLine(in);
                if (line == null || line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            String host = headers.get("host");
            if (host == null || !host.matches(IP_HOST)) {
                Http.json(out, 403, Http.err("Open the remote with the TV's IP address."));
                c.close();
                return;
            }
            // A web page on another site can't drive the remote: its requests either don't carry the
            // cookie (SameSite=Strict) or are flagged here. The IP-only Host check above already
            // stops DNS rebinding.
            String origin = headers.get("origin");
            if ("cross-site".equals(headers.get("sec-fetch-site"))
                    || (origin != null && !origin.matches("(?i)https?://" + IP_HOST))) {
                Http.json(out, 403, Http.err("Cross-site requests aren't allowed."));
                c.close();
                return;
            }
            String clientIp = c.getInetAddress().getHostAddress();
            if (!allow(clientIp)) {
                Http.json(out, 429, Http.err("Too many requests from this device. Try again in a moment."));
                c.close();
                return;
            }
            long length = headers.containsKey("content-length") ? -1 : 0;
            try {
                length = Long.parseLong(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            Map<String, String> query = Http.parseQuery(q < 0 ? "" : target.substring(q + 1));

            // Uploads are the one route with a big body: stream it straight to disk, on the IO pool
            // so a slow transfer can't block key presses.
            if (path.equals("/api/files/upload")) {
                if (!api.authorised(RemoteApi.requestToken(headers))) {
                    Http.json(out, 401, Http.err("Not paired."));
                    c.close();
                    return;
                }
                if (!Http.post(method, out)) {
                    c.close();
                    return;
                }
                c.setSoTimeout(30_000);
                final long size = length;
                final String dir = query.get("dir"), name = query.get("name");
                offload(c, () -> Http.json(out, api.upload(dir, name, size, in)));
                return;
            }

            if (length < 0 || length > MAX_BODY) {
                Http.json(out, 413, Http.err("Request too large."));
                c.close();
                return;
            }
            byte[] body = new byte[(int) length];
            for (int read = 0; read < length; ) {
                int n = in.read(body, read, (int) length - read);
                if (n < 0) break;
                read += n;
            }
            // Downloads and screenshots are slow too: same separate pool.
            if (path.equals("/api/files/raw") || path.equals("/api/screenshot")) {
                final String m = method, p = path, ip = clientIp;
                final Map<String, String> h = headers, qy = query;
                final byte[] b = body;
                offload(c, () -> api.route(m, p, qy, h, b, out, ip));
                return;
            }
            api.route(method, path, query, headers, body, out, clientIp);
            c.close();
        } catch (IOException | RuntimeException | JSONException e) {
            // Swallowing this hid every remote bug. Log one line (path, not token) and answer 500,
            // instead of leaving the phone to time out.
            Log.w(TAG, summary + " failed: " + e);
            try {
                Http.json(out, 500, Http.err("The TV couldn't do that."));
            } catch (IOException ignored) {
            }
            Http.closeQuietly(c);
        }
    }

    /** Runs a slow transfer on the IO pool, then closes the socket. */
    private void offload(Socket c, IoBody task) {
        ioPool.execute(() -> {
            try {
                task.run();
            } catch (IOException | RuntimeException | JSONException e) {
                // A partial body may already be on the wire, so only log it.
                Log.w(TAG, "transfer failed: " + e);
            } finally {
                Http.closeQuietly(c);
            }
        });
    }

    private interface IoBody {
        void run() throws IOException, JSONException;
    }

    /** False once a single phone has sent more than RATE_MAX requests in the window. */
    private synchronized boolean allow(String ip) {
        long now = SystemClock.elapsedRealtime();
        if (rates.size() > 32) {
            rates.entrySet().removeIf(e -> now - e.getValue()[0] > RATE_WINDOW_MS);
        }
        long[] w = rates.get(ip);
        if (w == null || now - w[0] > RATE_WINDOW_MS) {
            rates.put(ip, new long[] {now, 1});
            return true;
        }
        return ++w[1] <= RATE_MAX;
    }

    // ---------------------------------------------------------------- helpers

    /** e.g. http://192.168.0.44:8486, for the PIN card. */
    static String lanUrl(Context ctx) {
        ConnectivityManager cm = ctx.getSystemService(ConnectivityManager.class);
        Network net = NetState.physical(cm); // the address phones can reach, not a VPN's
        LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address && !la.getAddress().isLoopbackAddress()) {
                    return "http://" + la.getAddress().getHostAddress() + ":" + PORT;
                }
            }
        }
        return "port " + PORT;
    }

    /** Settings → Forget all paired phones: every phone has to pair with a PIN again. */
    static void forgetAll(Context ctx) {
        new RemoteDevices(ctx).removeAll();
    }
}
