package my.rafhan.app.tvhome;

import android.content.Context;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.view.View;

/** Runs a Saver as the TV's screen saver: ClockDream, LogDream and GlobeDream. */
abstract class SaverDream extends DreamService implements Saver.Host {
    private final Saver.ExitKey exitKey = new Saver.ExitKey();
    private Saver saver;

    abstract Saver make(Saver.Host host);

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

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        saver = make(this);
        setInteractive(saver.interactive()); // otherwise any key wakes the TV
        setFullscreen(true);
        setScreenBright(false);
        saver.attach();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        saver.start();
    }

    @Override
    public void onDreamingStopped() {
        saver.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (saver != null) saver.stop();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (saver == null || !saver.interactive()) return super.dispatchKeyEvent(e);
        if (!saver.key(e) && exitKey.ends(e)) finish();
        return true;
    }
}
