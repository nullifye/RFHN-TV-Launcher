package my.rafhan.app.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Opt-in local-only forwarding VPN. Never restarted automatically and never stores payloads. */
public final class TrafficVpnService extends VpnService {
    static final String STOP = "my.rafhan.app.tvhome.STOP_TRAFFIC";
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private volatile boolean stop;
    private boolean owned;
    private Thread worker, snapshots;
    private final Map<Integer, String> apps = new LinkedHashMap<>();
    private ConnectivityManager connectivity;

    // The native engine isn't in the APK: TrafficEngine.load() fetches, checks and loads it in run().
    private static native void nativeReset();
    private static native void nativeStop();
    private native int nativeRun(int fd);

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) { requestStop(); return START_NOT_STICKY; }
        if (owned) return START_NOT_STICKY;
        if (VpnService.prepare(this) != null) { // approval withdrawn meanwhile
            TrafficMonitor.preparing = false;
            stopSelf(); return START_NOT_STICKY;
        }
        if (!BUSY.compareAndSet(false, true)) { stopSelf(); return START_NOT_STICKY; } // already running
        owned = true;
        stop = false;
        connectivity = getSystemService(ConnectivityManager.class);
        TrafficMonitor.preparing = true;
        TrafficMonitor.status = "Starting…";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("traffic", "Local traffic monitor", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_NET_TRAFFIC).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent end = PendingIntent.getService(this, 0, new Intent(this, TrafficVpnService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "traffic").setSmallIcon(R.drawable.ic_home)
                .setContentTitle("TV internet monitor")
                .setContentText("Local VPN · last 30 minutes · tap to view or stop")
                .setContentIntent(open).addAction(new Notification.Action.Builder(null, "Stop", end).build())
                .setOngoing(true).build();
        startForeground(8, notification);
        worker = new Thread(this::run, "traffic-forward");
        worker.start();
        return START_NOT_STICKY;
    }

    private void run() {
        ParcelFileDescriptor tunnel = null;
        TrafficGeo geo = null;
        try {
            TrafficEngine.load(this); // first time: download and check it (before anything is rerouted)
            if (stop) return;
            nativeReset();
            geo = new TrafficGeo(this);
            if (stop) return;
            LinkProperties link = connectivity.getLinkProperties(connectivity.getActiveNetwork());
            Builder builder = new Builder().setSession("RFHN · local internet monitor")
                    .setMtu(1500).addAddress("10.111.0.1", 32);
            for (String route : TrafficRoutes.ipv4()) {
                String[] parts = route.split("/"); builder.addRoute(parts[0], Integer.parseInt(parts[1]));
            }
            // IPv6 only when the network really has it: claiming IPv6 on an IPv4-only network makes
            // apps try IPv6 through the VPN first, fail, and fall back slowly (streams stall).
            if (hasGlobalIpv6(link)) {
                builder.addAddress("fd11:111::1", 128).addRoute("2000::", 3).addRoute("64:ff9b::", 96);
            }
            if (link != null) for (InetAddress dns : link.getDnsServers()) builder.addDnsServer(dns);
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            tunnel = builder.establish();
            if (tunnel == null) throw new IllegalStateException("VPN approval was revoked");
            if (stop) return;
            TrafficMonitor.preparing = false;
            TrafficMonitor.running = true;
            TrafficMonitor.status = "Capturing: the TV's connections go through the local VPN";
            LauncherSettings.setFlag(this, LauncherSettings.GLOBE_TRAFFIC, true);
            TrafficGeo lookup = geo;
            snapshots = new Thread(() -> publish(lookup), "traffic-country");
            snapshots.start();
            int result = nativeRun(tunnel.getFd());
            if (result != 0 && !stop) TrafficMonitor.status = "Monitor stopped: forwarding failed; normal network restored";
        } catch (Throwable e) {
            if (!stop) TrafficMonitor.status = "Monitor stopped: " + (e instanceof java.io.IOException
                    ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            stop = true;
            if (TrafficEngine.loaded()) nativeStop();
            // Removing the VPN restores normal routing even if a worker failed.
            if (tunnel != null) try { tunnel.close(); } catch (Exception ignored) {}
            if (snapshots != null) {
                snapshots.interrupt();
                try { snapshots.join(3000); } catch (InterruptedException ignored) {}
            }
            if (geo != null) geo.close();
            TrafficMonitor.running = TrafficMonitor.preparing = TrafficMonitor.stopping = false;
            if (TrafficMonitor.status.startsWith("Capturing") || TrafficMonitor.status.startsWith("Downloading")
                    || TrafficMonitor.status.startsWith("Starting")) TrafficMonitor.status = "The connections below stay for 30 minutes";
            BUSY.set(false);
            stopSelf();
        }
    }

    /** A global IPv6 address on the network (not link-local fe80::/10 or unique-local fc00::/7). */
    static boolean hasGlobalIpv6(LinkProperties link) {
        if (link == null) return false;
        for (android.net.LinkAddress la : link.getLinkAddresses()) {
            InetAddress a = la.getAddress();
            if (a instanceof java.net.Inet6Address && !a.isLinkLocalAddress() && !a.isLoopbackAddress()
                    && (a.getAddress()[0] & 0xFE) != 0xFC) return true;
        }
        return false;
    }

    private void publish(TrafficGeo geo) {
        // Bound geolocation results independently from connection retention. No IP-query service.
        Map<String, Countries.Country> locations = new LinkedHashMap<>(32, .75f, true);
        try {
            while (!stop) {
                List<TrafficMonitor.Destination> out = new ArrayList<>();
                int unknown = 0;
                for (TrafficHistory.Entry e : TrafficMonitor.HISTORY.snapshot(SystemClock.elapsedRealtime())) {
                    if (!locations.containsKey(e.ip)) {
                        if (locations.size() >= 256) locations.remove(locations.keySet().iterator().next());
                        locations.put(e.ip, geo.lookup(e.ip));
                    }
                    Countries.Country country = locations.get(e.ip);
                    if (country == null) unknown++;
                    out.add(new TrafficMonitor.Destination(e, country));
                }
                TrafficMonitor.unknown = unknown;
                TrafficMonitor.destinations = Collections.unmodifiableList(out);
                Thread.sleep(5000);
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // Native callbacks. Only once per connection or per-second aggregate, not once per packet.
    @SuppressWarnings("unused") private boolean protectSocket(int fd) { return !stop && protect(fd); }

    @SuppressWarnings("unused") private long connectionOpened(String source, String ip, int sourcePort, int port, int protocol) {
        int uid = -1;
        if (Build.VERSION.SDK_INT >= 29 && (protocol == 6 || protocol == 17)) {
            try {
                uid = connectivity.getConnectionOwnerUid(protocol, new InetSocketAddress(InetAddress.getByName(source), sourcePort),
                        new InetSocketAddress(InetAddress.getByName(ip), port));
            } catch (Exception ignored) {}
        }
        // Android's own DNS lookups (plain on 53, Private DNS over TLS on 853) come from the system,
        // not an app: name them, and keep them apart from the system's other traffic.
        boolean dns = (port == 53 || port == 853) && (uid < android.os.Process.FIRST_APPLICATION_UID);
        String app = dns ? "Android system (DNS)" : apps.get(uid);
        if (app == null) {
            app = label(uid);
            if (apps.size() >= 128) apps.clear();
            apps.put(uid, app);
        }
        return TrafficMonitor.HISTORY.open(ip, port, protocol, uid, app, SystemClock.elapsedRealtime());
    }

    /** What the list calls the app with this uid: "TV Home" for this one, its label, else the system or unknown. */
    private String label(int uid) {
        if (uid == android.os.Process.myUid()) return "TV Home";
        String[] names = uid < 0 ? null : getPackageManager().getPackagesForUid(uid);
        boolean system = uid >= 0 && uid < android.os.Process.FIRST_APPLICATION_UID;
        if (names == null || names.length == 0) return system ? "Android system" : "Unknown app";
        // A shared identity (the system's uid 1000 is shared by 35 apps on a Mi TV: Android, Xiaomi's
        // services, TV settings…) can't say which of them connected: the first one's name would be a guess.
        if (names.length > 1) {
            if (system) return "Android system";
            // Google Play services shares its identity with Google's framework and helpers: name it.
            for (String n : names) if ("com.google.android.gms".equals(n)) return label(n);
            return label(names[0]) + " (shared)";
        }
        return label(names[0]);
    }

    private String label(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    @SuppressWarnings("unused") private void traffic(long id, long sent, long received) {
        TrafficMonitor.HISTORY.traffic(id, sent, received, SystemClock.elapsedRealtime());
    }

    @SuppressWarnings("unused") private void connectionClosed(long id) { TrafficMonitor.HISTORY.close(id); }

    /** Native: what a connection's first bytes said (server name, request line or DNS name, kind). */
    @SuppressWarnings("unused") private void connectionInfo(long id, String host, String detail, String kind) {
        TrafficMonitor.HISTORY.info(id, host, detail, kind);
    }

    private void requestStop() {
        if (owned) TrafficMonitor.stopping = true;
        stop = true;
        if (owned && TrafficEngine.loaded()) nativeStop();
        if (worker != null) worker.interrupt();
        stopSelf();
    }

    @Override public void onRevoke() { requestStop(); super.onRevoke(); }
    @Override public void onDestroy() { requestStop(); super.onDestroy(); }
}
