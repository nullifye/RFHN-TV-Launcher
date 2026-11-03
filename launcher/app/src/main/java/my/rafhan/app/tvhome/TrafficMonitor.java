package my.rafhan.app.tvhome;

import android.os.SystemClock;
import java.util.Collections;
import java.util.List;

/** Small snapshots shared by the VPN, settings and Globe. Empty until capture is explicitly started. */
final class TrafficMonitor {
    static final TrafficHistory HISTORY = new TrafficHistory();
    static volatile String status = ""; // nothing to report yet; Settings shows what the monitor is
    static volatile boolean running, preparing;
    /** Stop pressed, until the VPN is really down (Settings shows "Stopping…" with a spinner). */
    static volatile boolean stopping;
    static volatile List<Destination> destinations = Collections.emptyList();
    static volatile int unknown;

    /** Starts capture (Android's VPN approval already given). Settings and Button Mapper. */
    static void start(android.content.Context ctx) {
        preparing = true; // the spinner shows now, not when the service gets going
        status = "Starting…";
        try {
            ctx.startForegroundService(new android.content.Intent(ctx, TrafficVpnService.class));
        } catch (RuntimeException e) {
            preparing = false;
            status = "The monitor couldn't start: " + e.getClass().getSimpleName();
        }
    }

    /** Stops capture: the STOP command, not stopService() (Android keeps a running VPN's service bound). */
    static void stop(android.content.Context ctx) {
        if (stopping) return; // already on its way
        stopping = true;
        try {
            ctx.startService(new android.content.Intent(ctx, TrafficVpnService.class).setAction(TrafficVpnService.STOP));
        } catch (RuntimeException e) {
            stopping = false;
            status = "Couldn't stop it here: use Android's VPN settings";
        }
    }

    static final class Destination {
        final TrafficHistory.Entry entry;
        final Countries.Country country;
        final double[] vector;
        Destination(TrafficHistory.Entry entry, Countries.Country country) {
            this.entry = entry; this.country = country;
            vector = country == null ? null : Globe.vector(country.lat, country.lon);
        }
    }

    /** "820 B", "12.4 KB", "3.1 MB". */
    static String bytes(long n) {
        return n < 1024 ? n + " B" : n < 1024 * 1024 ? String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0)
                : String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0);
    }

    static void clear() {
        HISTORY.clear();
        destinations = Collections.emptyList();
        unknown = 0;
    }

    static List<Destination> recent() {
        long now = SystemClock.elapsedRealtime();
        // The bounded snapshot is filtered at consumption too when capture has stopped.
        List<Destination> snapshot = destinations;
        int count = snapshot.size();
        while (count > 0 && now - snapshot.get(count - 1).entry.lastSeen >= TrafficHistory.WINDOW_MS) count--;
        return count == snapshot.size() ? snapshot : count == 0 ? Collections.emptyList() : snapshot.subList(0, count);
    }
}
