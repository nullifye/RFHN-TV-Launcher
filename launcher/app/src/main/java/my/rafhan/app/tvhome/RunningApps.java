package my.rafhan.app.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Running apps for the screen above home. Apps can't see other apps' processes, so the list is
 * read from `dumpsys activity lru` (DUMP is granted by build-and-install.sh). Force close uses
 * `am force-stop` over the loopback ADB connection when USB debugging is on; otherwise Android's
 * background kill, which can't stop apps that are on screen or running a service.
 */
final class RunningApps {
    static final class App {
        final AppEntry entry;
        final List<Integer> pids = new ArrayList<>();
        int rank;          // higher = more active
        String state = "";
        long pssKb;
        boolean hidden;    // on the user's "don't list" list (hold OK)

        App(AppEntry entry) {
            this.entry = entry;
        }
    }

    // Android 10: "#30: prcp FGS 27297:my.rafhan.app.radiostream/u0a274 activity=recents"
    // Allwinner 11 also pads single-digit indices and adds a capability column:
    // "# 7: prcp FGS --- 4493:com.softmedia.receiver/u0a69".
    private static final Pattern LINE = Pattern.compile(
            "^#\\s*\\d+:\\s+(\\S+(?:\\+\\s*\\d+)?)\\s+(\\S+)\\s+(?:\\S+\\s+)?(\\d+):([\\w.]+)(?::[\\w.]+)?/\\S+(.*)$");

    static final class ProcessRow {
        final String pkg, adj, proc, rest;
        final int pid;

        ProcessRow(Matcher m) {
            adj = m.group(1);
            proc = m.group(2);
            pid = Integer.parseInt(m.group(3));
            pkg = m.group(4);
            rest = m.group(5);
        }
    }

    /** Pure parser shared by the reader and tests using real firmware output. */
    static ProcessRow parseLine(String line) {
        if (line == null) return null;
        Matcher m = LINE.matcher(line.trim());
        if (!m.matches()) return null;
        try {
            ProcessRow row = new ProcessRow(m);
            return row.pid > 0 ? row : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final String PREFS = "running_apps", HIDDEN = "hidden";

    private RunningApps() {}

    /** Packages the user chose not to list (hold OK → Hide from this list). */
    static Set<String> hidden(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(HIDDEN, new HashSet<>()));
    }

    static void setHidden(Context ctx, String pkg, boolean hide) {
        Set<String> s = hidden(ctx);
        if (hide) s.add(pkg);
        else s.remove(pkg);
        prefs(ctx).edit().putStringSet(HIDDEN, s).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Launchable apps that have a process, most active first; hidden ones are flagged, not
     * dropped. Blocking: call off the main thread.
     */
    static List<App> list(Context ctx) {
        Map<String, AppEntry> launchable = new HashMap<>();
        for (AppEntry e : AppRepository.load(ctx)) launchable.put(e.pkg, e);
        // Apps with an enabled accessibility service (Button Mapper): force-closing them unbinds
        // the service and the remote's remapped buttons stop working, so they aren't offered.
        String a11y = android.provider.Settings.Secure.getString(ctx.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (a11y != null) for (String svc : a11y.split(":")) {
            int slash = svc.indexOf('/');
            if (slash > 0) launchable.remove(svc.substring(0, slash));
        }
        Map<String, App> byPkg = new LinkedHashMap<>();
        try {
            Process p = new ProcessBuilder("dumpsys", "activity", "lru").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = r.readLine()) != null; ) {
                    ProcessRow row = parseLine(line);
                    if (row == null) continue;
                    String pkg = row.pkg;
                    int pid = row.pid;
                    AppEntry e = launchable.get(pkg);
                    if (e == null || pkg.equals(ctx.getPackageName()) || pid <= 0) continue;
                    App a = byPkg.get(pkg);
                    if (a == null) byPkg.put(pkg, a = new App(e));
                    a.pids.add(pid);
                    int rank = rank(row.adj, row.proc, row.rest);
                    if (rank > a.rank || a.state.isEmpty()) {
                        a.rank = rank;
                        a.state = label(rank);
                    }
                }
            }
            p.destroy();
        } catch (Exception ignored) {
        }
        List<App> out = new ArrayList<>(byPkg.values());
        Set<String> hidden = hidden(ctx);
        for (App a : out) a.hidden = hidden.contains(a.entry.pkg);
        memory(ctx, out);
        out.sort((x, y) -> x.rank != y.rank ? Integer.compare(y.rank, x.rank) : Long.compare(y.pssKb, x.pssKb));
        return out;
    }

    private static int rank(String adj, String proc, String rest) {
        if (proc.equals("TOP")) return 4; // "vis" alone can be an app bound by another one (Play Store)
        if (proc.equals("FGS") || proc.equals("BFGS") || adj.startsWith("prcp")) return 3;
        if (adj.startsWith("vis") || adj.startsWith("prev") || adj.startsWith("hvy")) return 2;
        return 1;
    }

    private static String label(int rank) {
        switch (rank) {
            case 4: return "On screen";
            case 3: return "Service";
            case 2: return "Recent";
            default: return "Cached";
        }
    }

    /**
     * Resident memory per app. The app sandbox can't read other apps' memory (meminfo service
     * and /proc are off limits), so this uses the loopback ADB shell when USB debugging is on;
     * otherwise memory is left out.
     */
    private static void memory(Context ctx, List<App> apps) {
        if (apps.isEmpty()) return;
        AdbKeys adb = AdbKeys.get(ctx);
        if (adb.state() != AdbKeys.State.READY) return;
        StringBuilder cmd = new StringBuilder("for p in");
        for (App a : apps) for (int pid : a.pids) cmd.append(' ').append(pid);
        cmd.append("; do echo $p $(grep VmRSS /proc/$p/status); done");
        AdbKeys.Hid h = adb.shell("sh -c '" + cmd + "'");
        if (h == null) return;
        Map<Integer, Long> byPid = new HashMap<>();
        try {
            for (String line; (line = h.stream.getSource().readUtf8Line()) != null; ) {
                String[] f = line.trim().split("\\s+"); // "27297 VmRSS: 123456 kB"
                if (f.length >= 3 && f[1].equals("VmRSS:")) byPid.put(Integer.parseInt(f[0]), Long.parseLong(f[2]));
            }
        } catch (Exception ignored) {
        } finally {
            adb.stop(h);
        }
        for (App a : apps) for (int pid : a.pids) {
            Long kb = byPid.get(pid);
            if (kb != null) a.pssKb += kb;
        }
    }

    /** Force-closes {@code pkg}; returns a short message for the screen. Blocking. */
    static String forceStop(Context ctx, App app) {
        String pkg = app.entry.pkg;
        AdbKeys adb = AdbKeys.get(ctx);
        if (adb.state() == AdbKeys.State.READY) {
            AdbKeys.Hid h = adb.shell("am force-stop " + pkg);
            if (h != null) {
                try {
                    while (h.stream.getSource().readUtf8Line() != null) { /* until it exits */ }
                } catch (Exception ignored) {
                } finally {
                    adb.stop(h);
                }
                return app.entry.label + " closed";
            }
        }
        ctx.getSystemService(ActivityManager.class).killBackgroundProcesses(pkg);
        for (App a : list(ctx)) {
            if (a.entry.pkg.equals(pkg)) {
                return app.entry.label + " is still running. Turn on USB debugging to close apps that are playing or on screen.";
            }
        }
        return app.entry.label + " closed";
    }
}
