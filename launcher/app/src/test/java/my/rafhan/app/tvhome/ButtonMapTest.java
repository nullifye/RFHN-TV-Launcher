package my.rafhan.app.tvhome;

import android.view.KeyEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class ButtonMapTest {
    @Test public void keys() {
        assertEquals("btn_183_single", ButtonMap.key(183, ButtonMap.SINGLE));
        assertEquals("btn_4_long", ButtonMap.key(KeyEvent.KEYCODE_BACK, ButtonMap.LONG));
    }

    private static final String D = ButtonMap.DEFAULT;

    @Test public void arrowsAndOkAreNeverRemapped() {
        int ok = KeyEvent.KEYCODE_DPAD_CENTER;
        assertFalse(ButtonMap.remappable(ok));
        assertFalse(ButtonMap.remappable(KeyEvent.KEYCODE_DPAD_UP));
        assertFalse(ButtonMap.remappable(0)); // a button Android has no code for
        assertTrue(ButtonMap.allowed(ok, ButtonMap.SINGLE, D, D)); // Normal: always
        assertFalse(ButtonMap.allowed(ok, ButtonMap.LONG, ButtonMap.HOME, D));
        assertTrue(ButtonMap.remappable(KeyEvent.KEYCODE_PROG_RED));
    }

    /** Works with USB debugging off: a press TV Home can't play back is never left on Normal. */
    @Test public void doubleAndLongNeedTheSinglePressPlayedBackOrSet() {
        int red = KeyEvent.KEYCODE_PROG_RED, menu = KeyEvent.KEYCODE_MENU;
        assertFalse(ButtonMap.normalWithoutAdb(red));
        assertFalse(ButtonMap.allowed(red, ButtonMap.LONG, ButtonMap.SCREENSAVER, D));
        assertFalse(ButtonMap.allowed(menu, ButtonMap.DOUBLE, ButtonMap.HOME, D));
        assertTrue(ButtonMap.allowed(red, ButtonMap.LONG, ButtonMap.SCREENSAVER, ButtonMap.MUTE));
        assertTrue(ButtonMap.allowed(red, ButtonMap.SINGLE, ButtonMap.SLEEP, D));
        // Back, Home, media and volume keys are played back by Android: any press.
        assertTrue(ButtonMap.normalWithoutAdb(KeyEvent.KEYCODE_HOME));
        assertTrue(ButtonMap.allowed(KeyEvent.KEYCODE_HOME, ButtonMap.LONG, ButtonMap.SCREENSAVER, D));
        assertTrue(ButtonMap.allowed(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, ButtonMap.DOUBLE, ButtonMap.NEXT, D));
    }

    @Test public void homeAndBackNeverDoNothingOnASinglePress() {
        assertFalse(ButtonMap.allowed(KeyEvent.KEYCODE_HOME, ButtonMap.SINGLE, ButtonMap.NONE, D));
        assertFalse(ButtonMap.allowed(KeyEvent.KEYCODE_BACK, ButtonMap.SINGLE, ButtonMap.NONE, D));
        assertTrue(ButtonMap.allowed(KeyEvent.KEYCODE_BACK, ButtonMap.LONG, ButtonMap.NONE, D));
        assertTrue(ButtonMap.allowed(KeyEvent.KEYCODE_BACK, ButtonMap.SINGLE, ButtonMap.HOME, D));
        assertTrue(ButtonMap.allowed(KeyEvent.KEYCODE_PROG_RED, ButtonMap.SINGLE, ButtonMap.NONE, D));
    }

    /** name() matches ACTIONS to their names by position: every action has its own name. */
    @Test public void everyActionHasItsOwnName() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (String a : ButtonMap.ACTIONS) assertTrue(a, names.add(ButtonMap.name(a, p -> p)));
        assertEquals("Volume up", ButtonMap.name(ButtonMap.VOLUME_UP, p -> p));
        assertEquals("Net traffic on or off", ButtonMap.name(ButtonMap.NET_TRAFFIC, p -> p));
    }

    @Test public void names() {
        assertEquals("Normal", ButtonMap.name(null, p -> p));
        assertEquals("Normal", ButtonMap.name("something:new", p -> p));
        assertEquals("Start the screen saver", ButtonMap.name(ButtonMap.SCREENSAVER, p -> p));
        assertEquals("Open Netflix", ButtonMap.name(ButtonMap.APP + "com.netflix.ninja", p -> "Netflix"));
        assertEquals("OK", ButtonMap.keyName(KeyEvent.KEYCODE_DPAD_CENTER));
        assertEquals("Red", ButtonMap.keyName(KeyEvent.KEYCODE_PROG_RED));
        // Android's own names aren't available in unit tests (android.jar stubs): a number.
        assertEquals("Button 9999", ButtonMap.readable(9999));
    }
}
