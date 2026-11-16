package my.rafhan.app.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Plays an RTMKlik live channel with RTMKlik's own web player, in a WebView cropped to its video.
 * The channels are DRM-protected, so the licence comes through the TV's Widevine like in a
 * browser; no stream URLs or keys are handled here. The WebView exists only between
 * {@link #play} and {@link #release}, and one is reused across channel changes.
 */
final class TvPlayer {
    static final int CONNECTING = 0, PLAYING = 1, FAILED = 2;

    interface Listener {
        /** {@code message} explains FAILED; otherwise null. */
        void onState(int state, String message);
    }

    private static final String HOST = "rtmklik.rtm.gov.my";
    private static final long GIVE_UP_MS = 45_000;
    // How often the page is checked: often while it starts, rarely once the picture plays.
    private static final long POLL_STARTING_MS = 2000, POLL_PLAYING_MS = 5000;

    // Show only the player, filling the view; hide its on-screen controls (unusable with a
    // remote here). Its ads still show.
    private static final String CSS = "html,body{background:#000!important;overflow:hidden!important}"
            + "body *{visibility:hidden!important}"
            + "#vidBox,#vidBox *{visibility:visible!important}"
            + "#vidBox{position:fixed!important;left:0!important;top:0!important;width:100vw!important;"
            + "height:100vh!important;max-width:none!important;margin:0!important;border-radius:0!important;"
            + "z-index:2147483647!important;background:#000!important}"
            + "#vidBox .bmpui-ui-uicontainer,#vidBox .glue-settings-panel{display:none!important}";
    // Crops the page, starts the player through its own API (it loads paused and muted), picks
    // the stream (argument: the view's height in px, 0 for the player's own choice), and reports
    // whether the picture is playing.
    // The pick: the smallest rendition at least as tall as the view. The channels default to
    // 1080p at ~3 Mbps, which a small preview fetches, decrypts and decodes for nothing.
    // A rendition whose picture comes out the wrong shape is never used again: RTM's 480p one
    // reports 854x11712 on this TV and shows as a thin strip down the middle of the poster. If
    // the player's own choice (full screen) lands on such a one, the next taller one is used.
    private static final String SNAPSHOT = "(function(cap){"
            + "if(!document.getElementById('tvhome-crop')){var s=document.createElement('style');"
            + "s.id='tvhome-crop';s.textContent=" + JSONObject.quote(CSS) + ";"
            + "document.documentElement.appendChild(s);}"
            + "var g=window.gluePlayer,p=g&&g.player,v=document.querySelector('#vidBox video');"
            + "var on=false;try{on=!!p&&p.isPlaying();}catch(e){}"
            + "if(p&&!on){try{p.unmute();}catch(e){}try{var r=p.play();if(r&&r.catch)r.catch(function(){});}catch(e){}}"
            + "var bad=window.__tvhomeBad||(window.__tvhomeBad={}),shape=true;"
            + "if(p&&on){try{var d=p.getPlaybackVideoData();"
            + "if(d&&d.id&&v&&v.videoWidth&&v.videoHeight){var a=v.videoWidth/v.videoHeight;"
            + "if(a<1.2||a>2.4){bad[d.id]=1;shape=false;}}"
            + "var q=(p.getAvailableVideoQualities()||[]).filter(function(x){return !bad[x.id];})"
            + ".sort(function(x,y){return (x.height||0)-(y.height||0);});"
            + "var want='auto',pick=null,i;"
            + "if(cap>0){for(i=0;i<q.length;i++)if((q[i].height||0)>=cap){pick=q[i];break;}pick=pick||q[q.length-1];}"
            + "else if(d&&bad[d.id]){for(i=0;i<q.length;i++)if((q[i].height||0)>(d.height||0)){pick=q[i];break;}"
            + "pick=pick||q[q.length-1];}"
            + "if(pick)want=pick.id;"
            + "var cur=p.getVideoQuality();if(!cur||cur.id!==want)p.setVideoQuality(want);}catch(e){}}"
            + "return JSON.stringify({playing:on&&shape&&!!v&&v.readyState>=3,"
            + "error:v&&v.error?v.error.code:0});})";

    private final Context ctx;
    private final FrameLayout host;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView browser;
    private String url;
    private long deadline;
    private int generation, state = -1, viewHeight;

    TvPlayer(Context ctx, FrameLayout host, Listener listener) {
        this.ctx = ctx;
        this.host = host;
        this.listener = listener;
    }

    /** Starts {@code page} (an RTMKlik channel page), replacing whatever was playing. */
    void play(String page) {
        if (!LiveChannels.isChannelPage(page)) return;
        if (page.equals(url) && browser != null && state != FAILED) return;
        generation++;
        main.removeCallbacks(poll);
        url = page;
        if (browser == null && !create()) return;
        setState(CONNECTING, null);
        deadline = SystemClock.elapsedRealtime() + GIVE_UP_MS;
        browser.loadUrl(page);
        main.postDelayed(poll, POLL_STARTING_MS);
    }

    /**
     * The view's height in px: the stream used is the smallest rendition at least this tall. 0
     * leaves the choice to the player (full screen). Applied straight away while playing.
     */
    void setViewHeight(int px) {
        if (px == viewHeight) return;
        viewHeight = px;
        if (browser != null && url != null && state != FAILED) {
            main.removeCallbacks(poll);
            main.post(poll);
        }
    }

    /** Stops playback and frees the browser and its memory. */
    void release() {
        generation++;
        main.removeCallbacks(poll);
        url = null;
        state = -1;
        if (browser != null) {
            host.removeView(browser);
            browser.stopLoading();
            browser.loadUrl("about:blank");
            browser.destroy();
            browser = null;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private boolean create() {
        try {
            browser = new WebView(ctx);
        } catch (RuntimeException e) { // WebView disabled or being updated
            browser = null;
            setState(FAILED, "Live TV needs Android System WebView enabled and up to date");
            return false;
        }
        WebSettings s = browser.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true); // onCreateWindow isn't handled: popups cannot open
        browser.setBackgroundColor(Color.BLACK);
        browser.setFocusable(false); // the remote stays with the launcher
        browser.setFocusableInTouchMode(false);
        browser.setVerticalScrollBarEnabled(false);
        browser.setHorizontalScrollBarEnabled(false);
        browser.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        browser.setWebChromeClient(new WebChromeClient() {
            // The player asks for the DRM system; allow only that, and only for RTMKlik.
            @Override public void onPermissionRequest(PermissionRequest r) {
                Uri o = r.getOrigin();
                boolean ours = o != null && "https".equals(o.getScheme()) && HOST.equals(o.getHost());
                for (String res : r.getResources()) {
                    if (ours && PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) {
                        r.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                        return;
                    }
                }
                r.deny();
            }
        });
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
                return !r.getUrl().toString().equals(url); // never navigate away from the channel
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && url != null) fail("Couldn't reach RTMKlik. Check the connection");
            }
        });
        host.addView(browser, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return true;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (browser == null) return;
            int gen = generation;
            browser.evaluateJavascript(SNAPSHOT + "(" + viewHeight + ")", value -> {
                if (gen != generation || browser == null) return;
                boolean playing = false;
                int error = 0;
                try {
                    JSONObject o = new JSONObject((String) new JSONTokener(value).nextValue());
                    playing = o.optBoolean("playing");
                    error = o.optInt("error");
                } catch (Exception ignored) { // page still loading
                }
                if (error != 0) {
                    fail("This channel couldn't play (error " + error + ")");
                    return;
                }
                if (playing) {
                    deadline = SystemClock.elapsedRealtime() + GIVE_UP_MS;
                    setState(PLAYING, null);
                } else if (SystemClock.elapsedRealtime() > deadline) {
                    fail("Not on air right now. Try again later");
                    return;
                }
                main.postDelayed(this, playing ? POLL_PLAYING_MS : POLL_STARTING_MS);
            });
        }
    };

    /** Keeps the browser (a retry reuses it) but stops this attempt. */
    private void fail(String why) {
        generation++;
        main.removeCallbacks(poll);
        if (browser != null) {
            browser.stopLoading();
            browser.loadUrl("about:blank");
        }
        setState(FAILED, why);
    }

    private void setState(int s, String message) {
        if (s == state && s != FAILED) return;
        state = s;
        listener.onState(s, message);
    }
}
