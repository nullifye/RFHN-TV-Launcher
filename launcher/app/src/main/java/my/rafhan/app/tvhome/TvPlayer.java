package my.rafhan.app.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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

import androidx.webkit.UserAgentMetadata;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.Objects;

import my.rafhan.app.tvhome.TvStartupProgress.Stage;

/**
 * Plays a live channel with its provider's own web player, in a WebView cropped to its video.
 * The channels are DRM-protected, so the licence comes through the TV's Widevine like in a
 * browser; no stream URLs or keys are handled here. The WebView is prepared while the Live TV
 * screen is open (no channel page loads until OK), and one is reused across channel changes.
 */
final class TvPlayer {
    static final int CONNECTING = 0, PLAYING = 1, FAILED = 2, SIGN_IN = 3;

    interface Listener {
        /** {@code message} is the startup stage for CONNECTING, or an explanation for FAILED. */
        void onState(int state, String message);
    }

    private static final long GIVE_UP_MS = 45_000;
    private static final String TAG = "TvPlayer";
    // How often the page is checked: often while it starts, rarely once the picture plays.
    private static final long POLL_STARTING_MS = 250, POLL_PLAYING_MS = 5000;

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
    private static final String SNAPSHOT = "(function(cap,expected){"
            + "if(expected&&location.origin+location.pathname!==expected)return JSON.stringify({playing:false});"
            + "if(!document.getElementById('tvhome-crop')){var s=document.createElement('style');"
            + "s.id='tvhome-crop';s.textContent=" + JSONObject.quote(CSS) + ";"
            + "document.documentElement.appendChild(s);}"
            + "var g=window.gluePlayer,p=g&&g.player,v=document.querySelector('#vidBox video');"
            + "var on=false;try{on=!!p&&p.isPlaying();}catch(e){}"
            + "if(p&&!on&&Date.now()-(window.__tvhomePlayAt||0)>=1000){window.__tvhomePlayAt=Date.now();"
            + "try{p.unmute();}catch(e){}try{var r=p.play();if(r&&r.catch)r.catch(function(){});}catch(e){}}"
            + "var bad=window.__tvhomeBad||(window.__tvhomeBad={}),shape=true;"
            + "if(p){try{var d=null;try{d=p.getPlaybackVideoData();}catch(e){}"
            + "if(on&&d&&d.id&&v&&v.videoWidth&&v.videoHeight){var a=v.videoWidth/v.videoHeight;"
            + "if(a<1.2||a>2.4){bad[d.id]=1;shape=false;}}"
            + "var q=(p.getAvailableVideoQualities()||[]).filter(function(x){return !bad[x.id];})"
            + ".sort(function(x,y){return (x.height||0)-(y.height||0);});"
            + "var want='auto',pick=null,i;"
            + "if(cap>0){for(i=0;i<q.length;i++)if((q[i].height||0)>=cap){pick=q[i];break;}pick=pick||q[q.length-1];}"
            + "else if(d&&bad[d.id]){for(i=0;i<q.length;i++)if((q[i].height||0)>(d.height||0)){pick=q[i];break;}"
            + "pick=pick||q[q.length-1];}"
            + "if(pick)want=pick.id;"
            + "var cur=p.getVideoQuality();if((!cur||cur.id!==want)&&(window.__tvhomeQuality!==want||"
            + "Date.now()-(window.__tvhomeQualityAt||0)>=2000)){p.setVideoQuality(want);"
            + "window.__tvhomeQuality=want;window.__tvhomeQualityAt=Date.now();}}catch(e){}}"
            + "return JSON.stringify({player:!!p,video:!!v,readyState:v?v.readyState:0,"
            + "playing:on&&shape&&!!v&&v.readyState>=3,"
            + "error:v&&v.error?v.error.code:0});})";

    // Tonton (TV3, NTV7, 8TV, TV9, …): its Shaka player in #player-container, cropped the same way.
    private static final String TONTON_CSS = "html,body{background:#000!important;overflow:hidden!important}"
            + "body *{visibility:hidden!important}"
            + "#player-container,#player-container *{visibility:visible!important}"
            + "#player-container{position:fixed!important;left:0!important;top:0!important;width:100vw!important;"
            + "height:100vh!important;max-width:none!important;margin:0!important;z-index:2147483647!important;"
            + "background:#000!important}"
            + "#player-container video{width:100%!important;height:100%!important;object-fit:contain!important}"
            + "#player-container .shaka-controls-container,#player-container .overlay,"
            + "#player-container .shaka-spinner-container{display:none!important}";
    // Starts and unmutes the video; picks the stream like RTMKlik's (smallest rendition at least as
    // tall as the view, never one that comes out the wrong shape; Shaka's own choice for 0); and
    // reports whether the picture plays, or that Tonton wants a sign-in.
    private static final String TONTON_SNAPSHOT = "(function(cap,expected){"
            + "if(location.origin==='https://watch.tonton.com.my'&&/^\\/login|^\\/signin/.test(location.pathname))"
            + "return JSON.stringify({signIn:true});"
            + "if(expected&&location.origin+location.pathname!==expected)return JSON.stringify({playing:false});"
            + "if(!document.getElementById('tvhome-crop')){var s=document.createElement('style');"
            + "s.id='tvhome-crop';s.textContent=" + JSONObject.quote(TONTON_CSS) + ";"
            + "document.documentElement.appendChild(s);}"
            + "var v=document.querySelector('#player-container video')||document.querySelector('video'),p=window.player;"
            + "if(v){if(v.muted)v.muted=false;if(v.paused&&Date.now()-(window.__tvhomePlayAt||0)>=1000){"
            + "window.__tvhomePlayAt=Date.now();var r=v.play();if(r&&r.catch)r.catch(function(){});}}"
            + "var on=!!v&&!v.paused&&v.readyState>=3,shape=true,bad=window.__tvhomeBad||(window.__tvhomeBad={});"
            + "if(p&&p.getVariantTracks){try{var t=p.getVariantTracks(),cur=null,i;"
            + "for(i=0;i<t.length;i++)if(t[i].active)cur=t[i];"
            + "if(on&&cur&&v.videoWidth&&v.videoHeight){var a=v.videoWidth/v.videoHeight;if(a<1.2||a>2.4){bad[cur.id]=1;shape=false;}}"
            + "var q=t.filter(function(x){return !bad[x.id];}).sort(function(x,y){return (x.height||0)-(y.height||0);}),pick=null;"
            + "if(cap>0){for(i=0;i<q.length;i++)if((q[i].height||0)>=cap){pick=q[i];break;}pick=pick||q[q.length-1];}"
            + "else if(cur&&bad[cur.id]){for(i=0;i<q.length;i++)if((q[i].height||0)>(cur.height||0)){pick=q[i];break;}"
            + "pick=pick||q[q.length-1];}"
            + "if(pick){if(p.getConfiguration().abr.enabled)p.configure({abr:{enabled:false}});"
            + "if((!cur||cur.id!==pick.id)&&(window.__tvhomeQuality!==pick.id||"
            + "Date.now()-(window.__tvhomeQualityAt||0)>=2000)){p.selectVariantTrack(pick,true);"
            + "window.__tvhomeQuality=pick.id;window.__tvhomeQualityAt=Date.now();}}"
            + "else if(cap===0&&!p.getConfiguration().abr.enabled){p.configure({abr:{enabled:true}});"
            + "window.__tvhomeQuality=null;}"
            + "}catch(e){}}"
            + "return JSON.stringify({player:!!p,video:!!v,readyState:v?v.readyState:0,"
            + "playing:on&&shape,error:v&&v.error?v.error.code:0});})";

    private final Context ctx;
    private final FrameLayout host;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TvStartupProgress startup = new TvStartupProgress();
    private WebView browser;
    private String url;
    private LiveChannels.Site site;
    private LiveChannels.Site identitySite;
    private UserAgentMetadata defaultMetadata; // the WebView's own Client Hints, before identify()
    private long deadline, startedAt;
    private String channelSlug;
    private String stateMessage;
    private int generation, pollSerial, state = -1, viewHeight, startupPhases;

    TvPlayer(Context ctx, FrameLayout host, Listener listener) {
        this.ctx = ctx;
        this.host = host;
        this.listener = listener;
    }

    /** Warm the browser engine while browsing; no station, video, or licence is requested yet. */
    void prepare() {
        if (browser != null) return;
        long start = SystemClock.elapsedRealtime();
        if (create()) Log.i(TAG, "Browser prepared in " + (SystemClock.elapsedRealtime() - start) + " ms");
    }

    /** Starts {@code channel}, replacing whatever was playing. */
    void play(LiveChannels.Channel channel) {
        String page = channel.page();
        if (!LiveChannels.isChannelPage(page)) return;
        site = channel.site;
        if (page.equals(url) && browser != null && state != FAILED && state != SIGN_IN) return;
        generation++;
        main.removeCallbacks(poll);
        main.removeCallbacks(watchdog);
        startedAt = SystemClock.elapsedRealtime();
        startupPhases = 0;
        startup.reset(channel.name);
        channelSlug = channel.slug;
        trace("tune requested");
        url = page;
        if (browser == null && !create()) return;
        if (identitySite != site) {
            identify(site == LiveChannels.Site.TONTON);
            identitySite = site;
        }
        setState(CONNECTING, startup.label());
        deadline = SystemClock.elapsedRealtime() + GIVE_UP_MS;
        main.postDelayed(watchdog, GIVE_UP_MS);
        browser.loadUrl(page);
        checkSoon();
    }

    /**
     * The view's height in px: the stream used is the smallest rendition at least this tall. 0
     * leaves the choice to the player (full screen). Applied straight away while playing.
     */
    void setViewHeight(int px) {
        if (px == viewHeight) return;
        viewHeight = px;
        if (browser != null && url != null && state != FAILED) {
            checkSoon();
        }
    }

    /** Stops playback and frees the browser and its memory. */
    void release() {
        generation++;
        main.removeCallbacks(poll);
        main.removeCallbacks(watchdog);
        url = null;
        state = -1;
        stateMessage = null;
        if (browser != null) {
            host.removeView(browser);
            browser.stopLoading();
            browser.loadUrl("about:blank");
            browser.destroy();
            browser = null;
            defaultMetadata = null; // a new WebView next time: read its own again
            identitySite = null;
        }
    }

    /** Tonton's pages see a desktop browser (TontonAccount.identify); other sites the WebView as it is. */
    private void identify(boolean desktop) {
        defaultMetadata = TontonAccount.identify(browser, desktop, defaultMetadata);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private boolean create() {
        try {
            browser = new WebView(ctx);
        } catch (RuntimeException e) { // WebView disabled or being updated
            browser = null;
            defaultMetadata = null;
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
            // The player asks for the DRM system; allow only that, for the selected provider.
            @Override public void onPermissionRequest(PermissionRequest r) {
                Uri o = r.getOrigin();
                LiveChannels.Site s = site;
                boolean ours = o != null && s != null && "https".equals(o.getScheme()) && s.host.equals(o.getHost());
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
            @Override public void onPageCommitVisible(WebView view, String page) {
                if (!page.equals(url)) return;
                trace("page visible");
                progress(Stage.PAGE_VISIBLE);
                checkSoon();
            }
            @Override public void onPageFinished(WebView view, String page) {
                if (!page.equals(url)) return;
                trace("page loaded");
                progress(Stage.PAGE_LOADED);
                checkSoon();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
                String to = r.getUrl().toString();
                if (to.equals(url)) return false;
                // Never navigate away from the channel. Tonton sends a signed-out viewer to sign in.
                if (site == LiveChannels.Site.TONTON && TontonAccount.isSignInPage(r.getUrl())) needsSignIn();
                return true;
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame() && r.getUrl().toString().equals(url))
                    fail("Couldn't reach " + site.label + ". Check the connection");
            }
        });
        host.addView(browser, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return true;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (browser == null || url == null || state == FAILED || state == SIGN_IN) return;
            int gen = generation, serial = ++pollSerial;
            String script = site == LiveChannels.Site.TONTON ? TONTON_SNAPSHOT : SNAPSHOT;
            browser.evaluateJavascript(script + "(" + viewHeight + "," + JSONObject.quote(url) + ")", value -> {
                if (gen != generation || serial != pollSerial || browser == null) return;
                boolean playing = false;
                int error = 0;
                try {
                    JSONObject o = new JSONObject((String) new JSONTokener(value).nextValue());
                    startupPhase(o.optBoolean("player"), 1, Stage.PLAYER_READY);
                    startupPhase(o.optBoolean("video"), 2, Stage.VIDEO_ATTACHED);
                    startupPhase(o.optInt("readyState") >= 1, 4, Stage.MEDIA_METADATA_READY);
                    if (o.optBoolean("signIn")) {
                        needsSignIn();
                        return;
                    }
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
                    // Playing a Tonton channel proves the session (Settings shows it signed in).
                    if (site == LiveChannels.Site.TONTON && state != PLAYING) TontonAccount.setSignedIn(ctx, true);
                    setState(PLAYING, null);
                } else if (SystemClock.elapsedRealtime() > deadline) {
                    fail("Not on air right now. Try again later");
                    return;
                }
                main.removeCallbacks(this);
                main.postDelayed(this, playing ? POLL_PLAYING_MS : POLL_STARTING_MS);
            });
        }
    };

    private void checkSoon() {
        if (browser == null || url == null || state == FAILED || state == SIGN_IN) return;
        // Navigation can discard an evaluateJavascript callback. A new document must be able
        // to restart checks without waiting for that callback, while older results are ignored.
        pollSerial++;
        main.removeCallbacks(poll);
        main.post(poll);
    }

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (browser == null || url == null || state == FAILED || state == SIGN_IN) return;
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) fail("Playback took too long. Check the connection or try again");
            else main.postDelayed(this, remaining);
        }
    };

    private void startupPhase(boolean ready, int bit, Stage stage) {
        if (!ready || (startupPhases & bit) != 0) return;
        startupPhases |= bit;
        trace(stage.label);
        progress(stage);
    }

    private void progress(Stage stage) {
        if (state == CONNECTING && startup.advance(stage)) setState(CONNECTING, startup.label());
    }

    /** Log only channel slugs and timings, never stream URLs, cookies, tokens, or DRM data. */
    private void trace(String event) {
        if (channelSlug != null) Log.i(TAG, channelSlug + " +"
                + (SystemClock.elapsedRealtime() - startedAt) + " ms: " + event);
    }

    /** Tonton wants a sign-in: stop, and say so (the sign-in itself is in Settings → Live TV). */
    private void needsSignIn() {
        generation++;
        main.removeCallbacks(poll);
        main.removeCallbacks(watchdog);
        if (browser != null) {
            browser.stopLoading();
            browser.loadUrl("about:blank");
        }
        TontonAccount.setSignedIn(ctx, false);
        setState(SIGN_IN, null);
    }

    /** Keeps the browser (a retry reuses it) but stops this attempt. */
    private void fail(String why) {
        generation++;
        main.removeCallbacks(poll);
        main.removeCallbacks(watchdog);
        if (browser != null) {
            browser.stopLoading();
            browser.loadUrl("about:blank");
        }
        setState(FAILED, why);
    }

    private void setState(int s, String message) {
        if (s == state && s != FAILED && s != SIGN_IN && Objects.equals(message, stateMessage)) return;
        if (s == PLAYING) trace("playing");
        else if (s == FAILED || s == SIGN_IN) trace(s == FAILED ? "failed" : "sign-in required");
        state = s;
        stateMessage = message;
        listener.onState(s, message);
    }
}
