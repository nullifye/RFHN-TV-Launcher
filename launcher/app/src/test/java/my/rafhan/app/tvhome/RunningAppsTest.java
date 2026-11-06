package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RunningAppsTest {
    @Test
    public void originalAndroidTvFormat() {
        RunningApps.ProcessRow row = RunningApps.parseLine(
                "  #30: prcp   FGS  27297:my.rafhan.app.radiostream/u0a274  activity=recents");
        assertNotNull(row);
        assertEquals(27297, row.pid);
        assertEquals("my.rafhan.app.radiostream", row.pkg);
        assertEquals("FGS", row.proc);
    }

    @Test
    public void allwinnerPaddedIndexAndCapabilityColumn() {
        RunningApps.ProcessRow row = RunningApps.parseLine(
                "  # 7: prcp   FGS  --- 4493:com.softmedia.receiver/u0a69");
        assertNotNull(row);
        assertEquals(4493, row.pid);
        assertEquals("com.softmedia.receiver", row.pkg);
        assertEquals("prcp", row.adj);
        assertEquals("FGS", row.proc);
    }

    @Test
    public void cachedAdjustmentWithSpacesAndSecondaryProcess() {
        RunningApps.ProcessRow row = RunningApps.parseLine(
                "  #18: cch+ 5 CEM  --- 12385:com.google.android.gms:persistent/u0a25");
        assertNotNull(row);
        assertEquals("com.google.android.gms", row.pkg);
        assertEquals("cch+ 5", row.adj);
        assertEquals("CEM", row.proc);
    }

    @Test
    public void topProcessWithCapabilities() {
        RunningApps.ProcessRow row = RunningApps.parseLine(
                "  #22: fg     TOP  LCM 8602:my.rafhan.app.tvhome/u0a74 act:activities");
        assertNotNull(row);
        assertEquals("TOP", row.proc);
        assertEquals(" act:activities", row.rest);
    }

    @Test
    public void cachedProcessWithoutCapabilityColumn() {
        assertNotNull(RunningApps.parseLine("# 2: cch+ 6 CEM 1234:com.example.app/u0a20"));
        assertNotNull(RunningApps.parseLine("#12: cch+10 CEM 1234:com.example.app/u0a20"));
    }

    @Test
    public void headersAndInvalidPidsAreIgnored() {
        assertNull(RunningApps.parseLine("ACTIVITY MANAGER LRU PROCESSES"));
        assertNull(RunningApps.parseLine(null));
        assertNull(RunningApps.parseLine("# 1: fg TOP LCM 0:com.example.app/u0a20"));
        assertNull(RunningApps.parseLine("# 1: fg TOP LCM 9999999999999999:com.example.app/u0a20"));
    }
}
