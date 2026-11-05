package my.rafhan.app.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The Weather card's data, free of Android so it's unit tested:
 *  - MET Malaysia's forecast, from data.gov.my (no key): per location and day, the low and high
 *    and the morning/afternoon/night outlook, in Malay. english() turns MET's fixed phrases into
 *    English; anything new stays in Malay.
 *  - The Air Pollutant Index from the Department of Environment's APIMS: every station's latest
 *    hourly reading, from the ArcGIS layer the APIMS portal itself draws its map from.
 *  - Copernicus CAMS air quality (US AQI), from Open-Meteo (no key), at the APIMS station's spot.
 */
final class Weather {
    private Weather() {}

    /** Today's forecast for a place: temperatures in °C, the outlook as MET writes it (in Malay). */
    static final class Day {
        final int min, max;
        final String summary, when, morning, afternoon, night;

        Day(int min, int max, String summary, String when, String morning, String afternoon, String night) {
            this.min = min;
            this.max = max;
            this.summary = summary;
            this.when = when;
            this.morning = morning;
            this.afternoon = afternoon;
            this.night = night;
        }
    }

    /** A MET forecast location: id like "Tn181" (town), "Ds054" (district), "St008" (state). */
    static final class Place {
        final String id, name;

        Place(String id, String name) {
            this.id = id;
            this.name = name;
        }

        /** "Town", "District", "Division" (Sarawak), "State" or "Recreation". */
        String type() {
            String p = id.length() >= 2 ? id.substring(0, 2) : "";
            switch (p) {
                case "Tn": return "Town";
                case "Ds": return "District";
                case "Dv": return "Division";
                case "St": return "State";
                case "Rc": return "Recreation";
                default: return "Place";
            }
        }
    }

    /** An APIMS station and its latest reading (api < 0: none this hour). */
    static final class Station {
        final String id, place, state, pollutant;
        final double lat, lon;
        final int api;
        /** The reading's hour, Malaysian time. */
        final LocalDateTime time;

        Station(String id, String place, String state, double lat, double lon, int api, String pollutant,
                LocalDateTime time) {
            this.id = id;
            this.place = place;
            this.state = state;
            this.lat = lat;
            this.lon = lon;
            this.api = api;
            this.pollutant = pollutant;
            this.time = time;
        }
    }

    /**
     * Copernicus's air quality now: US AQI, PM2.5 (µg/m³), haze as aerosol optical depth (how much
     * the air's particles dim the sunlight: about 0.1 clear, 1 thick haze) and the model hour it's
     * for (Malaysian time). Missing numbers are NaN.
     */
    static final class Air {
        final int aqi;
        final double pm25, haze;
        final LocalDateTime time;

        Air(int aqi, double pm25, double haze, LocalDateTime time) {
            this.aqi = aqi;
            this.pm25 = pm25;
            this.haze = haze;
            this.time = time;
        }
    }

    /**
     * ECMWF's weather now (its IFS model, through Open-Meteo): the temperature and how hot it
     * feels (°C), the WMO weather code (-1 if missing) and the 15-minute step it's for (Malaysian
     * time).
     */
    static final class Now {
        final double temp, feels;
        final int code;
        final LocalDateTime time;

        Now(double temp, double feels, int code, LocalDateTime time) {
            this.temp = temp;
            this.feels = feels;
            this.code = code;
            this.time = time;
        }
    }

    /** One hourly API reading at a station (APIMS's table of the last 24 hours). */
    static final class Reading {
        final LocalDateTime time;
        final int api;

        Reading(LocalDateTime time, int api) {
            this.time = time;
            this.api = api;
        }
    }

    /** A MET warning (thunderstorms, heavy rain…): its title, the areas in words, and when it holds. */
    static final class Warning {
        final String title, text;
        final LocalDateTime from, to;

        Warning(String title, String text, LocalDateTime from, LocalDateTime to) {
            this.title = title;
            this.text = text;
            this.from = from;
            this.to = to;
        }
    }

    // ---------------------------------------------------------------- parsing

    /** The row for {@code today} among a location's forecast days (they don't come in order), or null. */
    static Day parseForecast(String json, String placeId, LocalDate today) {
        try {
            JSONArray rows = new JSONArray(json);
            String date = today.toString();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                JSONObject loc = r.optJSONObject("location");
                if (loc == null || !placeId.equals(loc.optString("location_id"))) continue;
                if (!date.equals(r.optString("date"))) continue;
                if (r.isNull("min_temp") || r.isNull("max_temp")) return null;
                return new Day(r.getInt("min_temp"), r.getInt("max_temp"), r.optString("summary_forecast"),
                        r.optString("summary_when"), r.optString("morning_forecast"),
                        r.optString("afternoon_forecast"), r.optString("night_forecast"));
            }
        } catch (Exception ignored) {
            // not the forecast (an error page): nothing to show
        }
        return null;
    }

    /** Every location in a forecast response, once each, by name. */
    static List<Place> parsePlaces(String json) {
        List<Place> out = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(json);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject loc = rows.getJSONObject(i).optJSONObject("location");
                if (loc == null) continue;
                String id = loc.optString("location_id"), name = loc.optString("location_name");
                if (id.isEmpty() || name.isEmpty() || !seen.add(id)) continue;
                out.add(new Place(id, name));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        out.sort((a, b) -> {
            int c = a.name.compareToIgnoreCase(b.name);
            return c != 0 ? c : a.id.compareTo(b.id);
        });
        return out;
    }

    /** The APIMS stations (ArcGIS query result), by state then place. */
    static List<Station> parseStations(String json) {
        List<Station> out = new ArrayList<>();
        try {
            JSONArray features = new JSONObject(json).getJSONArray("features");
            for (int i = 0; i < features.length(); i++) {
                JSONObject a = features.getJSONObject(i).optJSONObject("attributes");
                if (a == null || a.optString("STATION_ID").isEmpty()) continue;
                double lat = a.optDouble("LATITUDE", Double.NaN), lon = a.optDouble("LONGITUDE", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                int api = a.isNull("API") ? -1 : (int) Math.round(a.optDouble("API", -1));
                // STATION_LOCATION is "Klang, SELANGOR": the place before the comma.
                String place = a.optString("STATION_LOCATION").split(",")[0].trim();
                // DATETIME is Malaysian wall-clock time stored as if it were UTC.
                LocalDateTime time = a.isNull("DATETIME") ? null
                        : LocalDateTime.ofInstant(Instant.ofEpochMilli(a.getLong("DATETIME")), ZoneOffset.UTC);
                out.add(new Station(a.getString("STATION_ID"), place, a.optString("STATE_NAME"), lat, lon,
                        api, a.optString("PARAM_SYMBOL"), time));
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        out.sort((x, y) -> {
            int c = x.state.compareToIgnoreCase(y.state);
            return c != 0 ? c : x.place.compareToIgnoreCase(y.place);
        });
        return out;
    }

    /** A station's hourly readings (APIMS's last 24 hours), newest first. */
    static List<Reading> parseHourly(String json, String stationId) {
        List<Reading> out = new ArrayList<>();
        try {
            JSONArray rows = new JSONObject(json).getJSONArray("api_table_hourly");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                if (!stationId.equals(r.optString("STATION_ID")) || r.isNull("API") || r.isNull("DATETIME")) continue;
                try {
                    out.add(new Reading(LocalDateTime.parse(r.getString("DATETIME")), r.getInt("API")));
                } catch (RuntimeException ignored) {
                    // an hour it can't read is left out
                }
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        out.sort((x, y) -> y.time.compareTo(x.time));
        return out;
    }

    /**
     * How far the API moved in the {@code hours} up to {@code at} (the reading the card shows; null:
     * the newest): that hour's reading less the one {@code hours} before. Null when either is missing.
     */
    static Integer change(List<Reading> readings, LocalDateTime at, int hours) {
        if (readings.isEmpty()) return null;
        LocalDateTime t = at != null ? at : readings.get(0).time;
        Integer now = null, then = null;
        for (Reading r : readings) {
            if (r.time.equals(t)) now = r.api;
            if (r.time.equals(t.minusHours(hours))) then = r.api;
        }
        return now == null || then == null ? null : now - then;
    }

    /** "↑ Rising · +4 in 3 h", "↓ Falling · −3 in 3 h", "→ Steady": a move of 2 or more counts. */
    static String trend(Integer change, int hours) {
        if (change == null) return "";
        if (change >= 2) return "↑ Rising · +" + change + " in " + hours + " h";
        if (change <= -2) return "↓ Falling · −" + (-change) + " in " + hours + " h";
        return "→ Steady";
    }

    /**
     * Whether an hourly reading is worth fetching: the source puts out each hour's reading
     * {@code publishMinute} minutes past the hour (APIMS about :10, Copernicus on the hour). Due
     * when the one we have ({@code have}; null: none) is older than the newest that should be out
     * by {@code now}, and the last try was at least {@code retryMs} ago (it can be late). Once we
     * have that hour's, nothing more is fetched until the next one.
     */
    static boolean hourlyDue(LocalDateTime have, LocalDateTime now, int publishMinute, long sinceFetchMs,
                             long retryMs) {
        if (sinceFetchMs < retryMs) return false;
        LocalDateTime hour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        LocalDateTime newest = now.getMinute() >= publishMinute ? hour : hour.minusHours(1);
        return have == null || have.isBefore(newest);
    }

    /** MET's warnings (data.gov.my), the ones with a time they hold; "No Advisory" has none. */
    static List<Warning> parseWarnings(String json) {
        List<Warning> out = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(json);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject w = rows.getJSONObject(i);
                if (w.isNull("valid_from") || w.isNull("valid_to")) continue;
                JSONObject issue = w.optJSONObject("warning_issue");
                String title = issue == null ? "" : issue.optString("title_en");
                try {
                    out.add(new Warning(title, w.optString("text_en"), LocalDateTime.parse(w.getString("valid_from")),
                            LocalDateTime.parse(w.getString("valid_to"))));
                } catch (RuntimeException ignored) {
                    // a time it can't read: that warning is left out
                }
            }
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
        return out;
    }

    /**
     * The warning in force at {@code now} (Malaysian time) that names one of {@code places} as a
     * whole word, or null. MET lists the districts it covers ("Selangor (Sepang, Kuala Langat,
     * Klang and Petaling)"). Sea warnings are about the waters off the coast, so they're left out.
     */
    static Warning activeWarning(List<Warning> warnings, LocalDateTime now, List<String> places) {
        for (Warning w : warnings) {
            if (now.isBefore(w.from) || now.isAfter(w.to)) continue;
            if (isSea(w)) continue;
            for (String p : places) {
                String name = p.trim();
                if (name.isEmpty()) continue;
                if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(w.text).find()) return w;
            }
        }
        return null;
    }

    static boolean isSea(Warning w) {
        String t = w.title.toLowerCase(Locale.ROOT);
        return t.contains("sea") || t.contains("laut");
    }

    /** "Thunderstorms Warning" → "Thunderstorms warning". */
    static String warningName(Warning w) {
        String t = w.title.trim();
        return t.isEmpty() ? "Weather warning" : t.charAt(0) + t.substring(1).toLowerCase(Locale.ROOT);
    }

    /** Open-Meteo's current air quality, or null. */
    static Air parseAir(String json) {
        try {
            JSONObject cur = new JSONObject(json).getJSONObject("current");
            if (cur.isNull("us_aqi")) return null;
            LocalDateTime time = null;
            try {
                time = LocalDateTime.parse(cur.optString("time")); // "2026-10-31T15:00", asked for in Malaysian time
            } catch (RuntimeException ignored) {
                // no time: the reading still shows
            }
            return new Air((int) Math.round(cur.getDouble("us_aqi")), cur.optDouble("pm2_5", Double.NaN),
                    cur.optDouble("aerosol_optical_depth", Double.NaN), time);
        } catch (Exception e) {
            return null;
        }
    }

    /** Open-Meteo's ECMWF "current" block, or null; a missing feels-like is NaN. */
    static Now parseNow(String json) {
        try {
            JSONObject cur = new JSONObject(json).getJSONObject("current");
            if (cur.isNull("temperature_2m")) return null;
            LocalDateTime time = null;
            try {
                time = LocalDateTime.parse(cur.optString("time")); // "2026-11-01T16:00", Malaysian time
            } catch (RuntimeException ignored) {
                // no time: the values still show
            }
            return new Now(cur.getDouble("temperature_2m"), cur.optDouble("apparent_temperature", Double.NaN),
                    cur.isNull("weather_code") ? -1 : cur.optInt("weather_code", -1), time);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- home screen effects

    /** The home screen's rain on the glass: none, mist (drizzle, fog), light rain, rain, heavy rain. */
    static final int FX_NONE = 0, FX_MIST = 1, FX_LIGHT = 2, FX_RAIN = 3, FX_HEAVY = 4;

    /** The rain effect for a WMO weather code (ECMWF's weather_code now). */
    static int rainEffect(int wmo) {
        switch (wmo) {
            case 45: case 48: // fog
            case 51: case 53: case 55: case 56: case 57: // drizzle
                return FX_MIST;
            case 61: case 66: case 80: // slight rain, showers
                return FX_LIGHT;
            case 63: case 81: case 95: // moderate rain, showers, a thunderstorm
                return FX_RAIN;
            case 65: case 67: case 82: case 96: case 99: // heavy rain, violent showers, storms with hail
                return FX_HEAVY;
            default:
                return FX_NONE;
        }
    }

    /** Lightning: a thunderstorm now (WMO 95–99), or a MET thunderstorm warning in force. */
    static boolean thunder(int wmo, Warning warning) {
        if (wmo == 95 || wmo == 96 || wmo == 99) return true;
        if (warning == null) return false;
        String t = warning.title.toLowerCase(Locale.ROOT);
        return t.contains("thunder") || t.contains("ribut petir");
    }

    // ---------------------------------------------------------------- choosing

    /**
     * The forecast location for a prayer zone: its first area that's a MET town (else district,
     * Sarawak division or recreation spot), trying each area in turn; else the zone's state.
     * {@code areas} and {@code state} as zones.xml has them ("Klang, Kuala Langat", "Selangor").
     */
    static Place autoPlace(String state, String areas, List<Place> places) {
        return autoPlace(state, areas, places, null);
    }

    /**
     * As above, but an area the zone's nearest APIMS station is in comes first ("Pegoh Ipoh" →
     * Ipoh, not the zone's first area, Kuala Kangsar), so the forecast and the air readings are for
     * the same town. {@code stationPlace}: that station's town, or null.
     */
    static Place autoPlace(String state, String areas, List<Place> places, String stationPlace) {
        if (stationPlace != null) {
            for (String area : areas.split(",")) {
                String a = area.trim();
                if (a.isEmpty() || !java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(a) + "\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(stationPlace).find()) continue;
                Place p = town(a, places);
                if (p != null) return p;
            }
        }
        for (String area : areas.split(",")) {
            Place p = town(area, places);
            if (p != null) return p;
        }
        String s = norm(state);
        for (Place p : places) {
            if (!p.id.startsWith("St")) continue;
            String n = norm(p.name);
            // "Wilayah Persekutuan" zones: MET splits them into "WP Kuala Lumpur" and so on.
            if (n.equals(s) || (s.startsWith("wilayah") && n.startsWith("wp"))) return p;
        }
        return null;
    }

    /** MET's place for an area's name: a town, else a district, Sarawak division or recreation spot. */
    private static Place town(String area, List<Place> places) {
        for (String type : new String[]{"Tn", "Ds", "Dv", "Rc"}) {
            for (Place p : places) {
                if (p.id.startsWith(type) && norm(p.name).equals(norm(area))) return p;
            }
        }
        return null;
    }

    /**
     * Where MET's towns are ("Tn181 3.043 101.449;…"): MET lists them by id and name only. From
     * OpenStreetMap (ODbL), looked up once when the table was made, so nothing's asked online.
     */
    private static final String TOWNS = "Tn002 6.267 100.420;Tn003 6.123 100.368;Tn004 6.173 100.520;Tn005 6.255 100.607;"
            + "Tn006 5.994 100.477;Tn007 5.800 100.372;Tn008 5.644 100.487;Tn009 5.351 100.235;"
            + "Tn010 5.406 100.283;Tn011 5.810 100.736;Tn012 5.517 100.426;Tn013 5.414 100.329;"
            + "Tn014 5.408 100.370;Tn015 5.295 100.260;Tn016 5.387 100.382;Tn017 5.369 100.420;"
            + "Tn018 5.364 100.461;Tn019 5.263 100.430;Tn020 5.371 100.617;Tn021 5.676 100.917;"
            + "Tn022 5.170 100.479;Tn023 5.210 100.612;Tn024 5.092 100.486;Tn025 5.218 100.692;"
            + "Tn026 5.028 100.527;Tn027 5.431 101.130;Tn028 5.109 100.968;Tn029 4.855 100.744;"
            + "Tn030 6.018 101.973;Tn031 6.176 102.166;Tn032 6.008 102.093;Tn033 6.125 102.238;"
            + "Tn034 5.701 101.843;Tn035 4.772 100.941;Tn036 4.819 101.071;Tn037 5.995 102.387;"
            + "Tn038 5.808 102.147;Tn039 5.768 102.238;Tn040 4.216 100.695;Tn041 4.599 101.090;"
            + "Tn042 5.835 102.383;Tn043 4.460 101.050;Tn044 4.361 100.951;Tn045 5.531 102.199;"
            + "Tn046 4.477 101.168;Tn047 5.828 102.556;Tn048 5.738 102.497;Tn049 3.986 100.784;"
            + "Tn050 4.300 101.150;Tn051 4.023 101.026;Tn052 4.199 101.260;Tn053 4.882 101.969;"
            + "Tn054 3.769 100.986;Tn055 5.521 102.747;Tn056 5.550 102.736;Tn057 3.827 101.402;"
            + "Tn058 5.396 103.062;Tn059 5.330 103.138;Tn060 4.187 102.053;Tn061 3.340 101.250;"
            + "Tn062 3.793 101.857;Tn063 3.563 101.661;Tn064 3.319 101.575;Tn065 3.254 101.654;"
            + "Tn066 3 101.391;Tn067 3.214 101.635;Tn068 3.936 102.362;Tn069 3.238 101.681;"
            + "Tn070 3.074 101.519;Tn071 3.182 101.689;Tn072 3.522 101.909;Tn073 3.171 101.672;"
            + "Tn074 3.129 101.643;Tn075 3.198 101.714;Tn076 3.099 101.645;Tn077 3.051 101.582;"
            + "Tn078 3.128 101.679;Tn079 3.152 101.694;Tn080 3.146 101.711;Tn081 3.151 101.760;"
            + "Tn082 4.764 103.418;Tn083 3.065 101.711;Tn084 2.803 101.496;Tn085 3.034 101.706;"
            + "Tn086 3.099 101.737;Tn087 2.934 101.646;Tn088 2.938 101.692;Tn089 4.637 103.436;"
            + "Tn090 2.995 101.790;Tn091 3.484 102.343;Tn092 2.904 101.786;Tn093 2.947 101.846;"
            + "Tn094 4.508 103.442;Tn095 3.449 102.418;Tn096 2.803 101.800;Tn097 2.690 101.750;"
            + "Tn098 4.235 103.424;Tn099 2.938 102.071;Tn100 3.037 102.118;Tn101 3.246 102.411;"
            + "Tn102 3.237 102.542;Tn103 3.585 102.773;Tn104 2.723 101.940;Tn105 2.526 101.811;"
            + "Tn106 2.743 102.215;Tn107 2.558 102.108;Tn108 3.797 103.322;Tn109 2.897 102.406;"
            + "Tn110 2.470 102.230;Tn111 2.352 102.109;Tn112 2.383 102.211;Tn113 2.249 102.155;"
            + "Tn114 3.492 103.390;Tn115 2.312 102.282;Tn116 3.065 103.081;Tn117 2.580 102.614;"
            + "Tn118 2.256 102.292;Tn119 2.196 102.248;Tn120 2.306 102.422;Tn121 2.250 102.583;"
            + "Tn122 2.146 102.426;Tn123 2.492 102.845;Tn124 2.050 102.567;Tn125 2.149 102.771;"
            + "Tn126 2.383 103.020;Tn127 2.806 103.489;Tn128 2.013 103.057;Tn129 1.933 103.000;"
            + "Tn130 1.918 103.180;Tn131 2.032 103.319;Tn132 2.430 103.835;Tn133 1.827 103.309;"
            + "Tn134 1.486 103.390;Tn135 1.667 103.600;Tn136 1.601 103.645;Tn137 1.734 103.901;"
            + "Tn138 1.452 103.614;Tn139 1.458 103.765;Tn140 1.495 103.921;Tn141 4.632 101.460;"
            + "Tn142 1.554 104.231;Tn143 6.322 99.848;Tn144 6.094 102.285;Tn145 4.471 101.378;"
            + "Tn146 3.690 101.523;Tn147 3.139 101.441;Tn148 3.104 101.597;Tn149 2.812 101.737;"
            + "Tn150 5.738 102.497;Tn151 1.507 103.513;Tn152 3.750 102.550;Tn153 1.599 103.821;"
            + "Tn154 3.425 101.177;Tn155 3.224 101.715;Tn156 4.205 100.660;Tn157 6.107 100.293;"
            + "Tn158 3.249 101.704;Tn159 1.537 103.662;Tn160 5.325 100.285;Tn161 5.820 100.477;"
            + "Tn162 3.376 101.614;Tn163 2.284 102.134;Tn164 3.005 101.710;Tn165 2.251 102.254;"
            + "Tn166 2.895 101.469;Tn167 5.445 100.429;Tn168 2.811 102.404;Tn169 3.769 100.986;"
            + "Tn170 5.368 100.310;Tn171 3.034 101.617;Tn172 5.465 100.284;Tn173 5.451 100.306;"
            + "Tn174 3.147 101.619;Tn175 5.321 100.268;Tn176 5.347 100.436;Tn177 5.199 100.492;"
            + "Tn178 3.678 100.988;Tn179 6.439 100.194;Tn180 3.220 101.777;Tn181 3.045 101.445;"
            + "Tn182 5.167 103.167;Tn183 5.074 103.017;Tn184 5.796 102.564;Tn185 4.616 103.208;"
            + "Tn186 4.219 103.423;Tn187 1.560 110.345;Tn188 1.237 111.463;Tn189 2.125 111.521;"
            + "Tn190 2.300 111.871;Tn191 2.898 112.094;Tn192 2.014 112.936;Tn193 3.187 113.047;"
            + "Tn194 4.394 113.988;Tn195 4.750 115.005;Tn196 5.978 116.073;Tn197 5.337 116.157;"
            + "Tn198 6.178 116.233;Tn199 4.244 117.885;Tn200 5.839 118.116;Tn201 5.030 118.327";
    /** Farther than this from every town (about 30 km): no town is near enough, the zone decides. */
    private static final double TOWN_DEGREES = 0.27;

    /** The MET town nearest a spot, among the places MET lists now, or null when none is near. */
    static Place nearestTown(List<Place> places, double lat, double lon) {
        Place best = null;
        double bestD = TOWN_DEGREES * TOWN_DEGREES, k = Math.cos(Math.toRadians(lat));
        for (String t : TOWNS.split(";")) {
            String[] f = t.split(" ");
            double dLat = lat - Double.parseDouble(f[1]), dLon = (lon - Double.parseDouble(f[2])) * k;
            double d = dLat * dLat + dLon * dLon;
            if (d >= bestD) continue;
            Place p = place(places, f[0]);
            if (p == null) continue; // MET no longer lists it
            bestD = d;
            best = p;
        }
        return best;
    }

    /** The station nearest a spot (great-circle), or null if there are none. */
    static Station nearest(List<Station> stations, double lat, double lon) {
        Station best = null;
        double bestD = Double.MAX_VALUE;
        double[] here = Globe.vector(lat, lon);
        for (Station s : stations) {
            double[] v = Globe.vector(s.lat, s.lon);
            double d = -(here[0] * v[0] + here[1] * v[1] + here[2] * v[2]); // smaller is nearer
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    static Place place(List<Place> places, String id) {
        for (Place p : places) if (p.id.equals(id)) return p;
        return null;
    }

    static Station station(List<Station> stations, String id) {
        for (Station s : stations) if (s.id.equals(id)) return s;
        return null;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    // ---------------------------------------------------------------- words

    /** Sun (no rain), cloud, rain, storm or haze: the card's icon. */
    static final int SUN = 0, CLOUD = 1, RAIN = 2, STORM = 3, HAZE = 4;

    static int kind(String summary) {
        String s = summary.toLowerCase(Locale.ROOT);
        if (s.contains("ribut petir")) return STORM;
        if (s.contains("tiada hujan")) return SUN;
        if (s.contains("hujan")) return RAIN;
        if (s.contains("jerebu")) return HAZE;
        if (s.contains("cerah")) return SUN;
        return CLOUD; // mendung, berangin, anything new
    }

    /**
     * MET's outlook in English: "Ribut petir di beberapa tempat di kawasan pedalaman" →
     * "Thunderstorms in places, inland". A phrase it doesn't know stays in Malay.
     */
    static String english(String phrase) {
        String s = phrase.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String area = "";
        if (s.endsWith(" di kawasan pedalaman")) {
            area = ", inland";
            s = s.substring(0, s.length() - " di kawasan pedalaman".length());
        } else if (s.endsWith(" di kawasan pantai")) {
            area = ", on the coast";
            s = s.substring(0, s.length() - " di kawasan pantai".length());
        }
        String where = "";
        boolean widespread = false;
        if (s.endsWith(" di beberapa tempat")) {
            where = " in places";
            s = s.substring(0, s.length() - " di beberapa tempat".length());
        } else if (s.endsWith(" di kebanyakan tempat")) {
            where = " in most places";
            s = s.substring(0, s.length() - " di kebanyakan tempat".length());
        } else if (s.endsWith(" di satu dua tempat")) {
            where = " in one or two places";
            s = s.substring(0, s.length() - " di satu dua tempat".length());
        } else if (s.endsWith(" menyeluruh")) {
            widespread = true;
            s = s.substring(0, s.length() - " menyeluruh".length());
        }
        String base;
        switch (s) {
            case "tiada hujan": base = "No rain"; break;
            case "hujan": base = "Rain"; break;
            case "hujan lebat": base = "Heavy rain"; break;
            case "ribut petir": base = "Thunderstorms"; break;
            case "mendung": base = "Cloudy"; break;
            case "cerah": base = "Fair"; break;
            case "berjerebu": base = "Hazy"; break;
            case "berangin": base = "Windy"; break;
            default: return phrase.trim();
        }
        if (widespread) base = "Widespread " + base.toLowerCase(Locale.ROOT);
        return base + where + area;
    }

    /** When it's expected: "Petang dan Malam" → "afternoon and night". Unknown stays as is. */
    static String englishWhen(String when) {
        StringBuilder out = new StringBuilder();
        for (String part : when.trim().split("(?i)\\s+dan\\s+")) {
            String p = part.trim().toLowerCase(Locale.ROOT), e;
            switch (p) {
                case "pagi": e = "morning"; break;
                case "petang": e = "afternoon"; break;
                case "malam": e = "night"; break;
                case "sepanjang hari": e = "all day"; break;
                default: return when.trim();
            }
            if (out.length() > 0) out.append(" and ");
            out.append(e);
        }
        return out.toString();
    }

    /** The whole outlook in a line: "Thunderstorms in places, afternoon"; "No rain" on a dry day. */
    static String outlook(Day d) {
        String what = english(d.summary);
        if (kind(d.summary) == SUN || d.when.trim().isEmpty()) return what;
        return what + ", " + englishWhen(d.when);
    }

    /** One word or two for a part of the day: "No rain", "Storms", "Rain", "Cloudy", "Hazy". */
    static String word(String phrase) {
        switch (kind(phrase)) {
            case SUN: return phrase.toLowerCase(Locale.ROOT).contains("cerah") ? "Fair" : "No rain";
            case STORM: return "Storms";
            case RAIN: return "Rain";
            case HAZE: return "Hazy";
            default: return english(phrase);
        }
    }

    /** For the card's small label: "STORMS PM", "RAIN AM & PM", "NO RAIN", "CLOUDY". */
    static String shortOutlook(Day d) {
        String what;
        switch (kind(d.summary)) {
            case SUN: return "No rain";
            case STORM: what = "Storms"; break;
            case RAIN: what = "Rain"; break;
            case HAZE: return "Hazy";
            default: return english(d.summary);
        }
        String w = englishWhen(d.when).replace("morning", "am").replace("afternoon", "pm")
                .replace(" and ", " & ");
        return w.isEmpty() || w.equals(d.when.trim()) ? what : what + " " + w;
    }

    // ---------------------------------------------------------------- bands

    // The bands' colours: each index's own hues (the DOE's blue, green, yellow, orange, red as on the
    // APIMS portal: #3D8AF7 #7CDE6B #FFFF00 #FFA834 #FF3823; the US EPA's green, yellow, orange,
    // red, purple, maroon), softened to the pastels of the System card (its green, amber and red).
    private static final int BLUE = 0xFF6AAEF5, GREEN = 0xFF63D489, YELLOW = 0xFFF2DC6B, ORANGE = 0xFFFFB340,
            RED = 0xFFFF6B5E, PURPLE = 0xFFB38AE3, MAROON = 0xFFC9607A;
    /** The DOE's API bands: upper ends, names and colours. */
    static final int[] API_TOP = {50, 100, 200, 300, 500};
    static final String[] API_BANDS = {"Good", "Moderate", "Unhealthy", "Very unhealthy", "Hazardous"};
    static final int[] API_COLORS = {BLUE, GREEN, YELLOW, ORANGE, RED};
    /** The US EPA's AQI bands, names (short for the card) and colours. */
    static final int[] US_TOP = {50, 100, 150, 200, 300, 500};
    static final String[] US_BANDS = {"Good", "Moderate", "Unhealthy for sensitive groups", "Unhealthy",
            "Very unhealthy", "Hazardous"};
    static final String[] US_SHORT = {"Good", "Moderate", "Sensitive", "Unhealthy", "V. unhealthy", "Hazardous"};
    static final int[] US_COLORS = {GREEN, YELLOW, ORANGE, RED, PURPLE, MAROON};

    /** Which band a value is in (the last one past the scale's end). */
    static int band(int value, int[] tops) {
        for (int i = 0; i < tops.length; i++) if (value <= tops[i]) return i;
        return tops.length - 1;
    }

    static String apiBand(int api) {
        return API_BANDS[band(api, API_TOP)];
    }

    static int apiColor(int api) {
        return API_COLORS[band(api, API_TOP)];
    }

    static String usAqiBand(int aqi) {
        return US_BANDS[band(aqi, US_TOP)];
    }

    static int usAqiColor(int aqi) {
        return US_COLORS[band(aqi, US_TOP)];
    }

    /** APIMS's pollutant marks: "**" PM2.5, "*" PM10, a SO₂, b NO₂, c O₃, d CO. */
    static String pollutant(String symbol) {
        switch (symbol.trim()) {
            case "**": return "PM2.5";
            case "*": return "PM10";
            case "a": return "SO₂";
            case "b": return "NO₂";
            case "c": return "Ozone";
            case "d": return "CO";
            default: return "";
        }
    }
}
