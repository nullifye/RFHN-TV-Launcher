package my.rafhan.app.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/**
 * Sleep, restart and shut down, for the quick actions, the sleep timer and the phone remote.
 *
 * An app can't do these itself. Sleep goes through TV Home's accessibility service first: its
 * "lock screen" action (Android 9+) turns the screen off the way the power button does, and it
 * needs no USB debugging. Restart and shut down need the shell (the loopback ADB connection, so
 * USB debugging on); without it the accessibility service can still open the TV's own power menu,
 * to choose there. Blocking (ADB): call off the main thread.
 */
final class Power {
    /** What restart / shut down did. */
    static final int DONE = 0, MENU = 1, FAILED = 2;
    private static final String TAG = "TvHome";

    private Power() {
    }

    /** Standby. False when neither the accessibility service nor ADB is available. */
    static boolean sleep(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) {
            Log.i(TAG, "power: sleep (accessibility)");
            return true;
        }
        boolean ok = AppManager.run(ctx, "input keyevent KEYCODE_SLEEP") != null;
        Log.i(TAG, "power: sleep (ADB) " + (ok ? "ok" : "unavailable"));
        return ok;
    }

    /** Restarts or turns off the TV over ADB; without ADB, opens the TV's power menu instead (MENU). */
    static int restart(Context ctx, boolean shutDown) {
        if (AdbKeys.get(ctx).state() == AdbKeys.State.READY
                && AppManager.run(ctx, shutDown ? "reboot -p" : "reboot") != null) {
            Log.i(TAG, "power: " + (shutDown ? "shut down" : "restart") + " (ADB)");
            return DONE;
        }
        boolean menu = RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
        Log.i(TAG, "power: " + (shutDown ? "shut down" : "restart") + " without ADB: the TV's power menu "
                + (menu ? "opened" : "unavailable"));
        return menu ? MENU : FAILED;
    }
}
