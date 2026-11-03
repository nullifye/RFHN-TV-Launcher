package my.rafhan.app.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings → Button Mapper, in RemoteAccessibilityService: the remapped buttons (kept in memory,
 * reloaded when Settings changes them) and what each action does. Everything works with USB
 * debugging off: no action uses ADB.
 *
 * "Normal" plays the button's own press again, because its press was taken to tell single,
 * double and long apart: Back, Home, Recents and Notifications through the accessibility service,
 * media and volume keys through Android's audio service, Input and Settings by opening them. Only
 * those keys get a "Normal" double or long press (ButtonMap.normalWithoutAdb).
 */
final class ButtonActions implements PressDetector.Buttons {
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(); // Sleep can block briefly
    private final Map<Integer, String[]> map = new HashMap<>();

    ButtonActions(Context ctx) {
        this.ctx = ctx;
        reload();
    }

    /** Reads the buttons again (Settings changed them). A button with only Normal actions is left alone. */
    void reload() {
        map.clear();
        for (int code : LauncherSettings.buttons(ctx)) {
            String[] a = new String[ButtonMap.PRESS.length];
            boolean any = false;
            String single = LauncherSettings.buttonAction(ctx, code, ButtonMap.SINGLE);
            for (int p = 0; p < a.length; p++) {
                String s = LauncherSettings.buttonAction(ctx, code, p);
                a[p] = ButtonMap.allowed(code, p, s, single) ? s : ButtonMap.DEFAULT;
                any |= !ButtonMap.DEFAULT.equals(a[p]);
            }
            if (any) map.put(code, a);
        }
    }

    @Override
    public boolean mapped(int code) {
        return map.containsKey(code);
    }

    @Override
    public boolean hasDouble(int code) {
        String[] a = map.get(code);
        return a != null && !ButtonMap.DEFAULT.equals(a[ButtonMap.DOUBLE]);
    }

    @Override
    public boolean hasLong(int code) {
        String[] a = map.get(code);
        return a != null && !ButtonMap.DEFAULT.equals(a[ButtonMap.LONG]);
    }

    /** Does what the button's press is set to. Main thread. */
    void run(int code, int press) {
        String[] a = map.get(code);
        String action = a != null ? a[press] : ButtonMap.DEFAULT;
        if (action.startsWith(ButtonMap.APP)) {
            openApp(action.substring(ButtonMap.APP.length()));
            return;
        }
        switch (action) {
            case ButtonMap.NONE: return;
            case ButtonMap.HOME: RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_HOME); return;
            case ButtonMap.BACK: RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_BACK); return;
            case ButtonMap.RECENTS: RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_RECENTS); return;
            case ButtonMap.INPUTS: start(new Intent(ctx, InputsActivity.class)); return;
            case ButtonMap.SCREENSAVER: screenSaver(); return;
            case ButtonMap.SLEEP: worker.execute(() -> Power.sleep(ctx)); return;
            case ButtonMap.QUICK_MENU: start(new Intent(ctx, MainActivity.class).setAction(MainActivity.ACTION_QUICK_MENU)); return;
            case ButtonMap.SCREENSHOT: screenshot(); return;
            case ButtonMap.POWER_MENU: RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG); return;
            case ButtonMap.SETTINGS:
            case ButtonMap.WIFI:
            case ButtonMap.SOUND:
            case ButtonMap.APPS:
            case ButtonMap.ASSISTANT: {
                Intent i = intent(ctx, action);
                if (i != null) start(i);
                else toast("This TV doesn't have that");
                return;
            }
            case ButtonMap.HOME_SETTINGS: start(new Intent(ctx, MainActivity.class).setAction(MainActivity.ACTION_SETTINGS)); return;
            case ButtonMap.MUTE: volume(AudioManager.ADJUST_TOGGLE_MUTE); return;
            case ButtonMap.VOLUME_UP: volume(AudioManager.ADJUST_RAISE); return;
            case ButtonMap.VOLUME_DOWN: volume(AudioManager.ADJUST_LOWER); return;
            case ButtonMap.PLAY_PAUSE: media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return;
            case ButtonMap.NEXT: media(KeyEvent.KEYCODE_MEDIA_NEXT); return;
            case ButtonMap.PREVIOUS: media(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return;
            case ButtonMap.OVERLAY: overlay(); return;
            case ButtonMap.NET_TRAFFIC: netTraffic(); return;
            default: normal(code);
        }
    }

    /** The screen an action opens (the TV's Settings pages, the assistant), or null if this TV has none. */
    static Intent intent(Context ctx, String action) {
        String[] tries;
        switch (action) {
            case ButtonMap.SETTINGS: tries = new String[] {android.provider.Settings.ACTION_SETTINGS}; break;
            case ButtonMap.WIFI: tries = new String[] {android.provider.Settings.ACTION_WIFI_SETTINGS}; break;
            case ButtonMap.SOUND: tries = new String[] {android.provider.Settings.ACTION_SOUND_SETTINGS}; break;
            case ButtonMap.APPS: tries = new String[] {android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS}; break;
            case ButtonMap.ASSISTANT: tries = new String[] {"android.intent.action.VOICE_ASSIST", Intent.ACTION_ASSIST}; break;
            default: return null;
        }
        PackageManager pm = ctx.getPackageManager();
        for (String a : tries) {
            Intent i = new Intent(a);
            if (pm.resolveActivity(i, 0) != null) return i;
        }
        return null;
    }

    /** Whether this TV can do the action (Settings lists only those). */
    static boolean available(Context ctx, String action) {
        switch (action) {
            case ButtonMap.SCREENSHOT:
                return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P; // Android's action is 9+
            case ButtonMap.SETTINGS:
            case ButtonMap.WIFI:
            case ButtonMap.SOUND:
            case ButtonMap.APPS:
            case ButtonMap.ASSISTANT:
                return intent(ctx, action) != null;
            default:
                return true;
        }
    }

    /** The button's own press, played again. */
    private void normal(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK: RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_BACK); return;
            case KeyEvent.KEYCODE_HOME: RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_HOME); return;
            case KeyEvent.KEYCODE_APP_SWITCH: RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_RECENTS); return;
            case KeyEvent.KEYCODE_NOTIFICATION: RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); return;
            case KeyEvent.KEYCODE_TV_INPUT: start(new Intent(ctx, InputsActivity.class)); return; // what TV Home does with it
            case KeyEvent.KEYCODE_SETTINGS: start(new Intent(android.provider.Settings.ACTION_SETTINGS)); return;
            case KeyEvent.KEYCODE_VOLUME_UP: volume(AudioManager.ADJUST_RAISE); return;
            case KeyEvent.KEYCODE_VOLUME_DOWN: volume(AudioManager.ADJUST_LOWER); return;
            case KeyEvent.KEYCODE_VOLUME_MUTE: volume(AudioManager.ADJUST_TOGGLE_MUTE); return;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                media(code);
                return;
            default: // can't be played back without USB debugging: Settings never leaves such a press on Normal
        }
    }

    private void media(int code) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    private void volume(int direction) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am != null) {
            am.adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
        }
    }

    private void openApp(String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) toast("That app isn't on the TV any more");
        else start(i);
    }

    /** The chosen screen saver: the TV's way, or TV Home's own preview where the TV has none. */
    private void screenSaver() {
        if (ScreenSaver.enabled(ctx) && ScreenSaver.startSystem(ctx)) return;
        Intent own = SaverActivity.intent(ctx, ScreenSaver.current(ctx));
        if (own != null) start(own);
        else toast(ScreenSaver.enabled(ctx) ? "This TV starts " + ScreenSaver.label(ctx, ScreenSaver.current(ctx))
                + " only by itself, when it's been idle" : "Turn the screen saver on first");
    }

    /** Android's own screenshot (9+, the accessibility service): saved where the TV keeps them. */
    private void screenshot() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P
                || !RemoteAccessibilityService.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)) {
            toast("This TV can't take a screenshot this way");
        }
    }

        private void overlay() {
        boolean on = !LauncherSettings.flag(ctx, LauncherSettings.STATUS_BAR);
        LauncherSettings.setFlag(ctx, LauncherSettings.STATUS_BAR, on);
        toast(!on ? "Info overlay off" : android.provider.Settings.canDrawOverlays(ctx) ? "Info overlay on"
                : "Info overlay on: it needs TV Home allowed to draw over apps");
    }

    private void netTraffic() {
        if (TrafficMonitor.running || TrafficMonitor.preparing) {
            TrafficMonitor.stop(ctx);
            toast("Net traffic: stopping");
        } else if (android.net.VpnService.prepare(ctx) == null) {
            TrafficMonitor.start(ctx);
            toast("Net traffic: starting");
        } else { // Android's VPN approval first: TV Home's Net traffic page asks for it
            start(new Intent(ctx, MainActivity.class).setAction(MainActivity.ACTION_NET_TRAFFIC));
        }
    }

    private void start(Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            toast("Couldn't open it");
        }
    }

    private void toast(String s) {
        main.post(() -> Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show());
    }

    void close() {
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
