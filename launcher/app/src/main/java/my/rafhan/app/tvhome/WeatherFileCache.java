package my.rafhan.app.tvhome;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Function;

/** One parsed weather file, retained without keeping its raw JSON or an unbounded location map. */
final class WeatherFileCache<T> {
    private File file;
    private String key;
    private long modified, length;
    private T value;

    T get(File next, String context, Function<String, T> parse) {
        long stamp = next.lastModified(), size = next.length();
        if (!next.equals(file) || !context.equals(key) || stamp != modified || size != length) {
            String json;
            try {
                json = new String(Files.readAllBytes(next.toPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                // Retry on the next refresh if an existing file was temporarily unreadable.
                if (next.isFile()) return parse.apply("");
                json = "";
            }
            T parsed = parse.apply(json);
            file = next;
            key = context;
            modified = stamp;
            length = size;
            value = parsed;
        }
        return value;
    }
}
