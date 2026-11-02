package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The phone remote's TV tab: what the home screen shows (Now Playing, prayer times, System card,
 * bell), read from the same code as the cards so the phone and the TV always agree. Blocking
 * (reads `dumpsys` and files): called on the remote server's own threads.
 */
final class RemoteDashboard {
    private RemoteDashboard() {}

    static JSONObject build(Context ctx, AdbKeys adb, SleepTimer timer) throws JSONException {
        return new JSONObject()
                .put("sleepAt", timer.endsAt() > 0 ? timer.endsAt() : JSONObject.NULL)
                .put("nowPlaying", nowPlaying(ctx))
                .put("prayer", prayer(ctx))
                .put("system", system(ctx))
                .put("alerts", alerts())
                .put("adb", adb.state().name().toLowerCase(Locale.ROOT))
                .put("a11y", RemoteAccessibilityService.isOn()); // sleep and the timer work with it alone
    }

    /** The playing (or paused) media session, or null. Also used to open its app. */
    static NowPlayingPanel.Info session(Context ctx) {
        return NowPlayingPanel.readSession(ctx);
    }

    private static Object nowPlaying(Context ctx) throws JSONException {
        NowPlayingPanel.Info i = session(ctx);
        if (i == null) return JSONObject.NULL;
        String app = label(ctx, i.pkg);
        return new JSONObject()
                .put("pkg", i.pkg)
                .put("app", app)
                .put("title", i.title != null ? i.title : app)
                .put("artist", i.artist != null ? i.artist : "")
                .put("state", NowPlayingPanel.stateLabel(i.state))
                .put("playing", i.state == PlaybackState.STATE_PLAYING);
    }

    /** Today's five times for the zone in Settings, and the next prayer (tomorrow's Fajr after Isya). */
    private static Object prayer(Context ctx) throws JSONException {
        String zone = LauncherSettings.zone(ctx);
        JSONObject o = new JSONObject().put("zone", zone).put("area", zoneArea(ctx, zone));
        LocalDate today = LocalDate.now();
        LocalTime[] t = PrayerPanel.cachedTimes(ctx, zone, today);
        if (t == null) return o.put("times", JSONObject.NULL);
        DateTimeFormatter f = DateTimeFormatter.ofPattern(LauncherSettings.use24h(ctx) ? "HH:mm" : "h:mm a");
        JSONArray times = new JSONArray();
        LocalDateTime now = LocalDateTime.now(), next = null;
        int nextIdx = -1;
        for (int k = 0; k < t.length; k++) {
            times.put(new JSONObject().put("name", PrayerPanel.NAMES[k]).put("time", t[k] == null ? "--:--" : t[k].format(f)));
            if (next == null && t[k] != null && today.atTime(t[k]).isAfter(now)) {
                next = today.atTime(t[k]);
                nextIdx = k;
            }
        }
        o.put("times", times);
        if (next == null) { // after Isya: tomorrow's Fajr, if that month is cached
            LocalTime[] tm = PrayerPanel.cachedTimes(ctx, zone, today.plusDays(1));
            if (tm != null && tm[0] != null) {
                next = today.plusDays(1).atTime(tm[0]);
                nextIdx = 0;
            }
        }
        if (next != null) {
            o.put("next", new JSONObject().put("index", nextIdx).put("name", PrayerPanel.NAMES[nextIdx])
                    .put("tomorrow", !next.toLocalDate().equals(today))
                    .put("time", next.toLocalTime().format(f))
                    .put("at", next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()));
        }
        return o;
    }

    private static JSONObject system(Context ctx) throws JSONException {
        double[] cpu = SysInfo.cpu(Runtime.getRuntime().availableProcessors());
        long[] ram = SysInfo.ram(ctx), disk = SysInfo.disk(ctx);
        SysInfo.Net n = SysInfo.network(ctx);
        return new JSONObject()
                .put("cpuGhz", cpu[0]).put("cores", (int) cpu[1])
                .put("ramUsed", ram[0]).put("ramTotal", ram[1])
                .put("diskUsed", disk[0]).put("diskTotal", disk[1])
                .put("uptimeMin", SystemClock.elapsedRealtime() / 60_000)
                .put("ip", n.ip != null ? n.ip : JSONObject.NULL)
                .put("network", n.ethernet ? "LAN" : n.wifi ? (n.name != null ? n.name : "Wi-Fi") : "Offline")
                .put("rssi", n.wifi && n.rssi > -127 ? n.rssi : JSONObject.NULL)
                .put("adb", SysInfo.adbOn(ctx));
    }

    private static JSONObject alerts() throws JSONException {
        JSONArray list = new JSONArray();
        for (Alerts.Alert a : Alerts.get().list()) {
            list.put(new JSONObject().put("id", a.id).put("type", a.type).put("title", a.title)
                    .put("text", a.text != null ? a.text : "").put("time", a.time).put("openable", a.action != null));
        }
        return new JSONObject().put("unread", Alerts.get().unread()).put("list", list);
    }

    /** "Klang, Kuala Langat" for SGR03, from the zone list in Settings. */
    private static String zoneArea(Context ctx, String zone) {
        String[] z = RemoteSettings.zoneEntry(ctx, zone);
        return z != null ? z[2] : "";
    }

    static String label(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
