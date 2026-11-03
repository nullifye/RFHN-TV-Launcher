package my.rafhan.app.tvhome;

import java.util.List;

/**
 * Tells a remapped button's single, double and long presses apart, for Settings → Button Mapper
 * (RemoteAccessibilityService feeds it every key). Pure: times are passed in, and the service
 * calls tick() at deadline() with a Handler.
 *
 * Only buttons with an action are handled; every other key passes straight through. A button
 * waits for a second press only if it has a double-press action, and is timed for a long press
 * only if it has a long-press action, so a button with just a single-press action fires at once
 * on its release. The presses of a remapped button never reach the app: what the button does
 * normally ("Normal") is played again by the service when that's its action.
 */
final class PressDetector {
    /** Which buttons are remapped, and which presses have actions. */
    interface Buttons {
        boolean mapped(int code);

        boolean hasDouble(int code);

        boolean hasLong(int code);
    }

    /** A press that happened: what the service should do. */
    static final class Fire {
        final int code, press; // press: ButtonMap.SINGLE, DOUBLE or LONG

        Fire(int code, int press) {
            this.code = code;
            this.press = press;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Fire && ((Fire) o).code == code && ((Fire) o).press == press;
        }

        @Override
        public int hashCode() {
            return code * 3 + press;
        }

        @Override
        public String toString() {
            return code + ":" + ButtonMap.PRESS[press];
        }
    }

    private static final int NO_KEY = -1;
    private final Buttons buttons;
    private long longMs, doubleMs;
    private int code = NO_KEY;   // the button being followed
    private boolean held;        // its down came, its up not yet
    private int taps;            // presses so far: 1, or 2 for a double
    private boolean longFired;
    private long downAt, upAt;

    PressDetector(Buttons buttons, long longMs, long doubleMs) {
        this.buttons = buttons;
        setTimes(longMs, doubleMs);
    }

    void setTimes(long longMs, long doubleMs) {
        this.longMs = longMs;
        this.doubleMs = doubleMs;
    }

    /** A key went down. True: it's taken (a remapped button). Presses that finish go to out. */
    boolean down(int key, long now, List<Fire> out) {
        if (!buttons.mapped(key)) {
            flush(out); // another key: a button waiting for its second press was a single press
            return false;
        }
        if (code != key) {
            flush(out);
        } else if (held) {
            return true; // auto-repeat while it's held
        } else if (taps == 1) {
            taps = 2; // the second press of a double
            held = true;
            downAt = now;
            return true;
        }
        code = key;
        held = true;
        taps = 1;
        longFired = false;
        downAt = now;
        return true;
    }

    /** A key came up. True: it's taken. */
    boolean up(int key, long now, List<Fire> out) {
        if (key != code || !held) return false; // its down wasn't taken (pressed before it was mapped)
        held = false;
        if (longFired) {
            reset();
        } else if (taps == 2) {
            out.add(new Fire(key, ButtonMap.DOUBLE));
            reset();
        } else if (buttons.hasDouble(key)) {
            upAt = now; // wait for a second press
        } else {
            out.add(new Fire(key, ButtonMap.SINGLE));
            reset();
        }
        return true;
    }

    /** Time passed: a long press reached, or the wait for a second press over. */
    void tick(long now, List<Fire> out) {
        if (code == NO_KEY) return;
        if (held && taps == 1 && !longFired && buttons.hasLong(code) && now - downAt >= longMs) {
            longFired = true;
            out.add(new Fire(code, ButtonMap.LONG));
        } else if (!held && taps == 1 && now - upAt >= doubleMs) {
            out.add(new Fire(code, ButtonMap.SINGLE));
            reset();
        }
    }

    /** When tick() next has something to do, or -1. */
    long deadline() {
        if (code == NO_KEY) return -1;
        if (held && taps == 1 && !longFired && buttons.hasLong(code)) return downAt + longMs;
        if (!held && taps == 1) return upAt + doubleMs;
        return -1;
    }

    /** Settings changed: forget a press under way (a button waiting for its second press fires). */
    void flush(List<Fire> out) {
        if (code != NO_KEY && !held && taps == 1) out.add(new Fire(code, ButtonMap.SINGLE));
        reset();
    }

    private void reset() {
        code = NO_KEY;
        held = false;
        taps = 0;
        longFired = false;
    }
}
