package my.rafhan.app.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import dadb.AdbKeyPair;
import dadb.AdbStream;
import dadb.Dadb;

/**
 * Phone-remote keys inside any app, while USB debugging is on.
 *
 * Android won't let an app press keys in other apps, and on this TV SELinux stops the ADB shell
 * writing to the remote's /dev/input nodes. It does let the shell create a virtual USB keyboard
 * through /dev/uhid with Android's own `hid` command. So the launcher opens an ADB connection to
 * itself (127.0.0.1:5555, dadb) and keeps a `hid -` process running whose stdin takes JSON
 * commands: register a device once, then one "report" line per key press/release.
 *
 * Two virtual devices:
 *  - a keypad (arrows, Enter, Esc, Backspace, no letters, so Android still treats the TV as
 *    keyboard-less and keeps the on-screen keyboard), always on while connected;
 *  - a full keyboard for typing, created on first use and removed after a minute idle.
 *
 * The first connection makes the TV ask "Allow USB debugging?" for the launcher's key.
 */
final class AdbKeys {
    enum State { OFF, CONNECTING, ALLOW, READY, ERROR }

    // Keypad: report byte = index into [none, 0x28 Enter ... 0x52 Up] (usage = 0x27 + index).
    private static final String PAD_DESCRIPTOR = "[5,1,9,6,161,1,5,7,25,224,41,231,21,0,37,1,117,1,149,8,129,2,"
            + "149,1,117,8,129,1,149,6,117,8,21,0,37,43,5,7,9,0,25,40,41,82,129,0,192]";
    // Standard boot keyboard: report byte = HID usage.
    private static final String KEYBOARD_DESCRIPTOR = "[5,1,9,6,161,1,5,7,25,224,41,231,21,0,37,1,117,1,149,8,129,2,"
            + "149,1,117,8,129,1,149,6,117,8,21,0,37,101,5,7,25,0,41,101,129,0,192]";
    private static final Map<String, Integer> PAD_USAGE = new HashMap<>();

    static {
        PAD_USAGE.put("ok", 0x28);        // Enter: select in the launcher and in apps
        PAD_USAGE.put("enter", 0x28);
        PAD_USAGE.put("backspace", 0x2A);
        PAD_USAGE.put("right", 0x4F);
        PAD_USAGE.put("left", 0x50);
        PAD_USAGE.put("down", 0x51);
        PAD_USAGE.put("up", 0x52);
    }

    private static final long KEYBOARD_IDLE_MS = 60_000;
    // PIDs of the `hid` processes we started, so a later run (after a crash or update) can kill
    // only ours. `pkill -f com.android.commands.hid` would also kill other apps' virtual devices.
    private static final String PIDS_FILE = "/data/local/tmp/tvhome-hid-pids";
    // The keypad's `hid` process costs ~21 MB: start it on the first key, stop it after this idle.
    private static final long PAD_IDLE_MS = 5 * 60_000;
    // dadb doesn't always notice when "Allow USB debugging" is accepted, so a waiting connection is
    // dropped and retried after this long; once the key is allowed the retry connects at once.
    private static final long ALLOW_RETRY_MS = 20_000;
    private static final int ALLOW_RETRIES = 6;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private volatile State state = State.OFF;
    private volatile String error;
    private long lastAttempt;
    private int allowRetries;
    private Dadb adb;
    private Hid pad, keyboard;
    private long keyboardUsed, padUsed;

    private final Runnable padIdle = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                if (pad == null) return;
                long idle = SystemClock.elapsedRealtime() - padUsed;
                if (idle < PAD_IDLE_MS) {
                    main.postDelayed(this, PAD_IDLE_MS - idle + 50);
                    return;
                }
                Hid p = pad;
                pad = null;
                new Thread(() -> stopHid(p), "adb-hid-stop").start();
            }
        }
    };

    private final Runnable keyboardIdle = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                if (keyboard == null) return;
                long idle = SystemClock.elapsedRealtime() - keyboardUsed;
                if (idle < KEYBOARD_IDLE_MS) {
                    main.postDelayed(this, KEYBOARD_IDLE_MS - idle + 50);
                    return;
                }
                Hid k = keyboard;
                keyboard = null;
                // Removing the keyboard device lets Android show its on-screen keyboard again.
                new Thread(() -> stopHid(k), "adb-hid-stop").start();
            }
        }
    };

    /** A shell command running over the loopback ADB connection (a hid device, the media helper). */
    static final class Hid {
        final AdbStream stream;
        final Dadb adb;
        final String pid;

        Hid(AdbStream stream, Dadb adb, String pid) {
            this.stream = stream;
            this.adb = adb;
            this.pid = pid;
        }
    }

    private final ContentObserver adbSetting;

    @SuppressLint("StaticFieldLeak") // holds only the application context (get()): nothing to leak
    private static AdbKeys instance;

    /** One loopback ADB connection per process, shared by the phone remote and Now Playing. */
    static synchronized AdbKeys get(Context ctx) {
        if (instance == null) instance = new AdbKeys(ctx.getApplicationContext());
        return instance;
    }

    private AdbKeys(Context ctx) {
        this.ctx = ctx;
        adbSetting = new ContentObserver(main) {
            @Override
            public void onChange(boolean selfChange) {
                if (!enabled()) close(State.OFF, null);
                else lastAttempt = 0; // turned on: allow an immediate connect
            }
        };
        ctx.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbSetting);
    }

    boolean enabled() {
        return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    State state() {
        return enabled() ? state : State.OFF;
    }

    String error() {
        return error;
    }

    /** Starts connecting in the background if USB debugging is on and we aren't connected. */
    void ensure() {
        if (!enabled()) {
            state = State.OFF;
            return;
        }
        synchronized (lock) {
            if (state == State.READY || state == State.CONNECTING || state == State.ALLOW) return;
            long now = SystemClock.elapsedRealtime();
            if (lastAttempt != 0 && now - lastAttempt < 10_000) return;
            lastAttempt = now;
            state = State.CONNECTING;
        }
        Thread t = new Thread(this::connect, "adb-connect");
        t.setDaemon(true);
        t.start();
        // dadb blocks while the TV shows "Allow USB debugging?": report that after a moment.
        main.postDelayed(() -> {
            if (state == State.CONNECTING) state = State.ALLOW;
        }, 2500);
        main.postDelayed(() -> {
            if (state != State.ALLOW) return;
            if (++allowRetries > ALLOW_RETRIES) {
                close(State.ERROR, "USB debugging wasn't allowed for the launcher on the TV.");
                return;
            }
            close(State.OFF, null);
            lastAttempt = 0;
            ensure();
            if (state == State.CONNECTING) state = State.ALLOW; // still waiting on the user, as far as anyone can tell
        }, ALLOW_RETRY_MS);
    }

    private void connect() {
        Dadb d = null;
        try {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", 5555), 1500);
            } catch (Exception e) {
                close(State.ERROR, "Network debugging (port 5555) is off on the TV.");
                return;
            }
            File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
            if (!priv.isFile() || !pub.isFile()) AdbKeyPair.Companion.generate(priv, pub);
            AdbKeyPair keys = AdbKeyPair.Companion.read(priv, pub);
            d = Dadb.Companion.create("127.0.0.1", 5555, keys, 5_000, 0);
            synchronized (lock) {
                adb = d; // so a timeout can close it while it waits for "Allow"
            }
            // hid processes belong to adbd and can outlive the launcher (crash, update): clear
            // any we started so virtual devices don't pile up. Only our own PIDs are killed
            // (recorded in PIDS_FILE); `pkill -f com.android.commands.hid` would also take out
            // virtual devices another app started.
            killLeftoverHid(d);
            shellQuiet(d, "pkill -f " + MediaHelper.class.getName());
            // The keypad device is created on the first key press (see send()).
            synchronized (lock) {
                if (adb != d) return; // closed meanwhile (ADB turned off / timed out)
                state = State.READY;
                error = null;
                allowRetries = 0;
            }
        } catch (Throwable e) {
            synchronized (lock) {
                // Only if this attempt is still the current one: a timeout/ADB-off already set its own message.
                if (adb == d) close(State.ERROR, "Couldn't connect to the TV's ADB: " + e.getMessage());
            }
        }
    }

    /**
     * Starts {@code command} as the shell user; null unless connected. Its stdout is the stream's
     * source; stop it with {@link #stop(Hid)} (off the main thread).
     */
    Hid shell(String command) {
        Dadb d;
        synchronized (lock) {
            if (state != State.READY || adb == null) return null;
            d = adb;
        }
        try {
            return start(d, command);
        } catch (Throwable e) {
            return null;
        }
    }

    void stop(Hid h) {
        stopHid(h);
    }

    /** `echo $$; exec` reports the pid the command runs as, so it can be killed later: closing the
     *  stream alone doesn't always end it. */
    private static Hid start(Dadb d, String command) throws Exception {
        AdbStream s = d.open("shell:echo $$; exec " + command);
        StringBuilder pid = new StringBuilder();
        for (int b; (b = s.getSource().readByte()) != '\n'; ) {
            if (b != '\r') pid.append((char) b);
            if (pid.length() > 12) break;
        }
        return new Hid(s, d, pid.toString().trim());
    }

    /** Starts `hid -` (reading JSON commands from stdin) and registers a virtual HID device on it. */
    private static Hid openHid(Dadb d, String name, String descriptor) throws Exception {
        Hid h = start(d, "hid -");
        recordPid(d, h.pid);
        AdbStream s = h.stream;
        Thread drain = new Thread(() -> {
            try {
                byte[] buf = new byte[1024];
                while (s.getSource().read(buf) >= 0) { /* discard */ }
            } catch (Exception ignored) {
            }
        }, "adb-hid-drain");
        drain.setDaemon(true);
        drain.start();
        s.getSink().writeUtf8("{\"id\":1,\"command\":\"register\",\"name\":\"" + name
                + "\",\"vid\":4660,\"pid\":22136,\"descriptor\":" + descriptor + "}\n"
                // give Android time to add the device before the first key
                + "{\"id\":1,\"command\":\"delay\",\"duration\":400}\n").flush();
        return h;
    }

    /** Ends a hid process (removing its virtual device). Blocking: call off the main thread. */
    private static void stopHid(Hid h) {
        if (h == null) return;
        try {
            if (h.pid.matches("\\d+")) h.adb.shell("kill " + h.pid);
        } catch (Throwable ignored) {
        }
        try {
            h.stream.close();
        } catch (Throwable ignored) {
        }
    }

    /** Kills the `hid` processes recorded by a previous run, then clears the list. */
    private static void killLeftoverHid(Dadb d) {
        shellQuiet(d, "for p in $(cat " + PIDS_FILE + " 2>/dev/null); do "
                + "grep -qa com.android.commands.hid.Hid /proc/$p/cmdline 2>/dev/null && kill $p; done; "
                + "rm -f " + PIDS_FILE);
    }

    /** Remembers a `hid` PID (from the command's `echo $$`) so a later run can clean it up. */
    private static void recordPid(Dadb d, String pid) {
        if (pid == null || !pid.matches("\\d+")) return;
        shellQuiet(d, "echo " + pid + " >> " + PIDS_FILE);
    }

    /** Runs a shell command and discards its output (fire-and-forget cleanup). */
    private static void shellQuiet(Dadb d, String command) {
        try {
            AdbStream s = d.open("shell:" + command);
            try {
                while (s.getSource().readUtf8Line() != null) { /* discard */ }
            } finally {
                s.close();
            }
        } catch (Throwable ignored) {
        }
    }

    /** up/down/left/right/ok/enter/backspace, n times. False if not connected or unknown key. */
    boolean key(String k, int n) {
        Integer usage = PAD_USAGE.get(k);
        if (usage == null) return false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) press(sb, 0, usage - 0x27);
        return send(sb.toString(), false);
    }

    /** Types printable ASCII through the virtual keyboard. */
    boolean type(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            int[] k = HidKeys.usageFor(text.charAt(i));
            if (k != null) press(sb, k[0], k[1]);
        }
        return sb.length() == 0 || send(sb.toString(), true);
    }

    private static void press(StringBuilder sb, int modifiers, int keyByte) {
        sb.append("{\"id\":1,\"command\":\"report\",\"report\":[").append(modifiers).append(",0,").append(keyByte)
                .append(",0,0,0,0,0]}\n")
                .append("{\"id\":1,\"command\":\"report\",\"report\":[0,0,0,0,0,0,0,0]}\n");
    }

    private boolean send(String lines, boolean toKeyboard) {
        synchronized (lock) {
            if (state != State.READY || adb == null) return false;
            try {
                if (!toKeyboard) {
                    if (pad == null) pad = openHid(adb, "TV Home remote keypad", PAD_DESCRIPTOR);
                    padUsed = SystemClock.elapsedRealtime();
                    main.removeCallbacks(padIdle);
                    main.postDelayed(padIdle, PAD_IDLE_MS);
                }
                Hid s = pad;
                if (toKeyboard) {
                    if (keyboard == null) keyboard = openHid(adb, "TV Home remote keyboard", KEYBOARD_DESCRIPTOR);
                    keyboardUsed = SystemClock.elapsedRealtime();
                    main.removeCallbacks(keyboardIdle);
                    main.postDelayed(keyboardIdle, KEYBOARD_IDLE_MS);
                    s = keyboard;
                }
                s.stream.getSink().writeUtf8(lines).flush();
                return true;
            } catch (Throwable e) {
                // ADB restarted or was turned off: drop the session, reconnect on the next use.
                close(State.ERROR, "The ADB connection dropped; reconnecting.");
                lastAttempt = 0;
                return false;
            }
        }
    }

    void close() {
        ctx.getContentResolver().unregisterContentObserver(adbSetting);
        close(State.OFF, null);
    }

    private void close(State newState, String message) {
        synchronized (lock) {
            Hid k = keyboard, p = pad;
            Dadb d = adb;
            // kill the hid processes (needs the connection), then drop the connection; off-thread
            new Thread(() -> {
                stopHid(k);
                stopHid(p);
                if (d != null) {
                    try {
                        d.close();
                    } catch (Throwable ignored) {
                    }
                }
            }, "adb-close").start();
            keyboard = null;
            pad = null;
            adb = null;
            state = newState;
            error = message;
        }
    }
}
