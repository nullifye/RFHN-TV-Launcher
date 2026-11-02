package my.rafhan.app.tvhome;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.*;

public class WeatherFileCacheTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void parsesUnchangedFileOnlyOnce() throws Exception {
        File file = temp.newFile();
        write(file, "forecast");
        WeatherFileCache<String> cache = new WeatherFileCache<>();
        AtomicInteger calls = new AtomicInteger();
        Function<String, String> parse = json -> { calls.incrementAndGet(); return json; };
        assertEquals("forecast", cache.get(file, "today", parse));
        assertEquals("forecast", cache.get(file, "today", parse));
        assertEquals(1, calls.get());
        assertEquals("forecast", cache.get(file, "tomorrow", parse));
        assertEquals(2, calls.get()); // Date changes must reselect the forecast.
    }

    @Test public void refreshesOnTimestampSizeOrLocationChange() throws Exception {
        File file = temp.newFile();
        write(file, "old");
        WeatherFileCache<String> cache = new WeatherFileCache<>();
        assertEquals("old", cache.get(file, "", json -> json));
        long stamp = file.lastModified();
        write(file, "longer");
        assertTrue(file.setLastModified(stamp));
        assertEquals("longer", cache.get(file, "", json -> json));
        write(file, "newest");
        assertTrue(file.setLastModified(stamp + 2000));
        assertEquals("newest", cache.get(file, "", json -> json));
        File other = temp.newFile();
        write(other, "second");
        assertEquals("second", cache.get(other, "", json -> json));
    }

    @Test public void handlesMissingDeletedAndNewFiles() throws Exception {
        File file = new File(temp.getRoot(), "weather.json");
        WeatherFileCache<String> cache = new WeatherFileCache<>();
        assertEquals("", cache.get(file, "", json -> json));
        write(file, "new");
        assertEquals("new", cache.get(file, "", json -> json));
        assertTrue(file.delete());
        assertEquals("", cache.get(file, "", json -> json));
    }

    @Test public void cachesNullParseResults() throws Exception {
        File file = temp.newFile();
        WeatherFileCache<String> cache = new WeatherFileCache<>();
        AtomicInteger calls = new AtomicInteger();
        Function<String, String> parse = json -> { calls.incrementAndGet(); return null; };
        assertNull(cache.get(file, "", parse));
        assertNull(cache.get(file, "", parse));
        assertEquals(1, calls.get());
    }
}
