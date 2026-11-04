package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The launcher's own Settings (gear pill), laid out like Matinee's: categories on the left, the
 * category's rows on the right (switches, choices that cycle on OK, pickers that open a list in
 * the right pane, actions). Every option lives in LauncherSettings; the parts of the launcher
 * that use one listen for changes. MainActivity owns the keys and the focus highlight and asks
 * this class what's focused.
 */
final class SettingsScreen {
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x8CFFFFFF, GREEN = 0xFF34C759, AMBER = 0xFFFFB340;
    private static final int TOGGLE = 0, CHOICE = 1, PICKER = 2, ACTION = 3, INFO = 4, CHECK = 5;

    private static final int HOME = 0, PRAYER = 1, WEATHER = 2, USAGE = 3, SAVER = 4, NET = 5, ALERTS = 6, STATUS = 7,
            REMOTE = 8, BUTTONS = 9, HIDDEN = 10; // About: 11
    private static final String[] CATS = {"Home screen", "Prayer times", "Weather", "Most used", "Screen saver",
            "Net traffic", "Notifications", "Info overlay", "Phone remote", "Button Mapper", "Running apps", "About"};
    private static final String[] CAT_SUBS = {"Cards, clock and wallpaper", "Zone and card",
            "Forecast and air quality", "Your top apps this week", "What the TV shows when idle",
            "Where the TV's apps connect", "What the bell tells you",
            "Speed, uptime and clock over apps", "Control the TV from your phone",
            "New actions for the remote's buttons", "Apps hidden from the list", "Version and TV settings"};
    private static final int[] CAT_ICONS = {R.drawable.ic_tv, R.drawable.ic_crescent, R.drawable.ic_storm,
            R.drawable.ic_chart, R.drawable.ic_screensaver, R.drawable.ic_signal, R.drawable.ic_bell,
            R.drawable.ic_status_bar, R.drawable.ic_phone, R.drawable.ic_remote_buttons, R.drawable.ic_ram,
            R.drawable.ic_info};
    /** Net traffic's connection list updates this often while it's on screen. */
    private static final long NET_REFRESH_MS = 3_000;
    private static final String LIVE_TV = "com.mediatek.wwtv.tvcenter";

    interface Host {
        /** Focus or content changed: the highlight should follow. */
        void onFocusMoved();

        void openTvSettings();

        /** The TV's own screen-saver settings, where "Prayer time" or "Live log" can be chosen. */
        void openScreenSaverSettings();

        /** Starts the screen saver now, as a preview. */
        void startScreenSaver();

        /** Shows a wallpaper without saving it (focus in the Wallpaper list); null: the saved one. */
        void previewWallpaper(String id);

        /** Net traffic → Start capture: Android's VPN approval if needed, then the monitor. */
        void startNetTraffic();
    }

    private static final class Row {
        final int type;
        final String label;
        String sub, key;
        int[] values;
        String[] names;
        Supplier<String> value;
        Runnable action;
        java.util.function.BooleanSupplier on; // a switch for something other than a LauncherSettings flag
        boolean checked;
        String id;         // wallpaper list: what this row shows
        boolean thumbSlot; // wallpaper list: a small preview on the left
        Bitmap thumb;
        android.graphics.drawable.Drawable icon; // an app's icon before the label (Button Mapper's app list)
        String heading;    // a separator above the row, with this small title (never focused)
        boolean busy;      // an action under way: a small spinner before its value

        Row(int type, String label) {
            this.type = type;
            this.label = label;
        }
    }

    private final Context ctx;
    private final Executor worker;
    private final Host host;
    private final float dp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<View> catViews = new ArrayList<>();
    private final List<View> rowViews = new ArrayList<>();
    private final LinearLayout rowList;
    private final ScrollView scroll, catScroll;
    private final TextView crumb, paneTitle, paneSub;

    private int col, cat, row;
    private List<Row> rows = new ArrayList<>();
    // A picker (zone list, app list) replaces the rows until Back. A picker opened from a picker
    // (Button Mapper: button → press → action → app) keeps the one under it here.
    private List<Row> picker;
    private String pickerTitle;
    private int pickerFrom;
    private final java.util.ArrayDeque<Object[]> pickerStack = new java.util.ArrayDeque<>(); // {rows, title, from}
    private boolean confirmForget;
    // Button Mapper: waiting for "Press a button" (RemoteAccessibilityService.capture), and why
    // the last button pressed couldn't be added.
    private boolean capturing, shown;
    private String captureNote;
    private final Runnable captureTimeout = () -> {
        RemoteAccessibilityService.capture(null);
        capturing = false;
        captureNote = "Nothing pressed: try again";
        refreshRows();
    };
    private String confirmPhone; // Phone remote: the phone whose Forget is waiting for a second OK

    SettingsScreen(Context ctx, FrameLayout root, Executor worker, Host host) {
        this.ctx = ctx;
        this.worker = worker;
        this.host = host;
        dp = ctx.getResources().getDisplayMetrics().density;
        // Dark backdrop: text stays readable over bright wallpapers, which still show through
        // (dimmed) as a preview while picking one.
        root.setBackgroundColor(0xB8000000);

        // Title: [gear] Settings / Category
        LinearLayout title = new LinearLayout(ctx);
        title.setGravity(Gravity.CENTER_VERTICAL);
        ImageView gear = icon(R.drawable.ic_settings, TEXT, 22);
        gear.setBackgroundResource(R.drawable.sys_bg);
        gear.setScaleType(ImageView.ScaleType.CENTER);
        title.addView(gear, new LinearLayout.LayoutParams(px(44), px(44)));
        TextView name = text(30, 300, TEXT);
        name.setText("Settings");
        LinearLayout.LayoutParams nlp = wrap();
        nlp.setMarginStart(px(16));
        title.addView(name, nlp);
        crumb = text(15, 400, DIM);
        LinearLayout.LayoutParams clp = wrap();
        clp.setMarginStart(px(12));
        title.addView(crumb, clp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(px(64), px(44), 0, 0);
        root.addView(title, tlp);

        LinearLayout body = new LinearLayout(ctx);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        blp.setMargins(px(56), px(112), px(64), px(52));
        root.addView(body, blp);

        // Left: categories.
        LinearLayout cats = new LinearLayout(ctx);
        cats.setOrientation(LinearLayout.VERTICAL);
        // Scrolls when the screen is too short for every category (720p sets at a low density).
        catScroll = new ScrollView(ctx);
        catScroll.setVerticalScrollBarEnabled(false);
        catScroll.setFocusable(false);
        catScroll.setVerticalFadingEdgeEnabled(true);
        catScroll.setFadingEdgeLength(px(24));
        catScroll.addView(cats, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(catScroll, new LinearLayout.LayoutParams(px(290), ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < CATS.length; i++) {
            LinearLayout c = new LinearLayout(ctx);
            c.setGravity(Gravity.CENTER_VERTICAL);
            c.setPadding(px(14), 0, px(14), 0);
            ImageView ic = icon(CAT_ICONS[i], TEXT, 18);
            ic.setBackgroundResource(R.drawable.np_button);
            ic.setScaleType(ImageView.ScaleType.CENTER);
            c.addView(ic, new LinearLayout.LayoutParams(px(32), px(32)));
            TextView t = text(16, 500, TEXT);
            t.setText(CATS[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(px(14));
            c.addView(t, lp);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(50));
            rlp.bottomMargin = px(4);
            cats.addView(c, rlp);
            catViews.add(c);
        }

        // Right: the category's card.
        LinearLayout pane = new LinearLayout(ctx);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setBackgroundResource(R.drawable.sys_bg);
        pane.setPadding(px(18), px(16), px(18), px(8));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        plp.setMarginStart(px(28));
        body.addView(pane, plp);
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(6), 0, px(6), 0);
        paneTitle = text(21, 600, TEXT);
        head.addView(paneTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        paneSub = text(13, 400, DIM);
        head.addView(paneSub);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = px(10);
        pane.addView(head, hlp);
        scroll = new ScrollView(ctx);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(px(24));
        rowList = new LinearLayout(ctx);
        rowList.setOrientation(LinearLayout.VERTICAL);
        rowList.setPadding(0, px(4), 0, px(12));
        scroll.addView(rowList, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        pane.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = text(12, 400, DIM);
        hint.setText("BACK  return     ▲ ▼  move     OK  select");
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        hl.setMargins(px(64), 0, 0, px(20));
        root.addView(hint, hl);
    }

    // ---------------------------------------------------------------- focus (MainActivity)

    /** Opened on Net traffic (the monitor's notification). */
    void openNetTraffic() {
        cat = NET;
        open();
    }

    /** Opened from the gear: categories, on the last category used. */
    void open() {
        shown = true;
        col = 0;
        row = 0;
        picker = null;
        pickerStack.clear();
        confirmForget = false;
        confirmPhone = null;
        render();
    }

    /** Settings closed (or left): Button Mapper works again, and stop waiting for a button. */
    void hidden() {
        shown = false;
        RemoteAccessibilityService.editing = false;
        if (capturing) {
            main.removeCallbacks(captureTimeout);
            RemoteAccessibilityService.capture(null);
            capturing = false;
        }
    }

    /** What the highlight frames. */
    View focused() {
        if (col == 0) return catViews.get(cat);
        return row < rowViews.size() ? rowViews.get(row) : null;
    }

    /** Rows again from current state (e.g. the screen saver switch after a preview). */
    void refreshRows() {
        if (picker != null) return;
        rows = rowsFor(cat);
        render();
    }

    void up() {
        move(-1);
    }

    void down() {
        move(1);
    }

    private void move(int d) {
        confirmForget = false;
        confirmPhone = null;
        if (col == 0) {
            int c = cat + d;
            if (c < 0 || c >= CATS.length) return;
            cat = c;
            row = 0;
            render();
        } else {
            int r = row + d;
            if (r < 0 || r >= current().size()) return;
            row = r;
            if (picker == null) {
                render(); // rebuilt: a pending "press OK again" goes back to normal
            } else {
                scrollToFocus(); // long lists (zones) aren't rebuilt per key press
                host.onFocusMoved();
                if (inWallpaperList()) host.previewWallpaper(picker.get(row).id);
            }
        }
    }

    void left() {
        if (col == 1) back();
    }

    void right() {
        if (col == 0 && !current().isEmpty()) {
            col = 1;
            row = 0;
            render();
        }
    }

    void select() {
        if (col == 0) {
            right();
            return;
        }
        List<Row> list = current();
        if (row >= list.size()) return;
        act(list.get(row));
    }

    /** Back: picker → rows → categories → (true) close Settings. */
    boolean back() {
        confirmForget = false;
        confirmPhone = null;
        if (picker != null) {
            closePicker();
            return false;
        }
        if (col == 1) {
            col = 0;
            render();
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- actions

    private void act(Row r) {
        switch (r.type) {
            case TOGGLE:
                if (r.on != null) r.action.run();
                else LauncherSettings.setFlag(ctx, r.key, !LauncherSettings.flag(ctx, r.key));
                break;
            case CHOICE: {
                int now = LauncherSettings.choice(ctx, r.key), i = 0;
                for (int n = 0; n < r.values.length; n++) if (r.values[n] == now) i = n;
                LauncherSettings.setChoice(ctx, r.key, r.values[(i + 1) % r.values.length]);
                break;
            }
            case PICKER:
            case ACTION:
            case CHECK:
                if (r.action != null) r.action.run();
                break;
            default:
                return; // INFO: nothing to do
        }
        if (picker == null) rows = rowsFor(cat);
        render();
    }

    private void openPicker(String title, List<Row> items, int focus) {
        if (picker != null) pickerStack.push(new Object[] {picker, pickerTitle, pickerFrom});
        pickerFrom = row;
        pickerTitle = title;
        picker = items;
        row = Math.max(0, Math.min(focus, items.size() - 1));
        render();
    }

    @SuppressWarnings("unchecked")
    private void closePicker() {
        if (inWallpaperList()) host.previewWallpaper(null); // left without OK: back to the saved one
        if (!pickerStack.isEmpty()) { // back to the picker under it, on the row it was opened from
            Object[] under = pickerStack.pop();
            row = pickerFrom;
            picker = (List<Row>) under[0];
            pickerTitle = (String) under[1];
            pickerFrom = (Integer) under[2];
            render();
            return;
        }
        picker = null;
        row = pickerFrom;
        rows = rowsFor(cat);
        render();
    }

    private List<Row> current() {
        return picker != null ? picker : rows;
    }

    // ---------------------------------------------------------------- rows per category

    /**
     * Net traffic: Start or Stop capture (with what it's doing now, and a spinner while it starts or
     * stops), Clear history (on or off), two short notes, then the latest connections, newest
     * first (TrafficMonitor). Drawing them on the Globe is the Globe's own layer switch (Screen saver).
     */
    private void netRows(List<Row> out) {
        if (TrafficMonitor.stopping && !TrafficMonitor.running && !TrafficMonitor.preparing) TrafficMonitor.stopping = false;
        boolean busy = TrafficMonitor.running || TrafficMonitor.preparing;
        Row capture = new Row(ACTION, busy ? "Stop capture" : "Start capture");
        capture.busy = TrafficMonitor.preparing || TrafficMonitor.stopping;
        // What it's doing (the value on the right already says On or Off); with nothing to say, what it is.
        capture.sub = TrafficMonitor.status.isEmpty() ? "See where the TV's apps connect" : TrafficMonitor.status;
        capture.value = () -> TrafficMonitor.stopping ? "Stopping…" : TrafficMonitor.preparing ? "Starting…"
                : TrafficMonitor.running ? "On" : "Off";
        capture.action = () -> {
            if (TrafficMonitor.stopping) return; // already on its way
            if (TrafficMonitor.running || TrafficMonitor.preparing) {
                TrafficMonitor.stop(ctx);
            } else {
                host.startNetTraffic();
            }
            refreshRows();
        };
        out.add(capture);

        List<TrafficHistory.Entry> entries = TrafficMonitor.HISTORY.snapshot(android.os.SystemClock.elapsedRealtime());
        // Always there, capture on or off: while capturing, the list starts again from now.
        Row clear = new Row(ACTION, "Clear history");
        clear.sub = entries.isEmpty() ? "Nothing to clear yet" : "Forget the connections below";
        clear.action = () -> {
            TrafficMonitor.clear();
            if (!TrafficMonitor.running && !TrafficMonitor.preparing) TrafficMonitor.status = "History cleared";
            refreshRows();
        };
        out.add(clear);

        Row how = new Row(INFO, "How it works");
        how.sub = "A local VPN on this TV: no outside server, nothing decrypted, no content saved. Countries are approximate";
        how.heading = "About";
        out.add(how);
        Row know = new Row(INFO, "Good to know");
        know.sub = "Uses the TV's one VPN slot. Starting or stopping may briefly reconnect a stream. First start downloads about 30 KB";
        out.add(know);

        Map<String, String> where = new HashMap<>();
        for (TrafficMonitor.Destination d : TrafficMonitor.destinations) {
            if (d.country != null) where.put(d.entry.ip, d.country.name);
        }
        String heading = "Last 30 minutes · " + entries.size() + (entries.size() == 1 ? " connection" : " connections");
        if (entries.isEmpty()) {
            Row none = new Row(INFO, "No connections yet");
            none.sub = busy ? "They appear here as the TV's apps go online" : "Start capture, then use the TV as usual";
            none.heading = heading;
            out.add(none);
            return;
        }
        long elapsed = android.os.SystemClock.elapsedRealtime();
        for (int i = 0; i < entries.size() && i < 40; i++) {
            TrafficHistory.Entry e = entries.get(i);
            String country = where.get(e.ip);
            Row r = new Row(ACTION, e.app + " → " + (country != null ? country : "unknown"));
            long at = System.currentTimeMillis() - Math.max(0, elapsed - e.lastSeen);
            // The server's name when the connection gave one, else its address.
            String server = e.hosts.isEmpty() ? e.ip + ":" + e.port : e.hosts.get(e.hosts.size() - 1);
            r.sub = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at)) + " · " + server
                    + " · " + e.connections + (e.connections == 1 ? " start" : " starts");
            r.value = () -> "↓ " + TrafficMonitor.bytes(e.received) + "  ↑ " + TrafficMonitor.bytes(e.sent);
            String place = country;
            r.action = () -> openConnection(e, place);
            r.id = "net:" + e.uid + "|" + e.protocol + "|" + e.ip + "|" + e.port; // focus follows it when the list re-sorts
            if (i == 0) r.heading = heading;
            out.add(r);
        }
    }

    /** OK on a connection: everything known about it, as a list (Back returns). */
    private void openConnection(TrafficHistory.Entry e, String country) {
        List<Row> items = new ArrayList<>();
        items.add(detail("App", e.app, null));
        String[] pkgs = e.uid < 0 ? null : ctx.getPackageManager().getPackagesForUid(e.uid);
        if (pkgs == null || pkgs.length == 0) {
            items.add(detail("Package", "Not known", e.uid >= 0 ? "Android user ID " + e.uid : null));
        } else if (pkgs.length == 1) {
            items.add(detail("Package", pkgs[0], null));
        } else {
            java.util.Arrays.sort(pkgs);
            StringBuilder few = new StringBuilder();
            for (int i = 0; i < pkgs.length && i < 6; i++) few.append(i > 0 ? ", " : "").append(pkgs[i]);
            if (pkgs.length > 6) few.append(", …");
            items.add(detail("Package", "Shared by " + pkgs.length + " apps",
                    "Android can't tell which of them connected: " + few));
        }
        boolean tls = "TLS".equals(e.kind), quic = "QUIC".equals(e.kind), dot = "DNS over TLS".equals(e.kind);
        String hostNote = e.hosts.isEmpty()
                ? quic ? "QUIC encrypts it" : dot ? "Private DNS: the lookups themselves are encrypted"
                : "The app didn't send one (it may connect by address)"
                : e.hosts.size() > 1 ? "Names seen on this address (newest last)" : null;
        items.add(detail("Server", e.hosts.isEmpty() ? "Not visible" : TextUtils.join(", ", e.hosts), hostNote));
        items.add(detail("Address", e.ip + ":" + e.port + (e.protocol == 6 ? " · TCP" : e.protocol == 17 ? " · UDP" : ""),
                service(e.port)));
        String kind = e.kind == null ? (e.protocol == 17 ? "UDP" : "TCP") + ", not recognised"
                : tls ? "HTTPS (TLS)" + (e.request != null ? " · asks for " + e.request : "")
                : quic ? "QUIC (HTTP/3)" : e.kind;
        items.add(detail("Protocol", kind, null));
        if ("HTTP".equals(e.kind) && e.request != null) {
            items.add(detail("Request", e.request, "Plain HTTP: the request line is visible; its data isn't kept"));
        } else if ("DNS".equals(e.kind) && e.request != null) {
            items.add(detail("Looked up", e.request, "Plain DNS"));
        } else {
            items.add(detail("Request", "Encrypted", tls || quic || dot
                    ? "HTTPS hides the path and the data: only the server's name is sent openly" : null));
        }
        items.add(detail("Country", country != null ? country : "Not known", "Approximate, by IP address"));
        long now = android.os.SystemClock.elapsedRealtime(), wall = System.currentTimeMillis();
        DateFormat t = DateFormat.getTimeInstance(DateFormat.MEDIUM);
        String first = e.firstSeen < 0 ? "—" : t.format(new Date(wall - Math.max(0, now - e.firstSeen)));
        items.add(detail("Seen", first + " – " + t.format(new Date(wall - Math.max(0, now - e.lastSeen))),
                e.connections + (e.connections == 1 ? " connection started" : " connections started") + " in the last 30 minutes"));
        items.add(detail("Data", "↓ " + TrafficMonitor.bytes(e.received) + "  ↑ " + TrafficMonitor.bytes(e.sent),
                "Received and sent, including protocol overhead"));
        openPicker(e.app, items, 0);
    }

    private static Row detail(String label, String value, String sub) {
        Row r = new Row(INFO, label);
        r.value = () -> value;
        r.sub = sub;
        return r;
    }

    /** What a well-known port usually is, or null. */
    private static String service(int port) {
        switch (port) {
            case 443: return "Port 443: HTTPS";
            case 80: return "Port 80: HTTP";
            case 853: return "Port 853: DNS over TLS";
            case 53: return "Port 53: DNS";
            case 5228: case 5229: case 5230: return "Google Play services (notifications)";
            case 8008: case 8009: return "Google Cast";
            case 123: return "Port 123: time (NTP)";
            default: return null;
        }
    }

    /**
     * While Net traffic is on screen, its rows follow the monitor: every half second while it starts
     * or stops, else every NET_REFRESH_MS; rebuilt only when something changed, so the spinner
     * keeps turning instead of restarting.
     */
    private final Runnable netTick = new Runnable() {
        @Override
        public void run() {
            if (cat != NET || picker != null || !rowList.isShown()) return;
            if (netState().equals(netShown)) {
                main.postDelayed(this, netBusy() ? 500 : NET_REFRESH_MS);
                return;
            }
            // Newest first means the list re-sorts: keep the focus on the same connection.
            String keep = col == 1 && row < rows.size() ? rows.get(row).id : null;
            rows = rowsFor(cat);
            if (keep != null) {
                for (int i = 0; i < rows.size(); i++) {
                    if (keep.equals(rows.get(i).id)) {
                        row = i;
                        break;
                    }
                }
            }
            render(); // posts the next tick
        }
    };
    private String netShown = "";

    private static boolean netBusy() {
        return TrafficMonitor.preparing || TrafficMonitor.stopping;
    }

    /** What the Net traffic rows show, in one string: a change means rebuild. */
    private static String netState() {
        long bytes = 0;
        List<TrafficHistory.Entry> e = TrafficMonitor.HISTORY.snapshot(android.os.SystemClock.elapsedRealtime());
        for (TrafficHistory.Entry x : e) bytes += x.sent + x.received;
        return TrafficMonitor.running + "|" + TrafficMonitor.preparing + "|" + TrafficMonitor.stopping + "|"
                + TrafficMonitor.status + "|" + e.size() + "|" + bytes + "|" + TrafficMonitor.destinations.size();
    }

    private List<Row> rowsFor(int c) {
        List<Row> out = new ArrayList<>();
        switch (c) {
            case HOME: homeRows(out); break;
            case PRAYER: prayerRows(out); break;
            case WEATHER: weatherRows(out); break;
            case SAVER: saverRows(out); break;
            case NET: netRows(out); break;
            case USAGE: usageRows(out); break;
            case ALERTS: alertRows(out); break;
            case STATUS: statusRows(out); break;
            case REMOTE: remoteRows(out); break;
            case BUTTONS: buttonRows(out); break;
            case HIDDEN: hiddenRows(out); break;
            default: aboutRows(out); break;
        }
        return out;
    }

    /**
     * The TV's screen saver (ScreenSaver): on/off, which one (OK cycles Prayer time → Live log →
     * Globe → the TV's others), a preview, and with Globe chosen, a switch for each of its layers. Some TVs can't open their screen-saver page from another
     * app, so with WRITE_SECURE_SETTINGS TV Home sets it itself; without, it opens the TV's Settings.
     */
    private void saverRows(List<Row> out) {
        android.content.ComponentName current = ScreenSaver.current(ctx);
        String name = ScreenSaver.label(ctx, current);
        if (ScreenSaver.canSet(ctx)) {
            Row on = new Row(TOGGLE, "Screen saver");
            on.sub = "Starts when the TV has been idle for a while";
            on.on = () -> ScreenSaver.enabled(ctx);
            on.action = () -> ScreenSaver.setEnabled(ctx, !ScreenSaver.enabled(ctx));
            out.add(on);
            Row show = new Row(PICKER, "Show");
            // What the chosen one shows, so the row explains itself as OK cycles.
            String what = ScreenSaver.prayer(ctx).equals(current) ? "Big clock, dates and prayer times"
                    : ScreenSaver.log(ctx).equals(current) ? "The TV's live log, scrolling on black"
                    : ScreenSaver.globe(ctx).equals(current) ? "The Earth now, with live dots. The arrows turn it"
                    : "One of the TV's own screen savers";
            show.sub = what;
            show.value = () -> name;
            show.action = () -> ScreenSaver.next(ctx);
            out.add(show);
        } else {
            Row pick = new Row(ACTION, "Choose a screen saver");
            pick.sub = "In the TV's Settings: \"Prayer time\" (clock and prayer times), \"Live log\" or \"Globe\"";
            pick.value = () -> name;
            pick.action = host::openScreenSaverSettings;
            out.add(pick);
        }
        Row now = new Row(ACTION, "Start now");
        now.sub = "Preview it: any key ends it";
        now.action = host::startScreenSaver;
        out.add(now);
        if (ScreenSaver.globe(ctx).equals(current)) {
            Row turning = choice(LauncherSettings.GLOBE_SPIN, "Turning",
                    "A spin every 2 minutes, or the Earth's real speed",
                    new int[]{LauncherSettings.SPIN_TWO_MINUTES, LauncherSettings.SPIN_REAL_TIME},
                    new String[]{"Every 2 minutes", "Real time"});
            turning.heading = "Globe";
            out.add(turning);
            out.add(toggle(LauncherSettings.GLOBE_PRAYER_LINES, "Prayer lines", "Where it's Maghrib and Fajr right now"));
            out.add(toggle(LauncherSettings.GLOBE_MAKKAH, "Makkah and qibla", "The Kaaba, and the way to it from your zone"));
            out.add(toggle(LauncherSettings.GLOBE_ZONE, "Your zone", "Where your prayer zone is"));
            out.add(toggle(LauncherSettings.GLOBE_CITY_LIGHTS, "City lights", "The night side's lights"));
            out.add(toggle(LauncherSettings.GLOBE_GLINT, "Sun glint", "The sun's reflection on the sea"));
            out.add(toggle(LauncherSettings.GLOBE_RIM, "Sunlit rim", "The atmosphere glows brighter on the sun's side"));
            out.add(toggle(LauncherSettings.GLOBE_SNOW, "Snow and sea ice", "From NASA, daily"));
            out.add(toggle(LauncherSettings.GLOBE_SKY, "Sky", "The Sun, the Moon in its phase, real stars and deep-sky objects"));
            out.add(toggle(LauncherSettings.GLOBE_CLOUDS, "Clouds", "From weather satellites, every 3 hours"));
            out.add(toggle(LauncherSettings.GLOBE_WIND, "Wind", "Moving lines: the wind near the ground, NOAA's forecast"));
            out.add(toggle(LauncherSettings.GLOBE_AURORA, "Aurora", "NOAA's forecast, every 15 minutes"));
            out.add(toggle(LauncherSettings.GLOBE_EDITS, "Map edits", "OpenStreetMap edits as people save them"));
            out.add(toggle(LauncherSettings.GLOBE_QUAKES, "Earthquakes", "Magnitude 2.5 and up, the past day"));
            out.add(toggle(LauncherSettings.GLOBE_ISS, "ISS", "The International Space Station"));
            out.add(toggle(LauncherSettings.GLOBE_TIANGONG, "Tiangong", "China's space station"));
            out.add(toggle(LauncherSettings.GLOBE_HUBBLE, "Hubble", "The Hubble Space Telescope"));
            out.add(toggle(LauncherSettings.GLOBE_COUNTRIES, "Countries",
                    "A country's flag and name now and then, and when you stop turning"));
            out.add(toggle(LauncherSettings.GLOBE_TRAFFIC, "Internet connections",
                    "Routes from Settings → Net traffic"));
            out.add(toggle(LauncherSettings.GLOBE_LEGEND, "Legend", "What each colour and icon means, with the latest edit and earthquake"));
        }
    }

    private void prayerRows(List<Row> out) {
        out.add(toggle(LauncherSettings.SHOW_PRAYER, "Prayer card", "Today's five prayer times on the home screen"));
        String zone = LauncherSettings.zone(ctx);
        String[] z = RemoteSettings.zoneEntry(ctx, zone);
        Row r = new Row(PICKER, "Zone");
        r.sub = z != null ? z[0] + " · " + z[2] : "JAKIM e-solat zone";
        r.value = () -> zone;
        r.action = this::openZonePicker;
        out.add(r);
        out.add(toggle(LauncherSettings.AZAN, "Azan", "Plays the azan and shows a banner at each prayer time, while the TV is on"));
        out.add(choice(LauncherSettings.PRAYER_REMINDER, "Reminder before prayer",
                "A banner over any app, without sound, while the TV is on",
                new int[] {0, 5, 10, 15, 30}, new String[] {"Off", "5 min", "10 min", "15 min", "30 min"}));
        Row test = new Row(ACTION, "Play azan now");
        test.sub = "Hear it and check the volume";
        test.action = LauncherSettings::playAzanNow;
        out.add(test);
    }

    /** The Weather card: on/off, the MET place (Auto: the prayer zone's town) and the APIMS station. */
    private void weatherRows(List<Row> out) {
        out.add(toggle(LauncherSettings.SHOW_WEATHER, "Weather card",
                "Today's forecast and air quality on the home screen"));
        List<Weather.Place> places = WeatherPanel.places(ctx);
        Weather.Place place = WeatherPanel.place(ctx, places);
        boolean auto = LauncherSettings.weatherPlace(ctx).isEmpty();
        Row loc = new Row(PICKER, "Location");
        loc.sub = place != null ? "MET Malaysia's forecast for this " + place.type().toLowerCase(Locale.ROOT)
                : "MET Malaysia's forecast. Auto: your prayer zone's town";
        loc.value = () -> place == null ? "Auto" : auto ? "Auto (" + place.name + ")" : place.name;
        loc.action = this::openPlacePicker;
        out.add(loc);
        List<Weather.Station> stations = WeatherPanel.stations(ctx);
        Weather.Station station = WeatherPanel.station(ctx, stations);
        boolean nearest = LauncherSettings.weatherStation(ctx).isEmpty();
        Row st = new Row(PICKER, "Air quality station");
        st.sub = "APIMS reading there; Copernicus for the same spot";
        st.value = () -> station == null ? "Nearest" : nearest ? "Nearest (" + station.place + ")" : station.place;
        st.action = this::openStationPicker;
        out.add(st);
    }

    private void usageRows(List<Row> out) {
        out.add(toggle(LauncherSettings.SHOW_USAGE, "Most used card", "Your top 5 apps this week on the home screen"));
        out.add(choice(LauncherSettings.WEEK_START, "Week starts on", "Counts from this day up to today",
                new int[] {1, 7}, new String[] {"Monday", "Sunday"}));
        Row ex = new Row(PICKER, "Leave out");
        ex.sub = "Not on the card, like Live TV (it's only the HDMI and antenna screen)";
        int n = LauncherSettings.usageExcludedPresent(ctx).size();
        ex.value = () -> n == 1 ? "1 app" : n + " apps";
        ex.action = this::openUsagePicker;
        out.add(ex);
    }

    private void homeRows(List<Row> out) {
        out.add(choice(LauncherSettings.CLOCK, "Clock", "Auto follows the TV's own setting",
                new int[] {LauncherSettings.CLOCK_AUTO, LauncherSettings.CLOCK_12, LauncherSettings.CLOCK_24},
                new String[] {"Auto", "12-hour", "24-hour"}));
        out.add(choice(LauncherSettings.APPS_PER_ROW, "Apps per row", "The dock and the app list. More apps, smaller tiles",
                new int[] {5, 6, 7}, new String[] {"5", "6", "7"}));
        out.add(toggle(LauncherSettings.SHOW_SYSTEM, "System card", "CPU, RAM, storage, network and USB debugging"));
        out.add(toggle(LauncherSettings.SHOW_NOW, "Now Playing card", "Cover, title and controls while something plays"));
        out.add(choice(LauncherSettings.WALL_DIM, "Wallpaper dim", "How dark the wallpaper is behind the cards",
                new int[] {0, 1, 2, 3}, new String[] {"Off", "Low", "Medium", "High"}));
        Row w = new Row(PICKER, "Wallpaper");
        w.sub = "Bing photo of the day, built-in photos or your own";
        String name = wallpaperName(LauncherSettings.wallpaper(ctx));
        w.value = () -> name;
        w.action = this::openWallpaperPicker;
        out.add(w);
        out.add(toggle(LauncherSettings.WEATHER_FX, "Weather effects", "Rain, lightning and haze, as it is outside"));
    }

    /** The name the wallpaper row shows (also the phone remote's). */
    static String wallpaperName(String choice) {
        if (Wallpapers.CUSTOM.equals(choice)) return "Your picture";
        if (Wallpapers.BING.equals(choice)) return "Bing daily";
        if (choice.startsWith(Wallpapers.FILE)) return baseName(choice.substring(Wallpapers.FILE.length()));
        Wallpapers.Photo p = Wallpapers.photo(choice);
        return p != null ? p.name : "None";
    }

    private void alertRows(List<Row> out) {
        out.add(toggle(LauncherSettings.ALERT_UPDATE, "TV Home updates", "Checked once a day"));
        out.add(toggle(LauncherSettings.ALERT_PLAY, "Play Store updates", "Apps with an update waiting, checked every hour"));
        out.add(toggle(LauncherSettings.ALERT_HDMI, "New HDMI devices", "A device that appears over HDMI-CEC"));
        out.add(toggle(LauncherSettings.ALERT_BT, "Bluetooth devices", "Connecting or disconnecting (not the TV remote)"));
        out.add(toggle(LauncherSettings.NET_BANNER, "Connection status",
                "Wi-Fi connected, disconnected or no internet, over any app"));
        out.add(toggle(LauncherSettings.ALERT_MEMORY, "Low memory", "When RAM use passes the level below"));
        out.add(choice(LauncherSettings.MEMORY_LEVEL, "Low-memory level", "Alerts again after dropping 5 points under it",
                new int[] {80, 85, 90}, new String[] {"80 %", "85 %", "90 %"}));
    }

    private void statusRows(List<Row> out) {
        out.add(toggle(LauncherSettings.STATUS_BAR, "Show over apps",
                "A thin bar over every app, never on the home screen"));
        out.add(choice(LauncherSettings.STATUS_LEFT, "Left", "What the left end shows",
                StatusOverlay.SLOT_VALUES, StatusOverlay.SLOT_NAMES));
        out.add(choice(LauncherSettings.STATUS_CENTER, "Centre", "What the middle shows",
                StatusOverlay.SLOT_VALUES, StatusOverlay.SLOT_NAMES));
        out.add(choice(LauncherSettings.STATUS_RIGHT, "Right", "What the right end shows",
                StatusOverlay.SLOT_VALUES, StatusOverlay.SLOT_NAMES));
        out.add(choice(LauncherSettings.STATUS_POS, "Position", "Top or bottom edge of the screen",
                StatusOverlay.POS_VALUES, StatusOverlay.POS_NAMES));
        out.add(choice(LauncherSettings.STATUS_SIZE, "Text size", "Tiny 10, Small 12, Medium 14, Large 18",
                StatusOverlay.SIZE_VALUES, StatusOverlay.SIZE_NAMES));
        out.add(toggle(LauncherSettings.STATUS_BG, "Background", "A dark see-through box behind the text"));
    }

    private void remoteRows(List<Row> out) {
        out.add(toggle(LauncherSettings.REMOTE, "Phone remote",
                "Open the address below in your phone's browser and pair with the PIN shown on the TV"));
        Row addr = new Row(INFO, "Address");
        String url = RemoteServer.lanUrl(ctx);
        addr.value = () -> url.replace("http://", "");
        out.add(addr);
        int paired = new RemoteDevices(ctx).count();
        Row f = new Row(paired > 0 ? ACTION : INFO, confirmForget ? "Press OK again to forget them" : "Forget all paired phones");
        f.sub = paired == 0 ? "No phones paired" : paired == 1 ? "1 phone paired" : paired + " phones paired";
        f.value = () -> confirmForget ? "Confirm" : "";
        f.action = () -> {
            if (!confirmForget) {
                confirmForget = true;
                return;
            }
            confirmForget = false;
        confirmPhone = null;
            RemoteServer.forgetAll(ctx);
        };
        out.add(f);
        // Each paired phone, newest first: its name (typed when pairing, or Rename on the phone)
        // and when it was last used. OK twice forgets that one; it has to pair again.
        try {
            org.json.JSONArray phones = new RemoteDevices(ctx).json(null);
            for (int i = 0; i < phones.length(); i++) {
                org.json.JSONObject d = phones.getJSONObject(i);
                String id = d.optString("id"), name = d.optString("name", "Phone");
                boolean asking = id.equals(confirmPhone);
                Row r = new Row(ACTION, asking ? "Press OK again to forget " + name : name);
                r.id = id;
                r.value = () -> asking ? "Confirm" : "Forget";
                r.action = () -> {
                    if (!id.equals(confirmPhone)) {
                        confirmPhone = id;
                        return;
                    }
                    confirmPhone = null;
                    new RemoteDevices(ctx).remove(id);
                };
                long seen = d.optLong("lastSeen"), now = System.currentTimeMillis();
                r.sub = seen <= 0 ? "Paired"
                        : now - seen < 60_000 ? "Last used just now"
                        : "Last used " + android.text.format.DateUtils.getRelativeTimeSpanString(
                                seen, now, android.text.format.DateUtils.MINUTE_IN_MILLIS);
                out.add(r);
            }
        } catch (org.json.JSONException ignored) {
        }
    }

    // ---------------------------------------------------------------- Button Mapper

    /**
     * Settings → Button Mapper: the buttons given new actions (ButtonMap; done by
     * RemoteAccessibilityService and ButtonActions), "Add a button" (press it), Back (which
     * cancels adding, so it has its own row), and the long- and double-press times.
     */
    private void buttonRows(List<Row> out) {
        boolean on = RemoteAccessibilityService.isOn();
        if (!on) {
            Row r = new Row(ACTION, "Turn on TV Home's accessibility service");
            r.sub = "Button Mapper needs \"TV Home phone remote\" switched on in the TV's Settings → Accessibility";
            r.action = this::openAccessibilitySettings;
            out.add(r);
        }
        List<Integer> buttons = LauncherSettings.buttons(ctx);
        for (int code : buttons) {
            Row r = new Row(ACTION, buttonTitle(code));
            r.sub = buttonSummary(code);
            r.action = () -> openButton(code, 0);
            out.add(r);
        }
        Row add = new Row(on ? ACTION : INFO, capturing ? "Press a button on the remote…" : "Add a button");
        add.sub = capturing ? "Back cancels" : captureNote != null ? captureNote
                : "Press it on the remote. Buttons the TV keeps for itself, like Power, can't be added";
        add.value = () -> capturing ? "Waiting" : "";
        add.action = this::startCapture;
        out.add(add);
        if (on && !buttons.contains(KeyEvent.KEYCODE_BACK)) {
            Row back = new Row(ACTION, "Add the Back button");
            back.sub = "Back cancels \"Add a button\", so it's added here";
            back.action = () -> {
                LauncherSettings.addButton(ctx, KeyEvent.KEYCODE_BACK);
                openButton(KeyEvent.KEYCODE_BACK, 0);
            };
            out.add(back);
        }
        Row hold = choice(LauncherSettings.BUTTON_LONG_MS, "Long press", "How long to hold a button",
                new int[] {400, 600, 900}, new String[] {"Short", "Medium", "Long"});
        hold.heading = "Timing";
        out.add(hold);
        out.add(choice(LauncherSettings.BUTTON_DOUBLE_MS, "Double press",
                "How quickly to press twice. A button with a double press waits this long after one press",
                new int[] {250, 350, 500}, new String[] {"Fast", "Medium", "Slow"}));
        Row note = new Row(INFO, "On this page every button works normally");
        note.sub = "So a change can always be undone here. The arrows and OK can't be remapped. Works with USB debugging off";
        out.add(note);
    }

    /** "Red (183)": the button's name and its Android key code. */
    private static String buttonTitle(int code) {
        return ButtonMap.keyName(code) + " (" + code + ")";
    }

    /** "Single: Open Netflix · Long: Sleep", or "Normal". */
    private String buttonSummary(int code) {
        StringBuilder sb = new StringBuilder();
        String[] word = {"Single", "Double", "Long"};
        for (int p = 0; p < ButtonMap.PRESS.length; p++) {
            String a = LauncherSettings.buttonAction(ctx, code, p);
            if (ButtonMap.DEFAULT.equals(a)) continue;
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(word[p]).append(": ").append(ButtonMap.name(a, this::appName));
        }
        return sb.length() > 0 ? sb.toString() : "Works normally";
    }

    private String appName(String pkg) {
        return label(ctx.getPackageManager(), pkg);
    }

    private final java.util.Map<String, android.graphics.drawable.Drawable> appIcons = new java.util.HashMap<>();

    /** An app's icon (kept for the next rebuild), or null if it isn't on the TV. Any thread. */
    private android.graphics.drawable.Drawable appIcon(String pkg) {
        synchronized (appIcons) {
            android.graphics.drawable.Drawable d = appIcons.get(pkg);
            if (d != null) return d;
            try {
                d = ctx.getPackageManager().getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
            appIcons.put(pkg, d);
            return d;
        }
    }

    private void openAccessibilitySettings() {
        try {
            ctx.startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            host.openTvSettings();
        }
    }

    /** "Add a button": the next key pressed (RemoteAccessibilityService) is the one, for 10 s. */
    private void startCapture() {
        if (capturing || !RemoteAccessibilityService.isOn()) return;
        capturing = true;
        captureNote = null;
        RemoteAccessibilityService.capture(code -> {
            main.removeCallbacks(captureTimeout);
            capturing = false;
            captured(code);
        });
        main.postDelayed(captureTimeout, 10_000);
    }

    private void captured(int code) {
        if (code == KeyEvent.KEYCODE_BACK) { // cancelled
            refreshRows();
            return;
        }
        if (!ButtonMap.remappable(code)) {
            captureNote = ButtonMap.navigation(code) ? "The arrows and OK can't be remapped: they move around the TV"
                    : "Android doesn't name that button, so it can't be remapped";
            refreshRows();
            return;
        }
        LauncherSettings.addButton(ctx, code);
        if (col != 1 || cat != BUTTONS || picker != null) return; // left meanwhile: it's in the list
        rows = rowsFor(cat);
        row = rows.size() - 1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).label.equals(buttonTitle(code))) row = i;
        openButton(code, 0);
    }

    /** A button's page: its single, double and long press, and Remove. */
    private void openButton(int code, int focus) {
        String single = LauncherSettings.buttonAction(ctx, code, ButtonMap.SINGLE);
        String[] how = {"Press and let go", "Press twice quickly", "Hold it down"};
        List<Row> items = new ArrayList<>();
        for (int p = 0; p < ButtonMap.PRESS.length; p++) {
            int press = p;
            if (p != ButtonMap.SINGLE && !ButtonMap.extraPressAllowed(code, single)) {
                Row r = new Row(INFO, ButtonMap.PRESS_NAMES[p]);
                r.sub = "Give the single press an action first: TV Home can't play this button's own press back";
                r.value = () -> "Normal";
                items.add(r);
                continue;
            }
            Row r = new Row(PICKER, ButtonMap.PRESS_NAMES[p]);
            r.sub = how[p];
            r.value = () -> ButtonMap.name(LauncherSettings.buttonAction(ctx, code, press), this::appName);
            r.action = () -> openActions(code, press);
            items.add(r);
        }
        Row remove = new Row(ACTION, "Remove this button");
        remove.sub = "It works normally again";
        remove.action = () -> {
            LauncherSettings.removeButton(ctx, code);
            closePicker();
        };
        items.add(remove);
        openPicker(buttonTitle(code), items, focus);
    }

    /** What a press can do: CHECK rows, with "Open an app…" for a list of apps. */
    private void openActions(int code, int press) {
        String now = LauncherSettings.buttonAction(ctx, code, press);
        String single = LauncherSettings.buttonAction(ctx, code, ButtonMap.SINGLE);
        boolean extras = !ButtonMap.DEFAULT.equals(LauncherSettings.buttonAction(ctx, code, ButtonMap.DOUBLE))
                || !ButtonMap.DEFAULT.equals(LauncherSettings.buttonAction(ctx, code, ButtonMap.LONG));
        List<Row> items = new ArrayList<>();
        int focus = 0;
        for (String a : ButtonMap.ACTIONS) {
            if (!ButtonMap.allowed(code, press, a, single)) continue;
            if (!ButtonActions.available(ctx, a)) continue; // this TV can't (no such Settings page, Android 8)
            Row r = new Row(CHECK, ButtonMap.name(a, this::appName));
            if (ButtonMap.DEFAULT.equals(a)) {
                r.sub = press == ButtonMap.SINGLE && extras && !ButtonMap.normalWithoutAdb(code)
                        ? "What the button does. Also clears its double and long press" : "What the button does";
            }
            r.checked = a.equals(now);
            if (r.checked) focus = items.size();
            r.action = () -> chooseAction(code, press, a);
            items.add(r);
            if (ButtonMap.NONE.equals(a) || (ButtonMap.DEFAULT.equals(a) && !ButtonMap.allowed(code, press, ButtonMap.NONE, single))) {
                boolean isApp = now.startsWith(ButtonMap.APP);
                Row app = new Row(isApp ? PICKER : ACTION, "Open an app…");
                app.value = () -> isApp ? appName(now.substring(ButtonMap.APP.length())) : "";
                if (isApp) focus = items.size();
                app.action = () -> openAppChoice(code, press, now);
                items.add(app);
            }
        }
        openPicker(ButtonMap.PRESS_NAMES[press], items, focus);
    }

    private void openAppChoice(int code, int press, String now) {
        String title = "Open an app";
        Row wait = new Row(INFO, "Loading…");
        List<Row> loading = new ArrayList<>();
        loading.add(wait);
        openPicker(title, loading, 0);
        worker.execute(() -> {
            List<AppEntry> apps = AppRepository.load(ctx);
            for (AppEntry a : apps) appIcon(a.pkg); // loaded here, off the screen's thread
            main.post(() -> {
                if (picker == null || !title.equals(pickerTitle)) return; // left meanwhile
                List<Row> items = new ArrayList<>();
                List<String> seen = new ArrayList<>();
                for (AppEntry a : apps) {
                    if (seen.contains(a.pkg) || a.pkg.equals(ctx.getPackageName())) continue;
                    seen.add(a.pkg);
                    Row r = new Row(CHECK, a.label);
                    r.icon = appIcon(a.pkg);
                    r.checked = now.equals(ButtonMap.APP + a.pkg);
                    r.action = () -> chooseAction(code, press, ButtonMap.APP + a.pkg);
                    items.add(r);
                }
                items.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
                int focus = 0;
                for (int i = 0; i < items.size(); i++) if (items.get(i).checked) focus = i;
                picker = items;
                row = focus;
                render();
            });
        });
    }

    /** Saves a press's action and goes back to the button's page, rebuilt. */
    private void chooseAction(int code, int press, String action) {
        LauncherSettings.setButtonAction(ctx, code, press, action);
        if (press == ButtonMap.SINGLE && !ButtonMap.extraPressAllowed(code, action)) {
            // Its own press can't be played back: no double or long press without a single-press action.
            LauncherSettings.setButtonAction(ctx, code, ButtonMap.DOUBLE, ButtonMap.DEFAULT);
            LauncherSettings.setButtonAction(ctx, code, ButtonMap.LONG, ButtonMap.DEFAULT);
        }
        while (picker != null) closePicker();
        openButton(code, press);
    }

    private void hiddenRows(List<Row> out) {
        PackageManager pm = ctx.getPackageManager();
        List<String> pkgs = new ArrayList<>(RunningApps.hidden(ctx));
        pkgs.sort(String::compareTo);
        for (String pkg : pkgs) {
            Row r = new Row(ACTION, label(pm, pkg));
            r.sub = "Hidden from the running-apps list";
            r.value = () -> "Show again";
            r.action = () -> RunningApps.setHidden(ctx, pkg, false);
            out.add(r);
        }
        if (out.isEmpty()) {
            Row r = new Row(INFO, "No hidden apps");
            r.sub = "Hold OK on an app in the running-apps screen (Up from the top bar) to hide it";
            out.add(r);
        }
    }

    private void aboutRows(List<Row> out) {
        String version = "?", installed = "?";
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
            version = pi.versionName + " (" + code + ")";
            installed = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(pi.lastUpdateTime));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        out.add(info("Version", version));
        out.add(info("Installed", installed));
        out.add(info("APK size", (new File(ctx.getPackageCodePath()).length() / 1024) + " KB"));
        out.add(updateRow());
        Row tv = new Row(ACTION, "TV settings");
        tv.sub = "Android Settings: network, display, sound, apps";
        tv.action = host::openTvSettings;
        out.add(tv);
    }

    // Settings → About → Check for updates: a check running from here (the result is UpdateCheck's,
    // shared with the bell's daily check).
    private boolean updChecking;

    /** Check for updates, showing the last check's answer; once one is found, the same row installs it. */
    private Row updateRow() {
        UpdateCheck.load(ctx); // the saved answer, before the bell's first look after a restart
        UpdateCheck.Release found = UpdateCheck.lastFound();
        if (!updChecking && found != null) {
            Row u = new Row(ACTION, "Update to " + found.version);
            u.sub = "Download and install it" + (found.size > 0 ? " (" + size(found.size) + ")" : "") + ". Your settings are kept";
            u.action = () -> {
                try {
                    ctx.startActivity(UpdateActivity.intent(ctx, found).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    android.widget.Toast.makeText(ctx, "Couldn't start the update", android.widget.Toast.LENGTH_SHORT).show();
                }
            };
            return u;
        }
        Row u = new Row(ACTION, "Check for updates");
        long at = UpdateCheck.lastCheckedAt();
        String when = at == 0 ? "" : " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at));
        if (updChecking) u.sub = "Checking…";
        else if (at != 0) u.sub = UpdateCheck.lastFailed() ? "Couldn't check. Try again" + when : "You have the latest version" + when;
        u.action = this::checkForUpdate;
        return u;
    }

    /** "540 KB" under a megabyte, else "1.2 MB". */
    private static String size(long bytes) {
        return bytes < 1_000_000 ? Math.max(1, Math.round(bytes / 1024.0)) + " KB"
                : String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    private void checkForUpdate() {
        if (updChecking) return;
        updChecking = true;
        refreshRows();
        worker.execute(() -> {
            try {
                UpdateCheck.check(ctx);
            } catch (Exception ignored) { // shown from UpdateCheck.lastFailed()
            }
            main.post(() -> {
                updChecking = false;
                refreshRows();
            });
        });
    }

    // ---------------------------------------------------------------- pickers

    private void openZonePicker() {
        String zone = LauncherSettings.zone(ctx);
        List<Row> items = new ArrayList<>();
        int focus = 0;
        for (String e : ctx.getResources().getStringArray(R.array.prayer_zones)) {
            String[] z = e.split("\\|", 3); // state | code | areas
            Row r = new Row(CHECK, z[2]);
            r.sub = z[0] + " · " + z[1];
            r.checked = z[1].equals(zone);
            if (r.checked) focus = items.size();
            r.action = () -> {
                LauncherSettings.setZone(ctx, z[1]);
                closePicker();
            };
            items.add(r);
        }
        openPicker("Zone", items, focus);
    }

    /** Every MET forecast location, with Auto (the prayer zone's town) first. */
    private void openPlacePicker() {
        if (WeatherPanel.places(ctx).isEmpty()) loadingPicker("Location", this::openPlacePicker);
        else preparing("Location", this::showPlaces);
    }

    /** The 441 rows take a moment to build: shown after "Loading…" (preparing). */
    private void showPlaces() {
        List<Weather.Place> places = WeatherPanel.places(ctx);
        String chosen = LauncherSettings.weatherPlace(ctx);
        Weather.Place auto = WeatherPanel.autoPlace(ctx, places);
        List<Row> items = new ArrayList<>();
        Row a = new Row(CHECK, "Auto");
        a.sub = "Your prayer zone's town" + (auto != null ? ": " + auto.name : "");
        a.checked = chosen.isEmpty();
        a.action = () -> {
            LauncherSettings.setWeatherPlace(ctx, "");
            closePicker();
        };
        items.add(a);
        int focus = 0;
        for (Weather.Place p : places) {
            Row r = new Row(CHECK, p.name);
            r.sub = p.type() + " · " + p.id;
            r.checked = p.id.equals(chosen);
            if (r.checked) focus = items.size();
            r.action = () -> {
                LauncherSettings.setWeatherPlace(ctx, p.id);
                closePicker();
            };
            items.add(r);
        }
        openPicker("Location", items, focus);
    }

    /** Every APIMS station by state, with Nearest (to the prayer zone's town) first. */
    private void openStationPicker() {
        if (WeatherPanel.stations(ctx).isEmpty()) loadingPicker("Air quality station", this::openStationPicker);
        else preparing("Air quality station", this::showStations);
    }

    private void showStations() {
        List<Weather.Station> stations = WeatherPanel.stations(ctx);
        String chosen = LauncherSettings.weatherStation(ctx);
        Weather.Station near = WeatherPanel.nearestStation(ctx, stations);
        List<Row> items = new ArrayList<>();
        Row n = new Row(CHECK, "Nearest");
        n.sub = "Closest to your prayer zone's town" + (near != null ? ": " + near.place : "");
        n.checked = chosen.isEmpty();
        n.action = () -> {
            LauncherSettings.setWeatherStation(ctx, "");
            closePicker();
        };
        items.add(n);
        int focus = 0;
        for (Weather.Station s : stations) {
            Row r = new Row(CHECK, s.place);
            r.sub = s.state + " · " + s.id + (s.api >= 0 ? " · API " + s.api : "");
            r.checked = s.id.equals(chosen);
            if (r.checked) focus = items.size();
            r.action = () -> {
                LauncherSettings.setWeatherStation(ctx, s.id);
                closePicker();
            };
            items.add(r);
        }
        openPicker("Air quality station", items, focus);
    }

    /**
     * A long list: "Loading…" at once, so OK answers straight away, then the list itself once the
     * highlight has settled on it (building hundreds of rows holds the screen's thread for a
     * moment, and a highlight still on its way would freeze half-way meanwhile).
     */
    private void preparing(String title, Runnable show) {
        Row r = new Row(INFO, "Loading…");
        r.sub = "Getting the list ready";
        List<Row> items = new ArrayList<>();
        items.add(r);
        openPicker(title, items, 0);
        main.postDelayed(() -> {
            if (picker == null || !title.equals(pickerTitle) || picker.size() != 1) return; // left meanwhile
            int from = pickerFrom;
            picker = null;
            row = from;
            show.run(); // opens the real list, still returning to the same row
        }, 350); // after the highlight has glided onto "Loading…": the build holds the screen's thread
    }

    /**
     * A list that isn't downloaded yet: "Loading…" while it is, then the list (or a line saying it
     * couldn't be fetched). Leaving meanwhile is fine: the result is kept for next time.
     */
    private void loadingPicker(String title, Runnable reopen) {
        Row r = new Row(INFO, "Loading…");
        r.sub = "Fetching the list from the internet";
        List<Row> items = new ArrayList<>();
        items.add(r);
        openPicker(title, items, 0);
        WeatherPanel.loadLists(ctx, () -> {
            if (picker == null || !title.equals(pickerTitle)) return; // left meanwhile
            int from = pickerFrom;
            boolean got = title.equals("Location") ? !WeatherPanel.places(ctx).isEmpty()
                    : !WeatherPanel.stations(ctx).isEmpty();
            if (got) {
                picker = null;
                row = from;
                reopen.run(); // opens the real list, still returning to the same row
            } else {
                Row fail = new Row(INFO, "Couldn't load the list");
                fail.sub = "Check the internet connection and try again";
                picker.set(0, fail);
                render();
            }
        });
    }

    private static final String WALLPAPER_TITLE = "Wallpaper";
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private boolean fetchingWallpapers;

    /**
     * None, the built-ins (thumbnail, credit) and the pushed picture if any. Opening it downloads
     * the built-ins not cached yet, one by one; rows update as each lands. Moving the focus
     * previews a wallpaper behind Settings; OK sets it; Back without OK restores the saved one.
     */
    private void openWallpaperPicker() {
        List<Row> items = wallpaperRows();
        int focus = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).checked) focus = i;
        openPicker(WALLPAPER_TITLE, items, focus);
        fetchWallpapers();
    }

    private List<Row> wallpaperRows() {
        String current = LauncherSettings.wallpaper(ctx);
        List<Row> items = new ArrayList<>();
        Row none = new Row(CHECK, "None");
        none.sub = "Plain dark background";
        none.thumbSlot = true;
        none.checked = Wallpapers.NONE.equals(current);
        none.id = Wallpapers.NONE;
        none.action = () -> pickWallpaper(Wallpapers.NONE);
        items.add(none);
        File bingFile = Bing.shown(ctx);
        Row bing = new Row(CHECK, "Bing wallpaper of the day");
        String cap = Bing.caption(ctx);
        bing.sub = bingFile != null && !cap.isEmpty() ? cap + " · bing.com"
                : "A new photo every day · bing.com";
        bing.thumbSlot = true;
        bing.thumb = bingFile != null ? thumbs.get(thumbKey(bingFile)) : null;
        bing.checked = Wallpapers.BING.equals(current);
        bing.id = Wallpapers.BING;
        bing.action = () -> pickWallpaper(Wallpapers.BING);
        items.add(bing);
        for (Wallpapers.Photo p : Wallpapers.BUILT_IN) {
            Row r = new Row(CHECK, p.name);
            boolean cached = Wallpapers.isCached(ctx, p.id);
            r.sub = cached ? "Photo: " + p.credit + " · Wikimedia Commons"
                    : failed.contains(p.id) ? "Couldn't download. Check the internet connection" : "Downloading…";
            r.thumbSlot = true;
            r.thumb = thumbs.get(p.id);
            r.checked = p.id.equals(current);
            r.id = p.id;
            r.action = () -> pickWallpaper(p.id);
            items.add(r);
        }
        List<File> mine = Wallpapers.userPictures();
        for (File f : mine) {
            String id = Wallpapers.FILE + f.getName();
            Row r = new Row(CHECK, baseName(f.getName()));
            r.sub = "Wallpapers folder · " + String.format(Locale.US, "%.1f MB", f.length() / 1048576.0);
            r.thumbSlot = true;
            r.thumb = thumbs.get(thumbKey(f));
            r.checked = id.equals(current);
            r.id = id;
            r.action = () -> pickWallpaper(id);
            items.add(r);
        }
        if (mine.isEmpty()) {
            Row r = new Row(INFO, "Add your own");
            r.sub = "On the phone remote: Files → Wallpapers → upload a picture. It shows up here.";
            items.add(r);
        }
        File custom = Wallpapers.custom(ctx);
        if (custom != null) {
            Row r = new Row(CHECK, "Your picture");
            r.sub = "Android/data/" + ctx.getPackageName() + "/files/" + custom.getName();
            r.thumbSlot = true;
            r.thumb = thumbs.get(Wallpapers.CUSTOM);
            r.checked = Wallpapers.CUSTOM.equals(current);
            r.id = Wallpapers.CUSTOM;
            r.action = () -> pickWallpaper(Wallpapers.CUSTOM);
            items.add(r);
        }
        return items;
    }

    /** OK: the previewed wallpaper becomes the saved one. */
    private void pickWallpaper(String id) {
        LauncherSettings.setWallpaper(ctx, id);
        host.previewWallpaper(null);
        refreshWallpaperRows();
    }

    private boolean inWallpaperList() {
        return picker != null && WALLPAPER_TITLE.equals(pickerTitle);
    }

    /** Rebuilds the wallpaper list in place if it's still open (download finished, choice made). */
    private void refreshWallpaperRows() {
        if (picker == null || !WALLPAPER_TITLE.equals(pickerTitle)) return;
        picker = wallpaperRows();
        renderRows();
    }

    /** Downloads the built-ins not cached yet and makes thumbnails, on a background thread. */
    private void fetchWallpapers() {
        if (fetchingWallpapers) return;
        fetchingWallpapers = true;
        failed.clear();
        new Thread(() -> {
            if (Bing.due(ctx)) Bing.refresh(ctx);
            File bf = Bing.shown(ctx);
            if (bf != null) {
                String key = thumbKey(bf);
                if (!thumbs.containsKey(key)) {
                    Bitmap t = thumbnail(bf);
                    main.post(() -> {
                        if (t != null) thumbs.put(key, t);
                        refreshWallpaperRows();
                    });
                }
            }
            List<String> ids = new ArrayList<>();
            for (Wallpapers.Photo p : Wallpapers.BUILT_IN) ids.add(p.id);
            for (File f : Wallpapers.userPictures()) {
                String key = thumbKey(f);
                if (thumbs.containsKey(key)) continue;
                Bitmap t = thumbnail(f);
                main.post(() -> {
                    if (t != null) thumbs.put(key, t);
                    refreshWallpaperRows();
                });
            }
            if (Wallpapers.custom(ctx) != null) ids.add(Wallpapers.CUSTOM);
            for (String id : ids) {
                boolean ok = Wallpapers.CUSTOM.equals(id) || Wallpapers.fetch(ctx, id);
                File f = ok ? Wallpapers.file(ctx, id) : null;
                Bitmap t = f != null && !thumbs.containsKey(id) ? thumbnail(f) : null;
                main.post(() -> {
                    if (!ok) failed.add(id);
                    if (t != null) thumbs.put(id, t);
                    refreshWallpaperRows();
                });
            }
            main.post(() -> fetchingWallpapers = false);
        }, "wallpaper-thumbs").start();
    }

    /** Downscaled to about 240 px wide: enough for the list's 96 dp preview. */
    private static Bitmap thumbnail(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 240) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** A picture replaced under the same name gets a new thumbnail. */
    private static String thumbKey(File f) {
        return Wallpapers.FILE + f.getName() + "@" + f.lastModified();
    }

    static String baseName(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private void openUsagePicker() {
        Set<String> excluded = LauncherSettings.usageExcluded(ctx);
        int from = row;
        worker.execute(() -> {
            List<AppEntry> apps = AppRepository.load(ctx);
            main.post(() -> {
                if (col != 1 || cat != USAGE || picker != null || row != from) return; // moved on meanwhile
                List<Row> items = new ArrayList<>();
                List<String> seen = new ArrayList<>();
                for (AppEntry a : apps) {
                    if (seen.contains(a.pkg)) continue;
                    seen.add(a.pkg);
                    Row r = new Row(CHECK, a.label);
                    r.sub = a.pkg.equals(LIVE_TV) ? "Live TV: HDMI, AV and antenna" : null;
                    r.checked = excluded.contains(a.pkg);
                    r.action = () -> {
                        r.checked = !r.checked;
                        LauncherSettings.setUsageExcluded(ctx, a.pkg, r.checked);
                    };
                    items.add(r);
                }
                // Apps already left out first (Live TV), then A–Z.
                items.sort((x, y) -> x.checked != y.checked ? (x.checked ? -1 : 1) : x.label.compareToIgnoreCase(y.label));
                openPicker("Leave out", items, 0);
            });
        });
    }

    // ---------------------------------------------------------------- views

    private void render() {
        // On Button Mapper every button works normally, so a remapping can always be undone here.
        RemoteAccessibilityService.editing = shown && cat == BUTTONS;
        if (picker == null) rows = rowsFor(cat);
        main.removeCallbacks(netTick);
        if (cat == NET && picker == null) {
            netShown = netState();
            main.postDelayed(netTick, netBusy() ? 500 : NET_REFRESH_MS); // runs again from here
        }
        for (int i = 0; i < catViews.size(); i++) {
            // The open category stays marked while its rows have focus.
            catViews.get(i).setBackgroundResource(i == cat && col == 1 ? R.drawable.row_selected : 0);
        }
        View c = catViews.get(cat);
        catScroll.post(() -> catScroll.smoothScrollTo(0, Math.max(0, c.getTop() - px(54))));
        crumb.setText("/  " + CATS[cat] + (picker != null ? "  /  " + pickerTitle : ""));
        paneTitle.setText(picker != null ? pickerTitle : CATS[cat]);
        paneSub.setText(inWallpaperList() ? "Move to preview  ·  OK to set"
                : picker != null ? count(picker) : CAT_SUBS[cat]);
        renderRows();
    }

    private void renderRows() {
        rowList.removeAllViews();
        rowViews.clear();
        List<Row> list = current();
        for (int i = 0; i < list.size(); i++) {
            Row r = list.get(i);
            if (r.heading != null) rowList.addView(heading(r.heading), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View v = rowView(r);
            rowList.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            rowViews.add(v);
        }
        if (col == 0) scroll.scrollTo(0, 0);
        else scrollToFocus();
        host.onFocusMoved();
    }

    /** "441 items", "1 item"; nothing while a list only says it's loading (or couldn't). */
    private static String count(List<Row> list) {
        if (list.size() == 1 && list.get(0).type == INFO) return "";
        return list.size() == 1 ? "1 item" : list.size() + " items";
    }

    private void scrollToFocus() {
        View f = row < rowViews.size() ? rowViews.get(row) : null;
        if (f != null) scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, f.getTop() - px(70))));
    }

    /** A thin line with a small title, between groups of rows (Row.heading). */
    private View heading(String title) {
        LinearLayout h = new LinearLayout(ctx);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(px(16), px(14), px(14), px(4));
        View line = new View(ctx);
        line.setBackgroundColor(0x26FFFFFF);
        h.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1))));
        TextView t = text(12, 500, DIM);
        t.setText(title.toUpperCase(Locale.ROOT));
        t.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams tl = wrap();
        tl.topMargin = px(12);
        h.addView(t, tl);
        return h;
    }

    private View rowView(Row r) {
        LinearLayout v = new LinearLayout(ctx);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setMinimumHeight(px(58));
        v.setPadding(px(16), px(9), px(14), px(9));
        if (r.thumbSlot) {
            ImageView t = new ImageView(ctx);
            t.setBackgroundResource(R.drawable.tile_bg);
            t.setClipToOutline(true);
            t.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (r.thumb != null) t.setImageBitmap(r.thumb);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(px(96), px(54));
            tl.setMarginEnd(px(16));
            v.addView(t, tl);
        }

        if (r.icon != null) {
            ImageView i = new ImageView(ctx);
            i.setImageDrawable(r.icon);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(px(32), px(32));
            il.setMarginEnd(px(14));
            v.addView(i, il);
        }
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = text(16, 500, TEXT);
        label.setText(r.label);
        texts.addView(label);
        if (!TextUtils.isEmpty(r.sub)) {
            TextView sub = text(12, 400, DIM);
            sub.setSingleLine(false);
            sub.setMaxLines(2);
            sub.setText(r.sub);
            LinearLayout.LayoutParams slp = wrap();
            slp.topMargin = px(3);
            texts.addView(sub, slp);
        }
        v.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String value = r.value != null ? r.value.get() : null;
        switch (r.type) {
            case TOGGLE:
                boolean isOn = r.on != null ? r.on.getAsBoolean() : LauncherSettings.flag(ctx, r.key);
                v.addView(switchView(isOn), margin(new LinearLayout.LayoutParams(px(44), px(26))));
                break;
            case CHOICE:
            case PICKER: {
                TextView pill = text(14, 500, TEXT);
                pill.setText(r.type == CHOICE ? r.names[indexOf(r.values, LauncherSettings.choice(ctx, r.key))] : value);
                pill.setBackgroundResource(R.drawable.pill_bg);
                pill.setPadding(px(12), px(5), px(12), px(5));
                v.addView(pill, margin(wrap()));
                v.addView(icon(R.drawable.ic_chevron, DIM, 18), new LinearLayout.LayoutParams(px(20), px(20)));
                break;
            }
            case ACTION:
                if (r.busy) {
                    android.widget.ProgressBar spin = new android.widget.ProgressBar(ctx);
                    spin.setIndeterminate(true);
                    spin.setIndeterminateTintList(ColorStateList.valueOf(DIM));
                    v.addView(spin, margin(new LinearLayout.LayoutParams(px(18), px(18))));
                }
                if (!TextUtils.isEmpty(value)) {
                    boolean asking = (r.id == null && confirmForget) || (r.id != null && r.id.equals(confirmPhone));
                    TextView t = text(14, 500, asking ? AMBER : DIM);
                    t.setText(value);
                    v.addView(t, margin(wrap()));
                }
                v.addView(icon(R.drawable.ic_chevron, DIM, 18), new LinearLayout.LayoutParams(px(20), px(20)));
                break;
            case INFO:
                if (!TextUtils.isEmpty(value)) {
                    TextView t = text(14, 500, DIM);
                    t.setText(value);
                    v.addView(t, margin(wrap()));
                }
                break;
            case CHECK: {
                ImageView check = icon(R.drawable.ic_check, GREEN, 20);
                check.setVisibility(r.checked ? View.VISIBLE : View.INVISIBLE);
                v.addView(check, margin(new LinearLayout.LayoutParams(px(22), px(22))));
                break;
            }
        }
        return v;
    }

    private View switchView(boolean on) {
        FrameLayout track = new FrameLayout(ctx);
        track.setBackgroundResource(on ? R.drawable.switch_track_on : R.drawable.switch_track);
        View knob = new View(ctx);
        knob.setBackgroundResource(R.drawable.switch_knob);
        FrameLayout.LayoutParams k = new FrameLayout.LayoutParams(px(20), px(20),
                Gravity.CENTER_VERTICAL | (on ? Gravity.END : Gravity.START));
        k.setMargins(px(3), 0, px(3), 0);
        track.addView(knob, k);
        return track;
    }

    // ---------------------------------------------------------------- helpers

    private Row toggle(String key, String label, String sub) {
        Row r = new Row(TOGGLE, label);
        r.key = key;
        r.sub = sub;
        return r;
    }

    private Row choice(String key, String label, String sub, int[] values, String[] names) {
        Row r = new Row(CHOICE, label);
        r.key = key;
        r.sub = sub;
        r.values = values;
        r.names = names;
        return r;
    }

    private Row info(String label, String value) {
        Row r = new Row(INFO, label);
        r.value = () -> value;
        return r;
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 0;
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private LinearLayout.LayoutParams margin(LinearLayout.LayoutParams lp) {
        lp.setMarginStart(px(12));
        return lp;
    }

    private ImageView icon(int res, int color, int sizeDp) {
        ImageView i = new ImageView(ctx);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(color));
        i.setScaleType(ImageView.ScaleType.FIT_CENTER);
        i.setMaxWidth(px(sizeDp));
        i.setMaxHeight(px(sizeDp));
        i.setAdjustViewBounds(true);
        return i;
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Fonts.outfit(ctx, weight));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }
}
