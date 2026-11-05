package my.rafhan.app.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class ZoneDetectTest {
    @Test public void townsLandInTheirZones() {
        assertEquals("SGR03", ZoneDetect.nearest(3.04, 101.45));   // Klang
        assertEquals("SGR03", ZoneDetect.nearest(2.81, 101.50));   // Banting (Kuala Langat)
        assertEquals("SGR01", ZoneDetect.nearest(3.07, 101.52));   // Shah Alam
        assertEquals("SGR01", ZoneDetect.nearest(3.11, 101.61));   // Petaling Jaya
        assertEquals("WLY01", ZoneDetect.nearest(3.15, 101.70));   // Kuala Lumpur
        assertEquals("WLY01", ZoneDetect.nearest(2.93, 101.69));   // Putrajaya
        assertEquals("SGR02", ZoneDetect.nearest(3.34, 101.25));   // Kuala Selangor
        assertEquals("JHR02", ZoneDetect.nearest(1.49, 103.74));   // Johor Bahru
        assertEquals("PNG01", ZoneDetect.nearest(5.41, 100.33));   // George Town
        assertEquals("KTN01", ZoneDetect.nearest(6.13, 102.24));   // Kota Bharu
        assertEquals("SBH07", ZoneDetect.nearest(5.98, 116.07));   // Kota Kinabalu
        assertEquals("SWK08", ZoneDetect.nearest(1.55, 110.34));   // Kuching
        assertEquals("WLY02", ZoneDetect.nearest(5.28, 115.24));   // Labuan
        assertEquals("MLK01", ZoneDetect.nearest(2.19, 102.25));   // Melaka
    }

    /** Mountain-top zones are never picked: a TV in Taiping or Ranau gets the town's zone. */
    @Test public void peaksAreNeverPicked() {
        assertEquals("PRK06", ZoneDetect.nearest(4.85, 100.74));   // Taiping, under Bukit Larut
        assertEquals("PRK06", ZoneDetect.nearest(4.86, 100.80));   // Bukit Larut itself
        assertEquals("SBH07", ZoneDetect.nearest(6.08, 116.56));   // Gunung Kinabalu
        assertEquals("KDH02", ZoneDetect.nearest(5.79, 100.43));   // Gunung Jerai
    }

    @Test public void notNearMalaysiaIsNoGuess() {
        assertNull(ZoneDetect.nearest(51.5, -0.12));    // London
        assertNull(ZoneDetect.nearest(13.75, 100.50));  // Bangkok
        assertNull(ZoneDetect.nearest(-6.2, 106.8));    // Jakarta
    }
}
