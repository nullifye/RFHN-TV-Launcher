package my.rafhan.app.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * The prayer zone's "Auto": the JAKIM zone the TV is in, from its location. The TV's network
 * location (Google's, from nearby Wi-Fi networks; TV Home already has the location permission) is
 * matched offline to the nearest of a few reference towns per zone: no online service is asked.
 * Near a zone border it can pick the neighbour, where the times differ by a minute or two.
 *
 * Mountain-top and special zones (Gunung Jerai, Bukit Larut, Gunung Kinabalu, Kampung Patarikan)
 * are never picked: a TV in Taiping must not land on the peak's zone. They can still be chosen.
 */
final class ZoneDetect {
    // Zone, then latitude/longitude pairs of its main towns (approximate, to about a kilometre).
    private static final Object[][] TOWNS = {
            {"JHR01", 2.45, 104.52, 2.58, 104.32},
            {"JHR02", 1.49, 103.74, 1.73, 103.90, 2.43, 103.84, 1.66, 103.60, 1.46, 103.76},
            {"JHR03", 2.03, 103.32, 1.49, 103.39},
            {"JHR04", 1.85, 102.93, 2.04, 102.57, 2.51, 102.82, 2.58, 102.61, 2.27, 102.55},
            {"KDH01", 6.12, 100.37, 6.27, 100.42, 6.17, 100.52},
            {"KDH02", 5.65, 100.49, 5.80, 100.37, 5.99, 100.48},
            {"KDH03", 6.26, 100.61, 5.81, 100.73},
            {"KDH04", 5.68, 100.92},
            {"KDH05", 5.21, 100.61, 5.37, 100.56},
            {"KDH06", 6.32, 99.85, 6.40, 99.73},
            {"KTN01", 6.13, 102.24, 6.05, 102.40, 5.77, 102.22, 6.05, 102.14, 5.84, 102.40, 5.81, 102.15,
                    6.20, 102.17, 5.53, 102.20},
            {"KTN02", 4.88, 101.97, 5.70, 101.84, 4.60, 101.50},
            {"MLK01", 2.19, 102.25, 2.38, 102.21, 2.31, 102.43},
            {"NGS01", 2.47, 102.23, 2.81, 102.41},
            {"NGS02", 2.95, 102.07, 2.74, 102.25, 2.59, 102.09},
            {"NGS03", 2.52, 101.80, 2.73, 101.94, 2.79, 101.81},
            {"PHG01", 2.79, 104.16},
            {"PHG02", 3.81, 103.33, 3.49, 103.39, 3.06, 103.08},
            {"PHG03", 3.94, 102.36, 3.45, 102.42, 3.59, 102.77, 3.27, 102.43, 3.49, 102.58, 3.75, 102.55},
            {"PHG04", 3.52, 101.91, 4.18, 102.05, 3.79, 101.86},
            {"PHG05", 3.36, 101.79, 3.33, 101.86, 3.35, 101.82},
            {"PHG06", 4.47, 101.38, 3.42, 101.79, 3.71, 101.74},
            {"PHG07", 2.81, 103.49, 2.65, 103.62},
            {"PLS01", 6.44, 100.20, 6.66, 100.32, 6.43, 100.27},
            {"PNG01", 5.41, 100.33, 5.40, 100.36, 5.36, 100.47, 5.17, 100.48, 5.35, 100.24, 5.30, 100.27},
            {"PRK01", 4.20, 101.26, 3.83, 101.40, 3.68, 101.52},
            {"PRK02", 4.77, 100.94, 4.82, 101.07, 4.60, 101.08, 4.47, 101.04, 4.31, 101.15},
            {"PRK03", 5.10, 100.97, 5.70, 100.99, 5.43, 101.13},
            {"PRK04", 5.40, 101.30, 5.55, 101.40},
            {"PRK05", 4.18, 100.94, 4.02, 101.02, 3.99, 100.79, 4.36, 100.98, 4.50, 100.78, 4.47, 100.91,
                    4.23, 100.63, 4.22, 100.70, 4.21, 100.57},
            {"PRK06", 5.22, 100.69, 4.85, 100.74, 5.01, 100.54, 5.13, 100.49},
            {"SBH01", 5.84, 118.12, 5.52, 118.29},
            {"SBH02", 5.89, 117.56, 5.63, 117.12},
            {"SBH03", 5.03, 118.33, 4.69, 118.25, 4.48, 118.61},
            {"SBH04", 4.24, 117.89, 4.42, 117.48},
            {"SBH05", 6.88, 116.84, 6.49, 116.75, 6.71, 117.06, 7.23, 117.08},
            {"SBH07", 5.98, 116.07, 5.95, 116.66, 6.35, 116.43, 6.18, 116.23, 5.92, 116.11, 5.73, 115.93,
                    5.92, 116.06},
            {"SBH08", 5.34, 116.16, 5.67, 116.37, 5.03, 116.44, 4.55, 116.32},
            {"SBH09", 5.35, 115.75, 5.57, 115.60, 5.08, 115.55, 5.12, 115.94, 4.40, 115.72, 5.46, 115.78},
            {"SGR01", 3.24, 101.68, 3.11, 101.61, 2.69, 101.75, 2.99, 101.79, 3.57, 101.66, 3.07, 101.52,
                    3.05, 101.58, 3.03, 101.62, 2.92, 101.65, 3.32, 101.58},
            {"SGR02", 3.34, 101.25, 3.77, 100.99, 3.43, 101.18},
            {"SGR03", 3.04, 101.45, 3.00, 101.39, 2.81, 101.50, 2.88, 101.51},
            {"SWK01", 4.75, 115.01, 4.86, 115.41},
            {"SWK02", 4.40, 113.99, 4.18, 114.32, 4.06, 113.84, 3.83, 113.73},
            {"SWK03", 3.17, 113.04, 2.88, 112.85, 2.70, 113.78},
            {"SWK04", 2.29, 111.83, 2.90, 112.09, 2.74, 111.93, 2.10, 112.15, 2.01, 112.93, 2.00, 112.55},
            {"SWK05", 2.13, 111.52, 2.17, 111.63, 2.52, 111.43, 2.03, 111.92, 2.66, 111.53},
            {"SWK06", 1.24, 111.46, 1.41, 111.53, 1.74, 111.34, 1.04, 111.83, 1.11, 111.65},
            {"SWK07", 1.17, 110.57, 1.46, 110.43, 1.38, 110.75, 1.52, 110.92},
            {"SWK08", 1.55, 110.34, 1.42, 110.15, 1.67, 109.85, 1.80, 109.78},
            {"TRG01", 5.33, 103.14, 5.21, 103.21, 5.38, 103.07},
            {"TRG02", 5.74, 102.49, 5.52, 102.74},
            {"TRG03", 5.07, 103.01},
            {"TRG04", 4.76, 103.42, 4.23, 103.42},
            {"WLY01", 3.15, 101.70, 2.93, 101.69, 3.10, 101.74, 3.21, 101.64},
            {"WLY02", 5.28, 115.24},
    };
    /** Farther than this (about 110 km) from every town: not in Malaysia, no guess. */
    private static final double MAX_DEGREES = 1.0;
    private static final long WAIT_MS = 30_000, FRESH_MS = 7L * 24 * 60 * 60_000;

    private ZoneDetect() {}

    /** The zone of the town nearest to this point, or null when it's not near any (outside Malaysia). */
    static String nearest(double lat, double lon) {
        String best = null;
        double bestD = MAX_DEGREES * MAX_DEGREES, k = Math.cos(Math.toRadians(lat));
        for (Object[] z : TOWNS) {
            for (int i = 1; i + 1 < z.length; i += 2) {
                double dLat = lat - (Double) z[i], dLon = (lon - (Double) z[i + 1]) * k;
                double d = dLat * dLat + dLon * dLon;
                if (d < bestD) {
                    bestD = d;
                    best = (String) z[0];
                }
            }
        }
        return best;
    }

    /**
     * Finds where the TV is and hands {latitude, longitude} to done (main thread), or null when it
     * can't: no location permission or provider, location off, or no answer within 30 s.
     */
    @SuppressWarnings({"MissingPermission", "deprecation"}) // checked first; requestSingleUpdate: getCurrentLocation is 30+
    static void locate(Context ctx, java.util.function.Consumer<double[]> done) {
        Handler main = new Handler(Looper.getMainLooper());
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        boolean allowed = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (lm == null || !allowed || !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            main.post(() -> done.accept(null));
            return;
        }
        try {
            Location last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (last != null && System.currentTimeMillis() - last.getTime() < FRESH_MS) {
                double[] here = {last.getLatitude(), last.getLongitude()};
                main.post(() -> done.accept(here));
                return;
            }
            boolean[] answered = {false};
            LocationListener once = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    if (answered[0]) return;
                    answered[0] = true;
                    lm.removeUpdates(this);
                    done.accept(new double[] {l.getLatitude(), l.getLongitude()});
                }

                @Override public void onStatusChanged(String p, int s, Bundle b) {}

                @Override public void onProviderEnabled(String p) {}

                @Override public void onProviderDisabled(String p) {}
            };
            lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, once, Looper.getMainLooper());
            main.postDelayed(() -> {
                if (answered[0]) return;
                answered[0] = true;
                lm.removeUpdates(once);
                done.accept(null);
            }, WAIT_MS);
        } catch (RuntimeException e) { // SecurityException, a provider gone
            main.post(() -> done.accept(null));
        }
    }
}
