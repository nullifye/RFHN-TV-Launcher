package my.rafhan.app.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.widget.ImageView;

/** Sharp cover art with a softly blurred, transparent trailing edge into the glass card. */
final class ArtCoverView extends ImageView {
    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mask = new Paint();
    private final Rect bounds = new Rect();
    private Bitmap blurred;
    private boolean dirty = true;
    private final float edge;
    private Shader blurMask, fadeMask;

    ArtCoverView(Context context) {
        super(context);
        edge = 20 * context.getResources().getDisplayMetrics().density;
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        mask.setDither(true);
    }

    void artworkChanged() {
        dirty = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        dirty = true;
        if (w > 0) {
            float start = Math.max(0, w - edge);
            int[] blurColors = new int[17], fadeColors = new int[17];
            float[] stops = new float[17];
            for (int i = 0; i < stops.length; i++) {
                float t = i / (float) (stops.length - 1);
                // Smoothstep has a flat slope at both ends: no abrupt start or trailing seam.
                int alpha = Math.round(255 * t * t * (3 - 2 * t));
                stops[i] = t;
                blurColors[i] = Color.argb(alpha, 0, 0, 0);
                fadeColors[i] = Color.argb(255 - alpha, 0, 0, 0);
            }
            blurMask = new LinearGradient(start, 0, w, 0,
                    blurColors, stops, Shader.TileMode.CLAMP);
            fadeMask = new LinearGradient(start, 0, w, 0,
                    fadeColors, stops, Shader.TileMode.CLAMP);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (dirty) {
            // A tiny filtered copy provides a cheap soft blur on Android 8+, without RenderEffect.
            // Rebuild only when the artwork or dimensions change, not on progress updates.
            if (blurred == null) blurred = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            blurred.eraseColor(Color.TRANSPARENT);
            Canvas small = new Canvas(blurred);
            small.scale(16f / w, 16f / h);
            small.drawColor(0xFF1C1C1E);
            super.onDraw(small);
            dirty = false;
        }
        int artLayer = canvas.saveLayer(0, 0, w, h, null);
        canvas.drawColor(0xFF1C1C1E);
        super.onDraw(canvas);

        int blurLayer = canvas.saveLayer(0, 0, w, h, null);
        bounds.set(0, 0, w, h);
        canvas.drawBitmap(blurred, null, bounds, blurPaint);
        mask.setShader(blurMask);
        canvas.drawRect(0, 0, w, h, mask);
        canvas.restoreToCount(blurLayer);

        // Fade both sharp and blurred art to the card underneath; the shared sheen stays intact.
        mask.setShader(fadeMask);
        canvas.drawRect(0, 0, w, h, mask);
        canvas.restoreToCount(artLayer);
    }
}
