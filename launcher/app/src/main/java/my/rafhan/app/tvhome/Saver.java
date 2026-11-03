package my.rafhan.app.tvhome;

import android.content.Context;
import android.view.KeyEvent;
import android.view.View;

/**
 * One of TV Home's screen savers (Prayer time, Live log, Globe), apart from where it runs: as the
 * TV's screen saver (SaverDream) or, on TVs that can't start a screen saver on request (Google
 * TV has no "start now" screen), shown by TV Home itself for Settings' "Start now" (SaverActivity).
 */
abstract class Saver {
    /** Where it runs. */
    interface Host {
        Context context();

        void show(View v);

        /** Ends it (as a key does). */
        void end();
    }

    final Host host;

    Saver(Host host) {
        this.host = host;
    }

    /** Builds what it shows; the window is there. */
    abstract void attach();

    abstract void start();

    /** Stops everything; safe to call more than once. */
    abstract void stop();

    /** Keys come to key() first; false: any key ends it. */
    boolean interactive() {
        return false;
    }

    /** A key it uses itself (the Globe's arrows). False: the host ends it on the key's release. */
    boolean key(KeyEvent e) {
        return false;
    }

    /** Whether the class is one of TV Home's screen savers. */
    static boolean own(String dreamClass) {
        return ClockDream.class.getName().equals(dreamClass) || LogDream.class.getName().equals(dreamClass)
                || GlobeDream.class.getName().equals(dreamClass);
    }

    /** The saver for one of TV Home's screen-saver classes, or null for anyone else's. */
    static Saver of(String dreamClass, Host host) {
        if (ClockDream.class.getName().equals(dreamClass)) return new ClockDream.Show(host);
        if (LogDream.class.getName().equals(dreamClass)) return new LogDream.Show(host);
        if (GlobeDream.class.getName().equals(dreamClass)) return new GlobeDream.Show(host);
        return null;
    }

    /**
     * Ends on the release of a key pressed here: ending on the press would hand the release to the
     * app underneath, and releasing OK there clicks whatever is focused. A release without its
     * press here (OK on Settings' "Start now") is ignored. Returns true when it's time to end.
     */
    static final class ExitKey {
        private int down = KeyEvent.KEYCODE_UNKNOWN;

        boolean ends(KeyEvent e) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                down = e.getKeyCode();
                return false;
            }
            return e.getAction() == KeyEvent.ACTION_UP && e.getKeyCode() == down;
        }
    }
}
