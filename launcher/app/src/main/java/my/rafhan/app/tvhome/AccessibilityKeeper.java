package my.rafhan.app.tvhome;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Explicit opt-in for firmware that clears accessibility while the launcher is still running.
 * Uses the existing foreground service, never a second process or ADB connection. The master
 * switch is a secure setting so the setup/restore tools can stop it before undoing permissions.
 */
final class AccessibilityKeeper {
    static final String SETTING = "tvhome_keep_accessibility_enabled";
    private static final long CHECK_MS = 30_000, STABLE_MS = 60_000;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final String component;
    private boolean watching;
    private long nextAttemptAt, lastAttemptAt;
    private int attempts;
    private final Runnable check = this::checkNow;
    private final ContentObserver observer = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            // Coalesce the enabled-list and global-flag notifications from one settings change.
            schedule(250);
        }
    };

    AccessibilityKeeper(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        component = new ComponentName(ctx, RemoteAccessibilityService.class).flattenToString();
    }

    static boolean enabled(Context ctx) {
        return Settings.Secure.getInt(ctx.getContentResolver(), SETTING, 0) == 1;
    }

    static boolean canRestore(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean setEnabled(Context ctx, boolean on) {
        try {
            return Settings.Secure.putInt(ctx.getContentResolver(), SETTING, on ? 1 : 0);
        } catch (SecurityException e) {
            return false;
        }
    }

    static String status(Context ctx) {
        if (RemoteAccessibilityService.isOn()) return "Connected";
        if (!enabled(ctx)) return "Off";
        return canRestore(ctx) ? "Waiting to restore" : "Needs WRITE_SECURE_SETTINGS from setup";
    }

    void start() {
        if (watching) return;
        watching = true;
        for (String key : new String[] {SETTING, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                Settings.Secure.ACCESSIBILITY_ENABLED}) {
            ctx.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key), false, observer);
        }
        schedule(0);
    }

    void stop() {
        watching = false;
        main.removeCallbacks(check);
        ctx.getContentResolver().unregisterContentObserver(observer);
    }

    private void schedule(long delay) {
        main.removeCallbacks(check);
        if (watching) main.postDelayed(check, delay);
    }

    /** Bounded backoff prevents a tight write loop if vendor software keeps clearing the setting. */
    static long retryDelay(int attempts) {
        return Math.min(30_000L, 1_000L << Math.max(0, Math.min(5, attempts - 1)));
    }

    private void checkNow() {
        if (!watching) return;
        if (!enabled(ctx)) {
            attempts = 0;
            nextAttemptAt = 0;
            return; // No polling or writes at all while the user has opted out.
        }
        if (!canRestore(ctx)) {
            schedule(CHECK_MS); // The installer may grant it after the launcher has started.
            return;
        }
        String current = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean listed = AccessibilityServiceList.contains(current, component);
        boolean flag = Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        if (!listed || !flag) {
            long now = SystemClock.elapsedRealtime();
            if (now < nextAttemptAt) {
                schedule(nextAttemptAt - now);
                return;
            }
            if (now - lastAttemptAt >= STABLE_MS) attempts = 0;
            lastAttemptAt = now;
            nextAttemptAt = now + retryDelay(++attempts);
            try {
                // Re-read immediately before merging; never replace another service with ours.
                if (!listed) {
                    String latest = Settings.Secure.getString(ctx.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                    Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            AccessibilityServiceList.add(latest, component));
                }
                if (!flag) Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Log.i("TvHome", "Accessibility keeper restored TV Home (missing=" + !listed + ", disabled=" + !flag + ")");
            } catch (RuntimeException e) {
                Log.w("TvHome", "Accessibility keeper could not restore the service", e);
            }
        }
        schedule(CHECK_MS); // Normally the observer handles changes; this also covers missed notifications.
    }
}
