package my.rafhan.app.tvhome;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings → Speed test: the last few results, newest first, kept on the TV so runs can be
 * compared. One small JSON file in files/; the list and its JSON are pure (no Android) so they
 * unit-test without a device.
 */
final class SpeedHistory {
    static final int MAX = 10;
    private static final String FILE = "speedtest.json";

    /** One run: when, the source, download speed, latency and the network it ran on. */
    static final class Result {
        final long timeMs;
        final String source;    // "Cloudflare", "Unifi Malaysia", "Netflix"
        final double downMbps;
        final int pingMs;
        final boolean ethernet;
        final String netName;   // SSID, "Ethernet", or ""

        Result(long timeMs, String source, double downMbps, int pingMs, boolean ethernet, String netName) {
            this.timeMs = timeMs;
            this.source = source == null ? "" : source;
            this.downMbps = downMbps;
            this.pingMs = pingMs;
            this.ethernet = ethernet;
            this.netName = netName == null ? "" : netName;
        }
    }

    private SpeedHistory() {}

    /** Newest first, capped at {@code max} (drops the oldest). */
    static void add(List<Result> list, Result r, int max) {
        list.add(0, r);
        while (list.size() > max) list.remove(list.size() - 1);
    }

    static JSONArray toJson(List<Result> list) {
        JSONArray a = new JSONArray();
        for (Result r : list) {
            try {
                a.put(new JSONObject().put("t", r.timeMs).put("src", r.source).put("down", r.downMbps)
                        .put("ping", r.pingMs).put("eth", r.ethernet).put("net", r.netName));
            } catch (Exception ignored) {
            }
        }
        return a;
    }

    static List<Result> fromJson(String s) {
        List<Result> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Result(o.optLong("t"), o.optString("src", ""), o.optDouble("down"),
                        o.optInt("ping"), o.optBoolean("eth"), o.optString("net", "")));
            }
        } catch (Exception e) {
            out.clear(); // corrupt: start again
        }
        return out;
    }

    static List<Result> load(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.isFile()) return new ArrayList<>();
        try {
            return fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    static void save(Context ctx, List<Result> list) {
        try {
            File f = new File(ctx.getFilesDir(), FILE), tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), toJson(list).toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception ignored) {
        }
    }
}
