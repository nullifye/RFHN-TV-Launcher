package my.rafhan.app.tvhome;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class TvGuideTimelineTest {
    private static final ZoneId MALAYSIA = ZoneId.of("Asia/Kuala_Lumpur");
    private static final long NOW = at("2026-11-17T12:05:00Z"), MINUTE = 60_000L;
    private static long at(String iso) { return Instant.parse(iso).toEpochMilli(); }
    private static LiveChannels.Programme p(int start, int end) {
        return new LiveChannels.Programme(at("2026-11-17T12:00:00Z") + start * MINUTE,
                at("2026-11-17T12:00:00Z") + end * MINUTE, "Programme " + start, "");
    }

    @Test public void opensOnLiveProgrammeInCurrentLocalHour() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        assertEquals(at("2026-11-17T12:00:00Z"), t.hour());
        assertEquals(NOW, t.cursor());
        LiveChannels.Programme live = p(0, 15);
        assertSame(live, t.programme(Arrays.asList(p(-30, 0), live, p(15, 30))));
    }

    @Test public void visitsEveryShortProgrammeWithinTheSameHourInBothDirections() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        List<LiveChannels.Programme> list = Arrays.asList(p(0, 15), p(15, 30), p(30, 45), p(45, 60));
        for (int i = 1; i < list.size(); i++) {
            t.move(1, list);
            assertSame(list.get(i), t.programme(list));
            assertEquals(at("2026-11-17T12:00:00Z"), t.hour());
        }
        for (int i = list.size() - 2; i >= 0; i--) {
            t.move(-1, list);
            assertSame(list.get(i), t.programme(list));
        }
    }

    @Test public void skipsAcrossLongProgrammeInOnePress() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        List<LiveChannels.Programme> list = Arrays.asList(p(-30, 180), p(180, 210));
        t.move(1, list);
        assertSame(list.get(1), t.programme(list));
        t.move(-1, list);
        assertSame(list.get(0), t.programme(list));
    }

    @Test public void doesNotWrapAtFirstOrLastProgramme() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        List<LiveChannels.Programme> list = Arrays.asList(p(0, 15), p(15, 30));
        t.move(-1, list);
        assertSame(list.get(0), t.programme(list));
        t.move(1, list);
        t.move(1, list);
        assertSame(list.get(1), t.programme(list));
    }

    @Test public void crossesGapsAndUsesTheSameTimeWhenChangingChannels() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        List<LiveChannels.Programme> a = Arrays.asList(p(0, 15), p(45, 60));
        List<LiveChannels.Programme> b = Arrays.asList(p(0, 30), p(30, 75));
        t.move(1, a);
        assertSame(a.get(1), t.programme(a));
        assertSame(b.get(1), t.programme(b));
        assertEquals(p(45, 60).start, t.cursor());
        t.move(-1, b);
        assertSame(b.get(0), t.programme(b));
    }

    @Test public void gapsSelectNextListingAndAfterLastSelectsLastListing() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        List<LiveChannels.Programme> list = Arrays.asList(p(-60, -15), p(15, 30));
        assertSame(list.get(1), t.programme(list));
        assertSame(list.get(0), t.programme(Collections.singletonList(list.get(0))));
    }

    @Test public void refreshedListingsKeepTheSameFocusedTime() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        t.move(1, Arrays.asList(p(0, 15), p(15, 30)));
        List<LiveChannels.Programme> refreshed = Arrays.asList(p(0, 15), p(15, 30));
        assertSame(refreshed.get(1), t.programme(refreshed));
    }

    @Test public void overlappingListingsCanEachBeSelectedWithoutGettingStuck() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        List<LiveChannels.Programme> list = Arrays.asList(p(0, 60), p(15, 30), p(30, 90));
        t.move(1, list);
        assertSame(list.get(1), t.programme(list));
        t.move(1, list);
        assertSame(list.get(2), t.programme(list));
        t.move(-1, list);
        assertSame(list.get(1), t.programme(list));
    }

    @Test public void ignoresProgrammesOutsideTheDownloadedWindow() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        List<LiveChannels.Programme> list = Arrays.asList(p(-180, -120), p(0, 15), p(15, 30), p(2200, 2300));
        t.move(-1, list);
        assertSame(list.get(1), t.programme(list));
        t.move(1, list);
        t.move(1, list);
        assertSame(list.get(2), t.programme(list));
    }

    @Test public void emptyScheduleStillScrollsWithinTheDownloadedWindow() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        assertNull(t.programme(null));
        assertNull(t.programme(Collections.emptyList()));
        t.move(1, null);
        assertEquals(NOW + TvGuideTimeline.HOUR, t.cursor());
        for (int i = 0; i < 100; i++) t.move(-1, null);
        assertEquals(NOW - LiveChannels.KEEP_BEFORE_MS, t.cursor());
        for (int i = 0; i < 100; i++) t.move(1, null);
        assertEquals(NOW + LiveChannels.KEEP_AHEAD_MS - 1, t.cursor());
    }

    @Test public void movingBetweenVisibleCardsDoesNotScroll() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        List<LiveChannels.Programme> list = Arrays.asList(p(0, 15), p(15, 30), p(30, 45));
        long start = t.hour();
        assertEquals(start, t.windowStart(start, TvGuideTimeline.HOUR, list));
        t.move(1, list);
        assertEquals(start, t.windowStart(start, TvGuideTimeline.HOUR, list));
        t.move(1, list);
        assertEquals(start, t.windowStart(start, TvGuideTimeline.HOUR, list));
    }

    @Test public void scrollsOnlyEnoughToRevealOffscreenCardInEitherDirection() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        List<LiveChannels.Programme> list = Arrays.asList(p(0, 15), p(45, 75));
        long start = t.hour();
        t.move(1, list);
        long scrolled = t.windowStart(start, TvGuideTimeline.HOUR, list);
        assertEquals(start + 15 * MINUTE, scrolled);
        t.move(-1, list);
        assertEquals(start, t.windowStart(scrolled, TvGuideTimeline.HOUR, list));
    }

    @Test public void longCardsShowTheirStartOnEntryButDoNotScrollWhenAlreadyVisible() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        long start = t.hour();
        List<LiveChannels.Programme> list = Arrays.asList(p(0, 15), p(15, 180));
        t.move(1, list);
        assertEquals(start, t.windowStart(start, TvGuideTimeline.HOUR, list));
        List<LiveChannels.Programme> other = Collections.singletonList(p(180, 360));
        assertEquals(other.get(0).start, t.windowStart(start, TvGuideTimeline.HOUR, other));
    }

    @Test public void viewportDoesNotScrollBeyondEitherEndOfTheRuler() {
        TvGuideTimeline t = new TvGuideTimeline(NOW, MALAYSIA);
        assertTrue(t.windowStart(Long.MIN_VALUE / 2, TvGuideTimeline.HOUR, null) >= t.first);
        assertTrue(t.windowStart(Long.MAX_VALUE / 2, TvGuideTimeline.HOUR, null) <= t.last);
    }

    @Test public void localHoursWorkInHalfHourTimezonesAndProgrammesCrossMidnight() {
        TvGuideTimeline t = new TvGuideTimeline(at("2026-11-17T18:15:00Z"), ZoneId.of("Asia/Kolkata"));
        assertEquals(at("2026-11-17T17:30:00Z"), t.hour());
        List<LiveChannels.Programme> list = Arrays.asList(p(360, 390), p(390, 420));
        t.move(1, list);
        assertEquals(at("2026-11-17T18:30:00Z"), t.hour());
        assertSame(list.get(1), t.programme(list));
    }
}
