package my.rafhan.app.tvhome;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings → Live TV → Sign in to Tonton: Tonton's own sign-in page, full screen, driven with the
 * remote and the on-screen keyboard. TV Home never reads what's typed; it only notices when the
 * page holds a login token, then closes. The session stays in the app's WebView storage, which
 * Live TV's player shares.
 *
 * With {@link #SIGN_OUT}: signs out instead, behind a "Signing out" cover. Tonton's own /logout
 * first (so Tonton ends the session too), then its storage is cleared from inside its pages: the
 * login token is in localStorage, which WebStorage.deleteOrigin doesn't clear.
 */
public final class TontonSignInActivity extends Activity {
    static final String SIGN_OUT = "sign_out";
    private static final long CHECK_MS = 2000, LOGOUT_MS = 10_000;
    // Pages on each Tonton origin to clear its storage from (any page there will do).
    private static final String[] CLEAR_AT = {TontonAccount.ORIGIN + "/manifest.json", "https://id.tonton.com.my/robots.txt"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private WebView web, popup;
    private Dialog popupWindow;
    private boolean done, signingOut;
    private int clearing = -1; // sign-out: which of CLEAR_AT is loading (-1: still logging out)
    private long logoutUntil;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        try {
            web = new WebView(this);
        } catch (RuntimeException e) { // WebView disabled or being updated
            Toast.makeText(this, "Signing in needs Android System WebView enabled and up to date",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setUp(web);
        CookieManager.getInstance().setAcceptCookie(true);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView hint = new TextView(this);
        hint.setText("Sign in to Tonton  ·  BACK to close");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(12);
        hint.setTypeface(Fonts.outfit(this, 500));
        hint.setBackgroundColor(0x99000000);
        hint.setPadding(24, 10, 24, 10);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        root.addView(hint, hp);
        signingOut = getIntent().getBooleanExtra(SIGN_OUT, false);
        if (signingOut) {
            TextView cover = new TextView(this); // Tonton's pages stay hidden while it signs out
            cover.setText("Signing out of Tonton…");
            cover.setTextColor(0xCCFFFFFF);
            cover.setTextSize(18);
            cover.setTypeface(Fonts.outfit(this, 400));
            cover.setGravity(Gravity.CENTER);
            cover.setBackgroundColor(0xFF070A10);
            root.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            hint.setVisibility(android.view.View.GONE);
        }
        setContentView(root);
        web.requestFocus();
        if (signingOut) {
            logoutUntil = android.os.SystemClock.elapsedRealtime() + LOGOUT_MS;
            web.loadUrl(TontonAccount.ORIGIN + "/logout");
            main.postDelayed(signOutStep, CHECK_MS);
        } else {
            web.loadUrl(TontonAccount.SIGN_IN);
            main.postDelayed(check, CHECK_MS);
        }
    }

    // Sign-out: wait for Tonton's /logout to drop the token (or give it LOGOUT_MS), then clear the
    // storage of each Tonton origin from a page there, then forget the rest and close.
    private final Runnable signOutStep = new Runnable() {
        @Override public void run() {
            if (web == null || done) return;
            if (clearing < 0) {
                web.evaluateJavascript("location.host==='watch.tonton.com.my'&&" + TontonAccount.HAS_TOKEN, v -> {
                    boolean stillIn = !"false".equals(v);
                    if (stillIn && android.os.SystemClock.elapsedRealtime() < logoutUntil) {
                        main.postDelayed(this, 1000);
                        return;
                    }
                    clearing = 0;
                    web.loadUrl(CLEAR_AT[0]);
                    main.postDelayed(this, 1500);
                });
                return;
            }
            web.evaluateJavascript("try{localStorage.clear();sessionStorage.clear();}catch(e){}location.host", v -> {
                clearing++;
                if (clearing < CLEAR_AT.length) {
                    web.loadUrl(CLEAR_AT[clearing]);
                    main.postDelayed(this, 1500);
                    return;
                }
                done = true;
                TontonAccount.signOut(TontonSignInActivity.this); // cookies, storage, the saved state
                Toast.makeText(TontonSignInActivity.this, "Signed out of Tonton", Toast.LENGTH_SHORT).show();
                finish();
            });
        }
    };

    /**
     * Same settings for the page and its sign-in popup. Tonton signs in through a popup window
     * whose result goes back to the page that opened it, so popups open as a second WebView on
     * top (opened in place, the page that's waiting for the result would be gone).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void setUp(WebView w) {
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        TontonAccount.identify(w, true, null);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true); // the sign-in host's session
        w.setFocusable(true);
        w.setFocusableInTouchMode(true);
        w.setWebViewClient(new WebViewClient() {
            // Tonton's pages and its sign-in providers stay in here; nothing but https.
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
                Uri u = r.getUrl();
                return !"https".equals(u.getScheme());
            }

            // The remote (and so the keyboard) belongs to the window on top.
            @Override public void onPageFinished(WebView view, String url) {
                if (view == (popup != null ? popup : web)) view.requestFocus();
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            // The popup gets a window of its own (a full-screen dialog): the keyboard binds to the
            // focused window, and in the same window as the page it never showed for the popup.
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture, Message result) {
                closePopup();
                popup = new WebView(TontonSignInActivity.this);
                setUp(popup);
                popupWindow = new Dialog(TontonSignInActivity.this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                popupWindow.setContentView(popup);
                popupWindow.setOnKeyListener((d, code, e) -> {
                    if (code != KeyEvent.KEYCODE_BACK || e.getAction() != KeyEvent.ACTION_UP) return false;
                    closePopup(); // Back: out of the sign-in popup, to Tonton's page
                    return true;
                });
                popupWindow.setOnDismissListener(d -> closePopup());
                popupWindow.show();
                ((WebView.WebViewTransport) result.obj).setWebView(popup);
                result.sendToTarget();
                popup.requestFocus();
                return true;
            }

            @Override public void onCloseWindow(WebView window) {
                if (window == popup) closePopup();
            }
        });
    }

    private void closePopup() {
        if (popup == null) return;
        WebView p = popup;
        Dialog d = popupWindow;
        popup = null;
        popupWindow = null;
        if (d != null) {
            d.setOnDismissListener(null);
            d.dismiss();
        }
        p.destroy();
        if (web != null) web.requestFocus();
    }

    // Signed in once the page holds a login token: remember it, keep the session, close.
    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (web == null || done) return;
            web.evaluateJavascript(TontonAccount.HAS_TOKEN, v -> {
                if (done) return;
                if ("true".equals(v)) {
                    done = true;
                    TontonAccount.setSignedIn(TontonSignInActivity.this, true);
                    CookieManager.getInstance().flush();
                    Toast.makeText(TontonSignInActivity.this, "Signed in to Tonton", Toast.LENGTH_SHORT).show();
                    main.postDelayed(TontonSignInActivity.this::finish, 1200);
                } else {
                    main.postDelayed(this, CHECK_MS);
                }
            });
        }
    };

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Back closes the screen (the page's own history isn't a way out for a remote).
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            if (!signingOut) finish(); // signing out finishes by itself, in a few seconds
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        closePopup();
        CookieManager.getInstance().flush();
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
