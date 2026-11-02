package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Now Playing" card: what another app is playing (cover, title, artist, app, progress) with
 * Play/Pause, Stop and Open app buttons that MainActivity's remote focus can reach.
 *
 * This low-RAM TV won't let apps read other apps' media sessions (notification-listener access
 * is refused), so the data comes from outside the app:
 *  - USB debugging on: {@link MediaHelper} runs as the shell user over the loopback ADB
 *    connection ({@link AdbKeys}) and reports the session as JSON lines, cover art included;
 *  - otherwise: `dumpsys media_session` (DUMP is granted by build-and-install.sh), every few
 *    seconds while the home screen shows: app, title, artist, state, position; no cover/length.
 * Only while home is on screen (and not under the screensaver): `dumpsys` is skipped while no
 * audio plays, and the helper (~23 MB) runs only while a media session exists. Buttons send
 * media keys.
 */
final class NowPlayingPanel {
    static final int PLAY_PAUSE = 0, STOP = 1, OPEN = 2, BUTTONS = 3;
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x8CFFFFFF, LIVE = 0xFFFF5A52;
    /** Cover is a square this size, and sets the card's height. */
    private static final int COVER = 88;
    private static final long DUMPSYS_EVERY_MS = 4000;

    interface Listener {
        /** The card appeared or disappeared. */
        void onShownChanged(boolean shown);
    }

    /** One snapshot of the session being shown (also read by the phone remote's dashboard). */
    static final class Info {
        String pkg, title, artist, artUri;
        int state, artId;
        long pos, updated, duration, actions;
        float speed = 1f;
    }

    private final Context ctx;
    private final LinearLayout card;
    private final Executor worker;
    private final Listener listener;
    private final float dp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AdbKeys adb;
    private final AudioManager audio;

    private final ArtCoverView cover;
    private final TextView source, title, artist, time;
    private final Bar bar;
    private final ImageView[] buttons = new ImageView[BUTTONS];

    private Info info;
    private boolean started, shown, visibleOnScreen = true;
    private volatile AdbKeys.Hid helper;
    private int generation; // bumps on every start/stop, so stale threads don't touch the card
    private String coverKey;
    private Bitmap helperArt;
    private int helperArtId;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (needsTick()) main.postDelayed(this, 1000);
        }
    };

    /**
     * The cheap check: nothing to read while no audio plays and no card is up. Otherwise
     * `dumpsys media_session`, and when it finds a session (and ADB is up) the helper takes over
     * for cover art and progress.
     */
    private final Runnable dumpsysPoll = new Runnable() {
        @Override
        public void run() {
            if (!started || helper != null) return;
            if (visibleOnScreen) main.postDelayed(this, DUMPSYS_EVERY_MS);
            if (info == null && !audio.isMusicActive()) return;
            int gen = generation;
            worker.execute(() -> {
                Info i = readSession(ctx);
                main.post(() -> {
                    if (gen != generation || helper != null) return;
                    show(i);
                    if (i != null) startHelper();
                });
            });
        }
    };

    NowPlayingPanel(Context ctx, LinearLayout card, Executor worker, Listener listener) {
        this.ctx = ctx;
        this.card = card;
        this.worker = worker;
        this.listener = listener;
        dp = ctx.getResources().getDisplayMetrics().density;
        adb = AdbKeys.get(ctx);
        audio = ctx.getSystemService(AudioManager.class);

        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setVisibility(View.GONE);
        // The cover fills the card's left side edge to edge; the card's rounded outline clips it.
        card.setPadding(0, 0, px(12), 0);
        card.setClipToOutline(true);
        // Use the same neutral glass base as the other cards, without an artwork color wash.
        card.setBackgroundResource(R.drawable.sys_bg);
        // Carry the shared card border across the edge-to-edge artwork as well.
        card.setForeground(ctx.getDrawable(R.drawable.np_gloss));

        cover = new ArtCoverView(ctx);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(cover, new LinearLayout.LayoutParams(px(COVER), px(COVER)));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMarginStart(px(14));
        clp.setMarginEnd(px(8));
        card.addView(col, clp);

        source = text(9.5f, 500, DIM);
        source.setLetterSpacing(0.08f);
        col.addView(source);
        title = text(15f, 500, TEXT);
        col.addView(title, top(3));
        artist = text(12f, 400, 0xB3FFFFFF);
        col.addView(artist, top(2));

        LinearLayout progress = new LinearLayout(ctx);
        progress.setGravity(Gravity.CENTER_VERTICAL);
        bar = new Bar(ctx);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, px(3), 1f);
        blp.setMarginEnd(px(8));
        progress.addView(bar, blp);
        time = text(9.5f, 500, DIM);
        progress.addView(time);
        col.addView(progress, top(6));

        int[] icons = {R.drawable.ic_pause, R.drawable.ic_stop, R.drawable.ic_open};
        for (int i = 0; i < BUTTONS; i++) {
            ImageView b = new ImageView(ctx);
            b.setImageResource(icons[i]);
            b.setBackgroundResource(R.drawable.np_button);
            b.setScaleType(ImageView.ScaleType.CENTER);
            b.setImageTintList(ColorStateList.valueOf(TEXT));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(34), px(34));
            if (i > 0) lp.setMarginStart(px(8));
            card.addView(b, lp);
            buttons[i] = b;
        }
    }

    // ---------------------------------------------------------------- lifecycle

    void start() {
        if (started) return;
        started = true;
        generation++;
        adb.ensure();
        main.post(dumpsysPoll); // the helper is only started once something is playing
    }

    void stop() {
        if (!started) return;
        started = false;
        generation++;
        main.removeCallbacks(tick);
        main.removeCallbacks(dumpsysPoll);
        AdbKeys.Hid h = helper;
        helper = null;
        if (h != null) new Thread(() -> adb.stop(h), "media-helper-stop").start();
    }

    /** Settings → Now Playing card off: stop watching and take the card down. */
    void disable() {
        stop();
        clearCover();
        if (shown) {
            shown = false;
            card.setVisibility(View.GONE);
            listener.onShownChanged(false);
        }
    }

    /** Hidden while the app grid has the screen: pause the tick and dumpsys polling then. */
    void setVisible(boolean v) {
        if (visibleOnScreen == v) return;
        visibleOnScreen = v;
        main.removeCallbacks(tick);
        main.removeCallbacks(dumpsysPoll);
        if (needsTick()) main.post(tick);
        if (v && started && helper == null) main.post(dumpsysPoll);
    }

    boolean isShown() {
        return shown;
    }

    View button(int i) {
        return buttons[i];
    }

    // ---------------------------------------------------------------- actions

    void press(int i) {
        Info in = info;
        if (in == null) return;
        AudioManager am = ctx.getSystemService(AudioManager.class);
        switch (i) {
            case PLAY_PAUSE:
                media(am, in.state == PlaybackState.STATE_PLAYING ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY);
                break;
            case STOP:
                media(am, (in.actions & PlaybackState.ACTION_STOP) != 0 || in.actions == 0
                        ? KeyEvent.KEYCODE_MEDIA_STOP : KeyEvent.KEYCODE_MEDIA_PAUSE);
                break;
            case OPEN:
                PackageManager pm = ctx.getPackageManager();
                Intent launch = pm.getLeanbackLaunchIntentForPackage(in.pkg);
                if (launch == null) launch = pm.getLaunchIntentForPackage(in.pkg);
                if (launch != null) {
                    try {
                        ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException ignored) {
                    }
                }
                return;
            default:
                return;
        }
        // Show the new state soon (the helper reports it within a second by itself).
        if (helper == null) {
            main.removeCallbacks(dumpsysPoll);
            main.postDelayed(dumpsysPoll, 600);
        }
    }

    private static void media(AudioManager am, int code) {
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    // ---------------------------------------------------------------- source 1: helper over ADB

    private boolean helperStarting;

    /** Only while a media session exists; the helper costs ~23 MB while it runs. */
    private void startHelper() {
        if (!started || helper != null || helperStarting) return;
        if (adb.state() != AdbKeys.State.READY) {
            adb.ensure(); // no ADB: dumpsys keeps the card up to date without art
            return;
        }
        helperStarting = true;
        int gen = generation;
        new Thread(() -> {
            AdbKeys.Hid h = adb.state() == AdbKeys.State.READY ? adb.shell(
                    "app_process -cp " + ctx.getPackageCodePath() + " /system/bin " + MediaHelper.class.getName()) : null;
            main.post(() -> {
                helperStarting = false;
                if (h == null) return; // dumpsys polling carries on and will try again
                if (gen != generation || !started) {
                    new Thread(() -> adb.stop(h)).start();
                    return;
                }
                helper = h;
                main.removeCallbacks(dumpsysPoll);
                Thread reader = new Thread(() -> readHelper(h, gen), "media-helper-read");
                reader.setDaemon(true);
                reader.start();
            });
        }, "media-helper-start").start();
    }

    private void readHelper(AdbKeys.Hid h, int gen) {
        try {
            for (String line; (line = h.stream.getSource().readUtf8Line()) != null; ) {
                if (!line.startsWith("{")) continue;
                JSONObject o = new JSONObject(line);
                if (o.has("error")) continue;
                Info i = null;
                Bitmap art = null;
                if (o.has("pkg")) {
                    i = new Info();
                    i.pkg = o.getString("pkg");
                    i.title = o.optString("title", null);
                    i.artist = o.optString("artist", null);
                    i.state = o.optInt("state");
                    i.pos = o.optLong("pos");
                    i.updated = o.optLong("updated");
                    i.speed = (float) o.optDouble("speed", 1);
                    i.duration = o.optLong("duration");
                    i.actions = o.optLong("actions");
                    i.artId = o.optInt("artId");
                    i.artUri = o.optString("artUri", null);
                    if (o.has("art")) {
                        byte[] jpg = Base64.decode(o.getString("art"), Base64.DEFAULT);
                        art = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                    }
                }
                Info fi = i;
                Bitmap fart = art;
                main.post(() -> {
                    if (gen != generation) return;
                    if (fart != null && fi != null) {
                        helperArt = fart;
                        helperArtId = fi.artId;
                    }
                    show(fi);
                    if (fi == null) stopHelper(); // playback ended: free the helper
                });
            }
        } catch (Throwable ignored) {
        }
        // Helper ended (stopped, ADB off, connection dropped): back to dumpsys polling.
        main.post(() -> {
            if (helper == h) helper = null;
            if (gen == generation && started) {
                main.removeCallbacks(dumpsysPoll);
                main.post(dumpsysPoll);
            }
        });
    }

    private void stopHelper() {
        AdbKeys.Hid h = helper;
        helper = null;
        if (h != null) new Thread(() -> adb.stop(h), "media-helper-stop").start();
        main.removeCallbacks(dumpsysPoll);
        if (started) main.post(dumpsysPoll);
    }

    // ---------------------------------------------------------------- source 2: dumpsys

    private static final Pattern STATE = Pattern.compile(
            "state=PlaybackState \\{state=(\\d+), position=(-?\\d+),.*?speed=([\\d.]+), updated=(\\d+), actions=(\\d+)");

    /**
     * Best session from `dumpsys media_session` (needs DUMP), or null. Blocking: run off the main
     * thread. Shared with the phone remote, which needs it while another app is on screen too.
     */
    static Info readSession(Context ctx) {
        Info best = null;
        int bestRank = 0;
        try {
            Process p = new ProcessBuilder("dumpsys", "media_session").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                Info cur = null;
                boolean inStack = false;
                for (String line; (line = r.readLine()) != null; ) {
                    String t = line.trim();
                    if (t.startsWith("Sessions Stack")) {
                        inStack = true;
                        continue;
                    }
                    if (!inStack) continue;
                    if (t.startsWith("package=")) {
                        cur = new Info();
                        cur.pkg = t.substring(8);
                    } else if (cur != null && t.startsWith("state=PlaybackState")) {
                        Matcher m = STATE.matcher(t);
                        if (m.find()) {
                            cur.state = Integer.parseInt(m.group(1));
                            cur.pos = Long.parseLong(m.group(2));
                            cur.speed = Float.parseFloat(m.group(3));
                            cur.updated = Long.parseLong(m.group(4));
                            cur.actions = Long.parseLong(m.group(5));
                        }
                    } else if (cur != null && t.startsWith("metadata:")) {
                        int d = t.indexOf("description=");
                        if (d >= 0 && !cur.pkg.equals(ctx.getPackageName())) {
                            // MediaDescription.toString(): "title, subtitle, description"
                            String[] parts = t.substring(d + 12).split(", ", 3);
                            cur.title = blank(parts[0]);
                            cur.artist = parts.length > 1 ? blank(parts[1]) : null;
                            int rank = rank(cur.state);
                            if (rank > bestRank) {
                                best = cur;
                                bestRank = rank;
                            }
                        }
                        cur = null;
                    } else if (t.startsWith("Media button session")) {
                        break;
                    }
                }
            }
            p.destroy();
        } catch (Exception ignored) {
        }
        return best;
    }

    private static String blank(String s) {
        return s == null || s.isEmpty() || s.equals("null") ? null : s;
    }

    private static int rank(int state) {
        return state == PlaybackState.STATE_PLAYING ? 3
                : state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING ? 2
                : state == PlaybackState.STATE_PAUSED ? 1 : 0;
    }

    // ---------------------------------------------------------------- display

    private void show(Info i) {
        info = i;
        boolean visible = i != null;
        if (visible) {
            boolean playing = i.state == PlaybackState.STATE_PLAYING;
            String app = appLabel(i.pkg);
            setText(title, TextUtils.isEmpty(i.title) ? app : i.title);
            setText(artist, i.artist == null ? "" : i.artist);
            artist.setVisibility(TextUtils.isEmpty(i.artist) ? View.GONE : View.VISIBLE);
            setText(source, (app + " · " + stateLabel(i.state)).toUpperCase(Locale.ROOT));
            buttons[PLAY_PAUSE].setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            showCover(i);
            updateProgress();
        } else {
            clearCover();
        }
        if (visible != shown) {
            shown = visible;
            card.setVisibility(visible ? View.VISIBLE : View.GONE);
            listener.onShownChanged(visible);
        }
        main.removeCallbacks(tick);
        if (needsTick()) main.postDelayed(tick, 1000);
    }

    private boolean needsTick() {
        Info i = info;
        // A live stream's label is static; the helper already reports track/state changes.
        return started && visibleOnScreen && i != null && i.state == PlaybackState.STATE_PLAYING
                && (i.duration > 0 || helper == null);
    }

    private void updateProgress() {
        Info i = info;
        if (i == null) return;
        long pos = i.pos;
        if (i.state == PlaybackState.STATE_PLAYING && i.updated > 0) {
            pos += (long) ((SystemClock.elapsedRealtime() - i.updated) * i.speed);
        }
        pos = Math.max(0, pos);
        if (i.duration > 0) {
            pos = Math.min(i.duration, pos);
            bar.setVisibility(View.VISIBLE);
            bar.set(pos / (float) i.duration);
            setText(time, clock(pos) + " / " + clock(i.duration));
            time.setTextColor(DIM);
        } else {
            // The helper reports length 0 for live streams; dumpsys doesn't expose the length at all.
            bar.setVisibility(View.GONE);
            boolean live = helper != null;
            setText(time, live ? "\u25CF LIVE" : clock(pos));
            // Red, the broadcast "on air" colour (green already means OK/on in the System card).
            time.setTextColor(live ? LIVE : DIM);
        }
    }

    private void clearCover() {
        // No hidden artwork or session bitmap retained after playback ends.
        coverKey = null;
        helperArt = null;
        helperArtId = 0;
        cover.setImageDrawable(null);
        cover.artworkChanged();
    }

    /** Helper's cover art, else an http(s) art URI, else the app's icon. */
    private void showCover(Info i) {
        String key;
        if (helper != null && i.artId != 0 && i.artId == helperArtId && helperArt != null) {
            key = i.pkg + "|art" + i.artId;
            if (!key.equals(coverKey)) {
                coverKey = key;
                setCover(helperArt);
            }
            return;
        }
        key = i.pkg + "|" + i.artUri;
        if (key.equals(coverKey)) return;
        coverKey = key;
        showAppIcon(i.pkg);
        String uri = i.artUri;
        if (uri == null || !uri.startsWith("http")) return;
        int size = px(58) * 2;
        worker.execute(() -> {
            Bitmap loaded = download(uri, size);
            if (loaded != null) main.post(() -> {
                if (started && key.equals(coverKey)) setCover(loaded);
            });
        });
    }

    /** No cover art: the app's icon, filling the square like a cover would. */
    private void showAppIcon(String pkg) {
        try {
            Drawable icon = ctx.getPackageManager().getApplicationIcon(pkg);
            cover.setImageDrawable(icon);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } catch (PackageManager.NameNotFoundException e) {
            cover.setImageResource(R.drawable.ic_music);
            cover.setScaleType(ImageView.ScaleType.CENTER);
        }
        cover.artworkChanged();
    }

    private void setCover(Bitmap bmp) {
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setImageBitmap(bmp);
        cover.artworkChanged();
    }

    private static Bitmap download(String uri, int size) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(uri).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            byte[] data;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0 && out.size() < 8 << 20; ) out.write(buf, 0, n);
                data = out.toByteArray();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= size && o.outHeight / (o.inSampleSize * 2) >= size) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------------------------------------------------------------- helpers

    private String appLabel(String pkg) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return ctx.getPackageManager().getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static String stateLabel(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING: return "Playing";
            case PlaybackState.STATE_PAUSED: return "Paused";
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING: return "Loading";
            default: return "Now playing";
        }
    }

    private static String clock(long ms) {
        long s = ms / 1000, h = s / 3600, m = (s / 60) % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60) : String.format(Locale.US, "%d:%02d", m, s % 60);
    }

    private static void setText(TextView v, String s) {
        if (!s.contentEquals(v.getText())) v.setText(s);
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

    private LinearLayout.LayoutParams top(int dps) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px(dps);
        return lp;
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private static final class Bar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;

        Bar(Context c) {
            super(c);
        }

        void set(float f) {
            if (Math.abs(f - fraction) < 0.001f) return;
            fraction = f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight(), rad = h / 2f;
            r.set(0, 0, getWidth(), h);
            paint.setColor(0x26FFFFFF);
            canvas.drawRoundRect(r, rad, rad, paint);
            r.right = getWidth() * fraction;
            paint.setColor(0xD9FFFFFF);
            canvas.drawRoundRect(r, rad, rad, paint);
        }
    }
}
