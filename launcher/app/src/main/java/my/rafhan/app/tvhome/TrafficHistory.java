package my.rafhan.app.tvhome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded, in-memory, minute-resolution traffic totals. No packet payload is retained. */
final class TrafficHistory {
    static final int MAX_ENDPOINTS = 256;
    static final long WINDOW_MS = 30 * 60_000L;

    static final class Entry {
        final String ip, app;
        final int port, protocol, uid;
        final long sent, received, connections, lastSeen, firstSeen;
        /** What the connections' first bytes said (TrafficHistory.info): server names, the last request, the kind. */
        final List<String> hosts;
        final String request, kind;

        Entry(Bucket b, long now) {
            ip = b.ip; app = b.app; port = b.port; protocol = b.protocol; uid = b.uid; lastSeen = b.lastSeen;
            firstSeen = b.firstSeen;
            hosts = new ArrayList<>(b.hosts);
            request = b.request;
            kind = b.kind;
            long tx = 0, rx = 0, count = 0, minute = now / 60_000;
            for (int i = 0; i < 30; i++) if (b.minutes[i] > minute - 30 && b.minutes[i] <= minute) {
                tx += b.sent[i]; rx += b.received[i]; count += b.counts[i];
            }
            sent = tx; received = rx; connections = count;
        }
    }

    private static final class Bucket {
        final String ip, app;
        final int port, protocol, uid;
        final long[] minutes = new long[30], sent = new long[30], received = new long[30], counts = new long[30];
        long lastSeen, firstSeen = -1;
        final java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>(); // at most MAX_HOSTS
        String request, kind;

        Bucket(String ip, int port, int protocol, int uid, String app) {
            this.ip = ip; this.port = port; this.protocol = protocol; this.uid = uid; this.app = app;
            java.util.Arrays.fill(minutes, -1);
        }

        void add(long now, long tx, long rx, boolean opened) {
            long minute = now / 60_000;
            int slot = (int) (minute % 30);
            if (minutes[slot] != minute) {
                minutes[slot] = minute;
                sent[slot] = received[slot] = counts[slot] = 0;
            }
            sent[slot] += Math.max(0, tx);
            received[slot] += Math.max(0, rx);
            if (opened) counts[slot]++;
            if (firstSeen < 0) firstSeen = now;
            lastSeen = now;
        }
    }

    private final LinkedHashMap<String, Bucket> endpoints = new LinkedHashMap<>(32, .75f, true);
    private final LinkedHashMap<Long, Bucket> active = new LinkedHashMap<>();
    private long nextId, dropped;

    synchronized long open(String ip, int port, int protocol, int uid, String app, long now) {
        prune(now);
        String key = uid + "|" + protocol + "|" + ip + "|" + port;
        Bucket b = endpoints.get(key);
        if (b == null) {
            if (endpoints.size() >= MAX_ENDPOINTS) {
                endpoints.remove(endpoints.keySet().iterator().next());
                dropped++;
            }
            b = new Bucket(ip, port, protocol, uid, app);
            endpoints.put(key, b);
        }
        b.add(now, 0, 0, true);
        // The native engine caps simultaneous sockets, but bound this map independently too.
        if (active.size() >= 256) active.remove(active.keySet().iterator().next());
        long id = ++nextId;
        active.put(id, b);
        return id;
    }

    synchronized void traffic(long id, long tx, long rx, long now) {
        Bucket b = active.get(id);
        if (b != null) {
            String key = b.uid + "|" + b.protocol + "|" + b.ip + "|" + b.port;
            Bucket kept = endpoints.get(key);
            if (kept == null) {
                if (endpoints.size() >= MAX_ENDPOINTS) {
                    endpoints.remove(endpoints.keySet().iterator().next()); dropped++;
                }
                endpoints.put(key, b);
                kept = b;
            }
            kept.add(now, tx, rx, false);
        }
    }

    /** Most server names kept per endpoint (a shared CDN address can serve many). */
    static final int MAX_HOSTS = 4;

    /**
     * What a connection's first outgoing bytes said (the native engine): the server name from the
     * TLS hello or HTTP Host, the HTTP request line, ALPN or DNS name as {@code detail}, and the kind
     * ("TLS", "HTTP", "DNS", "QUIC", "DNS over TLS"). Any of them may be null.
     */
    synchronized void info(long id, String host, String detail, String kind) {
        Bucket b = active.get(id);
        if (b == null) return;
        if (host != null && !host.isEmpty()) {
            b.hosts.remove(host);
            b.hosts.add(host); // newest last
            while (b.hosts.size() > MAX_HOSTS) b.hosts.remove(b.hosts.iterator().next());
        }
        if (detail != null && !detail.isEmpty()) b.request = detail;
        if (kind != null) b.kind = kind;
    }

    synchronized void close(long id) { active.remove(id); }

    synchronized List<Entry> snapshot(long now) {
        prune(now);
        List<Entry> out = new ArrayList<>(endpoints.size());
        for (Bucket b : endpoints.values()) out.add(new Entry(b, now));
        out.sort(Comparator.comparingLong((Entry e) -> e.lastSeen).reversed());
        return out;
    }

    synchronized long dropped() { return dropped; }

    /**
     * Forgets everything shown. Connections still open stay known (traffic() puts them back on the
     * list as they carry more), counted from zero.
     */
    synchronized void clear() {
        endpoints.clear();
        dropped = 0;
        for (Bucket b : active.values()) {
            b.firstSeen = -1;
            java.util.Arrays.fill(b.minutes, -1);
            java.util.Arrays.fill(b.sent, 0);
            java.util.Arrays.fill(b.received, 0);
            java.util.Arrays.fill(b.counts, 0);
        }
    }

    private void prune(long now) {
        // Ongoing flows retain their bucket through the bounded active map, not the visible list.
        endpoints.values().removeIf(b -> now - b.lastSeen >= WINDOW_MS);
    }
}
