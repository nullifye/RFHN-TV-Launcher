package my.rafhan.app.tvhome;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hands phone-remote D-pad keys to the launcher. MainActivity attaches while it's resumed, so
 * "attached" also means "the home screen is what's on the TV"; home listeners (the info overlay)
 * hear when that changes, on the main thread, and when TV Home's screen-saver preview
 * (SaverActivity) comes and goes, which the overlay stays off for too.
 */
final class RemoteBridge {
    interface Launcher {
        /** up, down, left, right, ok or back. Called on a server thread. */
        void key(String k);
    }

    private static volatile Launcher launcher;
    private static volatile boolean saver, tvFullscreen;
    private static final CopyOnWriteArrayList<Runnable> homeListeners = new CopyOnWriteArrayList<>();

    private RemoteBridge() {}

    static void attach(Launcher l) {
        launcher = l;
        homeChanged();
    }

    static void detach(Launcher l) {
        if (launcher != l) return;
        launcher = null;
        homeChanged();
    }

    static void addHomeListener(Runnable r) {
        homeListeners.add(r);
    }

    static void removeHomeListener(Runnable r) {
        homeListeners.remove(r);
    }

    private static void homeChanged() {
        for (Runnable r : homeListeners) r.run();
    }

    static boolean isHome() {
        return launcher != null;
    }

    /** SaverActivity is (or no longer is) in front. Main thread. */
    static void saverShowing(boolean showing) {
        saver = showing;
        homeChanged();
    }

    /**
     * Live TV is (or no longer is) playing full screen. Home stays in front, but it's watched like
     * any app, so the info overlay shows over it as over other apps. Main thread.
     */
    static void tvFullscreen(boolean on) {
        if (tvFullscreen == on) return;
        tvFullscreen = on;
        homeChanged();
    }

    /**
     * Home (unless Live TV is full screen) or the screen-saver preview is in front: nothing is
     * drawn over it (the info overlay).
     */
    static boolean coverNothing() {
        return (launcher != null && !tvFullscreen) || saver;
    }

    static boolean key(String k) {
        Launcher l = launcher;
        if (l == null) return false;
        l.key(k);
        return true;
    }
}
