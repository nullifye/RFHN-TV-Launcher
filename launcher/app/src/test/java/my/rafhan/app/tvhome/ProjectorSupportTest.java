package my.rafhan.app.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProjectorSupportTest {
    @Test
    public void minimalDefaultsOnlyOnTheLowRamHotackHardware() {
        assertTrue(ProjectorSupport.minimalDefaults("sun50iw12p1", true, true));
        assertFalse(ProjectorSupport.minimalDefaults("sun50iw12p1", false, true));
        assertFalse(ProjectorSupport.minimalDefaults("sun50iw12p1", true, false));
        assertFalse(ProjectorSupport.minimalDefaults("mt5886", true, true));
        assertFalse(ProjectorSupport.minimalDefaults(null, true, true));
    }
}
