package my.rafhan.app.tvhome;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class CoverPaletteTest {
    @Test public void choosesPrevalentColorWithoutAveragingHues() {
        assertEquals(0xFFFF0000, CoverPalette.dominant(new int[] {0xFFFF0000, 0xFFFF0000, 0xFF0000FF}));
    }

    @Test public void ignoresTransparentPixels() {
        assertEquals(0xFF00FF00, CoverPalette.dominant(new int[] {0x00FF0000, 0x00FF0000, 0xFF00FF00}));
    }

    @Test public void retainsNeutralArtAndHandlesMissingPixels() {
        assertEquals(0xFFCCCCCC, CoverPalette.dominant(new int[] {0xFFCCCCCC}));
        assertEquals(0xFF707070, CoverPalette.dominant(new int[0]));
    }

    @Test public void favorsColorOverBlackBorders() {
        assertEquals(0xFF40A060, CoverPalette.dominant(new int[] {0xFF000000, 0xFF000000, 0xFF40A060}));
    }
}
