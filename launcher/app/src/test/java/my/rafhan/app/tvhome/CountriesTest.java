package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** The Globe's countries: Natural Earth's names, codes and label points, and which face the screen. */
public class CountriesTest {
    private static List<Countries.Country> excerpt() throws Exception {
        try (InputStream in = CountriesTest.class.getClassLoader().getResourceAsStream("countries-ne50-excerpt.geojson")) {
            return Countries.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Countries.Country named(List<Countries.Country> all, String name) {
        for (Countries.Country c : all) if (c.name.equals(name)) return c;
        return null;
    }

    @Test
    public void readsNamesCodesAndLabelPoints() throws Exception {
        List<Countries.Country> all = excerpt(); // Natural Earth 1:50m, v5.1.2: nine of its 242
        assertEquals(9, all.size());
        Countries.Country my = named(all, "Malaysia");
        assertNotNull(my);
        assertEquals("my", my.code);
        assertEquals("", my.sovereign);
        // Natural Earth's label point is in Borneo (Sarawak, 112.8° E); ours is the peninsula's middle.
        assertEquals(4.2, my.lat, 1e-9);
        assertEquals(101.9, my.lon, 1e-9);
        assertEquals(101.9, Countries.fromCsv(Countries.toCsv(all)).get(1).lon, 1e-9); // a kept list too
        assertEquals("fr", named(all, "France").code); // ISO_A2 is -99; ISO_A2_EH has it
        assertEquals("France", named(all, "New Caledonia").sovereign); // a territory
        assertEquals("China", named(all, "Hong Kong").sovereign);
        assertEquals("xk", named(all, "Kosovo").code);
        assertEquals("", named(all, "Northern Cyprus").code); // -99 in both: shown without a flag
        assertNotNull(named(all, "Ivory Coast")); // ADMIN, shorter than NAME_LONG "Côte d'Ivoire"
        assertNotNull(named(all, "Hong Kong")); // NAME_LONG, shorter than ADMIN "Hong Kong S.A.R."
        // Escapes in the file: "\u00c5land" and the like.
        assertEquals("Åland Islands", Countries.parse("{\"properties\":{\"NAME_LONG\":\"\\u00c5land Islands\","
                + "\"LABEL_X\":19.9,\"LABEL_Y\":60.1}}").get(0).name);
        assertTrue(Countries.parse("<html>502</html>").isEmpty());
        assertTrue(Countries.parse(null).isEmpty());
    }

    @Test
    public void israelIsShownAsPalestine() {
        // Natural Earth's two entries, as in the file: Israel (IL) and Palestine (PS, the West Bank and Gaza).
        String geo = "{\"properties\":{\"ADMIN\":\"Israel\",\"NAME_LONG\":\"Israel\",\"SOVEREIGNT\":\"Israel\","
                + "\"ISO_A2_EH\":\"IL\",\"LABEL_X\":34.87,\"LABEL_Y\":30.91}}"
                + "{\"properties\":{\"ADMIN\":\"Palestine\",\"NAME_LONG\":\"Palestine\",\"SOVEREIGNT\":\"Israel\","
                + "\"ISO_A2_EH\":\"PS\",\"LABEL_X\":35.29,\"LABEL_Y\":32.0}}";
        List<Countries.Country> list = Countries.parse(geo);
        assertEquals(1, list.size()); // one, not shown twice
        Countries.Country p = list.get(0);
        assertEquals("Palestine", p.name);
        assertEquals("ps", p.code); // Palestine's flag
        assertEquals("", p.sovereign);
        assertEquals(30.91, p.lat, 1e-9); // where Israel's label point is
        // A list kept before this (with both entries) reads the same way.
        String kept = "il|Israel||30.9100|34.8700\nps|Palestine|Israel|32.0000|35.2900\n";
        List<Countries.Country> back = Countries.fromCsv(kept);
        assertEquals(1, back.size());
        assertEquals("Palestine", back.get(0).name);
        assertEquals(1, Countries.fromCsv(Countries.toCsv(back)).size());
    }

    @Test
    public void outlinesForTheBorder() throws Exception {
        String geo;
        try (InputStream in = CountriesTest.class.getClassLoader().getResourceAsStream("countries-ne50-excerpt.geojson")) {
            geo = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        java.util.Map<String, Countries.Shape> shapes = Countries.parseShapes(geo);
        // Malaysia (a MultiPolygon of 509 points: the peninsula, Borneo, islands), Singapore, Kosovo;
        // the others in the excerpt are points there, so no outline.
        assertEquals(3, shapes.size());
        Countries.Shape my = shapes.get("Malaysia");
        assertTrue(my.rings.length >= 2);
        int points = 0;
        for (float[] r : my.rings) {
            assertTrue(r.length >= 6);
            for (int j = 0; j < r.length; j += 2) {
                assertTrue(r[j] > 0 && r[j] < 8); // latitude
                assertTrue(r[j + 1] > 99 && r[j + 1] < 120); // longitude
            }
            points += r.length / 2;
        }
        assertTrue(points < 509); // thinned
        assertTrue(points > 100); // but still its shape
        assertEquals(1, shapes.get("Singapore").rings.length);
        // Kept compactly and read back to within 1/150 of a degree.
        java.util.Map<String, Countries.Shape> back = Countries.shapesFromBytes(Countries.shapesToBytes(shapes));
        assertEquals(3, back.size());
        assertEquals(my.rings[0][0], back.get("Malaysia").rings[0][0], 1 / 150.0);
        assertEquals(my.rings[0][1], back.get("Malaysia").rings[0][1], 1 / 100.0);
        assertTrue(Countries.shapesFromBytes(new byte[]{1, 2}).isEmpty());
        // Israel and Palestine: one outline, shown as Palestine.
        String two = "{\"properties\":{\"ADMIN\":\"Israel\",\"ISO_A2_EH\":\"IL\",\"LABEL_X\":34.8,\"LABEL_Y\":30.9},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[34.2,29.5],[35.9,33.3],[34.3,31.3],[34.2,29.5]]]}},"
                + "{\"properties\":{\"ADMIN\":\"Palestine\",\"ISO_A2_EH\":\"PS\",\"LABEL_X\":35.3,\"LABEL_Y\":32.0},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[35.0,31.4],[35.5,32.5],[35.6,31.5],[35.0,31.4]]]}}";
        java.util.Map<String, Countries.Shape> ps = Countries.parseShapes(two);
        assertEquals(1, ps.size());
        assertEquals(2, ps.get("Palestine").rings.length);
    }

    @Test
    public void keptListRoundTrips() throws Exception {
        List<Countries.Country> all = excerpt();
        List<Countries.Country> back = Countries.fromCsv(Countries.toCsv(all));
        assertEquals(all.size(), back.size());
        Countries.Country nc = named(back, "New Caledonia");
        assertEquals("nc", nc.code);
        assertEquals("France", nc.sovereign);
        assertEquals(named(all, "New Caledonia").lat, nc.lat, 1e-4);
    }

    @Test
    public void facingTheScreen() throws Exception {
        List<Countries.Country> all = excerpt();
        long now = java.time.Instant.parse("2026-11-01T08:00:00Z").toEpochMilli();
        Countries.Country my = named(all, "Malaysia");
        // Level, the middle is the orbit's plane, which Malaysia can be up to about 27° above or
        // below (the axial tilt plus its latitude); turned to face it and tilted to its height,
        // it's straight ahead.
        float[] level = GlobeView.earthToView(now, GlobeView.facing(my.lat, my.lon, now), 0);
        assertTrue(Countries.facingness(my, level) > 0.85);
        double height = Math.toDegrees(Math.asin(Globe.toEcliptic(Globe.vector(my.lat, my.lon), now)[1]));
        float[] up = GlobeView.earthToView(now, GlobeView.facing(my.lat, my.lon, now), height);
        float[] down = GlobeView.earthToView(now, GlobeView.facing(my.lat, my.lon, now), -height);
        float[] view = Countries.facingness(my, up) > Countries.facingness(my, down) ? up : down;
        assertEquals(1, Countries.facingness(my, view), 1e-3);
        List<Countries.Country> facing = Countries.facing(all, view, 0.35, 1 / 3.0);
        assertTrue(facing.contains(my));
        assertTrue(facing.contains(named(all, "Singapore")));
        assertFalse(facing.contains(named(all, "Brazil"))); // the other side of the world
        assertEquals(0, Countries.across(my, view), 1e-3); // straight ahead: the middle
        // New Caledonia (165° E) faces the screen too, but on the right: it'd soon turn away.
        Countries.Country nc = named(all, "New Caledonia");
        assertTrue(Countries.facingness(nc, view) > 0.35);
        assertTrue(Countries.across(nc, view) > 1 / 3.0);
        assertFalse(facing.contains(nc));
        assertTrue(Countries.facingness(named(all, "Brazil"), view) < 0);
        assertEquals("Malaysia", Countries.nearestToMiddle(all, view).name);
    }

    @Test
    public void picksOneNotShownLately() throws Exception {
        List<Countries.Country> all = excerpt();
        List<Countries.Country> two = new ArrayList<>();
        two.add(named(all, "Malaysia"));
        two.add(named(all, "Singapore"));
        Set<String> recent = new HashSet<>();
        recent.add("Singapore");
        for (int i = 0; i < 20; i++) assertEquals("Malaysia", Countries.pick(two, recent, "my", new Random(i)).name);
        recent.add("Malaysia"); // all shown lately: any of them again
        assertNotNull(Countries.pick(two, recent, "my", new Random(1)));
        assertNull(Countries.pick(new ArrayList<>(), recent, "my", new Random(1)));
        // Your own country comes up about twice as often as another.
        int home = 0;
        Random r = new Random(7);
        for (int i = 0; i < 3000; i++) if (Countries.pick(two, new HashSet<>(), "my", r).code.equals("my")) home++;
        assertEquals(2000, home, 150);
    }
}
