package my.rafhan.app.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** An hourly EPG over the browsing screen. Only visible rows and programmes are drawn. */
final class TvGuide extends FrameLayout {
    private static final int TEXT = 0xFFFFFFFF, DIM = 0x99FFFFFF, FAINT = 0x59FFFFFF;
    private static final int INK = 0xFF101318;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH);
    private final float dp, sp;
    private final Grid grid;
    private final TextView date, title, description;
    private final Typeface regular, medium;
    private List<LiveChannels.Channel> channels;
    private Map<String, List<LiveChannels.Programme>> listings;
    private Map<String, Bitmap> logos;
    private TvGuideTimeline timeline;
    private int selected;
    private long now;
    private boolean loading;

    TvGuide(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        sp = getResources().getDisplayMetrics().scaledDensity;
        regular = Fonts.outfit(context, 400);
        medium = Fonts.outfit(context, 500);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF101A2B, 0xFF070A10, 0xFF040509}));
        TextView heading = text(26, TEXT);
        heading.setText("Programme guide");
        addView(heading, place(48, 34, -2, -2, Gravity.TOP | Gravity.START));
        date = text(14, DIM);
        addView(date, place(48, 43, -2, -2, Gravity.TOP | Gravity.END));
        grid = new Grid(context);
        LayoutParams gp = place(48, 94, -1, -1, Gravity.TOP | Gravity.START);
        gp.rightMargin = px(48);
        gp.bottomMargin = px(148);
        addView(grid, gp);
        title = text(18, TEXT);
        title.setTypeface(medium);
        LayoutParams tp = place(48, 105, -1, -2, Gravity.BOTTOM | Gravity.START);
        tp.rightMargin = px(48);
        addView(title, tp);
        description = text(12, DIM);
        description.setSingleLine(false);
        description.setMaxLines(2);
        LayoutParams dp = place(48, 57, -1, 36, Gravity.BOTTOM | Gravity.START);
        dp.rightMargin = px(48);
        addView(description, dp);
        TextView hint = text(11, FAINT);
        hint.setText("◀ ▶  Programmes      ▲▼  Channels      OK  Watch live      BACK  Channel list");
        addView(hint, place(48, 14, -2, -2, Gravity.BOTTOM | Gravity.START));
        setVisibility(GONE);
    }

    void open() {
        timeline = new TvGuideTimeline(System.currentTimeMillis(), ZoneId.systemDefault());
        grid.snap = true;
        setVisibility(VISIBLE);
        bringToFront();
    }

    void close() {
        setVisibility(GONE);
        grid.stop();
        // Do not retain guide data/logos after TvScreen drops them on a memory trim.
        listings = null;
        logos = null;
        timeline = null;
    }

    void move(int direction) { timeline.move(direction, list(channels.get(selected))); }

    void update(List<LiveChannels.Channel> channels, int selected,
            Map<String, List<LiveChannels.Programme>> listings, Map<String, Bitmap> logos,
            boolean loading, long now) {
        if (getVisibility() != VISIBLE) return;
        this.channels = channels;
        this.selected = selected;
        this.listings = listings;
        this.logos = logos;
        this.loading = loading;
        this.now = now;
        LiveChannels.Channel c = channels.get(selected);
        LiveChannels.Programme p = timeline.programme(list(c));
        date.setText(DATE.format(Instant.ofEpochMilli(p == null ? timeline.cursor() : p.start).atZone(ZoneId.systemDefault())));
        title.setText(c.name + "   ·   " + (p == null ? "No programme information" : p.title));
        description.setText(p == null ? unavailable() : time(p.start) + " – " + time(p.end)
                + (p.airingAt(now) ? "   ·   LIVE NOW" : "")
                + (TextUtils.isEmpty(p.description) ? "" : "\n" + p.description));
        grid.setContentDescription(title.getText() + ". " + description.getText());
        grid.position();
    }

    private List<LiveChannels.Programme> list(LiveChannels.Channel c) {
        return listings == null ? null : listings.get(c.slug);
    }

    private String unavailable() {
        return listings == null ? (loading ? "Loading the programme guide…" : "Programme guide unavailable")
                : "No schedule available at this time";
    }

    private String time(long ms) {
        return HM.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    private int px(float value) { return Math.round(value * dp); }

    private TextView text(float size, int color) {
        TextView view = new TextView(getContext());
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(regular);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        return view;
    }

    private LayoutParams place(int x, int y, int w, int h, int gravity) {
        LayoutParams lp = new LayoutParams(w > 0 ? px(w) : w, h > 0 ? px(h) : h, gravity);
        if ((gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == Gravity.END) lp.rightMargin = px(x);
        else lp.leftMargin = px(x);
        if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM) lp.bottomMargin = px(y);
        else lp.topMargin = px(y);
        return lp;
    }

    private final class Grid extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float channelW = px(174), hourW = px(360), rowH = px(54), rulerH = px(30);
        private float x, y, targetX, targetY;
        private long targetStart;
        private boolean snap;
        private ValueAnimator animation;

        Grid(Context context) { super(context); }

        void stop() {
            if (animation != null) animation.cancel();
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            if (timeline != null && getVisibility() == VISIBLE) position();
        }

        void position() {
            long start = snap ? timeline.hour() : targetStart;
            long width = Math.round(Math.max(1, getWidth() - channelW) / hourW * TvGuideTimeline.HOUR);
            long nextStart = timeline.windowStart(start, width, list(channels.get(selected)));
            targetStart = nextStart;
            float nextX = (nextStart - timeline.first) / (float) TvGuideTimeline.HOUR * hourW;
            float visibleH = Math.max(rowH, getHeight() - rulerH);
            float nextY = Math.max(targetY, (selected + 1) * rowH - visibleH);
            nextY = Math.max(0, Math.min(nextY, selected * rowH));
            nextY = Math.min(nextY, Math.max(0, channels.size() * rowH - visibleH));
            if (snap || nextX != targetX || nextY != targetY) {
                stop();
                targetX = nextX;
                targetY = nextY;
                if (snap || getWidth() == 0) {
                    x = nextX;
                    y = nextY;
                } else {
                    float fromX = x, fromY = y;
                    animation = ValueAnimator.ofFloat(0, 1);
                    animation.setDuration(180);
                    animation.addUpdateListener(a -> {
                        float f = (float) a.getAnimatedValue();
                        x = fromX + (targetX - fromX) * f;
                        y = fromY + (targetY - fromY) * f;
                        invalidate();
                    });
                    animation.start();
                }
                snap = false;
            }
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (channels == null || timeline == null) return;
            float w = getWidth(), h = getHeight();
            int firstRow = Math.max(0, (int) (y / rowH));
            int lastRow = Math.min(channels.size(), (int) Math.ceil((y + h - rulerH) / rowH));
            // Clip the timetable separately: channel labels stay pinned while time scrolls.
            canvas.save();
            canvas.clipRect(channelW, 0, w, h);
            int firstHour = Math.max(0, (int) (x / hourW));
            for (int hour = firstHour; ; hour++) {
                long at = timeline.first + hour * TvGuideTimeline.HOUR;
                float left = channelW + hour * hourW - x;
                if (left >= w || at > timeline.last) break;
                label(canvas, time(at), left + px(10), px(17), hourW - px(14), 12, DIM);
                paint.setColor(0x1AFFFFFF);
                canvas.drawRect(left, rulerH, left + px(1), h, paint);
            }
            canvas.clipRect(channelW, rulerH, w, h);
            for (int i = firstRow; i < lastRow; i++) {
                float top = rulerH + i * rowH - y;
                LiveChannels.Channel channel = channels.get(i);
                List<LiveChannels.Programme> programmes = list(channel);
                LiveChannels.Programme focused = i == selected ? timeline.programme(programmes) : null;
                if (programmes != null) for (LiveChannels.Programme p : programmes) {
                    float left = channelW + (p.start - timeline.first) / (float) TvGuideTimeline.HOUR * hourW - x;
                    float right = channelW + (p.end - timeline.first) / (float) TvGuideTimeline.HOUR * hourW - x;
                    if (right <= channelW || left >= w) continue;
                    boolean focus = p == focused;
                    box(canvas, left + px(2), top + px(2), right - px(2), top + rowH - px(2),
                            focus ? 0xFFF2F2F2 : p.airingAt(now) ? 0x263D91C9 : 0x14FFFFFF);
                    float textLeft = Math.max(channelW, left) + px(10);
                    float textW = Math.min(right, w) - textLeft - px(8);
                    label(canvas, p.title, textLeft, top + px(22), textW, 13, focus ? INK : TEXT);
                    label(canvas, time(p.start) + " – " + time(p.end), textLeft, top + px(40), textW, 10,
                            focus ? 0xB3101318 : DIM);
                }
                if (programmes == null || programmes.isEmpty())
                    label(canvas, unavailable(), channelW + px(12), top + px(30), w - channelW - px(24), 12, DIM);
            }
            // A live-time marker stays at the actual time, independent of the selected programme.
            float liveX = channelW + (now - timeline.first) / (float) TvGuideTimeline.HOUR * hourW - x;
            paint.setColor(0xFFFF4D4D);
            canvas.drawRect(liveX, rulerH, liveX + px(2), h, paint);
            canvas.restore();

            label(canvas, "CHANNEL", px(10), px(17), channelW - px(20), 11, FAINT);
            canvas.save();
            canvas.clipRect(0, rulerH, channelW - px(8), h);
            for (int i = firstRow; i < lastRow; i++) {
                float top = rulerH + i * rowH - y;
                LiveChannels.Channel c = channels.get(i);
                if (i == selected && timeline.programme(list(c)) != null)
                    box(canvas, 0, top + px(2), channelW - px(10), top + rowH - px(2), 0x26FFFFFF);
                Bitmap logo = logos == null ? null : logos.get(c.slug);
                if (logo != null) {
                    box(canvas, px(8), top + px(11), px(64), top + px(43),
                            c.site == LiveChannels.Site.TONTON ? 0xFF1C2433 : 0xFFFFFFFF);
                    float scale = Math.min(px(56) / (float) logo.getWidth(), px(32) / (float) logo.getHeight());
                    float lw = logo.getWidth() * scale, lh = logo.getHeight() * scale;
                    rect.set(px(36) - lw / 2, top + rowH / 2 - lh / 2, px(36) + lw / 2, top + rowH / 2 + lh / 2);
                    paint.setColor(TEXT);
                    canvas.drawBitmap(logo, null, rect, paint);
                }
                float left = logo == null ? px(10) : px(74);
                label(canvas, c.name, left, top + px(31), channelW - left - px(18), 13, TEXT);
            }
            canvas.restore();
        }

        private void box(Canvas canvas, float l, float t, float r, float b, int color) {
            paint.setColor(color);
            canvas.drawRoundRect(l, t, r, b, px(6), px(6), paint);
        }

        private void label(Canvas canvas, String value, float x, float baseline, float width, int size, int color) {
            if (width <= 0) return;
            text.setTypeface(regular);
            text.setTextSize(size * sp);
            text.setColor(color);
            CharSequence clipped = TextUtils.ellipsize(value, text, width, TextUtils.TruncateAt.END);
            canvas.drawText(clipped, 0, clipped.length(), x, baseline, text);
        }
    }
}
