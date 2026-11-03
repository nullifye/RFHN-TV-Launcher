package my.rafhan.app.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Settings' "Start now" (and the quick menu's "Start screen saver") on TVs that can't start a
 * screen saver on request: Google TV has no SystemUI "start now" screen (Somnambulator). It shows
 * one of TV Home's own screen savers full screen, as the screen saver does (other apps' overlays
 * hidden on Android 12+); any key ends it (the Globe's arrows turn the globe), and so does anything
 * else coming in front.
 */
public final class SaverActivity extends Activity implements Saver.Host {
    private static final String EXTRA_SAVER = "saver";
    private final Saver.ExitKey exitKey = new Saver.ExitKey();
    private Saver saver;

    /** Null if the screen saver isn't one of TV Home's own. */
    static Intent intent(Context ctx, ComponentName dream) {
        if (dream == null || !dream.getPackageName().equals(ctx.getPackageName())) return null;
        if (!Saver.own(dream.getClassName())) return null;
        return new Intent(ctx, SaverActivity.class).putExtra(EXTRA_SAVER, dream.getClassName());
    }

    @Override
    @SuppressWarnings("deprecation") // FLAG_FULLSCREEN: the insets API is Android 11+
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        saver = Saver.of(getIntent().getStringExtra(EXTRA_SAVER), this);
        if (saver == null) {
            finish();
            return;
        }
        // Like the real screen saver: full screen, nothing from other apps drawn over it (their
        // overlays: Android 12+), and the screen not held on, so the TV's own sleep timer applies.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) getWindow().setHideOverlayWindows(true);
        saver.attach();
    }

    @Override
    @SuppressWarnings("deprecation") // the flags: WindowInsetsController is Android 11+
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (saver != null) saver.start();
        RemoteBridge.saverShowing(true); // TV Home's info overlay stays off, as over the real one
    }

    @Override
    protected void onPause() {
        RemoteBridge.saverShowing(false);
        if (saver != null) saver.stop();
        super.onPause();
        finish(); // a preview: once something else is in front, it's over
    }

    @Override
    protected void onDestroy() {
        if (saver != null) saver.stop();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (saver != null && saver.interactive() && saver.key(e)) return true;
        if (exitKey.ends(e)) finish();
        return true;
    }

    @Override
    public Context context() {
        return this;
    }

    @Override
    public void show(View v) {
        setContentView(v);
    }

    @Override
    public void end() {
        finish();
    }
}
