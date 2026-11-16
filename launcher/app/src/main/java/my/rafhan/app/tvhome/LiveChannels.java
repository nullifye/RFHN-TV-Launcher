package my.rafhan.app.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The Live TV screen's channels (RTMKlik's and Tonton's) and their programme guide. RTMKlik's guide
 * comes from its schedule API, asked for just these channels over today and tomorrow (~100 KB);
 * if that fails, from the snapshot RTMKlik's EPG page reads (every channel for a week, ~5 MB
 * unpacked). Tonton's from the public guide its website reads. Only these channels, over a day and
 * a half from now, are kept.
 */
final class LiveChannels {
    static final String SITE = "https://rtmklik.rtm.gov.my/";
    static final String SCHEDULE = "https://rtm.glueapi.io/v3/epg/channelSchedule?sort=id&embed=program,channel"
            + "&timezone=8&limit=0&ids=";
    static final String SNAPSHOT = "https://dggz3oflbpnah.cloudfront.net/epg-data/latest/epg_today.json";
    static final String SNAPSHOT_FALLBACK = "https://epg-exports-prod.s3.ap-southeast-1.amazonaws.com/epg-data/latest/epg_today.json";
    /** The guide keeps programmes from this long ago... */
    static final long KEEP_BEFORE_MS = 2 * 3600_000L;
    /** ...to this far ahead. */
    static final long KEEP_AHEAD_MS = 36 * 3600_000L;
    private static final ZoneOffset MALAYSIA = ZoneOffset.ofHours(8);
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s+");
    private static final String IMAGES = "https://rtm-images.glueapi.io/240x0/";
    private static final String TONTON_API = "https://headend-api.tonton.com.my/v600/";
    private static final String TONTON_IMAGES = TONTON_API + "imageHelper.php?id=";
    /** Tonton's guide (public, as its website reads it): listings for the tags given, between two times. */
    static final String TONTON_GUIDE = TONTON_API + "api/epg.class.api.php/getChannelListings/378?format=json&appID=TONTON";

    /** Where a channel plays from: its website, whose own player (and DRM licence) TvPlayer uses. */
    enum Site {
        RTMKLIK("RTMKlik", "rtmklik.rtm.gov.my"),
        TONTON("Tonton", "watch.tonton.com.my");

        final String label, host;

        Site(String label, String host) {
            this.label = label;
            this.host = host;
        }

        String base() { return "https://" + host + "/"; }
    }

    /**
     * The WebView's own user agent as Windows Chrome's (same Chrome version). Tonton's site plays
     * only on the computers and browsers it supports, and shows an "install our app" page to the
     * rest (Android, and Linux too).
     */
    static String windowsUserAgent(String webViewAgent) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/([0-9.]+)").matcher(webViewAgent);
        String chrome = m.find() ? m.group(1) : "120.0.0.0";
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome
                + " Safari/537.36";
    }

    static final class Channel {
        final String slug, name, path, logoUrl;
        final Site site;
        final int epgId;     // the channel's id in RTMKlik's schedule API; 0 for other sites
        final String epgTag; // the channel's tag in Tonton's guide; null for other sites
        Channel(String slug, String name, Site site, String path, String logoUrl, int epgId, String epgTag) {
            this.slug = slug;
            this.name = name;
            this.site = site;
            this.path = path;
            this.logoUrl = logoUrl;
            this.epgId = epgId;
            this.epgTag = epgTag;
        }
        String page() { return site.base() + path; }
        String logoUrl() { return logoUrl; }
    }

    private static Channel rtm(String slug, String name, String path, String logo, int epgId) {
        return new Channel(slug, name, Site.RTMKLIK, path, IMAGES + logo, epgId, null);
    }

    /** Tonton: its guide tag, and its logo's image id (transparent, made for a dark background). */
    private static Channel tonton(String slug, String name, String epgTag, long imageId) {
        return new Channel(slug, name, Site.TONTON, "live/" + slug, TONTON_IMAGES + imageId
                + ":378:CHANNEL:IMAGE:png&w=240&appID=TONTON", 0, epgTag);
    }

    /**
     * In the order the screen lists them: the free-to-air channels by their usual numbers, then
     * RTM's others, Tonton's, and RTMKlik's streaming channels. RTMKlik's logos are white 16:9 cards.
     */
    static final List<Channel> ALL = Collections.unmodifiableList(java.util.Arrays.asList(
            rtm("tv1", "TV1", "live/tv/tv1", "live_channel/tv1_bckg.png", 1),
            rtm("tv2", "TV2", "live/tv/tv2", "live_channel/6.png", 2),
            tonton("tv3", "TV3", "TV3", 6420323),
            tonton("ntv7", "NTV7", "NTV7", 6420324),
            tonton("8tv", "8TV", "8TV", 6420325),
            tonton("tv9", "TV9", "TV9", 6420326),
            rtm("okey", "OKEY", "live/tv/okey", "live_channel/4.png", 3),
            rtm("berita", "Berita RTM", "live/tv/berita", "live_channel/berita_bckg.jpg", 7),
            rtm("sukan", "Sukan RTM", "live/tv/sukan", "live_channel/1920SUKAN_Logo_-01.jpg", 4),
            rtm("dewanrakyat00002", "Dewan Rakyat", "live/khas/dewanrakyat00002", "live_channel/PDR_bckg.png", 9),
            rtm("dewannegara00002", "Dewan Negara", "live/khas/dewannegara00002", "live_channel/PDN_bckg.png", 10),
            rtm("rtmworld00001", "RTM World", "live/khas/rtmworld00001", "live_channel/RtmW.png", 61),
            tonton("ds", "Drama Sangat", "DS", 6420342),
            tonton("fifa", "FIFA+", "FIFA", 6576795),
            tonton("mlp", "Major League Pickleball Asia", "MLP", 6621108),
            // RTMKlik's "Feeling-feeling" streaming channels.
            rtm("roll", "Roll", "live/feeling-feeling/roll", "fast_channel/ROLL_CHANNEL.jpg", 70),
            rtm("snap", "Snap", "live/feeling-feeling/snap", "fast_channel/SNAP_CHANNEL.jpg", 84),
            rtm("apetito", "Apetito", "live/feeling-feeling/apetito", "fast_channel/APETITO_CHANNEL.jpg", 85),
            rtm("lead", "Lead", "live/feeling-feeling/lead", "fast_channel/LEAD_CHANNEL.jpg", 81),
            rtm("aura", "Aura", "live/feeling-feeling/aura", "fast_channel/AURA_CHANNEL.jpg", 88),
            rtm("fitrah", "Fitrah", "live/feeling-feeling/fitrah", "fast_channel/FITRAH_CHANNEL.jpg", 89),
            rtm("rtmklikjr", "JR.", "live/feeling-feeling/rtmklikjr", "fast_channel/JR_CHANNEL.jpg", 83)));

    // The list before Tonton's channels, in order: a saved channel number from then maps to its slug.
    private static final String[] LEGACY_ORDER = {"tv1", "tv2", "okey", "berita", "sukan", "dewanrakyat00002",
            "dewannegara00002", "rtmworld00001", "roll", "snap", "apetito", "lead", "aura", "fitrah", "rtmklikjr"};

    static String legacyChannelSlug(int index) {
        return LEGACY_ORDER[Math.max(0, Math.min(LEGACY_ORDER.length - 1, index))];
    }

    /** The stations switched on in Settings → Live TV, in list order. */
    static List<Channel> enabled(android.content.Context ctx) {
        List<Channel> out = new ArrayList<>();
        for (Channel c : ALL) if (LauncherSettings.flag(ctx, LauncherSettings.stationKey(c.slug))) out.add(c);
        return out;
    }

    static Channel byPage(String url) {
        for (Channel c : ALL) if (c.page().equals(url)) return c;
        return null;
    }

    static boolean isChannelPage(String url) {
        return byPage(url) != null;
    }

    static final class Programme {
        final long start, end; // epoch ms
        final String title, description;
        Programme(long start, long end, String title, String description) {
            this.start = start;
            this.end = end;
            this.title = title;
            this.description = description;
        }
        boolean airingAt(long now) { return start <= now && now < end; }
        float progress(long now) {
            return end <= start ? 0f : Math.max(0f, Math.min(1f, (now - start) / (float) (end - start)));
        }
    }

    /** Every programme of the listed channels, by slug and sorted by start. Channels without one are absent. */
    static Map<String, List<Programme>> parse(String json) throws Exception {
        return parse(json, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * Schedules by channel slug, each sorted by start time, keeping only the listed channels and
     * programmes overlapping [from, to). Reads both the API's shape ({"data": [rows]}) and the
     * snapshot's ({"data": {"data": [rows]}}).
     */
    static Map<String, List<Programme>> parse(String json, long from, long to) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray rows = root.optJSONArray("data");
        if (rows == null) {
            JSONObject data = root.optJSONObject("data");
            rows = data == null ? null : data.optJSONArray("data");
        }
        if (rows == null) throw new IOException("No EPG data");
        java.util.Set<String> wanted = new java.util.HashSet<>();
        for (Channel c : ALL) if (c.site == Site.RTMKLIK) wanted.add(c.slug); // RTMKlik's guide
        Map<String, List<Programme>> out = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            JSONArray objs = row.optJSONArray("channelObj");
            JSONObject obj = objs == null ? null : objs.optJSONObject(0);
            String slug = obj == null ? "" : obj.optString("slug");
            if (!wanted.contains(slug) || out.containsKey(slug)) continue;
            ZoneOffset zone = ZoneOffset.ofHours(row.optInt("timezone", 8));
            JSONArray schedule = row.optJSONArray("schedule");
            List<Programme> list = new ArrayList<>();
            for (int j = 0; schedule != null && j < schedule.length(); j++) {
                JSONObject p = schedule.optJSONObject(j);
                if (p == null) continue;
                try {
                    long start = LocalDateTime.parse(p.getString("dateTimeStart")).toInstant(zone).toEpochMilli();
                    long end = LocalDateTime.parse(p.getString("dateTimeEnd")).toInstant(zone).toEpochMilli();
                    if (end <= from || start >= to) continue; // outside the window kept
                    String title = firstText(p, "scheduleProgramTitle", "programTitle");
                    if (end > start && !title.isEmpty())
                        list.add(new Programme(start, end, title, firstText(p, "scheduleProgramDescription", "description")));
                } catch (Exception ignored) { // one bad row doesn't lose the channel
                }
            }
            if (list.isEmpty()) continue;
            Collections.sort(list, (a, b) -> Long.compare(a.start, b.start));
            ((ArrayList<Programme>) list).trimToSize();
            out.put(slug, list);
        }
        return out;
    }

    private static String firstText(JSONObject o, String a, String b) {
        String v = o.optString(a).trim();
        return clean(v.isEmpty() || "null".equals(v) ? o.optString(b).trim() : v);
    }

    /** Some guide text arrives with its quotes still escaped (\") and stray line breaks. */
    static String clean(String s) {
        return SPACES.matcher(s.replace("\\\"", "\"").replace("\\'", "'")).replaceAll(" ").trim();
    }

    /** The programme on now, or null. */
    static Programme now(List<Programme> list, long now) {
        if (list != null) for (Programme p : list) if (p.airingAt(now)) return p;
        return null;
    }

    /** Up to {@code max} programmes starting after {@code now}. */
    static List<Programme> upcoming(List<Programme> list, long now, int max) {
        List<Programme> out = new ArrayList<>();
        if (list != null) for (Programme p : list) {
            if (out.size() == max) break;
            if (p.start > now) out.add(p);
        }
        return out;
    }

    /** Tonton's guide for its channels here, from {@code from} to {@code to} (epoch ms). */
    static String tontonGuideUrl(long from, long to) {
        StringBuilder tags = new StringBuilder();
        for (Channel c : ALL) if (c.epgTag != null) tags.append(tags.length() == 0 ? "" : ",").append(c.epgTag);
        return TONTON_GUIDE + "&filter_starttime=" + from / 1000 + "&filter_endtime=" + to / 1000
                + "&filter_channels=" + tags;
    }

    /**
     * Tonton's guide: [{SourceChannel: {ChannelTag}, ChannelSchedule: {EventList: [{StartTimeUTC,
     * EndTimeUTC, EventTitle, ShortSynopsis}]}}] (times in epoch seconds), by our slugs.
     */
    static Map<String, List<Programme>> parseTonton(String json, long from, long to) throws Exception {
        Map<String, String> slugs = new HashMap<>();
        for (Channel c : ALL) if (c.epgTag != null) slugs.put(c.epgTag, c.slug);
        JSONArray rows = new JSONArray(json);
        Map<String, List<Programme>> out = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            JSONObject src = row == null ? null : row.optJSONObject("SourceChannel");
            String slug = src == null ? null : slugs.get(src.optString("ChannelTag"));
            JSONObject sched = row == null ? null : row.optJSONObject("ChannelSchedule");
            JSONArray events = sched == null ? null : sched.optJSONArray("EventList");
            if (slug == null || events == null || out.containsKey(slug)) continue;
            List<Programme> list = new ArrayList<>();
            for (int j = 0; j < events.length(); j++) {
                JSONObject e = events.optJSONObject(j);
                if (e == null) continue;
                long start = e.optLong("StartTimeUTC") * 1000, end = e.optLong("EndTimeUTC") * 1000;
                String title = clean(e.optString("EventTitle"));
                if (end <= start || title.isEmpty() || "null".equals(title) || end <= from || start >= to) continue;
                String synopsis = e.isNull("ShortSynopsis") ? "" : clean(e.optString("ShortSynopsis"));
                list.add(new Programme(start, end, title, synopsis));
            }
            if (list.isEmpty()) continue;
            Collections.sort(list, (a, b) -> Long.compare(a.start, b.start));
            ((ArrayList<Programme>) list).trimToSize();
            out.put(slug, list);
        }
        return out;
    }

    /** The schedule API's URL for these channels over today and tomorrow (Malaysian dates). */
    static String scheduleUrl(long now) {
        java.time.LocalDate today = java.time.Instant.ofEpochMilli(now).atOffset(MALAYSIA).toLocalDate();
        StringBuilder ids = new StringBuilder();
        for (Channel c : ALL) if (c.epgId > 0) ids.append(ids.length() == 0 ? "" : ",").append(c.epgId);
        return SCHEDULE + ids + "&dateStart=" + today + "&dateEnd=" + today.plusDays(1);
    }

    /** RTMKlik's guide and Tonton's, merged by slug; whichever is reachable (fails if neither is). */
    static Map<String, List<Programme>> load() throws Exception {
        long now = System.currentTimeMillis(), from = now - KEEP_BEFORE_MS, to = now + KEEP_AHEAD_MS;
        Map<String, List<Programme>> all = new HashMap<>();
        Exception failure = null;
        try {
            all.putAll(loadRtm(now, from, to));
        } catch (Exception e) {
            failure = e;
        }
        try {
            all.putAll(parseTonton(text(tontonGuideUrl(from, to), 4 << 20), from, to));
        } catch (Exception e) {
            if (failure != null) throw failure;
        }
        return all;
    }

    private static Map<String, List<Programme>> loadRtm(long now, long from, long to) throws Exception {
        try {
            Map<String, List<Programme>> g = parse(text(scheduleUrl(now), 4 << 20), from, to);
            if (!g.isEmpty()) return g;
        } catch (Exception ignored) { // the snapshot instead
        }
        try {
            return parse(text(SNAPSHOT, 12 << 20), from, to);
        } catch (Exception e) {
            return parse(text(SNAPSHOT_FALLBACK, 12 << 20), from, to);
        }
    }

    static byte[] download(String url, int limit) throws IOException {
        return fetch(url, limit).toByteArray();
    }

    /** The response as text, decoded straight from the download buffer (no extra byte[] copy). */
    private static String text(String url, int limit) throws IOException {
        return fetch(url, limit).toString(StandardCharsets.UTF_8.name());
    }

    private static ByteArrayOutputStream fetch(String url, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept-Encoding", "gzip");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            InputStream raw = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            try (InputStream in = "gzip".equalsIgnoreCase(c.getContentEncoding()) ? new GZIPInputStream(raw) : raw) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) != -1;) {
                    if (out.size() + n > limit) throw new IOException("Too large");
                    out.write(buf, 0, n);
                }
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

}
