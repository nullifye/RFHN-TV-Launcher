package my.rafhan.app.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class TrafficRoutesTest {
    private boolean routed(String address) {
        long ip = ip(address);
        for (String route : TrafficRoutes.ipv4()) {
            String[] p = route.split("/");
            long start = ip(p[0]), size = 1L << (32 - Integer.parseInt(p[1]));
            if (ip >= start && ip < start + size) return true;
        }
        return false;
    }

    private long ip(String value) {
        long out = 0;
        for (String part : value.split("\\.")) out = (out << 8) | Integer.parseInt(part);
        return out;
    }

    @Test public void publicInternetIsCaptured() {
        for (String ip : new String[] {"1.1.1.1", "8.8.8.8", "9.9.9.9", "11.0.0.1", "172.15.0.1", "172.32.0.1", "192.167.0.1", "192.169.0.1", "223.255.255.255"})
            assertTrue(ip, routed(ip));
    }

    @Test public void localNetworksAndCastingBypassVpn() {
        for (String ip : new String[] {"0.0.0.0", "10.0.0.1", "10.255.255.255", "127.0.0.1", "169.254.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.44", "224.0.0.251", "239.255.255.250", "255.255.255.255"})
            assertFalse(ip, routed(ip));
        assertTrue(TrafficRoutes.ipv4().size() < 128);
    }
}
