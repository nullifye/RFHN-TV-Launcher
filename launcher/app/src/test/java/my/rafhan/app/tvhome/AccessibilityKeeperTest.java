package my.rafhan.app.tvhome;

import static org.junit.Assert.*;

import org.junit.Test;

public class AccessibilityKeeperTest {
    private static final String OURS = "my.rafhan.app.tvhome/my.rafhan.app.tvhome.RemoteAccessibilityService";
    private static final String SHORT = "my.rafhan.app.tvhome/.RemoteAccessibilityService";
    private static final String OTHER = "com.example.reader/.ReaderService";

    @Test
    public void restoresOnlyOursWithoutDroppingOtherServices() {
        assertEquals(OTHER + ":" + OURS, AccessibilityServiceList.add(OTHER, OURS));
        assertEquals(OTHER, AccessibilityServiceList.remove(OTHER + ":" + OURS, SHORT));
        assertEquals(OTHER, AccessibilityServiceList.remove(OTHER, OURS));
    }

    @Test
    public void shortAndFullNamesAreEquivalentAndNotDuplicated() {
        assertTrue(AccessibilityServiceList.contains(SHORT, OURS));
        assertTrue(AccessibilityServiceList.contains(OURS, SHORT));
        assertEquals(OTHER + ":" + SHORT, AccessibilityServiceList.add(OTHER + ":" + SHORT, OURS));
        assertEquals("", AccessibilityServiceList.remove(SHORT, OURS));
        assertFalse(AccessibilityServiceList.contains(OTHER, OURS));
    }

    @Test
    public void handlesMissingAndEmptySettings() {
        assertEquals(OURS, AccessibilityServiceList.add(null, OURS));
        assertEquals(OURS, AccessibilityServiceList.add("null", OURS));
        assertEquals(OURS, AccessibilityServiceList.add("::", OURS));
        assertEquals("", AccessibilityServiceList.remove(null, OURS));
        assertFalse(AccessibilityServiceList.contains("", OURS));
    }

    @Test
    public void preservesOtherEntriesAndDoesNotConfuseSimilarNames() {
        String similar = "my.rafhan.app.tvhome/.DifferentService";
        assertFalse(AccessibilityServiceList.contains(similar, OURS));
        assertEquals(similar + ":" + OURS, AccessibilityServiceList.add(similar, OURS));
        assertEquals(OTHER + ":" + similar, AccessibilityServiceList.remove(OTHER + ":" + OURS + ":" + similar, OURS));
    }

    @Test
    public void repeatedResetsBackOffButNeverExceedThirtySeconds() {
        assertEquals(1000, AccessibilityKeeper.retryDelay(1));
        assertEquals(2000, AccessibilityKeeper.retryDelay(2));
        assertEquals(16000, AccessibilityKeeper.retryDelay(5));
        assertEquals(30000, AccessibilityKeeper.retryDelay(6));
        assertEquals(30000, AccessibilityKeeper.retryDelay(Integer.MAX_VALUE));
    }
}
