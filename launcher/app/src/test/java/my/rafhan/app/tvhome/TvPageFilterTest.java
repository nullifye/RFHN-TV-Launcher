package my.rafhan.app.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TvPageFilterTest {
    @Test
    public void refusesAdsAndTrackers() {
        assertTrue(TvPageFilter.blocked("imasdk.googleapis.com", "/js/sdkloader/ima3.js"));
        assertTrue(TvPageFilter.blocked("pubads.g.doubleclick.net", "/gampad/ads"));
        assertTrue(TvPageFilter.blocked("pagead2.googlesyndication.com", "/x"));
        assertTrue(TvPageFilter.blocked("WWW.GoogleTagManager.com", "/gtm.js"));
        assertTrue(TvPageFilter.blocked("connect.facebook.net", "/en_US/fbevents.js"));
        assertTrue(TvPageFilter.blocked("tonton.api.useinsider.com", "/ins.js"));
        assertTrue(TvPageFilter.blocked("analytics-ingress-global.bitmovin.com", "/licensing"));
        assertTrue(TvPageFilter.blocked("www.google.com", "/ccm/collect"));
        assertTrue(TvPageFilter.blocked("www.google.com.my", "/pagead/1p-conversion/1/"));
        assertTrue(TvPageFilter.blocked("www.facebook.com", "/tr/"));
        assertTrue(TvPageFilter.blocked("rtmklik.rtm.gov.my", "/api/video-ads/live-vmap"));
        assertTrue(TvPageFilter.blocked("d2r1yp2w7bby2u.cloudfront.net", "/js/clevertap.min.js"));
    }

    @Test
    public void refusesRtmKliksHiddenGuideWidget() {
        assertTrue(TvPageFilter.blocked("rtmklik-epg-production.s3.ap-southeast-1.amazonaws.com", "/epg-tv/epg-tv-v1.html"));
        assertTrue(TvPageFilter.blocked("rtmklik-epg-production.s3.ap-southeast-1.amazonaws.com", "/epg-tv/tv-epg-v1.js"));
        assertTrue(TvPageFilter.blocked("dggz3oflbpnah.cloudfront.net", "/epg-data/latest/epg_today.json"));
        assertFalse(TvPageFilter.blocked("dggz3oflbpnah.cloudfront.net", "/other/x.js"));
    }

    @Test
    public void keepsTontonsApisIncludingTheGuideItsPlayerWaitsFor() {
        assertFalse(TvPageFilter.blocked("headend-api.tonton.com.my", "/v600/api/epg.class.api.php/getChannelListings/378"));
        assertFalse(TvPageFilter.blocked("headend-api.tonton.com.my", "/v600/api/categoryTree.class.api.php/GOgetLiveChannels/378"));
        assertFalse(TvPageFilter.blocked("headend-api.tonton.com.my", "/v600/bundleAPI/getConfiguration.php"));
        assertFalse(TvPageFilter.blocked("headend-api.tonton.com.my", "/v600/api/auth.class.api.php/logon/377"));
    }

    @Test
    public void keepsTheProvidersPagesPlayerLicenceAndStream() {
        assertFalse(TvPageFilter.blocked("watch.tonton.com.my", "/live/tv3"));
        assertFalse(TvPageFilter.blocked("headend-api.tonton.com.my", "/v600/api/auth.class.api.php/logon/377"));
        assertFalse(TvPageFilter.blocked("live-ssar-02.tonton.com.my", "/x.mpd"));
        assertFalse(TvPageFilter.blocked("rtmklik.rtm.gov.my", "/live/tv/tv1"));
        assertFalse(TvPageFilter.blocked("rtmklik.rtm.gov.my", "/vendor/glueplayer/gluePlyrAPI.js"));
        assertFalse(TvPageFilter.blocked("rtm-player.glueapi.io", "/latest/drm/token"));
        assertFalse(TvPageFilter.blocked("licensing.bitmovin.com", "/licensing"));
        assertFalse(TvPageFilter.blocked("71f2efe7.drm-widevine-licensing.axprod.net", "/AcquireLicense"));
        assertFalse(TvPageFilter.blocked("d25tgymtnqzu8s.cloudfront.net", "/live/media0/tv2/HLS/tv2.m3u8"));
        assertFalse(TvPageFilter.blocked("www.google.com", "/recaptcha/api.js"));
        assertFalse(TvPageFilter.blocked("notdoubleclick.net", "/"));
        assertFalse(TvPageFilter.blocked(null, null));
        assertFalse(TvPageFilter.blocked("www.google.com", null));
    }
}
