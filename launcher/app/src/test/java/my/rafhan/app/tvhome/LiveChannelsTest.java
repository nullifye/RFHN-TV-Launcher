package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public class LiveChannelsTest {
    // Trimmed from RTMKlik's epg_today.json: local times (UTC+8), schedule out of order,
    // a channel without a schedule and a row with a bad time.
    private static final String JSON = "{\"exportedAt\":\"2026-11-16T11:19:54Z\",\"data\":{\"count\":3,\"data\":["
            + "{\"id\":1,\"channel\":\"TV1\",\"timezone\":8,\"channelObj\":[{\"slug\":\"tv1\"}],\"schedule\":["
            + "{\"dateTimeStart\":\"2026-11-16T20:00:00\",\"dateTimeEnd\":\"2026-11-16T20:30:00\","
            + "\"scheduleProgramTitle\":\"Berita Perdana\",\"scheduleProgramDescription\":\"Berita\"},"
            + "{\"dateTimeStart\":\"2026-11-16T19:30:00\",\"dateTimeEnd\":\"2026-11-16T20:00:00\","
            + "\"scheduleProgramTitle\":\"\",\"programTitle\":\"Citra\",\"description\":\"Drama\"},"
            + "{\"dateTimeStart\":\"bad\",\"dateTimeEnd\":\"2026-11-16T21:00:00\",\"scheduleProgramTitle\":\"X\"}]},"
            + "{\"id\":10,\"channel\":\"Dewan Negara\",\"timezone\":8,\"channelObj\":[{\"slug\":\"dewannegara00002\"}],\"schedule\":[]},"
            + "{\"id\":2,\"channel\":\"TV2\",\"channelObj\":[],\"schedule\":[]}]}}";

    private static long at(String utc) {
        return Instant.parse(utc).toEpochMilli();
    }

    @Test
    public void parsesSchedulesBySlugInLocalTime() throws Exception {
        Map<String, List<LiveChannels.Programme>> g = LiveChannels.parse(JSON);
        assertEquals(1, g.size()); // empty schedule and missing slug are left out
        List<LiveChannels.Programme> tv1 = g.get("tv1");
        assertEquals(2, tv1.size()); // the bad row is skipped
        assertEquals("Citra", tv1.get(0).title); // sorted, and falls back to programTitle
        assertEquals("Drama", tv1.get(0).description);
        assertEquals(at("2026-11-16T11:30:00Z"), tv1.get(0).start); // 19:30 at UTC+8
        assertEquals(at("2026-11-16T12:30:00Z"), tv1.get(1).end);
    }

    @Test
    public void nowAndUpcoming() throws Exception {
        List<LiveChannels.Programme> tv1 = LiveChannels.parse(JSON).get("tv1");
        long t = at("2026-11-16T11:45:00Z"); // 19:45
        LiveChannels.Programme now = LiveChannels.now(tv1, t);
        assertEquals("Citra", now.title);
        assertEquals(0.5f, now.progress(t), 0.001f);
        List<LiveChannels.Programme> next = LiveChannels.upcoming(tv1, t, 2);
        assertEquals(1, next.size());
        assertEquals("Berita Perdana", next.get(0).title);
        assertNull(LiveChannels.now(tv1, at("2026-11-16T13:00:00Z")));
        assertNull(LiveChannels.now(null, t));
    }

    // The schedule API's shape: rows straight under "data"; plus a channel the screen doesn't list.
    private static final String API_JSON = "{\"count\":2,\"data\":["
            + "{\"id\":1,\"timezone\":8,\"channelObj\":[{\"slug\":\"tv1\"}],\"schedule\":["
            + "{\"dateTimeStart\":\"2026-11-16T08:00:00\",\"dateTimeEnd\":\"2026-11-16T09:00:00\",\"scheduleProgramTitle\":\"Pagi\"},"
            + "{\"dateTimeStart\":\"2026-11-16T19:30:00\",\"dateTimeEnd\":\"2026-11-16T20:00:00\",\"scheduleProgramTitle\":\"Citra\"},"
            + "{\"dateTimeStart\":\"2026-11-18T20:00:00\",\"dateTimeEnd\":\"2026-11-18T21:00:00\",\"scheduleProgramTitle\":\"Lusa\"}]},"
            + "{\"id\":99,\"timezone\":8,\"channelObj\":[{\"slug\":\"radioklfm\"}],\"schedule\":["
            + "{\"dateTimeStart\":\"2026-11-16T19:00:00\",\"dateTimeEnd\":\"2026-11-16T20:00:00\",\"scheduleProgramTitle\":\"Radio\"}]}]}";

    @Test
    public void keepsOnlyListedChannelsWithinTheWindow() throws Exception {
        long now = at("2026-11-16T11:45:00Z"); // 19:45 in Malaysia
        Map<String, List<LiveChannels.Programme>> g = LiveChannels.parse(API_JSON,
                now - LiveChannels.KEEP_BEFORE_MS, now + LiveChannels.KEEP_AHEAD_MS);
        assertEquals(1, g.size()); // radioklfm isn't on the screen
        List<LiveChannels.Programme> tv1 = g.get("tv1");
        assertEquals(1, tv1.size()); // 08:00 ended too long ago; Wednesday is too far ahead
        assertEquals("Citra", tv1.get(0).title);
    }

    @Test
    public void scheduleUrlAsksForTheListedChannelsTodayAndTomorrow() {
        String url = LiveChannels.scheduleUrl(at("2026-11-16T17:30:00Z")); // 01:30 on the 17th in Malaysia
        assertTrue(url.startsWith(LiveChannels.SCHEDULE + "1,2,3,7,4,9,10,61,70,84,85,81,88,89,83"));
        assertTrue(url.endsWith("&dateStart=2026-11-17&dateEnd=2026-11-18"));
    }

    @Test
    public void cleansEscapedQuotesAndLineBreaks() {
        assertEquals("titled \"Surreal Experiences\" here", LiveChannels.clean("titled \\\"Surreal Experiences\\\"\n here "));
    }

    @Test
    public void timeLeftReadsInHoursWhenLong() {
        assertEquals("1 min left", TvScreen.left(10_000));
        assertEquals("18 min left", TvScreen.left(17 * 60_000 + 1));
        assertEquals("1 h left", TvScreen.left(60 * 60_000));
        assertEquals("14 h 15 min left", TvScreen.left(855 * 60_000L));
    }

    @Test
    public void onlyListedChannelPagesArePlayable() {
        assertEquals(15, LiveChannels.ALL.size());
        java.util.Set<String> slugs = new java.util.HashSet<>(), pages = new java.util.HashSet<>();
        for (LiveChannels.Channel c : LiveChannels.ALL) {
            assertTrue(slugs.add(c.slug)); // the guide is keyed by slug
            assertTrue(pages.add(c.page()));
        }
        assertTrue(LiveChannels.isChannelPage("https://rtmklik.rtm.gov.my/live/feeling-feeling/rtmklikjr"));
        assertTrue(LiveChannels.isChannelPage("https://rtmklik.rtm.gov.my/live/tv/tv1"));
        assertTrue(LiveChannels.isChannelPage("https://rtmklik.rtm.gov.my/live/khas/dewanrakyat00002"));
        assertFalse(LiveChannels.isChannelPage("https://rtmklik.rtm.gov.my/live/tv/tv1?x=1"));
        assertFalse(LiveChannels.isChannelPage("https://example.com/live/tv/tv1"));
    }
}
