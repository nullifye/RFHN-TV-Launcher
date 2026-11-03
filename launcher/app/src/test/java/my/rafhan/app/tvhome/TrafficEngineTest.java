package my.rafhan.app.tvhome;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TrafficEngineTest {
    private static final String A = "a".repeat(64), B = "0123456789abcdef".repeat(4);

    @Test
    public void readsTheFingerprints() {
        Map<String, String> pins = TrafficEngine.pins("armeabi-v7a libzdtun.so " + A + "\n"
                + "armeabi-v7a libtraffic.so " + B + "\n\n");
        assertEquals(2, pins.size());
        assertEquals(A, pins.get("armeabi-v7a libzdtun.so"));
        assertEquals(B, pins.get("armeabi-v7a libtraffic.so"));
    }

    @Test
    public void ignoresAnythingThatIsNotAFingerprint() {
        Map<String, String> pins = TrafficEngine.pins("x86 libzdtun.so NOTAHASH\narm64-v8a libzdtun.so\n# comment\n"
                + "arm64-v8a libtraffic.so " + A.toUpperCase());
        assertFalse(pins.containsKey("x86 libzdtun.so"));
        assertEquals(0, pins.size());
    }

    @Test
    public void sha256MatchesTheStandard() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                TrafficEngine.sha256("abc".getBytes(StandardCharsets.US_ASCII)));
    }
}
