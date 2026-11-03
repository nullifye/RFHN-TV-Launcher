package my.rafhan.app.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Country-level, offline IP lookup. Queries never leave the device. Used on a worker only. */
final class TrafficGeo implements AutoCloseable {
    private static final String SOURCE = "https://github.com/sapics/ip-location-db/releases/download/latest/";
    private final SQLiteDatabase db;
    private final Map<String, Countries.Country> countries = new HashMap<>();

    TrafficGeo(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "traffic");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Can't create traffic directory");
        File file = new File(dir, "country-v1.db");
        if (!file.isFile()) build(file);
        db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        if (!GlobeFeeds.fetchCountries(ctx)) { db.close(); throw new java.io.IOException("Country map unavailable"); }
        for (Countries.Country c : GlobeFeeds.countries(ctx)) {
            if (c.code.isEmpty()) continue; // no ISO code (N. Cyprus, Somaliland): never in the IP data
            // Territories can share their country's code (AU: Australia, Ashmore and Cartier, Indian
            // Ocean Ter.): the country itself wins, so 1.1.1.1 lands on Australia.
            Countries.Country had = countries.get(c.code.toUpperCase(Locale.ROOT));
            if (had == null || (!had.sovereign.isEmpty() && c.sovereign.isEmpty())) {
                countries.put(c.code.toUpperCase(Locale.ROOT), c);
            }
        }
    }

    private static void build(File file) throws Exception {
        File tmp = new File(file.getPath() + ".tmp");
        // SQLite keeps the index on disk; don't load multi-megabyte IP lists into the Java heap.
        SQLiteDatabase out = SQLiteDatabase.openOrCreateDatabase(tmp, null);
        try {
            out.execSQL("DROP TABLE IF EXISTS ranges");
            out.execSQL("CREATE TABLE ranges(f INTEGER,s TEXT,e TEXT,c TEXT,PRIMARY KEY(f,s)) WITHOUT ROWID");
            out.beginTransaction();
            try (SQLiteStatement insert = out.compileStatement("INSERT OR REPLACE INTO ranges VALUES(?,?,?,?)")) {
                for (int family : new int[] {4, 6}) {
                    TrafficMonitor.status = "Downloading country data (IPv" + family + ")…";
                    HttpURLConnection c = (HttpURLConnection) new URL(SOURCE + "server-country-ipv" + family + "-num.csv").openConnection();
                    c.setConnectTimeout(15000); c.setReadTimeout(20000);
                    try {
                        if (c.getResponseCode() != 200) throw new java.io.IOException("Country database HTTP " + c.getResponseCode());
                        int rows = 0;
                        long size = 0;
                        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8), 32768)) {
                            for (String line; (line = in.readLine()) != null;) {
                                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                                size += line.length() + 1;
                                if (size > 64L * 1024 * 1024 || line.length() > 256) throw new java.io.IOException("Country database too large");
                                String[] p = line.split(",", -1);
                                if (p.length != 3 || !p[2].matches("[A-Z]{2}")) continue;
                                BigInteger start = new BigInteger(p[0]), end = new BigInteger(p[1]);
                                int bits = family == 4 ? 32 : 128;
                                if (start.signum() < 0 || end.compareTo(start) < 0 || end.bitLength() > bits) throw new java.io.IOException("Invalid address range");
                                insert.bindLong(1, family); insert.bindString(2, hex(start));
                                insert.bindString(3, hex(end)); insert.bindString(4, p[2]); insert.executeInsert();
                                rows++;
                            }
                        }
                        if (rows < 1000) throw new java.io.IOException("Incomplete country database");
                    } finally { c.disconnect(); }
                }
                out.setTransactionSuccessful();
            } finally { out.endTransaction(); }
        } finally { out.close(); }
        if (!tmp.renameTo(file)) throw new java.io.IOException("Can't save country database");
        //noinspection ResultOfMethodCallIgnored
        new File(tmp.getPath() + "-journal").delete(); // SQLite's leftover from building it
    }

    private static String hex(BigInteger value) {
        String h = value.toString(16);
        return "00000000000000000000000000000000".substring(h.length()) + h;
    }

    Countries.Country lookup(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip); // numeric strings produced by inet_ntop only
            String key = hex(new BigInteger(1, address.getAddress()));
            int family = address.getAddress().length == 4 ? 4 : 6;
            try (Cursor c = db.rawQuery("SELECT e,c FROM ranges WHERE f=? AND s<=? ORDER BY s DESC LIMIT 1",
                    new String[] {Integer.toString(family), key})) {
                return c.moveToFirst() && key.compareTo(c.getString(0)) <= 0 ? countries.get(c.getString(1)) : null;
            }
        } catch (Exception ignored) { return null; }
    }

    @Override public void close() { db.close(); }
}
