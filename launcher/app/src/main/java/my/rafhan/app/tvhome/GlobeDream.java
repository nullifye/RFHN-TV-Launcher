package my.rafhan.app.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/**
 * "Globe": a screen saver with the Earth turning, lit as it is right now, with the prayer lines
 * and live dots (GlobeView). Its two maps are downloaded the first time (GlobeFeeds); until they
 * are, it shows the Prayer time picture (DreamView), like Live log does when it can't read the
 * log, and switches over once they're in. Chosen in TV Home's Settings → Screen saver.
 *
 * The remote's arrows turn the globe (GlobeView.key); any other key ends the screen saver.
 */
public class GlobeDream extends SaverDream {
    @Override
    Saver make(Saver.Host host) {
        return new Show(host);
    }

    static final class Show extends Saver {
        private final Handler main = new Handler(Looper.getMainLooper());
        private GlobeFeeds feeds;
        private Context ui;
        private GlobeView globe;
        private GlobeView.Overlay overlay;
        private DreamView fallback;
        private boolean running;

        Show(Host host) {
            super(host);
        }

        @Override
        boolean interactive() {
            return true; // keys come here: the arrows turn the globe, the rest end it
        }

        @Override
        void attach() {
            Context ctx = host.context();
            ui = UiScale.wrap(ctx);
            feeds = new GlobeFeeds(ctx);
            if (GlobeFeeds.mapsReady(ctx)) {
                showGlobe();
            } else {
                fallback = new DreamView(ui); // the big clock
                host.show(fallback);
            }
        }

        private void showGlobe() {
            FrameLayout root = new FrameLayout(ui);
            root.setBackgroundColor(0xFF000000);
            globe = new GlobeView(ui, feeds);
            globe.failed = this::cantDraw;
            overlay = new GlobeView.Overlay(ui, feeds, globe);
            root.addView(globe);
            root.addView(overlay);
            host.show(root);
        }

        @Override
        void start() {
            running = true;
            if (globe != null) {
                startGlobe();
                return;
            }
            fallback.start();
            Context app = host.context().getApplicationContext();
            Thread t = new Thread(() -> {
                if (GlobeFeeds.fetchMaps(app)) main.post(() -> {
                    if (!running || globe != null) return;
                    fallback.stop();
                    fallback = null;
                    showGlobe();
                    startGlobe();
                });
            }, "globe-maps");
            t.setDaemon(true);
            t.start();
        }

        @Override
        boolean key(KeyEvent e) {
            if (globe != null && globe.key(e)) {
                if (overlay != null) overlay.touched();
                return true;
            }
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true; // the clock before the maps are in: nothing to turn
                default:
                    return false; // any other key ends it
            }
        }

        /**
         * The GPU couldn't draw the globe: the screen saver stays as it is (clock, legend, the
         * latest edit and quake) with a line in the middle saying so, rather than a puzzling black
         * screen.
         */
        private void cantDraw() {
            if (globe != null && overlay != null) overlay.cantDraw(globe.gpu);
        }

        private void startGlobe() {
            globe.start();
            overlay.start();
            feeds.start();
        }

        @Override
        void stop() {
            running = false;
            main.removeCallbacksAndMessages(null);
            if (feeds != null) feeds.stop();
            if (globe != null) globe.stop();
            if (overlay != null) overlay.stop();
            if (fallback != null) fallback.stop();
        }
    }
}
