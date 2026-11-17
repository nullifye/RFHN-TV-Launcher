package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The Live TV screen (Up from the top of Running apps): RTMKlik's and Tonton's channels on the left, the
 * selected one playing on the right with what's on now and next, and full screen on OK.
 * MainActivity hands it the remote's keys while it has focus. Nothing plays or downloads while
 * it is closed.
 */
final class TvScreen {
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x99FFFFFF, FAINT = 0x59FFFFFF, LIVE = 0xFFFF4D4D;
    private static final int ROW_FOCUS = 0xFFF2F2F2, ROW_SELECTED = 0x1AFFFFFF, INK = 0xFF101318, INK_DIM = 0xB3101318;
    private static final long BANNER_MS = 5000;
    private static final long GUIDE_MAX_AGE_MS = 30 * 60_000L;
    private static final int LIST = 0, GUIDE = 1;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);

    private final Context ctx;
    private final Executor worker;
    private final float dp;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private List<LiveChannels.Channel> channels; // the stations switched on (Settings → Live TV)
    private Row[] rows;
    private final FrameLayout root, video, cover, listColumn;
    private final View fadeTop, fadeBottom;
    // dp: the list's faded edges, and the padding at each end that keeps the first and last row
    // clear of them. The top one sits under the header; the bottom one, above the key hints, is
    // shorter so the last channel isn't left far above them.
    private static final int FADE = 26, FADE_BOTTOM = 24; // the top one starts just under the header
    private static final int HINTS = 31; // dp: the key-hint line along the bottom (the list ends just above it)
    private final int rowH, windowH, listW; // dp
    private int contentH; // dp
    private float scroll = -1; // dp the list is scrolled by; -1 before the first layout
    private final LinearLayout banner;
    private final TvPlayer player;
    private TvGuide timetable; // built only when Left opens the hourly guide
    private final TextView clock, date, coverText, nowLabel, nowTitle, nowDesc, nextLabel;
    private final TextView[] nextTime = new TextView[2], nextTitle = new TextView[2];
    private final ImageView coverLogo, bannerLogo;
    private final ProgressBar spinner;
    private final Bar nowBar, bannerBar;
    private final TextView bannerChannel, bannerTitle, bannerTime, bannerNext, bannerHint, hint, watchTag;
    private final CornerMask corners;
    // The screen's background, top-left to bottom-right (CornerMask paints the same gradient).
    private static final int[] BACKGROUND = {0xFF101A2B, 0xFF070A10, 0xFF040509};
    private final FrameLayout.LayoutParams videoWindowed;
    private final int videoHeightPx; // the windowed video's height: the stream is capped to it
    private final View[] chrome; // everything behind the video, hidden while it's full screen
    private final GradientDrawable background;
    private final Map<String, Bitmap> logos = new HashMap<>();
    private Map<String, List<LiveChannels.Programme>> guide;
    private long guideAt;
    private boolean shown, fullscreen, guideLoading, logosLoading;
    private int focus = LIST, selected, tuned = -1, playerState = -1;

    TvScreen(Context ctx, FrameLayout root, Executor worker) {
        this.ctx = ctx;
        this.root = root;
        this.worker = worker;
        dp = ctx.getResources().getDisplayMetrics().density;
        prefs = ctx.getSharedPreferences("live_tv", Context.MODE_PRIVATE);
        channels = enabledChannels();
        selected = savedIndex();

        float w = ctx.getResources().getDisplayMetrics().widthPixels / dp;
        // top: where the list and video start, leaving a clear gap between the header and the
        // list's top fade (which reaches FADE above it).
        int margin = 48, top = 95, vw = Math.round(w * 0.4667f), vh = vw * 9 / 16;
        int videoX = Math.round(w) - margin - vw, rowH = 58;
        listW = videoX - 24 - margin;

        background = new GradientDrawable(GradientDrawable.Orientation.TL_BR, BACKGROUND);
        root.setBackground(background);

        // Header: title and source on the left, the time on the right.
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.BOTTOM);
        TextView title = text(26, 300, TEXT);
        title.setText("Live TV");
        head.addView(title);
        TextView source = text(12, 500, FAINT);
        source.setText("RTMKLIK  ·  TONTON");
        source.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams sp = wrap();
        sp.setMarginStart(px(14));
        sp.bottomMargin = px(4);
        head.addView(source, sp);
        root.addView(head, at(margin, 34, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout time = new LinearLayout(ctx);
        time.setOrientation(LinearLayout.VERTICAL);
        time.setGravity(Gravity.END);
        clock = text(24, 300, TEXT);
        date = text(12, 400, DIM);
        // Each sized to its own text (a vertical LinearLayout's default is the column's width,
        // which cut "20:01" to "20:…" under the narrower date) and never ellipsized; tabular
        // digits keep the clock's width steady from minute to minute.
        for (TextView t : new TextView[] {clock, date}) {
            t.setEllipsize(null);
            t.setFontFeatureSettings("tnum");
            time.addView(t, wrap());
        }
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        tp.topMargin = px(30);
        tp.setMarginEnd(px(margin));
        root.addView(time, tp);

        // Channel list: a window that scrolls to keep the selected channel (and part of each
        // neighbour) in view. Both edges always fade; FADE of padding at each end keeps the first
        // and last channel clear of the fade when they're selected.
        float h = ctx.getResources().getDisplayMetrics().heightPixels / dp;
        this.rowH = rowH;
        // The clear part of the list; a FADE strip above it (up to the header) and below it (down
        // to the key hints, which keep clean background under them) holds the fades, so neither
        // covers a row's text.
        windowH = Math.max(3 * rowH, (int) (h - top - FADE_BOTTOM - HINTS));
        FrameLayout window = new FrameLayout(ctx);
        listColumn = new FrameLayout(ctx);
        window.addView(listColumn, new FrameLayout.LayoutParams(px(listW), 0));
        buildRows();
        fadeTop = new EdgeFade(ctx, root, true);
        window.addView(fadeTop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(FADE), Gravity.TOP));
        fadeBottom = new EdgeFade(ctx, root, false);
        window.addView(fadeBottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(FADE_BOTTOM), Gravity.BOTTOM));
        root.addView(window, at(margin, top - FADE, listW, windowH + FADE + FADE_BOTTOM));
        head.bringToFront(); // the header always draws over the list
        time.bringToFront();

        // Video, with a cover (logo, spinner, message) until the picture plays.
        // Its corners are rounded by a mask on top (CornerMask), not by clipping to an outline.
        video = new FrameLayout(ctx);
        video.setBackgroundColor(Color.BLACK);
        cover = new FrameLayout(ctx);
        cover.setBackgroundColor(0xFF0B0F17);
        LinearLayout coverBox = new LinearLayout(ctx);
        coverBox.setOrientation(LinearLayout.VERTICAL);
        coverBox.setGravity(Gravity.CENTER_HORIZONTAL);
        coverLogo = logoView(8);
        coverBox.addView(coverLogo, new LinearLayout.LayoutParams(px(128), px(72)));
        spinner = new ProgressBar(ctx);
        spinner.setIndeterminate(true);
        spinner.getIndeterminateDrawable().setTint(TEXT);
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(px(28), px(28));
        spl.topMargin = px(16);
        coverBox.addView(spinner, spl);
        coverText = text(13, 400, DIM);
        coverText.setGravity(Gravity.CENTER);
        coverText.setSingleLine(false);
        coverText.setMaxLines(2);
        LinearLayout.LayoutParams ctl = wrap();
        ctl.topMargin = px(10);
        coverBox.addView(coverText, ctl);
        cover.addView(coverBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        video.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // While browsing other channels: what OK will switch the video to.
        watchTag = text(12, 500, TEXT);
        watchTag.setBackground(round(0xCC0B0F17, 8));
        watchTag.setPadding(px(10), px(6), px(10), px(6));
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        wl.leftMargin = px(12);
        wl.bottomMargin = px(12);
        video.addView(watchTag, wl);
        corners = new CornerMask(ctx, root, px(14));
        video.addView(corners, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)); // last: over the picture and the cover
        videoWindowed = at(videoX, top, vw, vh);
        videoHeightPx = px(vh);
        root.addView(video, videoWindowed);
        player = new TvPlayer(ctx, video, this::onPlayer);

        // What's on: now (with progress and description) and the next two.
        LinearLayout info = new LinearLayout(ctx);
        info.setOrientation(LinearLayout.VERTICAL);
        nowLabel = caps(11, DIM);
        info.addView(nowLabel);
        nowTitle = text(20, 500, TEXT);
        info.addView(nowTitle, topMargin(wrapW(), 6));
        nowBar = new Bar(ctx, 0x33FFFFFF, TEXT);
        info.addView(nowBar, topMargin(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3)), 10));
        nowDesc = text(12, 400, DIM);
        info.addView(nowDesc, topMargin(wrapW(), 9));
        nextLabel = caps(11, FAINT);
        nextLabel.setText("UP NEXT");
        info.addView(nextLabel, topMargin(wrap(), 14));
        for (int i = 0; i < nextTitle.length; i++) {
            LinearLayout line = new LinearLayout(ctx);
            nextTime[i] = text(13, 500, DIM);
            nextTime[i].setMinWidth(px(52)); // "20:00" lines up; "Tue 10:00" widens it
            LinearLayout.LayoutParams ntl = wrap();
            ntl.setMarginEnd(px(14));
            line.addView(nextTime[i], ntl);
            nextTitle[i] = text(13, 400, TEXT);
            line.addView(nextTitle[i], new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            info.addView(line, topMargin(wrapW(), 6));
        }
        root.addView(info, at(videoX, top + vh + 18, vw, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Key hints along the bottom.
        hint = text(11, 400, FAINT); // set in refresh(): what OK does depends on the selection
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        hp.setMarginStart(px(margin));
        hp.bottomMargin = px(14);
        root.addView(hint, hp);
        chrome = new View[] {head, time, window, info, hint};

        // Full-screen banner: channel, what's on, progress and next.
        banner = new LinearLayout(ctx);
        banner.setGravity(Gravity.BOTTOM);
        banner.setPadding(px(56), px(120), px(56), px(40));
        banner.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x00000000, 0x99000000, 0xD9000000, 0xF5000000}));
        bannerLogo = logoView(8);
        banner.addView(bannerLogo, new LinearLayout.LayoutParams(px(128), px(72)));
        LinearLayout bt = new LinearLayout(ctx);
        bt.setOrientation(LinearLayout.VERTICAL);
        bannerChannel = caps(12, DIM);
        bt.addView(bannerChannel);
        bannerTitle = text(26, 500, TEXT);
        bt.addView(bannerTitle, topMargin(wrapW(), 4));
        LinearLayout prog = new LinearLayout(ctx);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        bannerTime = text(13, 500, DIM);
        prog.addView(bannerTime);
        bannerBar = new Bar(ctx, 0x40FFFFFF, TEXT);
        LinearLayout.LayoutParams bbl = new LinearLayout.LayoutParams(px(320), px(3));
        bbl.setMarginStart(px(12));
        prog.addView(bannerBar, bbl);
        bt.addView(prog, topMargin(wrapW(), 8));
        bannerNext = text(13, 400, DIM);
        bt.addView(bannerNext, topMargin(wrapW(), 8));
        LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btl.setMarginStart(px(24));
        banner.addView(bt, btl);
        bannerHint = text(12, 400, FAINT);
        banner.addView(bannerHint);
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        refresh(); // the cover starts as "Press OK to watch"
    }

    /** The stations switched on in Settings → Live TV (all of them if none are, so the screen isn't empty). */
    private List<LiveChannels.Channel> enabledChannels() {
        List<LiveChannels.Channel> on = LiveChannels.enabled(ctx);
        return on.isEmpty() ? LiveChannels.ALL : on;
    }

    /** The last channel watched, by slug (once saved by list index: read that once). */
    private int savedIndex() {
        String slug = prefs.getString("channel_slug", null);
        if (slug == null && prefs.contains("channel")) {
            int old = prefs.getInt("channel", 0);
            slug = LiveChannels.legacyChannelSlug(old);
            prefs.edit().remove("channel").putString("channel_slug", slug).apply();
        }
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).slug.equals(slug)) return i;
        return 0;
    }

    /** (Re)builds the list's rows for {@code channels}. */
    private void buildRows() {
        listColumn.removeAllViews();
        contentH = FADE + FADE_BOTTOM + channels.size() * rowH - 4;
        ViewGroup.LayoutParams lp = listColumn.getLayoutParams();
        lp.height = px(contentH);
        listColumn.setLayoutParams(lp);
        rows = new Row[channels.size()];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new Row(i, channels.get(i));
            listColumn.addView(rows[i].view, at(0, FADE + i * rowH, listW, rowH - 4));
        }
        scroll = -1;
        listColumn.setTranslationY(0);
    }

    // ---------------------------------------------------------------- lifecycle

    /** The screen came into view (or home came back with it open). */
    void show() {
        if (shown) return;
        shown = true;
        focus = LIST;
        List<LiveChannels.Channel> now = enabledChannels();
        if (!now.equals(channels)) { // stations switched on or off in Settings since last time
            channels = now;
            selected = savedIndex();
            tuned = -1;
            buildRows();
            loadLogos();
        }
        refresh();
        loadGuide(false);
        loadLogos(); // nothing plays until OK
        main.post(() -> { if (shown) player.prepare(); }); // warm WebView without loading a station
        scheduleTick();
    }

    /**
     * The system is short of memory: while the screen is closed, drop the logos and the guide
     * (both come back the next time it opens).
     */
    void trimMemory() {
        if (shown) return;
        for (Row r : rows) setLogo(r.logo, null);
        setLogo(coverLogo, null);
        setLogo(bannerLogo, null);
        for (Bitmap b : logos.values()) b.recycle();
        logos.clear();
        guide = null;
        guideAt = 0;
    }

    /** The screen left view or home went behind another app: playback stops, memory is freed. */
    void hide() {
        if (!shown) return;
        shown = false;
        if (focus == GUIDE) closeTimetable();
        main.removeCallbacksAndMessages(null);
        if (fullscreen) setFullscreen(false);
        player.release();
        tuned = -1;
        playerState = -1;
        refresh();
    }

    // ---------------------------------------------------------------- keys

    // Up/Down only browse: the info follows the selection, and the video changes on OK.

    void up() {
        if (fullscreen) browse(-1, true);
        else if ((focus == LIST || focus == GUIDE) && selected > 0) browse(-1, false);
    }

    void down() {
        if (fullscreen) browse(1, true);
        else if ((focus == LIST || focus == GUIDE) && selected < channels.size() - 1) browse(1, false);
    }

    void left() {
        if (fullscreen) showBanner();
        else if (focus == GUIDE) scrollTimetable(-1);
        else openTimetable();
    }

    void right() {
        if (fullscreen) showBanner();
        else if (focus == GUIDE) scrollTimetable(1);
    }

    /**
     * OK: on a channel, watch it in the preview (OK again on the one playing goes full screen);
     * in full screen, switch to the channel browsed in the banner. The preview never takes focus.
     */
    void select() {
        if (focus == GUIDE) {
            closeTimetable();
            // A future listing isn't catch-up: OK always watches this channel's live stream.
            if (selected != tuned || playerState == TvPlayer.FAILED) tune(selected);
            return;
        }
        if (fullscreen) {
            if (selected != tuned) {
                tune(selected);
                showBanner();
            } else if (banner.getVisibility() == View.VISIBLE) {
                hideBanner.run();
            } else {
                showBanner();
            }
            return;
        }
        if (selected == tuned && playerState != TvPlayer.FAILED) {
            setFullscreen(true);
        } else {
            tune(selected);
        }
    }

    /** Back: closes the timetable first, then full screen. False when nothing is open here. */
    boolean back() {
        if (focus == GUIDE) {
            closeTimetable();
            return true;
        }
        if (!fullscreen) return false;
        setFullscreen(false);
        setFocus(LIST);
        return true;
    }

    /** CH+ / CH−: switches straight away (a dedicated channel key means watch), wrapping around. */
    void zap(int dir) {
        int n = channels.size();
        int from = tuned >= 0 ? tuned : selected;
        selected = ((from + dir) % n + n) % n;
        tune(selected);
        if (fullscreen) showBanner();
    }

    // ---------------------------------------------------------------- state

    private void openTimetable() {
        if (timetable == null) {
            timetable = new TvGuide(ctx);
            root.addView(timetable, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        timetable.open();
        loadGuide(false);
        setFocus(GUIDE);
    }

    private void closeTimetable() {
        timetable.close();
        setFocus(LIST);
    }

    private void scrollTimetable(int direction) {
        timetable.move(direction);
        refresh();
    }

    private void browse(int dir, boolean wrap) {
        int n = channels.size();
        selected = wrap ? ((selected + dir) % n + n) % n : Math.max(0, Math.min(n - 1, selected + dir));
        if (fullscreen) showBanner(); // which refreshes
        else refresh();
    }

    private void tune(int i) {
        if (!shown) return;
        selected = tuned = i;
        prefs.edit().putString("channel_slug", channels.get(i).slug).apply();
        LiveChannels.Channel c = channels.get(i);
        showCover(c, TvStartupProgress.tuningLabel(c.name), true, DIM);
        player.setViewHeight(fullscreen ? 0 : videoHeightPx);
        player.play(c);
        refresh();
    }

    private void onPlayer(int state, String message) {
        playerState = state;
        if (tuned >= 0) {
            LiveChannels.Channel c = channels.get(tuned);
            if (state == TvPlayer.PLAYING) {
                cover.animate().alpha(0f).setDuration(250).withEndAction(() -> cover.setVisibility(View.GONE)).start();
            } else if (state == TvPlayer.FAILED) {
                showCover(c, message, false, TEXT);
            } else if (state == TvPlayer.SIGN_IN) {
                showCover(c, "Sign in to Tonton in Settings → Live TV to watch " + c.name, false, TEXT);
            } else {
                showCover(c, message != null ? message : TvStartupProgress.tuningLabel(c.name), true, DIM);
            }
        }
        refresh();
    }

    private void showCover(LiveChannels.Channel c, String text, boolean busy, int color) {
        if (cover.getVisibility() != View.VISIBLE || cover.getAlpha() != 1f) {
            cover.animate().cancel();
            cover.setAlpha(1f);
            cover.setVisibility(View.VISIBLE);
        }
        logo(coverLogo, c);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        set(coverText, text);
        coverText.setTextColor(color);
    }

    private void setFocus(int f) {
        focus = f;
        refresh();
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        RemoteBridge.tvFullscreen(on); // the info overlay, if switched on, shows over full screen TV
        if (tuned >= 0) selected = tuned; // full screen opens and closes on the channel that's on
        // Full screen: the stream at the player's own choice of quality, and nothing drawn behind
        // the picture (the list, guide and background would otherwise be redrawn every frame).
        player.setViewHeight(on ? 0 : videoHeightPx);
        for (View v : chrome) v.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        root.setBackground(on ? null : background);
        if (on) {
            video.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            corners.setRadius(0);
            video.bringToFront();
            banner.bringToFront();
            showBanner();
        } else {
            video.setLayoutParams(videoWindowed);
            corners.setRadius(px(14));
            main.removeCallbacks(hideBanner);
            banner.setVisibility(View.GONE);
        }
        refresh();
    }

    private void showBanner() {
        refresh();
        banner.animate().cancel();
        banner.setAlpha(1f);
        banner.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideBanner);
        main.postDelayed(hideBanner, BANNER_MS);
    }

    private final Runnable hideBanner = this::fadeBanner;

    private void fadeBanner() {
        banner.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            banner.setVisibility(View.GONE);
            if (fullscreen && tuned >= 0 && selected != tuned) { // browsed without OK: back to it
                selected = tuned;
                refresh();
            }
        }).start();
    }

    // Once a minute, just after it turns: the clock, progress bars and what's on. Nothing finer
    // changes, so nothing runs more often while the screen is idle.
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!shown) return;
            if (System.currentTimeMillis() - guideAt > GUIDE_MAX_AGE_MS) loadGuide(true);
            refresh();
            scheduleTick();
        }
    };

    private void scheduleTick() {
        main.removeCallbacks(tick);
        main.postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000 + 250);
    }

    // ---------------------------------------------------------------- data

    private void loadGuide(boolean force) {
        if (guideLoading || (!force && guide != null && System.currentTimeMillis() - guideAt < GUIDE_MAX_AGE_MS)) return;
        guideLoading = true;
        worker.execute(() -> {
            Map<String, List<LiveChannels.Programme>> g;
            try { g = LiveChannels.load(); } catch (Exception e) { g = null; }
            Map<String, List<LiveChannels.Programme>> result = g;
            main.post(() -> {
                guideLoading = false;
                if (result != null) {
                    guide = result;
                    guideAt = System.currentTimeMillis();
                }
                refresh();
            });
        });
    }

    private void loadLogos() {
        if (logosLoading || logos.size() == channels.size()) return;
        logosLoading = true;
        worker.execute(() -> {
            Map<String, Bitmap> got = new HashMap<>();
            // Hardware bitmaps: the pixels live once, in graphics memory, not on the app's heap.
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.HARDWARE;
            for (LiveChannels.Channel c : channels) {
                if (logos.containsKey(c.slug)) continue;
                try {
                    byte[] b = LiveChannels.download(c.logoUrl(), 512 * 1024);
                    Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length, o);
                    if (bm != null) got.put(c.slug, bm);
                } catch (Exception ignored) { // the card shows the name instead
                }
            }
            main.post(() -> {
                logosLoading = false;
                logos.putAll(got);
                for (Row r : rows) logo(r.logo, r.channel);
                if (tuned >= 0) logo(coverLogo, channels.get(tuned));
                refresh();
            });
        });
    }

    // ---------------------------------------------------------------- drawing

    private void refresh() {
        long now = System.currentTimeMillis();
        Instant t = Instant.ofEpochMilli(now);
        set(clock, HM.format(t.atZone(ZoneId.systemDefault())));
        set(date, DATE.format(t.atZone(ZoneId.systemDefault())));
        for (Row r : rows) r.update(now);
        scrollList();
        LiveChannels.Channel sel = channels.get(selected);
        if (tuned < 0) showCover(sel, "Press OK to watch", false, DIM); // nothing on yet
        boolean browsing = tuned >= 0 && selected != tuned;
        set(watchTag, "OK   Watch " + sel.name);
        watchTag.setVisibility(browsing && !fullscreen ? View.VISIBLE : View.GONE);
        set(hint, "▲▼  Browse      OK  " + (selected == tuned ? "Full screen" : "Watch")
                + "      ◀  Guide      BACK  Running apps");
        set(bannerHint, (browsing ? "OK  Watch      " : "") + "▲▼  Channels      BACK  Exit full screen");

        LiveChannels.Channel c = channels.get(selected);
        List<LiveChannels.Programme> list = guide == null ? null : guide.get(c.slug);
        LiveChannels.Programme p = LiveChannels.now(list, now);
        List<LiveChannels.Programme> next = LiveChannels.upcoming(list, now, nextTitle.length);
        if (p != null) {
            set(nowLabel, c.name.toUpperCase(Locale.ROOT) + "   ·   " + span(p) + "   ·   " + left(p.end - now)
                    .toUpperCase(Locale.ROOT));
            set(nowTitle, p.title);
            nowBar.set(p.progress(now));
            nowBar.setVisibility(View.VISIBLE);
            set(nowDesc, p.description);
        } else {
            set(nowLabel, c.name.toUpperCase(Locale.ROOT) + "   ·   LIVE");
            set(nowTitle, c.name);
            nowBar.setVisibility(View.INVISIBLE);
            set(nowDesc, guide == null ? (guideLoading ? "Loading the programme guide…" : "Programme guide unavailable")
                    : c.path.startsWith("live/khas/") ? "Live when Parliament is sitting" : "No schedule for this channel right now");
        }
        nextLabel.setVisibility(next.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        for (int i = 0; i < nextTitle.length; i++) {
            boolean has = i < next.size();
            set(nextTime[i], has ? time(next.get(i).start) : "");
            set(nextTitle[i], has ? next.get(i).title : "");
        }

        // Full-screen banner: the channel on, or the one browsed to (OK switches to it).
        logo(bannerLogo, c);
        boolean live = playerState == TvPlayer.PLAYING && tuned == selected;
        set(bannerChannel, (selected + 1) + "   " + c.name.toUpperCase(Locale.ROOT) + (live ? "   ●  LIVE" : ""));
        bannerChannel.setTextColor(live ? LIVE : DIM);
        set(bannerTitle, p != null ? p.title : c.name);
        set(bannerTime, p != null ? span(p) : "Live");
        bannerBar.set(p != null ? p.progress(now) : 0f);
        bannerBar.setVisibility(p != null ? View.VISIBLE : View.GONE);
        set(bannerNext, next.isEmpty() ? "" : "Next   " + time(next.get(0).start) + "   " + next.get(0).title);
        if (focus == GUIDE) timetable.update(channels, selected, guide, logos, guideLoading, now);
    }

    /**
     * Scrolls only as far as needed to keep the selected row clear of both fades with half a
     * neighbouring row showing beyond it, so the list moves smoothly rather than by pages.
     */
    private void scrollList() {
        float view = windowH + FADE + FADE_BOTTOM; // the window's height, fades included
        float rowTop = FADE + selected * rowH, rowBottom = rowTop + rowH - 4, context = rowH / 2f;
        float s = scroll < 0 ? 0 : scroll;
        s = Math.max(s, rowBottom + context + FADE_BOTTOM - view); // room below
        s = Math.min(s, rowTop - context - FADE);           // room above
        s = Math.max(0, Math.min(Math.max(0, contentH - view), s));
        if (s == scroll) return;
        boolean animate = scroll >= 0 && shown;
        scroll = s;
        listColumn.animate().cancel();
        if (animate) listColumn.animate().translationY(-px(s)).setDuration(200).start();
        else listColumn.setTranslationY(-px(s));
    }

    /** Where {@code v} sits on {@code screen}, in px (layout position plus any translation). */
    private static float[] offsetIn(View v, View screen) {
        float x = 0, y = 0;
        for (View p = v; p != null && p != screen; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            x += p.getLeft() + p.getTranslationX();
            y += p.getTop() + p.getTranslationY();
        }
        return new float[] {x, y};
    }

    /** The screen's background gradient as seen from {@code v}'s own coordinates. */
    private static Shader screenBackground(View v, View screen) {
        float[] o = offsetIn(v, screen);
        return new LinearGradient(-o[0], -o[1], screen.getWidth() - o[0], screen.getHeight() - o[1],
                BACKGROUND, null, Shader.TileMode.CLAMP);
    }

    /**
     * The list's faded edge: the screen's own background, opaque at the edge and clear toward
     * the list, so rows dissolve into it with no band of a slightly different colour.
     */
    private static final class EdgeFade extends View {
        private final View screen;
        private final boolean top;
        private final Cached cache = new Cached();

        EdgeFade(Context c, View screen, boolean top) {
            super(c);
            this.screen = screen;
            this.top = top;
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w == 0 || h == 0) return;
            float[] o = offsetIn(this, screen);
            // Rendered once into a bitmap (it never changes while the screen stays put): drawing
            // it live needed an offscreen layer on every frame, i.e. every frame of the video.
            Bitmap b = cache.get(w, h, o, screen, (cv, bw, bh) -> {
                Paint bg = new Paint();
                bg.setShader(screenBackground(this, screen));
                cv.drawRect(0, 0, bw, bh, bg);
                // Solid at the outer edge, so a bright progress bar right there can't show
                // through as a thin line; then clear toward the list. The bottom one runs down to
                // the key hints, so only a sliver is solid there and the shading reaches the text.
                Paint mask = new Paint();
                mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
                mask.setShader(new LinearGradient(0, top ? 0 : bh, 0, top ? bh : 0,
                        new int[] {0xFF000000, 0xFF000000, 0x00000000}, new float[] {0f, top ? 0.25f : 0.12f, 1f},
                        Shader.TileMode.CLAMP));
                cv.drawRect(0, 0, bw, bh, mask);
            });
            c.drawBitmap(b, 0, 0, null);
        }
    }

    /**
     * A bitmap rendered once for a view's size and spot on the screen, and again only when either
     * changes (the screen's gradient behind it depends on both).
     */
    private static final class Cached {
        interface Painter { void paint(Canvas c, int w, int h); }
        private Bitmap bitmap;
        private int w, h, sw, sh;
        private float x = Float.NaN, y;

        Bitmap get(int w, int h, float[] at, View screen, Painter painter) {
            if (bitmap != null && w == this.w && h == this.h && at[0] == x && at[1] == y
                    && screen.getWidth() == sw && screen.getHeight() == sh) return bitmap;
            if (bitmap != null) bitmap.recycle();
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            painter.paint(new Canvas(bitmap), w, h);
            this.w = w;
            this.h = h;
            x = at[0];
            y = at[1];
            sw = screen.getWidth();
            sh = screen.getHeight();
            return bitmap;
        }
    }

    private static String span(LiveChannels.Programme p) {
        return time(p.start) + " – " + time(p.end);
    }

    /** "18 min left", "2 h 5 min left", "14 h left". */
    static String left(long ms) {
        long min = Math.max(1, (ms + 59_999) / 60_000);
        if (min < 60) return min + " min left";
        long h = min / 60, m = min % 60;
        return h + " h" + (m == 0 ? "" : " " + m + " min") + " left";
    }

    /** "20:00" today, "Tue 10:00" on another day (some listings run past midnight). */
    private static String time(long ms) {
        ZoneId zone = ZoneId.systemDefault();
        java.time.ZonedDateTime t = Instant.ofEpochMilli(ms).atZone(zone);
        boolean today = t.toLocalDate().equals(java.time.LocalDate.now(zone));
        return (today ? "" : DAY.format(t) + " ") + HM.format(t);
    }

    /** One channel in the list: number, logo card, name, what's on and its progress. */
    private final class Row {
        final LiveChannels.Channel channel;
        final int index;
        final LinearLayout view;
        final GradientDrawable bg;
        final TextView number, name, now, live;
        final ImageView logo;
        final Bar bar;

        Row(int index, LiveChannels.Channel channel) {
            this.index = index;
            this.channel = channel;
            view = new LinearLayout(ctx);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(px(12), 0, px(14), 0);
            bg = round(Color.TRANSPARENT, 12);
            view.setBackground(bg);
            number = text(13, 500, FAINT);
            number.setText(String.valueOf(index + 1));
            number.setGravity(Gravity.CENTER);
            view.addView(number, new LinearLayout.LayoutParams(px(22), ViewGroup.LayoutParams.WRAP_CONTENT));
            logo = logoView(5);
            logo(logo, channel);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(px(72), px(40));
            ll.setMarginStart(px(8));
            ll.setMarginEnd(px(14));
            view.addView(logo, ll);
            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout nameLine = new LinearLayout(ctx);
            nameLine.setGravity(Gravity.CENTER_VERTICAL);
            name = text(15, 500, TEXT);
            name.setText(channel.name);
            nameLine.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            live = caps(10, LIVE);
            live.setText("●  LIVE");
            nameLine.addView(live);
            texts.addView(nameLine);
            now = text(12, 400, DIM);
            texts.addView(now, topMargin(wrapW(), 3));
            bar = new Bar(ctx, 0x26FFFFFF, 0xCCFFFFFF);
            texts.addView(bar, topMargin(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(2)), 6));
            view.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        void update(long t) {
            boolean isSel = index == selected, focused = isSel && focus == LIST && !fullscreen;
            bg.setColor(focused ? ROW_FOCUS : isSel ? ROW_SELECTED : Color.TRANSPARENT);
            number.setTextColor(focused ? INK_DIM : FAINT);
            name.setTextColor(focused ? INK : TEXT);
            now.setTextColor(focused ? INK_DIM : DIM);
            bar.setColors(focused ? 0x26101318 : 0x26FFFFFF, focused ? INK : 0xCCFFFFFF);
            live.setVisibility(shown && index == tuned && playerState == TvPlayer.PLAYING ? View.VISIBLE : View.GONE);
            List<LiveChannels.Programme> list = guide == null ? null : guide.get(channel.slug);
            LiveChannels.Programme p = LiveChannels.now(list, t);
            set(now, p != null ? p.title : guide == null ? " " : "No programme information");
            bar.set(p != null ? p.progress(t) : 0f);
            bar.setVisibility(p != null ? View.VISIBLE : View.INVISIBLE);
        }
    }

    /**
     * Rounds the video's corners by painting the screen's own background over them. Clipping the
     * WebView and the loading cover to a rounded outline anti-aliased each layer separately, so
     * the RTMKlik page (white while it loads) showed through the cover's edge as a light fringe.
     * A mask on top rounds everything under it as one layer, video overlays included.
     */
    private static final class CornerMask extends View {
        private final View screen;
        private final Cached[] corners = {new Cached(), new Cached(), new Cached(), new Cached()};
        private float radius;

        CornerMask(Context c, View screen, float radius) {
            super(c);
            this.screen = screen;
            this.radius = radius;
        }

        void setRadius(float r) {
            if (r == radius) return;
            radius = r;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight(), r = (int) Math.ceil(radius);
            if (r <= 0 || w < 2 * r || h < 2 * r) return;
            float[] o = offsetIn(this, screen);
            // Only the four corners are painted, each from a small bitmap rendered once (drawn on
            // every frame of the video, so it must be cheap).
            for (int i = 0; i < 4; i++) {
                int cx = (i & 1) == 0 ? 0 : w - r, cy = (i & 2) == 0 ? 0 : h - r;
                float[] at = {o[0] + cx, o[1] + cy};
                Bitmap b = corners[i].get(r, r, at, screen, (cv, bw, bh) -> {
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    paint.setShader(screenBackground(this, screen)); // lined up with the screen behind
                    Path path = new Path();
                    path.setFillType(Path.FillType.EVEN_ODD); // the rect minus the rounded rect
                    path.addRect(0, 0, w, h, Path.Direction.CW);
                    path.addRoundRect(0, 0, w, h, radius, radius, Path.Direction.CW);
                    cv.translate(-cx, -cy);
                    cv.drawPath(path, paint);
                });
                c.drawBitmap(b, cx, cy, null);
            }
        }
    }

    /** A thin rounded progress bar. */
    private static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;

        Bar(Context c, int trackColor, int fillColor) {
            super(c);
            setColors(trackColor, fillColor);
        }

        void setColors(int t, int f) {
            if (track.getColor() == t && fill.getColor() == f) return;
            track.setColor(t);
            fill.setColor(f);
            invalidate();
        }

        void set(float p) {
            if (p == progress) return;
            progress = p;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), r = h / 2f;
            c.drawRoundRect(0, 0, getWidth(), h, r, r, track);
            if (progress > 0f) c.drawRoundRect(0, 0, Math.max(h, getWidth() * progress), h, r, r, fill);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A channel's logo into a card: RTMKlik's logos are white 16:9 cards (the card matches them
     * before they load); Tonton's are transparent, made for a dark background.
     */
    private void logo(ImageView v, LiveChannels.Channel c) {
        ((GradientDrawable) v.getBackground()).setColor(c.site == LiveChannels.Site.TONTON ? 0xFF1C2433 : Color.WHITE);
        setLogo(v, logos.get(c.slug));
    }

    /** A logo card (white until logo() says otherwise). */
    private ImageView logoView(int radiusDp) {
        ImageView v = new ImageView(ctx);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setBackground(round(Color.WHITE, radiusDp));
        v.setClipToOutline(true);
        return v;
    }

    /** setText only when the text changes: an equal setText still re-lays the view out. */
    private static void set(TextView t, CharSequence s) {
        if (!TextUtils.equals(t.getText(), s)) t.setText(s);
    }

    /** A logo into its card, skipped when it's already showing that bitmap. */
    private static void setLogo(ImageView v, Bitmap b) {
        if (v.getTag() == b) return;
        v.setTag(b);
        v.setImageBitmap(b);
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radiusDp));
        return d;
    }

    private FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w > 0 ? px(w) : w, h > 0 ? px(h) : h,
                Gravity.TOP | Gravity.START);
        lp.leftMargin = px(x);
        lp.topMargin = px(y);
        return lp;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapW() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams lp, int dps) {
        lp.topMargin = px(dps);
        return lp;
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private TextView caps(float sp, int color) {
        TextView t = text(sp, 600, color);
        t.setLetterSpacing(0.1f);
        return t;
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
