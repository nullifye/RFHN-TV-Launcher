package my.rafhan.app.tvhome;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class TrafficHistoryTest {
    @Test public void aggregatesWithoutSavingPackets() {
        TrafficHistory h = new TrafficHistory();
        long one = h.open("8.8.8.8", 443, 6, 123, "Player", 1000);
        h.traffic(one, 100, 400, 2000);
        h.open("8.8.8.8", 443, 6, 123, "Player", 3000);
        List<TrafficHistory.Entry> all = h.snapshot(4000);
        assertEquals(1, all.size());
        assertEquals(2, all.get(0).connections);
        assertEquals(100, all.get(0).sent);
        assertEquals(400, all.get(0).received);
    }

    @Test public void separatesAppsAndProtocols() {
        TrafficHistory h = new TrafficHistory();
        h.open("8.8.8.8", 443, 6, 1, "One", 1);
        h.open("8.8.8.8", 443, 6, 2, "Two", 2);
        h.open("8.8.8.8", 443, 17, 1, "One", 3);
        assertEquals(3, h.snapshot(4).size());
    }

    @Test public void expiresAndAcceptsLaterTrafficOnLongLivedConnections() {
        TrafficHistory h = new TrafficHistory();
        long id = h.open("1.1.1.1", 443, 6, -1, "Unknown", 0);
        h.traffic(id, 10, 20, 1000);
        assertTrue(h.snapshot(TrafficHistory.WINDOW_MS + 1001).isEmpty());
        h.traffic(id, 3, 4, TrafficHistory.WINDOW_MS + 2000);
        TrafficHistory.Entry e = h.snapshot(TrafficHistory.WINDOW_MS + 3000).get(0);
        assertEquals(3, e.sent);
        assertEquals(4, e.received);
        assertEquals(0, e.connections);
    }

    @Test public void boundsEndpointsAndClears() {
        TrafficHistory h = new TrafficHistory();
        for (int i = 0; i < 400; i++) h.open("1.2." + (i / 256) + "." + (i % 256), 443, 6, 1, "App", i);
        assertEquals(TrafficHistory.MAX_ENDPOINTS, h.snapshot(500).size());
        assertTrue(h.dropped() > 0);
        h.clear();
        assertTrue(h.snapshot(501).isEmpty());
        assertEquals(0, h.dropped());
    }

    @Test public void closedConnectionCannotAddBytes() {
        TrafficHistory h = new TrafficHistory();
        long id = h.open("1.1.1.1", 443, 6, 1, "App", 1);
        h.close(id);
        h.traffic(id, 5, 6, 2);
        assertEquals(0, h.snapshot(3).get(0).sent);
    }

    @Test public void clearingWhileCapturingKeepsOpenConnectionsFromZero() {
        TrafficHistory h = new TrafficHistory();
        long open = h.open("1.1.1.1", 443, 6, 10, "Stream", 1);
        h.traffic(open, 100, 900, 1);
        long done = h.open("8.8.8.8", 53, 17, 10, "Stream", 1);
        h.close(done);
        h.clear();
        assertEquals(0, h.snapshot(2).size()); // the list is empty straight away
        h.traffic(open, 5, 7, 2); // the still-open stream carries on
        assertEquals(1, h.snapshot(3).size());
        assertEquals(5, h.snapshot(3).get(0).sent); // counted from the clear, not from before it
        assertEquals(7, h.snapshot(3).get(0).received);
        h.traffic(done, 1, 1, 3); // a closed one stays gone
        assertEquals(1, h.snapshot(4).size());
    }

    @Test public void keepsWhatTheFirstBytesSaid() {
        TrafficHistory h = new TrafficHistory();
        long a = h.open("104.16.1.1", 443, 6, 10, "App", 1);
        h.info(a, "api.example.com", "h2", "TLS");
        long b = h.open("104.16.1.1", 443, 6, 10, "App", 2); // the same address, another site
        h.info(b, "cdn.example.com", null, "TLS");
        for (int i = 0; i < 5; i++) h.info(b, "s" + i + ".example.com", null, null);
        TrafficHistory.Entry e = h.snapshot(3).get(0);
        assertEquals(TrafficHistory.MAX_HOSTS, e.hosts.size()); // bounded
        assertEquals("s4.example.com", e.hosts.get(e.hosts.size() - 1)); // newest last
        assertEquals("h2", e.request); // a null detail keeps the last one
        assertEquals("TLS", e.kind);
        assertEquals(1, e.firstSeen);
        h.info(999, "nobody", "x", "HTTP"); // an unknown connection changes nothing
        assertEquals(1, h.snapshot(3).size());
    }
}
