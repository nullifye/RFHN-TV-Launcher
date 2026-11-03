package my.rafhan.app.tvhome;

/**
 * "Prayer time": a screen saver with a big clock, the Gregorian and Hijri dates and today's
 * prayer times on black, drifting against burn-in (DreamView). The same picture LogDream falls
 * back to when it can't read the log. Chosen in the TV's Settings → Screen saver.
 */
public class ClockDream extends SaverDream {
    @Override
    Saver make(Saver.Host host) {
        return new Show(host);
    }

    static final class Show extends Saver {
        private DreamView view;

        Show(Host host) {
            super(host);
        }

        @Override
        void attach() {
            view = new DreamView(UiScale.wrap(host.context())); // starts as the big clock
            host.show(view);
        }

        @Override
        void start() {
            view.start();
        }

        @Override
        void stop() {
            if (view != null) view.stop();
        }
    }
}
