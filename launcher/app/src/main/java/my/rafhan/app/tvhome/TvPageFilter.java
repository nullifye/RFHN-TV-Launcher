package my.rafhan.app.tvhome;

/**
 * What Live TV's player refuses to load from the channel pages: ad loaders, third-party trackers
 * and RTMKlik's own programme guide, hidden by the crop. On the TV's slow processor these compete with the page's
 * own start-up, and the players wait for their ad request to fail before starting the stream
 * (seconds, behind an ad-blocking DNS). Refused straight away, the players start the programme as
 * they do for a viewer with an ad blocker. The providers' own pages, APIs, player, licence and
 * stream hosts are never on this list.
 */
final class TvPageFilter {
    private TvPageFilter() {}

    // Blocked with all their subdomains.
    private static final String[] HOSTS = {
            // Ads: Google's video ad SDK (IMA) and ad servers
            "imasdk.googleapis.com", "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "googletagservices.com", "2mdn.net", "adtrafficquality.google",
            // Trackers and analytics
            "googletagmanager.com", "google-analytics.com", "analytics.google.com",
            "connect.facebook.net", "analytics.tiktok.com", "chartbeat.com", "chartbeat.net",
            "useinsider.com", "crwdcntrl.net", "scorecardresearch.com", "clevertap-prod.com",
            "analytics-ingress-global.bitmovin.com",
    };

    /** True for a request the player shouldn't make. {@code host} and {@code path} may be null. */
    static boolean blocked(String host, String path) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        for (String d : HOSTS) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        if (path == null) return false;
        switch (h) {
            case "www.google.com":
            case "www.google.com.my": // Google's conversion and measurement beacons only
                return path.startsWith("/ccm/") || path.startsWith("/g/collect") || path.startsWith("/rmkt/")
                        || path.startsWith("/pagead/") || path.startsWith("/measurement/");
            case "www.facebook.com": // the Meta pixel
                return path.equals("/tr") || path.startsWith("/tr/");
            case "rtmklik.rtm.gov.my": // RTMKlik's own ad schedule for its player
                return path.startsWith("/api/video-ads/");
            // RTMKlik's programme guide widget, hidden by the crop: its page and its data (4.8 MB of
            // JSON, never cached, parsed on every page load). Live TV reads the guide itself.
            case "rtmklik-epg-production.s3.ap-southeast-1.amazonaws.com":
                return path.startsWith("/epg-tv/");
            case "dggz3oflbpnah.cloudfront.net":
                return path.startsWith("/epg-data/");
            // Not Tonton's guide (headend-api …/epg.class.api.php/): its live page waits for it
            // before it creates the player, so without it no channel starts.
            default: // CleverTap's script, served from a shared CDN host
                return path.endsWith("/clevertap.min.js");
        }
    }
}
