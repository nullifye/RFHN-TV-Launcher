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
 * RTMKlik's live channels shown on the Live TV screen, and their programme guide. The guide comes
 * from RTMKlik's schedule API, asked for just these channels over today and tomorrow (~100 KB);
 * if that fails, from the snapshot RTMKlik's EPG page reads (every channel for a week, ~5 MB
 * unpacked). Either way only these channels, over a day and a half from now, are kept.
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

    static final class Channel {
        final String slug, name, path, logo;
        final int epgId; // the channel's id in RTMKlik's schedule API
        Channel(String slug, String name, String path, String logo, int epgId) {
            this.slug = slug;
            this.name = name;
            this.path = path;
            this.logo = logo;
            this.epgId = epgId;
        }
        String page() { return SITE + path; }
        String logoUrl() { return IMAGES + logo; }
    }

    /** In the order the screen lists them. Logos are RTMKlik's white 16:9 channel cards. */
    static final List<Channel> ALL = Collections.unmodifiableList(java.util.Arrays.asList(
            new Channel("tv1", "TV1", "live/tv/tv1", "live_channel/tv1_bckg.png", 1),
            new Channel("tv2", "TV2", "live/tv/tv2", "live_channel/6.png", 2),
            new Channel("okey", "OKEY", "live/tv/okey", "live_channel/4.png", 3),
            new Channel("berita", "Berita RTM", "live/tv/berita", "live_channel/berita_bckg.jpg", 7),
            new Channel("sukan", "Sukan RTM", "live/tv/sukan", "live_channel/1920SUKAN_Logo_-01.jpg", 4),
            new Channel("dewanrakyat00002", "Dewan Rakyat", "live/khas/dewanrakyat00002", "live_channel/PDR_bckg.png", 9),
            new Channel("dewannegara00002", "Dewan Negara", "live/khas/dewannegara00002", "live_channel/PDN_bckg.png", 10),
            new Channel("rtmworld00001", "RTM World", "live/khas/rtmworld00001", "live_channel/RtmW.png", 61),
            // RTMKlik's "Feeling-feeling" streaming channels.
            new Channel("roll", "Roll", "live/feeling-feeling/roll", "fast_channel/ROLL_CHANNEL.jpg", 70),
            new Channel("snap", "Snap", "live/feeling-feeling/snap", "fast_channel/SNAP_CHANNEL.jpg", 84),
            new Channel("apetito", "Apetito", "live/feeling-feeling/apetito", "fast_channel/APETITO_CHANNEL.jpg", 85),
            new Channel("lead", "Lead", "live/feeling-feeling/lead", "fast_channel/LEAD_CHANNEL.jpg", 81),
            new Channel("aura", "Aura", "live/feeling-feeling/aura", "fast_channel/AURA_CHANNEL.jpg", 88),
            new Channel("fitrah", "Fitrah", "live/feeling-feeling/fitrah", "fast_channel/FITRAH_CHANNEL.jpg", 89),
            new Channel("rtmklikjr", "JR.", "live/feeling-feeling/rtmklikjr", "fast_channel/JR_CHANNEL.jpg", 83)));

    static boolean isChannelPage(String url) {
        for (Channel c : ALL) if (c.page().equals(url)) return true;
        return false;
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
        for (Channel c : ALL) wanted.add(c.slug);
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

    /** The schedule API's URL for these channels over today and tomorrow (Malaysian dates). */
    static String scheduleUrl(long now) {
        java.time.LocalDate today = java.time.Instant.ofEpochMilli(now).atOffset(MALAYSIA).toLocalDate();
        StringBuilder ids = new StringBuilder();
        for (Channel c : ALL) ids.append(ids.length() == 0 ? "" : ",").append(c.epgId);
        return SCHEDULE + ids + "&dateStart=" + today + "&dateEnd=" + today.plusDays(1);
    }

    static Map<String, List<Programme>> load() throws Exception {
        long now = System.currentTimeMillis(), from = now - KEEP_BEFORE_MS, to = now + KEEP_AHEAD_MS;
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
