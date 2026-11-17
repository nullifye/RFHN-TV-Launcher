package my.rafhan.app.tvhome;

import org.junit.Test;

import static my.rafhan.app.tvhome.TvStartupProgress.Stage.*;
import static org.junit.Assert.*;

public class TvStartupProgressTest {
    @Test public void startsWithChannelNameAndReportsEachStageLabel() {
        TvStartupProgress progress = new TvStartupProgress();
        progress.reset("TV1");
        assertEquals("Tune in to TV1", progress.label());
        TvStartupProgress.Stage[] stages = {PAGE_VISIBLE, PAGE_LOADED, PLAYER_READY,
                VIDEO_ATTACHED, MEDIA_METADATA_READY};
        String[] labels = {"Page visible", "Page loaded", "Player ready", "Video attached", "Media metadata ready"};
        for (int i = 0; i < stages.length; i++) {
            assertTrue(progress.advance(stages[i]));
            assertEquals(labels[i], progress.label());
        }
    }

    @Test public void ignoresDuplicateStages() {
        TvStartupProgress progress = new TvStartupProgress();
        assertTrue(progress.advance(PAGE_LOADED));
        assertFalse(progress.advance(PAGE_LOADED));
        assertEquals("Page loaded", progress.label());
    }

    @Test public void latePageCallbacksNeverReplaceMoreAdvancedMediaProgress() {
        TvStartupProgress progress = new TvStartupProgress();
        assertTrue(progress.advance(VIDEO_ATTACHED));
        assertFalse(progress.advance(PAGE_VISIBLE));
        assertFalse(progress.advance(PAGE_LOADED));
        assertFalse(progress.advance(PLAYER_READY));
        assertEquals("Video attached", progress.label());
        assertTrue(progress.advance(MEDIA_METADATA_READY));
    }

    @Test public void channelChangeOrRetryResetsProgress() {
        TvStartupProgress progress = new TvStartupProgress();
        progress.advance(MEDIA_METADATA_READY);
        progress.reset("TV2");
        assertEquals("Tune in to TV2", progress.label());
        assertTrue(progress.advance(PAGE_VISIBLE));
        assertEquals("Page visible", progress.label());
    }
}
