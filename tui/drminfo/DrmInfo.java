package tvhome;

import android.media.MediaDrm;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * The setup app's Widevine reader. It isn't part of TV Home: build.sh compiles it into
 * drminfo.jar, which the setup app carries, pushes to /data/local/tmp and runs as the ADB shell
 * user, so it works on any Android TV:
 * {@code app_process -cp /data/local/tmp/tvhome-drminfo.jar /system/bin tvhome.DrmInfo}
 *
 * The shell can't read the Widevine security level any other way (dumpsys media.drm prints
 * nothing on Android 10), so this asks Android's DRM API and prints {@code key=value} lines:
 * widevine and playready (yes/no), then Widevine's securityLevel (L1/L3), version, systemId,
 * hdcpLevel and maxHdcpLevel. A value that can't be read is left out; a failure is error=….
 *
 * MediaDrm's constructor needs the calling app's package name, which a shell program doesn't
 * have ("application package name cannot be null"), so the object is created without it and its
 * native setup is called with the shell's own package. Hidden APIs are open to app_process
 * programs.
 */
public final class DrmInfo {
    private static final UUID WIDEVINE = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);
    private static final UUID PLAYREADY = new UUID(0x9A04F07998404286L, 0xAB92E65BE0885F95L);
    private static final String[] KEYS = {"securityLevel", "version", "systemId", "hdcpLevel", "maxHdcpLevel"};

    private DrmInfo() {}

    public static void main(String[] args) {
        boolean wv = MediaDrm.isCryptoSchemeSupported(WIDEVINE);
        System.out.println("widevine=" + (wv ? "yes" : "no"));
        System.out.println("playready=" + (MediaDrm.isCryptoSchemeSupported(PLAYREADY) ? "yes" : "no"));
        if (!wv) return;
        MediaDrm drm = null;
        try {
            drm = open(WIDEVINE);
            for (String key : KEYS) {
                try {
                    String v = drm.getPropertyString(key);
                    if (v != null && !v.isEmpty()) System.out.println(key + "=" + v.replace('\n', ' '));
                } catch (RuntimeException ignored) {
                    // not every device reports every property
                }
            }
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            System.out.println("error=" + c.getClass().getSimpleName() + ": " + c.getMessage());
        } finally {
            if (drm != null) {
                try {
                    drm.close();
                } catch (RuntimeException ignored) {
                    // the process ends anyway
                }
            }
        }
    }

    /** A MediaDrm for the scheme, set up as the shell's own package (see the class comment). */
    private static MediaDrm open(UUID scheme) throws Exception {
        try {
            return new MediaDrm(scheme); // works where the shell has a package name
        } catch (IllegalArgumentException noPackage) {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field f = unsafeClass.getDeclaredField("THE_ONE");
            f.setAccessible(true);
            Object unsafe = f.get(null);
            MediaDrm drm = (MediaDrm) unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, MediaDrm.class);
            Method setup = MediaDrm.class.getDeclaredMethod("native_setup", Object.class, byte[].class, String.class);
            setup.setAccessible(true);
            ByteBuffer uuid = ByteBuffer.allocate(16);
            uuid.putLong(scheme.getMostSignificantBits()).putLong(scheme.getLeastSignificantBits());
            setup.invoke(drm, new WeakReference<>(drm), uuid.array(), "com.android.shell");
            return drm;
        }
    }
}
