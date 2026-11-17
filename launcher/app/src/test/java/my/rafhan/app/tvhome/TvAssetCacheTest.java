package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TvAssetCacheTest {
    private static final String RTM = "rtmklik.rtm.gov.my";
    private static final String UI = "rtmklik-merdeka360.s3.ap-southeast-1.amazonaws.com";

    @Test
    public void keepsTheVersionedPlayerWhileItsNameStays() {
        assertEquals(TvAssetCache.FOREVER, TvAssetCache.keepFor(RTM,
                "/vendor/glueplayer/gluePlyrAPI-rtmklik-v3.0.02-275cb95f4bff.js", null));
        assertEquals(TvAssetCache.FOREVER, TvAssetCache.keepFor("RTMKLIK.rtm.gov.my",
                "/vendor/glueplayer/gluePlyrAPI-rtmklik-v3.0.02-275cb95f4bff.js", null));
    }

    @Test
    public void keepsThePlayerUiForADay() {
        assertEquals(TvAssetCache.UNVERSIONED_MS, TvAssetCache.keepFor(UI, "/Merdeka2026/bitmovinplayer-ui.js", null));
    }

    @Test
    public void keepsNothingElse() {
        assertEquals(0, TvAssetCache.keepFor(RTM, "/vendor/glueplayer/gluePlyrAPI-rtmklik.js", null)); // no hash
        assertEquals(0, TvAssetCache.keepFor(RTM, "/vendor/glueplayer/gluePlyrAPI-275cb95f4bff.js", "v=2"));
        assertEquals(0, TvAssetCache.keepFor(RTM, "/live/tv/tv1", null));
        assertEquals(0, TvAssetCache.keepFor(RTM, "/_next/static/chunks/57acac27260c3cb4.js", null));
        assertEquals(0, TvAssetCache.keepFor(UI, "/Merdeka2026/other.js", null));
        assertEquals(0, TvAssetCache.keepFor("rtm-player.glueapi.io", "/latest/drm/token", null));
        assertEquals(0, TvAssetCache.keepFor("watch.tonton.com.my", "/static/js/main.js", null));
        assertEquals(0, TvAssetCache.keepFor(null, null, null));
    }

    @Test
    public void unversionedCopiesExpire() {
        long day = TvAssetCache.UNVERSIONED_MS, t = 1_800_000_000_000L;
        assertTrue(TvAssetCache.fresh(day, t, t + day - 1));
        assertFalse(TvAssetCache.fresh(day, t, t + day));
        assertFalse(TvAssetCache.fresh(day, t + 5000, t)); // clock went back: fetch again
        assertTrue(TvAssetCache.fresh(TvAssetCache.FOREVER, 0, t));
    }

    @Test
    public void fileNamesAreFixedAndDistinct() {
        String a = TvAssetCache.fileName("https://rtmklik.rtm.gov.my/vendor/glueplayer/a-275cb95f4bff.js");
        assertEquals(67, a.length()); // 64 hex digits + ".js"
        assertTrue(a.matches("[0-9a-f]{64}\\.js"));
        assertEquals(a, TvAssetCache.fileName("https://rtmklik.rtm.gov.my/vendor/glueplayer/a-275cb95f4bff.js"));
        assertNotEquals(a, TvAssetCache.fileName("https://rtmklik.rtm.gov.my/vendor/glueplayer/b-275cb95f4bff.js"));
    }
}
