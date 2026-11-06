package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every launcher option in one place (prefs "settings"), edited from the Settings page (gear
 * pill). Defaults are what the launcher did before there were settings, so nothing changes until
 * an option is edited. Listeners hear about changes on the main thread, like DockConfig.
 */
final class LauncherSettings {
    // Keys
    static final String ZONE = "prayer_zone", SHOW_PRAYER = "show_prayer", SHOW_USAGE = "show_usage",
            SHOW_SYSTEM = "show_system", SHOW_NOW = "show_now_playing", CLOCK = "clock",
            USAGE_EXCLUDED = "usage_excluded", WALL_DIM = "wallpaper_dim",
            ALERT_UPDATE = "alert_update", ALERT_PLAY = "alert_play", ALERT_HDMI = "alert_hdmi", ALERT_BT = "alert_bluetooth",
            ALERT_MEMORY = "alert_memory", MEMORY_LEVEL = "memory_level", NET_BANNER = "net_banner",
            REMOTE = "remote_on", WALLPAPER = "wallpaper", WEEK_START = "week_start",
            AZAN = "azan", AZAN_TEST = "azan_test",
            PRAYER_REMINDER = "prayer_reminder",
            STATUS_BAR = "status_bar", STATUS_LEFT = "status_left", STATUS_CENTER = "status_center",
            STATUS_RIGHT = "status_right", STATUS_POS = "status_position", STATUS_SIZE = "status_size",
            STATUS_BG = "status_background", APPS_PER_ROW = "apps_per_row";
    // The Weather card (Settings → Weather): on/off, MET place id ("" = Auto), APIMS station id ("" = Nearest).
    static final String SHOW_WEATHER = "show_weather", WEATHER_PLACE = "weather_place",
            WEATHER_STATION = "weather_station", WEATHER_FX = "weather_effects";
    // The Globe screen saver's layers (Settings → Screen saver, when Globe is the one shown).
    static final String GLOBE_PRAYER_LINES = "globe_prayer_lines", GLOBE_MAKKAH = "globe_makkah",
            GLOBE_ZONE = "globe_zone", GLOBE_CITY_LIGHTS = "globe_city_lights", GLOBE_CLOUDS = "globe_clouds",
            GLOBE_SNOW = "globe_snow", GLOBE_AURORA = "globe_aurora", GLOBE_EDITS = "globe_edits",
            GLOBE_QUAKES = "globe_quakes", GLOBE_ISS = "globe_iss", GLOBE_TIANGONG = "globe_tiangong",
            GLOBE_HUBBLE = "globe_hubble", GLOBE_LEGEND = "globe_legend", GLOBE_WIND = "globe_wind",
            GLOBE_GLINT = "globe_glint", GLOBE_RIM = "globe_rim", GLOBE_SKY = "globe_sky",
            GLOBE_COUNTRIES = "globe_countries", GLOBE_TRAFFIC = "globe_traffic";
    /**
     * Settings → Button Mapper (ButtonMap): the buttons added (a set of keycodes), each press's
     * action under ButtonMap.key(code, press), and the long-press and double-press times (ms).
     * Every key starts with "btn_", which listeners match on.
     */
    static final String BUTTONS = "btn_keys", BUTTON_LONG_MS = "btn_long_ms", BUTTON_DOUBLE_MS = "btn_double_ms";
    /** How the Globe turns on its own (a choice): SPIN_TWO_MINUTES or SPIN_REAL_TIME. */
    static final String GLOBE_SPIN = "globe_spin";
    static final int SPIN_TWO_MINUTES = 0, SPIN_REAL_TIME = 1;

    /** What a status-bar slot shows. */
    static final int SLOT_NONE = 0, SLOT_SPEED = 1, SLOT_UPTIME = 2, SLOT_CLOCK = 3, SLOT_ELAPSED = 4;

    /** Clock format: follow the TV, or force one. */
    static final int CLOCK_AUTO = 0, CLOCK_12 = 1, CLOCK_24 = 2;
    /** Wallpaper dim levels (alpha of the black scrim) for Off / Low / Medium / High. */
    static final int[] DIM_ALPHA = {0, 70, 115, 160};

    interface Listener {
        void onSettingChanged(String key);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private LauncherSettings() {}

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** Fresh 1 GB H713 projector installs start small. Never overwrite an existing user's choices. */
    static void initializeDeviceDefaults(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.getAll().isEmpty() || !ProjectorSupport.minimalDefaults(ctx)) return;
        SharedPreferences.Editor edit = p.edit();
        for (String key : new String[] {SHOW_PRAYER, SHOW_USAGE, SHOW_SYSTEM, SHOW_NOW,
                SHOW_WEATHER, WEATHER_FX, REMOTE, AZAN, NET_BANNER, STATUS_BAR,
                ALERT_UPDATE, ALERT_PLAY, ALERT_HDMI, ALERT_BT, ALERT_MEMORY}) {
            edit.putBoolean(key, false);
        }
        edit.putInt(PRAYER_REMINDER, 0).putString(WALLPAPER, Wallpapers.NONE).apply();
    }

    /** Not a stored setting: tells listeners (the azan in RemoteService) to play once now. */
    static void playAzanNow() {
        changed(AZAN_TEST);
    }

    private static void changed(String key) {
        main.post(() -> {
            for (Listener l : listeners) l.onSettingChanged(key);
        });
    }

    static boolean flag(Context ctx, String key) {
        return prefs(ctx).getBoolean(key, defaultFlag(key));
    }

    /**
     * Every switch starts on, except the info overlay (it would double up with an overlay app)
     * and the Globe's clouds, wind and country popups (clouds cover much of the map; wind also
     * takes more CPU; the popups are something to switch on if you like them).
     */
    private static boolean defaultFlag(String key) {
        return !STATUS_BAR.equals(key) && !GLOBE_CLOUDS.equals(key) && !GLOBE_WIND.equals(key)
                && !GLOBE_COUNTRIES.equals(key) && !GLOBE_TRAFFIC.equals(key);
    }

    static void setFlag(Context ctx, String key, boolean on) {
        prefs(ctx).edit().putBoolean(key, on).apply();
        changed(key);
    }

    static int choice(Context ctx, String key) {
        int v = prefs(ctx).getInt(key, defaultChoice(key));
        // Apps per row once also offered 4: a saved 4 reads as 5, the smallest now.
        return APPS_PER_ROW.equals(key) ? Math.max(5, Math.min(7, v)) : v;
    }

    static void setChoice(Context ctx, String key, int value) {
        prefs(ctx).edit().putInt(key, value).apply();
        changed(key);
    }

    private static int defaultChoice(String key) {
        switch (key) {
            case WALL_DIM: return 2;      // Medium: the old fixed scrim
            case MEMORY_LEVEL: return 85; // %
            case WEEK_START: return 1;    // DayOfWeek: 1 = Monday, 7 = Sunday
            case PRAYER_REMINDER: return 5; // minutes before each prayer; 0 = off
            case STATUS_LEFT: return SLOT_SPEED;
            case STATUS_CENTER: return SLOT_UPTIME;
            case STATUS_RIGHT: return SLOT_CLOCK;
            case STATUS_SIZE: return 12;  // sp: Small
            case APPS_PER_ROW: return 5;  // the dock and each app-list row
            case BUTTON_LONG_MS: return 600;
            case BUTTON_DOUBLE_MS: return 350;
            default: return 0;
        }
    }

    // ---------------------------------------------------------------- typed helpers

    /** The zone setting's "Auto" (also what a fresh install has): the zone found from the TV's location. */
    static final String ZONE_AUTO = "auto";
    private static final String ZONE_FOUND = "prayer_zone_found", WHERE = "prayer_zone_where";

    /**
     * The prayer zone in use: the one chosen, or with Auto the one found from the TV's location
     * (ZoneDetect), and until one's found the default (SGR03).
     */
    static String zone(Context ctx) {
        String z = prefs(ctx).getString(ZONE, ZONE_AUTO);
        if (!ZONE_AUTO.equals(z)) return z;
        return prefs(ctx).getString(ZONE_FOUND, ctx.getString(R.string.prayer_zone));
    }

    /** Auto has found the TV's zone (it may not have yet: no location, or no network so far). */
    static boolean zoneFound(Context ctx) {
        return prefs(ctx).contains(ZONE_FOUND);
    }

    /** Auto: never chosen, or Auto picked. */
    static boolean zoneAuto(Context ctx) {
        return ZONE_AUTO.equals(prefs(ctx).getString(ZONE, ZONE_AUTO));
    }

    /** A zone code, or ZONE_AUTO. */
    static void setZone(Context ctx, String zone) {
        prefs(ctx).edit().putString(ZONE, zone).apply();
        changed(ZONE);
    }

    /** What Auto found (null: nothing yet). Listeners hear ZONE when it changes the zone in use. */
    static void setZoneFound(Context ctx, String zone) {
        String before = zone(ctx);
        if (zone == null) prefs(ctx).edit().remove(ZONE_FOUND).apply();
        else prefs(ctx).edit().putString(ZONE_FOUND, zone).apply();
        if (!before.equals(zone(ctx))) changed(ZONE);
    }

    /**
     * Where the TV is ({latitude, longitude}, to about 100 m), as Auto last found it; null when the
     * zone was picked by hand or nothing's found yet. The weather's Auto place and Nearest station
     * use it, else the prayer zone's town. Kept only on the TV.
     */
    static double[] where(Context ctx) {
        if (!zoneAuto(ctx)) return null;
        String[] p = prefs(ctx).getString(WHERE, "").split(",");
        if (p.length != 2) return null;
        try {
            return new double[] {Double.parseDouble(p[0]), Double.parseDouble(p[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** With Auto, looks for the TV's zone (from its location) and keeps it and where the TV is. */
    static void detectZone(Context ctx) {
        // With no prayer/weather features in use, don't request a location in the background.
        if (!flag(ctx, SHOW_PRAYER) && !flag(ctx, SHOW_WEATHER) && !flag(ctx, AZAN)
                && choice(ctx, PRAYER_REMINDER) == 0) return;
        if (!zoneAuto(ctx)) return;
        Context app = ctx.getApplicationContext();
        ZoneDetect.locate(app, here -> {
            if (here == null || !zoneAuto(app)) return;
            String found = ZoneDetect.nearest(here[0], here[1]);
            if (found == null) return; // outside Malaysia: keep what was found before
            String at = String.format(Locale.ROOT, "%.3f,%.3f", here[0], here[1]);
            boolean moved = !at.equals(prefs(app).getString(WHERE, ""));
            prefs(app).edit().putString(WHERE, at).apply();
            String before = zone(app);
            setZoneFound(app, found);
            if (moved && before.equals(zone(app))) changed(WEATHER_PLACE); // same zone, new spot: the weather's Auto
        });
    }

    /** The MET forecast place's id ("Tn181"), or "" for Auto: the town nearest the TV (else the prayer zone's). */
    static String weatherPlace(Context ctx) {
        return prefs(ctx).getString(WEATHER_PLACE, "");
    }

    static void setWeatherPlace(Context ctx, String id) {
        prefs(ctx).edit().putString(WEATHER_PLACE, id).apply();
        changed(WEATHER_PLACE);
    }

    /** The APIMS station's id ("CA21B"), or "" for Nearest: the one closest to the TV (else the prayer zone). */
    static String weatherStation(Context ctx) {
        return prefs(ctx).getString(WEATHER_STATION, "");
    }

    static void setWeatherStation(Context ctx, String id) {
        prefs(ctx).edit().putString(WEATHER_STATION, id).apply();
        changed(WEATHER_STATION);
    }

    /**
     * "none", a built-in id (Wallpapers.BUILT_IN) or "custom". Default: a picture pushed over
     * ADB if there is one (as before Settings existed), else the first built-in.
     */
    static String wallpaper(Context ctx) {
        String w = prefs(ctx).getString(WALLPAPER, null);
        // A built-in that was replaced in an update falls back to the default.
        if (w != null && (w.equals(Wallpapers.NONE) || w.equals(Wallpapers.CUSTOM) || w.equals(Wallpapers.BING)
                || Wallpapers.photo(w) != null
                || (w.startsWith(Wallpapers.FILE) && Wallpapers.file(ctx, w) != null))) {
            return w; // a picture deleted from the Wallpapers folder falls back too
        }
        return Wallpapers.custom(ctx) != null ? Wallpapers.CUSTOM : Wallpapers.DEFAULT;
    }

    static void setWallpaper(Context ctx, String id) {
        prefs(ctx).edit().putString(WALLPAPER, id).apply();
        changed(WALLPAPER);
    }

    static boolean use24h(Context ctx) {
        int c = choice(ctx, CLOCK);
        return c == CLOCK_AUTO ? DateFormat.is24HourFormat(ctx) : c == CLOCK_24;
    }

    /** Apps left out of Most used (Live TV by default). */
    static Set<String> usageExcluded(Context ctx) {
        Set<String> s = prefs(ctx).getStringSet(USAGE_EXCLUDED, null);
        return s != null ? new HashSet<>(s)
                : new HashSet<>(Arrays.asList(ctx.getResources().getStringArray(R.array.usage_excluded)));
    }

    /**
     * The left-out apps that are on the TV (installed and switched on), as the pickers list them.
     * Uninstalled ones stay saved, so a reinstalled app is still left out.
     */
    static Set<String> usageExcludedPresent(Context ctx) {
        Set<String> on = new HashSet<>();
        for (String pkg : usageExcluded(ctx)) {
            try {
                if (ctx.getPackageManager().getApplicationInfo(pkg, 0).enabled) on.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return on;
    }

    static void setUsageExcluded(Context ctx, String pkg, boolean excluded) {
        Set<String> s = usageExcluded(ctx);
        if (excluded) s.add(pkg);
        else s.remove(pkg);
        prefs(ctx).edit().putStringSet(USAGE_EXCLUDED, s).apply();
        changed(USAGE_EXCLUDED);
    }

    // ---------------------------------------------------------------- remote buttons

    /** The remote buttons added in Settings → Button Mapper, in the order they were added. */
    static java.util.List<Integer> buttons(Context ctx) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (String s : prefs(ctx).getString(BUTTONS, "").split(",")) {
            try {
                if (!s.isEmpty()) out.add(Integer.parseInt(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void addButton(Context ctx, int code) {
        java.util.List<Integer> all = buttons(ctx);
        if (all.contains(code)) return;
        all.add(code);
        saveButtons(ctx, all);
    }

    /** Removes the button and its actions. */
    static void removeButton(Context ctx, int code) {
        java.util.List<Integer> all = buttons(ctx);
        all.remove(Integer.valueOf(code));
        SharedPreferences.Editor e = prefs(ctx).edit();
        for (int p = 0; p < ButtonMap.PRESS.length; p++) e.remove(ButtonMap.key(code, p));
        e.apply();
        saveButtons(ctx, all);
    }

    /** Every button back to normal (the phone remote's reset, an escape hatch). */
    static void resetButtons(Context ctx) {
        for (int code : buttons(ctx)) removeButton(ctx, code);
    }

    private static void saveButtons(Context ctx, java.util.List<Integer> all) {
        StringBuilder sb = new StringBuilder();
        for (int c : all) sb.append(sb.length() > 0 ? "," : "").append(c);
        prefs(ctx).edit().putString(BUTTONS, sb.toString()).apply();
        changed(BUTTONS);
    }

    /** A button's action for a press (ButtonMap): DEFAULT when it has none. */
    static String buttonAction(Context ctx, int code, int press) {
        return prefs(ctx).getString(ButtonMap.key(code, press), ButtonMap.DEFAULT);
    }

    static void setButtonAction(Context ctx, int code, int press, String action) {
        String k = ButtonMap.key(code, press);
        if (ButtonMap.DEFAULT.equals(action)) prefs(ctx).edit().remove(k).apply();
        else prefs(ctx).edit().putString(k, action).apply();
        changed(k);
    }
}
