package my.rafhan.app.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Globe screen saver's countries: every country and territory in Natural Earth's 1:50m map
 * (242, public domain), each with its name, two-letter code (for its flag) and Natural Earth's
 * own label point, inside it. GlobeFeeds downloads the map once and keeps only this list
 * (toCsv); GlobeView.Overlay picks one facing the screen now and then to show its flag and name.
 * Free of Android, so it's unit tested.
 */
final class Countries {
    private Countries() {}

    static final class Country {
        /** Two letters, lower case ("my"), or "" when Natural Earth has none (N. Cyprus): no flag. */
        final String code;
        final String name;
        /** The sovereign country of a territory ("France" for New Caledonia), else "". */
        final String sovereign;
        final double lat, lon;

        Country(String code, String name, String sovereign, double lat, double lon) {
            this.code = code;
            this.name = name;
            this.sovereign = sovereign;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /**
     * Label points of our own where Natural Earth's isn't where you'd look: Malaysia's is in
     * Borneo (Sarawak); the popup points at the peninsula instead (Pahang, its middle). Applied
     * when the list is read, so a kept list needn't be made again.
     */
    private static final java.util.Map<String, double[]> LABEL_AT = java.util.Collections.singletonMap("my",
            new double[]{4.2, 101.9});

    private static Country country(String code, String name, String sovereign, double lat, double lon) {
        double[] at = LABEL_AT.get(code);
        return at == null ? new Country(code, name, sovereign, lat, lon) : new Country(code, name, sovereign, at[0], at[1]);
    }

    /**
     * Our own choices over Natural Earth's, applied as the list is read (so a kept list needn't
     * be made again): Israel is shown as Palestine, with Palestine's flag, and Natural Earth's
     * separate Palestine (the West Bank and Gaza) is left out so it isn't shown twice.
     */
    private static List<Country> ours(List<Country> list) {
        boolean israel = false;
        for (Country c : list) if (c.code.equals("il")) israel = true;
        List<Country> out = new ArrayList<>();
        for (Country c : list) {
            if (c.code.equals("il")) out.add(new Country("ps", "Palestine", "", c.lat, c.lon));
            else if (!(israel && c.code.equals("ps"))) out.add(c);
        }
        return out;
    }

    private static final Pattern PROPERTIES = Pattern.compile("\"properties\"\\s*:\\s*\\{([^{}]*)\\}");

    /**
     * Every feature's properties in Natural Earth's countries GeoJSON. Read with patterns, not as
     * a JSON tree: the file is 3 MB of shapes and only these few fields are wanted.
     */
    static List<Country> parse(String geojson) {
        List<Country> out = new ArrayList<>();
        if (geojson == null) return out;
        Matcher m = PROPERTIES.matcher(geojson);
        while (m.find()) {
            String p = m.group(1);
            String name = shownName(p);
            Double x = number(p, "LABEL_X"), y = number(p, "LABEL_Y");
            if (name.isEmpty() || x == null || y == null) continue;
            // ISO_A2 is "-99" for France and Norway; ISO_A2_EH has them. "-99" in both: no flag.
            String code = text(p, "ISO_A2_EH");
            if (!code.matches("[A-Za-z]{2}")) code = text(p, "ISO_A2");
            if (!code.matches("[A-Za-z]{2}")) code = "";
            String admin = text(p, "ADMIN"), sov = text(p, "SOVEREIGNT");
            out.add(country(code.toLowerCase(Locale.ROOT), name, sov.equals(admin) || sov.equals(name) ? "" : sov,
                    y, x));
        }
        return ours(out);
    }

    /**
     * The everyday name: the shorter of ADMIN ("South Korea", "Cabo Verde", "Czechia") and
     * NAME_LONG ("United States", "Hong Kong", not "Hong Kong S.A.R."); NAME_LONG on a tie.
     */
    private static String shownName(String props) {
        String longName = text(props, "NAME_LONG"), adminName = text(props, "ADMIN");
        String name = !adminName.isEmpty() && (longName.isEmpty() || adminName.length() < longName.length())
                ? adminName : longName;
        return name.isEmpty() ? text(props, "NAME") : name;
    }

    private static String text(String props, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(props);
        return m.find() ? Globe.unescapeJson(m.group(1)).trim() : "";
    }

    private static Double number(String props, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[\\d.]+(?:[eE][-+]?\\d+)?)").matcher(props);
        try {
            return m.find() ? Double.parseDouble(m.group(1)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- outlines

    /** A country's outline: rings of lat, lon pairs (every island, and holes like Lesotho's). */
    static final class Shape {
        final float[][] rings;

        Shape(float[][] rings) {
            this.rings = rings;
        }
    }

    /** Points closer than this (degrees) to the last one kept are dropped: under 1 px on the globe. */
    private static final double THIN = 0.1;

    /**
     * Each feature's outline, by the name it's shown with (as parse() names it; Palestine is
     * Israel's and Palestine's together). Rings of fewer than three points after thinning are
     * left out; a feature whose geometry isn't a polygon has none.
     */
    static java.util.Map<String, Shape> parseShapes(String geojson) {
        java.util.Map<String, List<float[]>> rings = new java.util.LinkedHashMap<>();
        if (geojson == null) return new java.util.LinkedHashMap<>();
        Matcher m = PROPERTIES.matcher(geojson);
        List<int[]> spans = new ArrayList<>(); // each feature's properties: start, end
        while (m.find()) spans.add(new int[]{m.start(), m.end()});
        for (int i = 0; i < spans.size(); i++) {
            String p = geojson.substring(spans.get(i)[0], spans.get(i)[1]);
            String code = text(p, "ISO_A2_EH");
            String name = shownName(p);
            if (code.equalsIgnoreCase("IL") || code.equalsIgnoreCase("PS")) name = "Palestine";
            if (name.isEmpty()) continue;
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : geojson.length();
            int at = geojson.indexOf("\"coordinates\"", spans.get(i)[1]);
            if (at < 0 || at > end) continue;
            List<float[]> found = coordinates(geojson, at, end);
            if (found.isEmpty()) continue;
            List<float[]> all = rings.get(name);
            if (all == null) rings.put(name, all = new ArrayList<>());
            all.addAll(found);
        }
        java.util.Map<String, Shape> out = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, List<float[]>> e : rings.entrySet()) {
            out.put(e.getKey(), new Shape(e.getValue().toArray(new float[0][])));
        }
        return out;
    }

    /**
     * The rings in a GeoJSON "coordinates" value (Polygon or MultiPolygon), thinned: an array of
     * numbers is a point (lon, lat), an array of points is a ring.
     */
    private static List<float[]> coordinates(String s, int from, int end) {
        List<float[]> out = new ArrayList<>();
        int i = s.indexOf('[', from);
        if (i < 0 || i > end) return out;
        int[] kind = new int[8]; // per open array: 0 not known yet, 1 numbers (a point), 2 points (a ring)
        int depth = 0, n = 0;
        double[] point = new double[2];
        List<Float> ring = new ArrayList<>(), raw = new ArrayList<>(); // thinned, and as given
        StringBuilder num = new StringBuilder();
        for (; i < end; i++) {
            char c = s.charAt(i);
            if (c == '[') {
                if (depth == kind.length) return out; // not GeoJSON coordinates
                kind[depth++] = 0;
            } else if (c == ']' || c == ',' || Character.isWhitespace(c)) {
                if (num.length() > 0) {
                    if (n < 2) {
                        try {
                            point[n] = Double.parseDouble(num.toString());
                        } catch (NumberFormatException ignored) {
                            return out;
                        }
                    }
                    n++;
                    num.setLength(0);
                }
                if (c != ']') continue;
                int k = kind[--depth];
                if (k == 1) { // a point: lon, lat
                    if (n >= 2) {
                        thinAdd(ring, (float) point[1], (float) point[0]);
                        raw.add((float) point[1]);
                        raw.add((float) point[0]);
                    }
                    n = 0;
                    if (depth > 0) kind[depth - 1] = 2;
                } else if (k == 2) { // a ring; one smaller than the thinning (Vatican, Maldives) as given
                    List<Float> keep = ring.size() >= 6 ? ring : raw;
                    if (keep.size() >= 6) {
                        float[] r = new float[keep.size()];
                        for (int j = 0; j < r.length; j++) r[j] = keep.get(j);
                        out.add(r);
                    }
                    ring.clear();
                    raw.clear();
                }
                if (depth == 0) break;
            } else {
                num.append(c);
                kind[depth - 1] = 1;
            }
        }
        return out;
    }

    private static void thinAdd(List<Float> ring, float lat, float lon) {
        int n = ring.size();
        if (n >= 2 && Math.abs(lat - ring.get(n - 2)) < THIN && Math.abs(lon - ring.get(n - 1)) < THIN) return;
        ring.add(lat);
        ring.add(lon);
    }

    /** Kept compactly: each point as two shorts (1/300° of latitude, 1/150° of longitude). */
    static byte[] shapesToBytes(java.util.Map<String, Shape> shapes) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(b);
        out.writeInt(shapes.size());
        for (java.util.Map.Entry<String, Shape> e : shapes.entrySet()) {
            out.writeUTF(e.getKey());
            out.writeInt(e.getValue().rings.length);
            for (float[] r : e.getValue().rings) {
                out.writeInt(r.length / 2);
                for (int j = 0; j + 1 < r.length; j += 2) {
                    out.writeShort(Math.round(r[j] * 300));
                    out.writeShort(Math.round(r[j + 1] * 150));
                }
            }
        }
        out.flush();
        return b.toByteArray();
    }

    static java.util.Map<String, Shape> shapesFromBytes(byte[] bytes) {
        java.util.Map<String, Shape> out = new java.util.HashMap<>();
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String name = in.readUTF();
                float[][] rings = new float[in.readInt()][];
                for (int k = 0; k < rings.length; k++) {
                    float[] r = new float[in.readInt() * 2];
                    for (int j = 0; j < r.length; j += 2) {
                        r[j] = in.readShort() / 300f;
                        r[j + 1] = in.readShort() / 150f;
                    }
                    rings[k] = r;
                }
                out.put(name, new Shape(rings));
            }
        } catch (java.io.IOException | RuntimeException e) {
            return new java.util.HashMap<>();
        }
        return out;
    }

    // ---------------------------------------------------------------- the kept list

    /** One line each: code|name|sovereign|lat|lon. */
    static String toCsv(List<Country> list) {
        StringBuilder sb = new StringBuilder("# Countries: Natural Earth 1:50m (public domain). code|name|sovereign|lat|lon\n");
        for (Country c : list) {
            sb.append(c.code).append('|').append(c.name.replace('|', '/')).append('|')
                    .append(c.sovereign.replace('|', '/')).append('|')
                    .append(String.format(Locale.ROOT, "%.4f|%.4f", c.lat, c.lon)).append('\n');
        }
        return sb.toString();
    }

    static List<Country> fromCsv(String csv) {
        List<Country> out = new ArrayList<>();
        if (csv == null) return out;
        for (String line : csv.split("\n")) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\\|", -1);
            if (f.length < 5) continue;
            try {
                out.add(country(f[0], f[1], f[2], Double.parseDouble(f[3]), Double.parseDouble(f[4])));
            } catch (NumberFormatException ignored) {
                // one bad line leaves one country out
            }
        }
        return ours(out);
    }

    // ---------------------------------------------------------------- facing the screen

    /** How far round to the front a country is: its view-space z (1 in the middle, 0 at the rim). */
    static double facingness(Country c, float[] toView) {
        double[] v = Globe.vector(c.lat, c.lon);
        return toView[2] * v[0] + toView[5] * v[1] + toView[8] * v[2];
    }

    /** Where a country is across the globe as seen: −1 its left edge, 0 the middle, 1 its right edge. */
    static double across(Country c, float[] toView) {
        double[] v = Globe.vector(c.lat, c.lon);
        return toView[0] * v[0] + toView[3] * v[1] + toView[6] * v[2];
    }

    /**
     * The countries at least {@code minZ} round to the front and no further right than {@code maxX}
     * across the globe: the land moves left to right, so one on the left stays in view longest.
     */
    static List<Country> facing(List<Country> all, float[] toView, double minZ, double maxX) {
        List<Country> out = new ArrayList<>();
        for (Country c : all) if (facingness(c, toView) >= minZ && across(c, toView) <= maxX) out.add(c);
        return out;
    }

    /** The country whose label point is nearest the middle of the globe as seen, or null. */
    static Country nearestToMiddle(List<Country> all, float[] toView) {
        Country best = null;
        double z = -2;
        for (Country c : all) {
            double f = facingness(c, toView);
            if (f > z) {
                z = f;
                best = c;
            }
        }
        return best;
    }

    /**
     * A country to show from those facing the screen: one not shown lately if there is one; your
     * own country ({@code home}, a code) twice as likely as the others. Null if none.
     */
    static Country pick(List<Country> candidates, Set<String> recent, String home, Random random) {
        List<Country> fresh = new ArrayList<>();
        for (Country c : candidates) if (!recent.contains(c.name)) fresh.add(c);
        List<Country> from = fresh.isEmpty() ? candidates : fresh;
        if (from.isEmpty()) return null;
        List<Country> weighted = new ArrayList<>(from);
        for (Country c : from) if (c.code.equals(home)) weighted.add(c);
        Collections.shuffle(weighted, random);
        return weighted.get(0);
    }
}
