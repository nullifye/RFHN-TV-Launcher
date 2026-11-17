package my.rafhan.app.tvhome;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Programme-by-programme focus, independent of the guide's hourly ruler and viewport. */
final class TvGuideTimeline {
    static final long HOUR = 3600_000L;
    final long first, last;
    private final ZoneId zone;
    private final long from, to;
    private long cursor;
    private List<LiveChannels.Programme> selectedList;
    private LiveChannels.Programme selectedProgramme;

    TvGuideTimeline(long now, ZoneId zone) {
        this.zone = zone;
        from = now - LiveChannels.KEEP_BEFORE_MS;
        to = now + LiveChannels.KEEP_AHEAD_MS;
        first = floor(from);
        last = floor(to - 1);
        cursor = now;
    }

    private long floor(long time) {
        return Instant.ofEpochMilli(time).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli();
    }

    long hour() { return floor(cursor); }
    long cursor() { return cursor; }

    void move(int direction, List<LiveChannels.Programme> list) {
        if (direction == 0) return;
        LiveChannels.Programme current = programme(list);
        if (current == null) { // No listings: still allow browsing the empty timeline.
            cursor = Math.max(from, Math.min(to - 1, cursor + direction * HOUR));
            return;
        }
        int index = list.indexOf(current), step = direction < 0 ? -1 : 1;
        for (int i = index + step; i >= 0 && i < list.size(); i += step) {
            LiveChannels.Programme p = list.get(i);
            if (!available(p)) continue;
            cursor = Math.max(from, p.start);
            selectedList = list;
            selectedProgramme = p;
            return;
        }
    }

    private boolean available(LiveChannels.Programme p) {
        return p.end > p.start && p.end > from && p.start < to;
    }

    /** Preserve the time when changing channels; in a gap choose the next available listing. */
    LiveChannels.Programme programme(List<LiveChannels.Programme> list) {
        // Providers can have overlapping listings: remember the actual card selected, rather
        // than resolving its start time back to the preceding, still-running programme.
        if (list == selectedList && selectedProgramme != null) return selectedProgramme;
        LiveChannels.Programme previous = null;
        if (list != null) for (LiveChannels.Programme p : list) {
            if (!available(p)) continue;
            if (p.end > cursor) return p;
            previous = p;
        }
        return previous;
    }

    /** Scroll only enough to reveal the focused card, not by an hour on every key press. */
    long windowStart(long start, long width, List<LiveChannels.Programme> list) {
        width = Math.max(1, width);
        LiveChannels.Programme p = programme(list);
        long left = p == null ? cursor : Math.max(first, p.start);
        long right = p == null ? cursor + Math.min(width, HOUR) : Math.min(to, p.end);
        if (right - left > width) {
            // A long programme cannot fit: show its start on entry, but don't move an already
            // visible part just because the user changed to a channel with a longer programme.
            long anchor = Math.max(left, Math.min(right - 1, cursor));
            if (anchor < start || anchor >= start + width) start = anchor;
        } else if (left < start) {
            start = left;
        } else if (right > start + width) {
            start = right - width;
        }
        return Math.max(first, Math.min(Math.max(first, last + HOUR - width), start));
    }
}
