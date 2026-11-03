package my.rafhan.app.tvhome;

import android.view.KeyEvent;

/**
 * Settings → Button Mapper: what each remapped remote button does on a single, double and long
 * press. Actions are short strings, stored per button and press in LauncherSettings
 * ("btn_<keycode>_<press>"); this class names them and keeps the rules. Pure, no Android calls.
 *
 * Everything works with USB debugging off, so the rules follow what an app can do without it. A
 * remapped button's presses are taken to tell single, double and long apart, so its own press
 * ("Normal") has to be played back; an app can only do that for some keys (normalWithoutAdb). The
 * others still take any single-press action, but get a double or long press only once their
 * single press has an action too. The arrows and OK can't be remapped (their own press can't be
 * played back, and they move around the TV); Home and Back can't be set to do nothing.
 */
final class ButtonMap {
    static final int SINGLE = 0, DOUBLE = 1, LONG = 2;
    static final String[] PRESS = {"single", "double", "long"};
    static final String[] PRESS_NAMES = {"Single press", "Double press", "Long press"};

    static final String DEFAULT = "default", NONE = "none", HOME = "home", BACK = "back", RECENTS = "recents",
            INPUTS = "inputs", SCREENSAVER = "screensaver", SLEEP = "sleep", QUICK_MENU = "quickmenu",
            SCREENSHOT = "screenshot", SETTINGS = "settings", WIFI = "settings:wifi", SOUND = "settings:sound",
            APPS = "settings:apps", HOME_SETTINGS = "tvhome:settings", POWER_MENU = "powermenu",
            ASSISTANT = "assistant", VOLUME_UP = "volume:up", VOLUME_DOWN = "volume:down",
            MUTE = "mute", PLAY_PAUSE = "media:playpause", NEXT = "media:next", PREVIOUS = "media:previous",
            OVERLAY = "overlay", NET_TRAFFIC = "nettraffic", APP = "app:";

    /** The fixed actions, in the order Settings lists them (an app and a key come after). */
    static final String[] ACTIONS = {DEFAULT, NONE, HOME, BACK, RECENTS, SETTINGS, WIFI, SOUND, APPS, HOME_SETTINGS,
            POWER_MENU, ASSISTANT, INPUTS, SCREENSAVER, SLEEP, QUICK_MENU, SCREENSHOT, MUTE, VOLUME_UP, VOLUME_DOWN,
            PLAY_PAUSE, NEXT, PREVIOUS, OVERLAY, NET_TRAFFIC};
    private static final String[] ACTION_NAMES = {"Normal", "Nothing", "Home", "Back", "Recent apps",
            "Android settings", "Wi-Fi settings", "Sound settings", "App settings", "TV Home settings",
            "Power menu", "Assistant", "Inputs (HDMI, antenna)", "Start the screen saver", "Sleep", "Quick menu",
            "Take a screenshot", "Mute", "Volume up", "Volume down", "Play / pause", "Next track", "Previous track", "Info overlay on or off", "Net traffic on or off"};

    private ButtonMap() {}

    /** The settings key for one button's press. */
    static String key(int code, int press) {
        return "btn_" + code + "_" + PRESS[press];
    }

    /** The arrows and OK: never remapped. */
    static boolean navigation(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return true;
            default:
                return false;
        }
    }

    /** Whether the button can be added at all (not the arrows or OK, not an unknown key). */
    static boolean remappable(int code) {
        return code > 0 && !navigation(code);
    }

    /**
     * Keys whose own press TV Home can play back without USB debugging (ButtonActions.normal):
     * Back, Home and Recents (accessibility actions), volume and media keys (Android's audio
     * service), Input (TV Home's picker), Settings and Notifications.
     */
    static boolean normalWithoutAdb(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_TV_INPUT:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_NOTIFICATION:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether a double or long press can have an action, given the single press's: always for a
     * key whose own press can be played back; otherwise only once the single press has one.
     */
    static boolean extraPressAllowed(int code, String single) {
        return normalWithoutAdb(code) || !DEFAULT.equals(single);
    }

    /** Whether the action is allowed for this button's press (single: the single press's action). */
    static boolean allowed(int code, int press, String action, String single) {
        if (DEFAULT.equals(action)) return true;
        if (!remappable(code)) return false;
        if (press != SINGLE && !extraPressAllowed(code, single)) return false;
        return !(NONE.equals(action) && press == SINGLE
                && (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_BACK));
    }

    /** "Home", "Open Netflix"; appName gives an app's name from its package. */
    static String name(String action, java.util.function.Function<String, String> appName) {
        if (action == null) return ACTION_NAMES[0];
        if (action.startsWith(APP)) return "Open " + appName.apply(action.substring(APP.length()));
        for (int i = 0; i < ACTIONS.length; i++) if (ACTIONS[i].equals(action)) return ACTION_NAMES[i];
        return ACTION_NAMES[0]; // unknown (a newer version's): treated as Normal
    }

    /** A button's name as the remote shows it: "OK", "Red", "Play/Pause"; otherwise from Android's name. */
    static String keyName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: return "OK";
            case KeyEvent.KEYCODE_DPAD_UP: return "Up";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "Down";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "Left";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "Right";
            case KeyEvent.KEYCODE_BACK: return "Back";
            case KeyEvent.KEYCODE_HOME: return "Home";
            case KeyEvent.KEYCODE_MENU: return "Menu";
            case KeyEvent.KEYCODE_TV_INPUT: return "Input";
            case KeyEvent.KEYCODE_PROG_RED: return "Red";
            case KeyEvent.KEYCODE_PROG_GREEN: return "Green";
            case KeyEvent.KEYCODE_PROG_YELLOW: return "Yellow";
            case KeyEvent.KEYCODE_PROG_BLUE: return "Blue";
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "Play/Pause";
            case KeyEvent.KEYCODE_VOLUME_MUTE: return "Mute";
            case KeyEvent.KEYCODE_CAPTIONS: return "Subtitles";
            default: return readable(code);
        }
    }

    /** KEYCODE_CHANNEL_UP → "Channel up"; a code Android has no name for → "Button 1234". */
    static String readable(int code) {
        String raw = KeyEvent.keyCodeToString(code);
        if (raw == null || !raw.startsWith("KEYCODE_")) return "Button " + code;
        String s = raw.substring(8).replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("media ")) s = s.substring(6);
        if (s.startsWith("tv ")) s = "TV " + s.substring(3);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
