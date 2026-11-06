package my.rafhan.app.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/** Hotack/Allwinner projectors keep projection controls outside Android's TV Settings. */
final class ProjectorSupport {
    private ProjectorSupport() {}

    /** Only offer vendor activities that an ordinary installed app can actually open. */
    private static Intent activity(Context ctx, String pkg, String name) {
        Intent intent = new Intent(Intent.ACTION_MAIN).setClassName(pkg, name);
        try {
            ActivityInfo info = ctx.getPackageManager().getActivityInfo(intent.getComponent(), 0);
            if (info.enabled && info.applicationInfo.enabled && info.exported
                    && (info.permission == null
                    || ctx.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)) {
                return intent;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return null;
    }

    static Intent projectorSettings(Context ctx) {
        return activity(ctx, "com.htc.htcsettings", "com.htc.activity.HomeActivity");
    }

    static Intent settings(Context ctx) {
        Intent vendor = projectorSettings(ctx);
        return vendor != null ? vendor : new Intent(Settings.ACTION_SETTINGS);
    }

    /** Opens the vendor's input picker; no guessed HDMI extras or engineering menus. */
    static Intent sourcePicker(Context ctx) {
        return activity(ctx, "com.softwinner.awsource", "com.softwinner.awsource.MainActivity");
    }

    static boolean minimalDefaults(Context ctx) {
        ActivityManager am = ctx.getSystemService(ActivityManager.class);
        return minimalDefaults(Build.HARDWARE, am != null && am.isLowRamDevice(),
                projectorSettings(ctx) != null);
    }

    /** Do not change defaults on other low-RAM TVs, or trust the firmware's generic model name. */
    static boolean minimalDefaults(String hardware, boolean lowRam, boolean hasProjectorSettings) {
        return "sun50iw12p1".equals(hardware) && lowRam && hasProjectorSettings;
    }
}
