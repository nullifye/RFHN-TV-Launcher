package my.rafhan.app.tvhome;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class PressDetectorTest {
    private static final int RED = 183, BLUE = 186, OTHER = 19;

    /** RED: single only. BLUE: single, double and long. OTHER: not remapped. */
    private static final PressDetector.Buttons BUTTONS = new PressDetector.Buttons() {
        @Override public boolean mapped(int code) { return code == RED || code == BLUE; }
        @Override public boolean hasDouble(int code) { return code == BLUE; }
        @Override public boolean hasLong(int code) { return code == BLUE; }
    };

    private final List<PressDetector.Fire> out = new ArrayList<>();
    private final PressDetector d = new PressDetector(BUTTONS, 600, 350);

    private static PressDetector.Fire fire(int code, int press) {
        return new PressDetector.Fire(code, press);
    }

    @Test public void otherKeysPassThrough() {
        assertFalse(d.down(OTHER, 0, out));
        assertFalse(d.up(OTHER, 50, out));
        assertTrue(out.isEmpty());
        assertEquals(-1, d.deadline());
    }

    @Test public void singleOnlyFiresOnReleaseWithoutWaiting() {
        assertTrue(d.down(RED, 0, out));
        assertEquals(-1, d.deadline()); // no long press to time
        assertTrue(d.up(RED, 80, out));
        assertEquals(List.of(fire(RED, ButtonMap.SINGLE)), out);
        assertEquals(-1, d.deadline());
    }

    @Test public void singleWaitsForTheDoubleWindow() {
        d.down(BLUE, 0, out);
        d.up(BLUE, 100, out);
        assertTrue(out.isEmpty());
        assertEquals(450, d.deadline());
        d.tick(449, out);
        assertTrue(out.isEmpty());
        d.tick(450, out);
        assertEquals(List.of(fire(BLUE, ButtonMap.SINGLE)), out);
    }

    @Test public void doublePress() {
        d.down(BLUE, 0, out);
        d.up(BLUE, 100, out);
        d.down(BLUE, 300, out);
        assertEquals(-1, d.deadline()); // a second press isn't timed for a long press
        d.up(BLUE, 380, out);
        assertEquals(List.of(fire(BLUE, ButtonMap.DOUBLE)), out);
    }

    @Test public void longPressFiresOnceAndSwallowsRepeatsAndRelease() {
        assertTrue(d.down(BLUE, 0, out));
        assertEquals(600, d.deadline());
        assertTrue(d.down(BLUE, 400, out)); // auto-repeat
        d.tick(600, out);
        assertEquals(List.of(fire(BLUE, ButtonMap.LONG)), out);
        assertTrue(d.down(BLUE, 650, out));
        d.tick(900, out);
        assertTrue(d.up(BLUE, 1200, out));
        assertEquals(1, out.size());
        assertEquals(-1, d.deadline());
    }

    @Test public void anotherKeyEndsTheWaitAsASinglePress() {
        d.down(BLUE, 0, out);
        d.up(BLUE, 100, out);
        assertFalse(d.down(OTHER, 200, out));
        assertEquals(List.of(fire(BLUE, ButtonMap.SINGLE)), out);
        out.clear();
        d.down(BLUE, 300, out);
        d.up(BLUE, 350, out);
        assertTrue(d.down(RED, 400, out)); // a remapped one too
        assertEquals(List.of(fire(BLUE, ButtonMap.SINGLE)), out);
    }

    @Test public void releaseWithoutItsPressPassesThrough() {
        assertFalse(d.up(RED, 10, out)); // pressed before it was remapped
        assertTrue(out.isEmpty());
    }

    @Test public void timesCanChange() {
        d.setTimes(400, 250);
        d.down(BLUE, 0, out);
        assertEquals(400, d.deadline());
        d.up(BLUE, 100, out);
        assertEquals(350, d.deadline());
    }
}
