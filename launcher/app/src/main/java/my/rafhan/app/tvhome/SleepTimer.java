package my.rafhan.app.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Sleep timer set from the phone remote or the home screen's quick actions: a banner a minute
 * before, then the TV goes to standby (Power.sleep: TV Home's accessibility service, else ADB).
 * Kept in memory by RemoteService; a restart of the launcher cancels it.
 */
final class SleepTimer {
    private final Context ctx;
    private final NetBanner banner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile long endsAt;

    /** The one RemoteService keeps (also for the home screen's quick actions), or null before it starts. */
    // Holds only the application context (and the service's banner), and RemoteService clears it on destroy.
    @SuppressLint("StaticFieldLeak")
    private static volatile SleepTimer current;

    SleepTimer(Context ctx, NetBanner banner) {
        this.ctx = ctx.getApplicationContext();
        this.banner = banner;
        current = this;
    }

    static SleepTimer current() {
        return current;
    }

    /** When the TV will sleep (epoch ms), or 0 when no timer is set. */
    long endsAt() {
        return endsAt;
    }

    void set(int minutes) {
        main.post(() -> {
            cancelNow();
            if (minutes <= 0) {
                banner.showMessage(R.drawable.ic_uptime, 0xFFFFFFFF, "Sleep timer cancelled", 4000);
                return;
            }
            long ms = minutes * 60_000L;
            endsAt = System.currentTimeMillis() + ms;
            if (ms > 60_000) main.postDelayed(warn, ms - 60_000);
            main.postDelayed(fire, ms);
            banner.showMessage(R.drawable.ic_uptime, 0xFFFFFFFF, "Sleep timer: the TV turns off in " + minutes + " min", 4000);
        });
    }

    void stop() {
        if (current == this) current = null;
        main.post(this::cancelNow);
    }

    private void cancelNow() {
        main.removeCallbacks(warn);
        main.removeCallbacks(fire);
        endsAt = 0;
    }

    private final Runnable warn = this::warnNow;
    private final Runnable fire = this::fireNow;

    private void warnNow() {
        banner.showMessage(R.drawable.ic_uptime, 0xFFFFC857, "Sleep timer: the TV turns off in 1 minute", 8000);
    }

    private void fireNow() {
        endsAt = 0;
        new Thread(() -> {
            if (!Power.sleep(ctx)) {
                banner.showMessage(R.drawable.ic_uptime, 0xFFFF6B6B,
                        "Sleep timer couldn't turn the TV off: turn on TV Home's accessibility service or USB debugging", 8000);
            }
        }, "sleep-timer").start();
    }
}
