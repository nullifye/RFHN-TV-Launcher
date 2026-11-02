package my.rafhan.app.tvhome;

/** Small, Android-independent palette sampler for the Now Playing card. */
final class CoverPalette {
    private CoverPalette() {}

    static int dominant(int[] pixels) {
        long[] weights = new long[512], red = new long[512], green = new long[512], blue = new long[512];
        int best = 0;
        for (int color : pixels) {
            int alpha = color >>> 24;
            if (alpha < 128) continue;
            int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            // Prefer visible color over black borders and white text, without inventing a hue.
            long weight = 1L + max - min;
            if (max < 24) weight = 1;
            int bucket = ((r >> 5) << 6) | ((g >> 5) << 3) | (b >> 5);
            weights[bucket] += weight;
            red[bucket] += r * weight;
            green[bucket] += g * weight;
            blue[bucket] += b * weight;
        }
        for (int i = 1; i < weights.length; i++) if (weights[i] > weights[best]) best = i;
        long n = weights[best];
        if (n == 0) return 0xFF707070;
        return 0xFF000000 | ((int) (red[best] / n) << 16)
                | ((int) (green[best] / n) << 8) | (int) (blue[best] / n);
    }
}
