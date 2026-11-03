package my.rafhan.app.tvhome;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Gives the phone remote Back and Home inside any app (performGlobalAction), and makes the
 * remote's Input button work: the TV's hotkey dispatcher opens one named screen of the stock
 * launcher for it (com.google.android.tvlauncher/.inputs.InputsPanelActivity), which is
 * disabled once TV Home replaces it, so the button did nothing. This catches that key first and
 * opens InputsActivity instead.
 *
 * It also runs Settings → Button Mapper: the buttons remapped there are taken (PressDetector
 * tells their single, double and long presses apart) and do their action (ButtonActions); every
 * other key passes through untouched. While Settings waits for "Press a button", the next key
 * goes there instead. On TV Home's Button Mapper page every button works normally, so a
 * remapping can always be undone there. It doesn't read window content and ignores events.
 * Enabled by build-and-install.sh and the setup app.
 */
public class RemoteAccessibilityService extends AccessibilityService {
    private static volatile RemoteAccessibilityService instance;
    /** Settings waiting for "Press a button": gets the next key's code (on its release). */
    private static volatile java.util.function.IntConsumer capture;
    /** TV Home's Button Mapper page is on screen (with home in front): remapping is paused. */
    static volatile boolean editing;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.List<PressDetector.Fire> fired = new java.util.ArrayList<>();
    private final Runnable tick = this::tick;
    private ButtonActions buttons;
    private PressDetector presses;
    private int captureDown = -1; // the key pressed while capturing: its release is the answer
    private final LauncherSettings.Listener settingsChanged = key -> {
        if (!key.startsWith("btn_") || buttons == null) return;
        presses.flush(fired); // a press under way ends with the old settings
        fire();
        buttons.reload();
        presses.setTimes(LauncherSettings.choice(this, LauncherSettings.BUTTON_LONG_MS),
                LauncherSettings.choice(this, LauncherSettings.BUTTON_DOUBLE_MS));
    };

    /** The next key goes to l (its keycode, on release) instead of anywhere else; null stops. */
    static void capture(java.util.function.IntConsumer l) {
        capture = l;
    }

    static boolean isOn() {
        return instance != null;
    }

    private static boolean checkScheduled, reconnected;

    /**
     * reconnect() if it isn't connected, once per process start, 10 s from now (time for Android
     * to connect it by itself first). Its own Handler: MainActivity clears its queue when it's
     * destroyed, which an update can do.
     */
    static synchronized void checkSoon(Context ctx) {
        if (checkScheduled) return;
        checkScheduled = true;
        Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isOn()) reconnect(app);
        }, 10_000);
    }

    /**
     * Switched on in Settings but not connected, or connected without the abilities this version
     * asks for (an update that adds one, like catching the Input key: Android keeps the old ones
     * until the service is switched off and on): takes it off the list and puts it back a second
     * later, which reconnects it with its current abilities. The other services stay on
     * throughout. Needs WRITE_SECURE_SETTINGS (given for the screen saver); switched off by the
     * user: left off. Once per process, so it can't loop.
     */
    static synchronized void reconnect(Context ctx) {
        if (reconnected || ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ContentResolver cr = ctx.getContentResolver();
        String all = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (all == null) return;
        ComponentName ours = new ComponentName(ctx, RemoteAccessibilityService.class);
        StringBuilder others = new StringBuilder();
        boolean listed = false;
        for (String s : all.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (ours.equals(c)) {
                listed = true;
            } else if (!s.isEmpty()) {
                if (others.length() > 0) others.append(':');
                others.append(s);
            }
        }
        if (!listed) return;
        reconnected = true;
        try {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.toString());
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, all);
                } catch (SecurityException ignored) {
                }
            }, 1_000);
            android.util.Log.i("TvHome", "accessibility service: reconnecting it");
        } catch (SecurityException ignored) { // the permission was taken away meanwhile
        }
    }

    /** GLOBAL_ACTION_BACK / GLOBAL_ACTION_HOME. False if the service isn't enabled. */
    static boolean global(int action) {
        RemoteAccessibilityService s = instance;
        return s != null && s.performGlobalAction(action);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        if (buttons == null) {
            buttons = new ButtonActions(this);
            presses = new PressDetector(buttons, LauncherSettings.choice(this, LauncherSettings.BUTTON_LONG_MS),
                    LauncherSettings.choice(this, LauncherSettings.BUTTON_DOUBLE_MS));
            LauncherSettings.addListener(settingsChanged);
        }
        // Connected with an older version's abilities (no key filtering): reconnect once.
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && (info.getCapabilities()
                & android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) == 0) {
            reconnect(this);
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        LauncherSettings.removeListener(settingsChanged);
        main.removeCallbacksAndMessages(null);
        if (buttons != null) buttons.close();
        super.onDestroy();
    }

    /**
     * Settings capturing a button, then the remapped buttons, then the Input button (taken, and
     * the picker opened on its release). Everything else passes through.
     */
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode(), action = e.getAction();
        java.util.function.IntConsumer cap = capture;
        if (cap != null) {
            if (action == KeyEvent.ACTION_DOWN) {
                captureDown = code;
            } else if (action == KeyEvent.ACTION_UP && code == captureDown) {
                capture = null; // one key; the release of the OK that started it doesn't count
                captureDown = -1;
                main.post(() -> cap.accept(code));
            }
            return true;
        }
        if (presses != null && !(editing && RemoteBridge.isHome())) {
            long now = SystemClock.uptimeMillis();
            boolean taken = action == KeyEvent.ACTION_DOWN ? presses.down(code, now, fired)
                    : action == KeyEvent.ACTION_UP && presses.up(code, now, fired);
            fire();
            schedule();
            if (taken) return true;
        }
        if (code != KeyEvent.KEYCODE_TV_INPUT) return false;
        if (action == KeyEvent.ACTION_UP) {
            try {
                startActivity(new Intent(this, InputsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) { // can't open from here: the key is lost, as before
            }
        }
        return true;
    }

    private void tick() {
        presses.tick(SystemClock.uptimeMillis(), fired);
        fire();
        schedule();
    }

    private void schedule() {
        main.removeCallbacks(tick);
        long at = presses.deadline();
        if (at >= 0) main.postAtTime(tick, at);
    }

    /** Runs the presses that finished. */
    private void fire() {
        for (PressDetector.Fire f : fired) buttons.run(f.code, f.press);
        fired.clear();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
