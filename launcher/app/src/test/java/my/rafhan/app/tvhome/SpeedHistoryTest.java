package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SpeedHistoryTest {
    private static SpeedHistory.Result r(long t, double down) {
        return new SpeedHistory.Result(t, "Cloudflare", down, 20, false, "Home");
    }

    @Test
    public void addKeepsNewestFirstAndTrims() {
        List<SpeedHistory.Result> list = new ArrayList<>();
        for (int i = 0; i < 13; i++) SpeedHistory.add(list, r(i, i), 10);
        assertEquals(10, list.size());
        assertEquals(12.0, list.get(0).downMbps, 1e-9); // newest first
        assertEquals(3.0, list.get(9).downMbps, 1e-9);  // 0,1,2 dropped
    }

    @Test
    public void jsonRoundTrip() {
        List<SpeedHistory.Result> list = new ArrayList<>();
        SpeedHistory.add(list, new SpeedHistory.Result(1000, "Unifi Malaysia", 42.5, 18, true, "Ethernet"), 10);
        List<SpeedHistory.Result> back = SpeedHistory.fromJson(SpeedHistory.toJson(list).toString());
        assertEquals(1, back.size());
        SpeedHistory.Result b = back.get(0);
        assertEquals(1000, b.timeMs);
        assertEquals("Unifi Malaysia", b.source);
        assertEquals(42.5, b.downMbps, 1e-9);
        assertEquals(18, b.pingMs);
        assertTrue(b.ethernet);
        assertEquals("Ethernet", b.netName);
    }

    @Test
    public void corruptOrEmptyJsonIsEmpty() {
        assertTrue(SpeedHistory.fromJson("not json").isEmpty());
        assertTrue(SpeedHistory.fromJson("").isEmpty());
        assertTrue(SpeedHistory.fromJson(null).isEmpty());
    }

    @Test
    public void sourceEnumMapsByIndex() {
        assertEquals(SpeedTest.Source.CLOUDFLARE, SpeedTest.Source.of(0));
        assertEquals(SpeedTest.Source.NETFLIX, SpeedTest.Source.of(1));
        assertEquals(SpeedTest.Source.UNIFI, SpeedTest.Source.of(2));
        assertEquals(SpeedTest.Source.CLOUDFLARE, SpeedTest.Source.of(99)); // out of range: safe default
    }
}
