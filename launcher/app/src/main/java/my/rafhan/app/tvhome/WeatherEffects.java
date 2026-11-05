package my.rafhan.app.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/**
 * The home screen's weather, between the wallpaper and the cards, as it is now (ECMWF's weather
 * code, from WeatherPanel): rain on the glass, a misty glass for drizzle or fog, lightning for a
 * thunderstorm (or a MET thunderstorm warning) and a warm haze when the API is unhealthy.
 *
 * It plays, then settles: for PLAY_MS after home comes to the front (or the weather changes),
 * small drops land and stay, and big ones run down the glass, wiping a clear trail, and off the
 * bottom (a big drop never hangs on the glass); then everything stops and nothing is drawn again
 * until the next time, so a home screen left on costs nothing.
 *
 * The glass (the mist and the drops sitting on it) is a grid of small bitmaps drawn in software:
 * a drop is drawn into it once, and only the tiles that changed go to the GPU again, at most
 * COMMIT_MS apart. Each drop is a tiny lens: the wallpaper behind it, shrunk and upside down, as
 * real water on a window shows it. Only the drops running down are drawn every frame.
 */
@SuppressLint("ViewConstructor") // only built in code (MainActivity), with the wallpaper it shows through
final class WeatherEffects extends View {
    private static final long PLAY_MS = 45_000, COMMIT_MS = 100, FRESH_AFTER_MS = 5 * 60_000L,
            DRY_MS = 2_500;
    private static final int COLS = 8, ROWS = 6;
    /** Wait this long, then draw on the next screen refresh: about 30 frames a second. */
    private static final long FRAME_MS = 20;
    /** How much a drop shrinks what's behind it (negative: upside down). */
    private static final float LENS = -0.35f;
    /** The lens picture is the wallpaper at 1/LENS_SCALE size: drops show it shrunk anyway. */
    private static final int LENS_SCALE = 4;

    private final View wallpaper;
    private final Random rnd = new Random();
    private final Paint lensPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG),
            shadePaint = new Paint(Paint.FILTER_BITMAP_FLAG), clearPaint = new Paint(Paint.ANTI_ALIAS_FLAG),
            fogPaint = new Paint(Paint.FILTER_BITMAP_FLAG), hazePaint = new Paint(), flashPaint = new Paint();
    private final Matrix lensMatrix = new Matrix();
    private final RectF oval = new RectF();

    // What to show (WeatherPanel), and whether home is in front.
    private int rain = Weather.FX_NONE;
    private boolean thunder, haze, live;

    // The glass: tiles over the whole view, the lens picture and the drop shading.
    private Bitmap[] tiles;
    private Canvas[] tileCanvas;
    private boolean[] dirty;
    private int tileW, tileH;
    private Bitmap lens, shade;
    private BitmapShader lensShader;
    private boolean wallpaperChanged = true;
    private float unit = 1f; // px per 1080p pixel

    // Playing.
    private boolean playing;
    private long playUntil, lastFrame, lastCommit, lastPlayEnd;
    private float spawnDue, slideDue;
    private final ArrayList<float[]> stamps = new ArrayList<>(); // x, y, rx, ry: drops waiting for a commit
    private final ArrayList<float[]> clears = new ArrayList<>(); // x, y, r: trail wiped clean
    private final ArrayList<Slider> sliders = new ArrayList<>();
    private long nextFlash, flashAt = -1, flash2At = -1;
    private float flash2Peak;

    private final Runnable nextFrame = this::invalidate;

    private static final class Slider {
        float x, y, r, vy, drift, sinceTrail;
    }

    WeatherEffects(Context ctx, View wallpaper) {
        super(ctx);
        this.wallpaper = wallpaper;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        flashPaint.setColor(0xFFE4ECFF); // lightning: white with a little blue
        setWillNotDraw(false);
    }

    /** The weather now (WeatherPanel, each time it renders). Plays when it changes. */
    void setWeather(int rain, boolean thunder, boolean haze) {
        if (rain == this.rain && thunder == this.thunder && haze == this.haze) return;
        boolean wasWet = this.rain != Weather.FX_NONE;
        boolean more = rain > this.rain || (thunder && !this.thunder);
        this.rain = rain;
        this.thunder = thunder;
        this.haze = haze;
        if (rain == Weather.FX_NONE && wasWet) dry();
        else if (more) play();
        invalidate();
    }

    /** Home is in front: play. */
    void resume() {
        live = true;
        play();
    }

    /** Home went behind something: stop where it is. */
    void pause() {
        live = false;
        if (playing) settle();
    }

    /** The wallpaper (or its dim) changed: the drops' picture is redone, on a fresh glass. */
    void wallpaperChanged() {
        wallpaperChanged = true;
        if (tiles != null && live) play();
    }

    // ---------------------------------------------------------------- playing

    private void play() {
        if (!live || getWidth() == 0 || (rain == Weather.FX_NONE && !thunder)) return;
        long now = SystemClock.uptimeMillis();
        if (rain != Weather.FX_NONE) {
            animate().cancel();
            setAlpha(1f);
            boolean fresh = tiles == null || wallpaperChanged || now - lastPlayEnd > FRESH_AFTER_MS;
            if (wallpaperChanged || lens == null) makeLens();
            if (fresh) freshGlass();
        }
        if (!playing) {
            lastFrame = lastCommit = now;
            nextFlash = now + 1_500 + rnd.nextInt(2_500);
        }
        playing = true;
        playUntil = now + PLAY_MS;
        postInvalidateOnAnimation();
    }

    private void settle() {
        sliders.clear(); // a running drop doesn't freeze big on the glass: it's gone, its trail stays
        commit();
        playing = false;
        flashAt = flash2At = -1;
        lastPlayEnd = SystemClock.uptimeMillis();
        invalidate();
    }

    /** The rain stopped: the glass clears, then its memory goes. */
    private void dry() {
        playing = false;
        sliders.clear();
        stamps.clear();
        clears.clear();
        animate().cancel();
        animate().alpha(0f).setDuration(DRY_MS).withEndAction(() -> {
            freeGlass();
            setAlpha(1f);
            invalidate();
        }).start();
    }

    private void freeGlass() {
        if (tiles != null) for (Bitmap b : tiles) b.recycle();
        tiles = null;
        tileCanvas = null;
        dirty = null;
    }

    // How each kind of rain lands: drops a second, their size (1080p px), big drops running down a second.
    private float spawnRate() {
        switch (rain) {
            case Weather.FX_MIST: return 70f;
            case Weather.FX_LIGHT: return 10f;
            case Weather.FX_RAIN: return 22f;
            case Weather.FX_HEAVY: return 40f;
            default: return 0f;
        }
    }

    private float slideRate() {
        switch (rain) {
            case Weather.FX_LIGHT: return 0.3f;
            case Weather.FX_RAIN: return 0.8f;
            case Weather.FX_HEAVY: return 1.6f;
            default: return 0f; // mist doesn't run
        }
    }

    /** The biggest drop that stays where it lands; bigger ones are too heavy and run (Slider). */
    private float maxRadius() {
        switch (rain) {
            case Weather.FX_MIST: return 3.2f;
            case Weather.FX_LIGHT: return 5f;
            case Weather.FX_RAIN: return 5.5f;
            default: return 6f;
        }
    }

    /** How fogged the glass is: misty for drizzle, a little for rain. */
    private float fog() {
        switch (rain) {
            case Weather.FX_MIST: return 0.30f;
            case Weather.FX_LIGHT: return 0.14f;
            case Weather.FX_RAIN: return 0.18f;
            default: return 0.22f;
        }
    }

    private void step(long now) {
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean raining = now < playUntil;
        if (raining && tiles != null) {
            float min = rain == Weather.FX_MIST ? 1.2f : 2.5f, max = maxRadius();
            for (spawnDue += spawnRate() * dt; spawnDue >= 1; spawnDue--) {
                float r = (min + (max - min) * (float) Math.pow(rnd.nextFloat(), 2.2)) * unit;
                stamps.add(new float[] {rnd.nextFloat() * getWidth(), rnd.nextFloat() * getHeight(), r,
                        r * (1f + rnd.nextFloat() * 0.15f)});
            }
            for (slideDue += slideRate() * dt; slideDue >= 1; slideDue--) {
                Slider s = new Slider();
                s.x = rnd.nextFloat() * getWidth();
                s.y = rnd.nextFloat() * getHeight() * 0.7f;
                s.r = (9f + rnd.nextFloat() * 5f) * unit;
                s.vy = 30f * unit;
                s.drift = (rnd.nextFloat() - 0.5f) * 20f * unit;
                sliders.add(s);
            }
        }
        for (int i = sliders.size() - 1; i >= 0; i--) {
            Slider s = sliders.get(i);
            s.vy = Math.min(s.vy + 260f * unit * dt * (0.5f + rnd.nextFloat()), 380f * unit);
            float dy = s.vy * dt;
            s.y += dy;
            s.x += (s.drift + (rnd.nextFloat() - 0.5f) * 60f * unit) * dt;
            s.r = Math.min(s.r + 1.5f * unit * dt, 16f * unit); // picks up the drops in its way
            clears.add(new float[] {s.x, s.y, s.r * 0.7f});
            s.sinceTrail += dy;
            if (s.sinceTrail > 14f * unit) { // little drops left behind in the trail
                s.sinceTrail = 0;
                if (rnd.nextFloat() < 0.6f) {
                    float r = (1.5f + rnd.nextFloat() * 2.5f) * unit;
                    stamps.add(new float[] {s.x + (rnd.nextFloat() - 0.5f) * s.r * 0.6f, s.y - s.r * 1.6f, r, r * 1.1f});
                }
            }
            if (s.y - s.r > getHeight()) sliders.remove(i); // ran off the bottom: never left hanging
        }
        if (now - lastCommit >= COMMIT_MS) {
            commit();
            lastCommit = now;
        }
        if (thunder && raining && now >= nextFlash) {
            flashAt = now;
            flash2At = rnd.nextFloat() < 0.4f ? now + 150 + rnd.nextInt(120) : -1;
            flash2Peak = 0.6f + rnd.nextFloat() * 0.3f;
            nextFlash = now + 5_000 + rnd.nextInt(8_000);
        }
        if (!raining && sliders.isEmpty() && flash(now) == 0) settle();
    }

    /** Lightning's brightness now: a quick rise, a slower fade, sometimes a second flash. */
    private float flash(long now) {
        return 0.28f * (envelope(now - flashAt, flashAt) + flash2Peak * envelope(now - flash2At, flash2At));
    }

    private static float envelope(long t, long at) {
        if (at < 0 || t < 0) return 0;
        if (t < 60) return t / 60f;
        float v = (float) Math.exp(-(t - 60) / 220.0);
        return v < 0.01f ? 0 : v;
    }

    /** Puts the waiting trail wipes, then the waiting drops, on the glass. */
    private void commit() {
        if (tiles == null) {
            stamps.clear();
            clears.clear();
            return;
        }
        for (float[] c : clears) {
            for (int i = 0; i < tiles.length; i++) {
                if (!overlaps(i, c[0], c[1], c[2], c[2])) continue;
                Canvas tc = tileCanvas[i];
                tc.save();
                tc.translate(-(i % COLS) * tileW, -(i / COLS) * tileH);
                tc.drawCircle(c[0], c[1], c[2], clearPaint);
                tc.restore();
                dirty[i] = true;
            }
        }
        for (float[] d : stamps) {
            for (int i = 0; i < tiles.length; i++) {
                if (!overlaps(i, d[0], d[1], d[2], d[3])) continue;
                Canvas tc = tileCanvas[i];
                tc.save();
                tc.translate(-(i % COLS) * tileW, -(i / COLS) * tileH);
                drawDrop(tc, d[0], d[1], d[2], d[3]);
                tc.restore();
                dirty[i] = true;
            }
        }
        clears.clear();
        stamps.clear();
        for (int i = 0; i < tiles.length; i++) {
            if (dirty[i]) tiles[i].prepareToDraw(); // only changed tiles go to the GPU again
            dirty[i] = false;
        }
    }

    private boolean overlaps(int tile, float x, float y, float rx, float ry) {
        float l = (tile % COLS) * tileW, t = (tile / COLS) * tileH;
        return x + rx > l && x - rx < l + tileW && y + ry > t && y - ry < t + tileH;
    }

    /** A drop: the wallpaper behind it shrunk and upside down, with a dark rim and a highlight. */
    private void drawDrop(Canvas c, float x, float y, float rx, float ry) {
        oval.set(x - rx, y - ry, x + rx, y + ry);
        if (lensShader != null) {
            float s = (float) getWidth() / lens.getWidth();
            lensMatrix.setScale(s * LENS, s * LENS);
            lensMatrix.postTranslate((1 - LENS) * x, (1 - LENS) * y);
            lensShader.setLocalMatrix(lensMatrix);
            c.drawOval(oval, lensPaint);
        }
        c.drawBitmap(shade, null, oval, shadePaint);
    }

    // ---------------------------------------------------------------- the glass

    /** The wallpaper as it's drawn (dimmed), small: what the drops show, and the mist's blur. */
    private void makeLens() {
        int w = Math.max(1, getWidth() / LENS_SCALE), h = Math.max(1, getHeight() / LENS_SCALE);
        if (lens == null || lens.getWidth() != w || lens.getHeight() != h) {
            if (lens != null) lens.recycle();
            lens = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        Canvas c = new Canvas(lens);
        c.drawColor(Color.BLACK);
        if (wallpaper.getVisibility() == VISIBLE && wallpaper.getWidth() > 0) {
            c.scale((float) w / wallpaper.getWidth(), (float) h / wallpaper.getHeight());
            wallpaper.draw(c);
        }
        lensShader = new BitmapShader(lens, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        lensPaint.setShader(lensShader);
        lensPaint.setColorFilter(new android.graphics.LightingColorFilter(0xFFF0F0F0, 0x00262626)); // water: lighter, less contrast
        wallpaperChanged = false;
    }

    /** A clean glass: the mist only (the wallpaper blurred, a little lighter). */
    private void freshGlass() {
        int w = getWidth(), h = getHeight();
        tileW = (w + COLS - 1) / COLS;
        tileH = (h + ROWS - 1) / ROWS;
        if (tiles == null || tiles[0].getWidth() != tileW || tiles[0].getHeight() != tileH) {
            freeGlass();
            tiles = new Bitmap[COLS * ROWS];
            tileCanvas = new Canvas[tiles.length];
            dirty = new boolean[tiles.length];
            for (int i = 0; i < tiles.length; i++) {
                tiles[i] = Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888);
                tileCanvas[i] = new Canvas(tiles[i]);
            }
        }
        if (shade == null) shade = makeShade(128);
        Bitmap blur = blurred(lens);
        RectF all = new RectF(0, 0, w, h);
        fogPaint.setAlpha(Math.round(255 * fog()));
        for (int i = 0; i < tiles.length; i++) {
            Canvas tc = tileCanvas[i];
            tc.drawColor(0, PorterDuff.Mode.CLEAR);
            tc.save();
            tc.translate(-(i % COLS) * tileW, -(i / COLS) * tileH);
            tc.drawBitmap(blur, null, all, fogPaint);
            tc.drawColor(Color.argb(Math.round(40 * fog()), 255, 255, 255)); // the mist itself, faintly white
            tc.restore();
            tiles[i].prepareToDraw();
        }
        blur.recycle();
        stamps.clear();
        clears.clear();
        sliders.clear();
    }

    /** The lens picture at half its size, box-blurred twice: what misted glass shows. */
    private static Bitmap blurred(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, Math.max(1, src.getWidth() / 2), Math.max(1, src.getHeight() / 2), true);
        int w = small.getWidth(), h = small.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        small.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            boxBlur(a, b, w, h, 3, true);
            boxBlur(b, a, w, h, 3, false);
        }
        small.setPixels(a, 0, w, 0, 0, w, h);
        return small;
    }

    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int rs = 0, gs = 0, bs = 0, n = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(len - 1, i + r); k++) {
                    int p = horizontal ? in[line * w + k] : in[k * w + line];
                    rs += (p >> 16) & 0xFF;
                    gs += (p >> 8) & 0xFF;
                    bs += p & 0xFF;
                    n++;
                }
                out[horizontal ? line * w + i : i * w + line] = 0xFF000000 | (rs / n) << 16 | (gs / n) << 8 | (bs / n);
            }
        }
    }

    /** A drop's light: a soft dark rim, a highlight up and to the left, a faint glow at the bottom. */
    private static Bitmap makeShade(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float r = size / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(r, r, r, new int[] {0, 0, 0x2C000000, 0x18000000},
                new float[] {0f, 0.78f, 0.94f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r, p);
        p.setShader(new RadialGradient(r * 0.72f, r * 0.58f, r * 0.32f, new int[] {0xC8FFFFFF, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(r * 0.72f, r * 0.58f, r * 0.32f, p);
        p.setShader(new RadialGradient(r, r * 1.62f, r * 0.42f, new int[] {0x60FFFFFF, 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        c.save();
        c.clipRect(0, 0, size, size);
        c.drawCircle(r, r * 1.62f, r * 0.42f, p);
        c.restore();
        return b;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        unit = h / 1080f;
        hazePaint.setShader(new LinearGradient(0, 0, 0, h, 0x1EC4AE8C, 0x45C4AE8C, Shader.TileMode.CLAMP));
        wallpaperChanged = true;
        if (tiles != null) freeGlass();
        if (live && (rain != Weather.FX_NONE || thunder)) post(this::play);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        if (playing) step(now);
        if (tiles != null) {
            for (int i = 0; i < tiles.length; i++) c.drawBitmap(tiles[i], (i % COLS) * tileW, (i / COLS) * tileH, null);
            for (Slider s : sliders) drawDrop(c, s.x, s.y, s.r, s.r * 1.2f);
        }
        if (haze) c.drawRect(0, 0, getWidth(), getHeight(), hazePaint); // the air outside: over the drops too
        float f = playing ? flash(now) : 0;
        if (f > 0) {
            flashPaint.setAlpha(Math.round(255 * Math.min(1f, f)));
            c.drawRect(0, 0, getWidth(), getHeight(), flashPaint);
        }
        if (playing) postOnAnimationDelayed(nextFrame, FRAME_MS); // about 30 a second: enough for rain
    }
}
