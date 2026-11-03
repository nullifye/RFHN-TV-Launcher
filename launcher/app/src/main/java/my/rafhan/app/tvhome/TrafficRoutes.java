package my.rafhan.app.tvhome;

import java.util.ArrayList;
import java.util.List;

/** Route internet IPv4 traffic while leaving LAN, loopback and multicast on their normal path. */
final class TrafficRoutes {
    private static final long[][] EXCLUDED = {
            {0x00000000L, 8}, {0x0A000000L, 8}, {0x7F000000L, 8},
            {0xA9FE0000L, 16}, {0xAC100000L, 12}, {0xC0A80000L, 16},
            {0xE0000000L, 4}, {0xF0000000L, 4}
    };
    private TrafficRoutes() {}

    static List<String> ipv4() {
        List<String> out = new ArrayList<>();
        visit(0, 0, out);
        return out;
    }

    private static void visit(long start, int prefix, List<String> out) {
        long end = start + (1L << (32 - prefix)) - 1;
        boolean overlaps = false;
        for (long[] range : EXCLUDED) {
            long last = range[0] + (1L << (32 - (int) range[1])) - 1;
            if (start >= range[0] && end <= last) return;
            overlaps |= start <= last && end >= range[0];
        }
        if (!overlaps) {
            out.add(((start >> 24) & 255) + "." + ((start >> 16) & 255) + "."
                    + ((start >> 8) & 255) + "." + (start & 255) + "/" + prefix);
        } else {
            visit(start, prefix + 1, out);
            visit(start + (1L << (31 - prefix)), prefix + 1, out);
        }
    }
}
