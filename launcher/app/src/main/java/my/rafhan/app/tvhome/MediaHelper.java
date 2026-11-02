package my.rafhan.app.tvhome;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Runs as the ADB shell user, not inside the app:
 * {@code app_process -cp <launcher apk> /system/bin my.rafhan.app.tvhome.MediaHelper}
 * started by NowPlayingPanel over the loopback ADB connection (AdbKeys).
 *
 * This low-RAM TV refuses notification-listener access, so the launcher itself can't read other
 * apps' media sessions. The shell user can (it holds STATUS_BAR_SERVICE), so this prints the
 * current session as one JSON line whenever it changes (checked every two seconds): app, title,
 * artist, state, position, duration, and the cover art as a small base64 JPEG when it changes.
 * Uses hidden APIs through reflection; that's allowed for app_process programs.
 */
@SuppressLint("PrivateApi") // run as the shell user (app_process), where these hidden APIs are open to it
public final class MediaHelper {
    private MediaHelper() {}

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "media_session");
        Object manager = Class.forName("android.media.session.ISessionManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Method getSessions = null;
        for (Method m : manager.getClass().getMethods()) {
            if (m.getName().equals("getSessions") && m.getParameterTypes().length == 2) getSessions = m;
        }
        if (getSessions == null) throw new IllegalStateException("no getSessions");
        Class<?> controllerStub = Class.forName("android.media.session.ISessionController$Stub");

        String last = null;
        int lastArt = 0;
        long lastPos = 0, lastUpdated = 0;
        float lastSpeed = 0;
        while (true) {
            JSONObject o = new JSONObject();
            try {
                List<?> sessions = (List<?>) getSessions.invoke(manager, null, 0);
                Object best = null;
                int bestRank = 0;
                PlaybackState bestState = null;
                MediaMetadata bestMeta = null;
                String bestPkg = null;
                for (Object s : sessions) {
                    // Android 10 returns Tokens whose getBinder() is the ISessionController; older ones IBinders.
                    Object c = s instanceof MediaSession.Token
                            ? MediaSession.Token.class.getMethod("getBinder").invoke(s)
                            : controllerStub.getMethod("asInterface", IBinder.class).invoke(null, s);
                    if (c == null) continue;
                    PlaybackState st = (PlaybackState) c.getClass().getMethod("getPlaybackState").invoke(c);
                    int state = st == null ? PlaybackState.STATE_NONE : st.getState();
                    int rank = state == PlaybackState.STATE_PLAYING ? 3
                            : state == PlaybackState.STATE_BUFFERING || state == PlaybackState.STATE_CONNECTING ? 2
                            : state == PlaybackState.STATE_PAUSED ? 1 : 0;
                    if (rank > bestRank) {
                        // Metadata can contain large bitmaps: don't fetch it for inactive/lower
                        // priority sessions on every poll.
                        MediaMetadata meta = (MediaMetadata) c.getClass().getMethod("getMetadata").invoke(c);
                        if (meta == null) continue;
                        best = c;
                        bestRank = rank;
                        bestState = st;
                        bestMeta = meta;
                        bestPkg = (String) c.getClass().getMethod("getPackageName").invoke(c);
                    }
                }
                if (best != null) {
                    o.put("pkg", bestPkg);
                    o.put("title", text(bestMeta, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE));
                    o.put("artist", text(bestMeta, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                            MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                            MediaMetadata.METADATA_KEY_ALBUM));
                    o.put("duration", bestMeta.getLong(MediaMetadata.METADATA_KEY_DURATION));
                    o.put("state", bestState.getState());
                    o.put("pos", bestState.getPosition());
                    o.put("updated", bestState.getLastPositionUpdateTime());
                    o.put("speed", (double) bestState.getPlaybackSpeed());
                    o.put("actions", bestState.getActions());
                    Bitmap art = null;
                    for (String k : new String[] {MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                            MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                        art = bestMeta.getBitmap(k);
                        if (art != null) break;
                    }
                    String artUri = text(bestMeta, MediaMetadata.METADATA_KEY_ART_URI,
                            MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                    if (artUri != null) o.put("artUri", artUri);
                    int artId = art == null ? 0 : art.getWidth() * 31 + art.getHeight() * 17 + art.getPixel(art.getWidth() / 2, art.getHeight() / 2);
                    o.put("artId", artId);
                    // Position moves every second: only report it again when it jumps (seek, new track).
                    JSONObject k = new JSONObject(o.toString());
                    k.remove("pos");
                    k.remove("updated");
                    long expect = lastPos + (long) ((bestState.getLastPositionUpdateTime() - lastUpdated) * lastSpeed);
                    if (Math.abs(bestState.getPosition() - expect) > 3000) k.put("jump", bestState.getPosition());
                    String key = k.toString();
                    if (art != null && artId != lastArt) o.put("art", encode(art));
                    if (!key.equals(last)) {
                        out.println(o.toString());
                        out.flush();
                        lastArt = artId;
                    }
                    k.remove("jump");
                    last = k.toString();
                    lastPos = bestState.getPosition();
                    lastUpdated = bestState.getLastPositionUpdateTime();
                    lastSpeed = bestState.getPlaybackSpeed();
                } else if (!"{}".equals(last)) {
                    out.println("{}");
                    out.flush();
                    last = "{}";
                    lastArt = 0;
                }
            } catch (Throwable e) {
                out.println("{\"error\":" + JSONObject.quote(String.valueOf(e)) + "}");
                out.flush();
            }
            SystemClock.sleep(2000);
        }
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    private static String encode(Bitmap art) {
        int max = 160;
        float scale = Math.min(1f, max / (float) Math.max(art.getWidth(), art.getHeight()));
        Bitmap small = scale < 1f ? Bitmap.createScaledBitmap(art, Math.round(art.getWidth() * scale),
                Math.round(art.getHeight() * scale), true) : art;
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        try {
            small.compress(Bitmap.CompressFormat.JPEG, 85, jpg);
        } finally {
            if (small != art) small.recycle();
        }
        return Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP);
    }
}
