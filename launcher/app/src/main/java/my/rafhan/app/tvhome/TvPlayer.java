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
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

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
    // After a switch inside the page (RTM_SWITCH), the previous channel's video is reported
    // "stale" until it's gone, so its picture is never taken for the new channel's.
    private static final String SNAPSHOT = "(function(cap,expected){"
            + "if(expected&&location.origin+location.pathname!==expected)return JSON.stringify({playing:false});"
            + "var sv=document.querySelector('#vidBox video');"
            + "if(sv&&sv===window.__tvhomeOldVideo&&(!sv.currentSrc||sv.currentSrc===window.__tvhomeOldSrc))"
            + "return JSON.stringify({playing:false,stale:true});"
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
            + "var sv=document.querySelector('#player-container video')||document.querySelector('video');"
            + "if(sv&&sv===window.__tvhomeOldVideo&&(!sv.currentSrc||sv.currentSrc===window.__tvhomeOldSrc))"
            + "return JSON.stringify({playing:false,stale:true});"
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

    // Changing channel within the same provider without reloading its page: the site's own router
    // shows the new channel, skipping the page's start-up (seconds on this TV). Argument: the new
    // channel's path. Returns false when the page can't switch (then it's loaded instead).
    // The playing video and its source are noted first: while that video is still there with its
    // old source or none, the snapshots report "stale" (Tonton keeps the same <video>, plays the
    // old channel for a moment, then empties it until the new stream attaches, within about a
    // second; RTMKlik replaces the <video>). Per-channel play/quality throttles start over.
    private static final String MARK = "window.__tvhomeOldVideo=v||null;window.__tvhomeOldSrc=v?v.currentSrc:null;"
            + "window.__tvhomePlayAt=0;window.__tvhomeQuality=null;window.__tvhomeQualityAt=0;";
    // RTMKlik is a Next.js app: its router is window.next.router.
    private static final String RTM_SWITCH = "(function(path){var n=window.next,r=n&&n.router;"
            + "if(location.origin!=='https://rtmklik.rtm.gov.my'||!r||typeof r.push!=='function')return false;"
            + "var v=document.querySelector('#vidBox video');" + MARK + "r.push(path);return true;})";
    // Tonton's React router follows the address on a history change (it has no channel links).
    private static final String TONTON_SWITCH = "(function(path){"
            + "if(location.origin!=='https://watch.tonton.com.my'||location.pathname.indexOf('/live/')!==0)return false;"
            + "var v=document.querySelector('#player-container video')||document.querySelector('video');" + MARK
            + "history.pushState({},'',path);dispatchEvent(new PopStateEvent('popstate',{state:{}}));return true;})";
    // A switch still stale after SWITCH_STALL_MS, or whose new stream has no media yet after
    // SWITCH_GIVE_UP_MS, loads the page instead (Tonton sometimes never starts the new stream).
    // Once the media has arrived it's left to start, as slow channels (RTM's Roll, ~7 s) do.
    // After MAX_IN_PAGE switches in a row the page is loaded afresh, so it can't build up.
    private static final long SWITCH_STALL_MS = 4000, SWITCH_GIVE_UP_MS = 8000;
    private static final int MAX_IN_PAGE = 20;

    private final Context ctx;
    private final FrameLayout host;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TvStartupProgress startup = new TvStartupProgress();
    private final AtomicInteger refused = new AtomicInteger(); // requests TvPageFilter refused this tune
    private final AtomicInteger fromCopy = new AtomicInteger(); // scripts served from TvAssetCache this tune
    private final File assets; // TvAssetCache's folder
    private volatile String userAgent; // the browser's, for TvAssetCache's fetches (read off the main thread)
    private WebView browser;
    private String url;
    private LiveChannels.Site site;
    private long deadline, startedAt;
    private long switchedAt; // when this channel was switched to inside the page; 0 when loaded
    private String channelSlug;
    private String stateMessage;
    private int generation, pollSerial, state = -1, viewHeight, startupPhases, inPageRun;

    TvPlayer(Context ctx, FrameLayout host, Listener listener) {
        this.ctx = ctx;
        this.host = host;
        this.listener = listener;
        this.assets = new File(ctx.getCacheDir(), "tv-player-scripts");
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
        // Only from a channel that plays: its page is up and its picture is known, so the
        // snapshots can tell it from the new one (one still starting may yet attach its stream).
        boolean inPage = browser != null && url != null && state == PLAYING && site == channel.site
                && inPageRun < MAX_IN_PAGE;
        site = channel.site;
        if (page.equals(url) && browser != null && state != FAILED && state != SIGN_IN) return;
        generation++;
        main.removeCallbacks(poll);
        main.removeCallbacks(watchdog);
        startedAt = SystemClock.elapsedRealtime();
        startupPhases = 0;
        refused.set(0);
        fromCopy.set(0);
        startup.reset(channel.name);
        channelSlug = channel.slug;
        trace("tune requested");
        url = page;
        if (browser == null && !create()) return;
        setState(CONNECTING, startup.label());
        deadline = SystemClock.elapsedRealtime() + GIVE_UP_MS;
        main.postDelayed(watchdog, GIVE_UP_MS);
        if (inPage) switchInPage(page);
        else load(page);
    }

    private void load(String page) {
        switchedAt = 0;
        inPageRun = 0;
        browser.loadUrl(page);
        checkSoon();
    }

    private void switchInPage(String page) {
        int gen = generation;
        String script = site == LiveChannels.Site.TONTON ? TONTON_SWITCH : RTM_SWITCH;
        browser.evaluateJavascript(script + "(" + JSONObject.quote(Uri.parse(page).getPath()) + ")", v -> {
            if (gen != generation || browser == null) return;
            if (!"true".equals(v)) {
                load(page);
                return;
            }
            switchedAt = SystemClock.elapsedRealtime();
            inPageRun++;
            trace("switched in page");
            checkSoon();
        });
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
        switchedAt = 0;
        inPageRun = 0;
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
        DesktopChrome.apply(browser); // both sites see Chrome on Windows 11
        userAgent = s.getUserAgentString();
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
            // Ad loaders, trackers and RTMKlik's hidden guide are refused at once (TvPageFilter), as
            // an ad blocker would: the players start the programme instead of waiting for them.
            // RTMKlik's player scripts come from the app's own copy (TvAssetCache).
            // Runs off the main thread.
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest r) {
                if (r.isForMainFrame()) return null;
                Uri u = r.getUrl();
                if (TvPageFilter.blocked(u.getHost(), u.getPath())) {
                    refused.incrementAndGet();
                    return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked",
                            Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
                }
                if (!"GET".equals(r.getMethod())) return null;
                WebResourceResponse kept = TvAssetCache.serve(assets, u, r.getRequestHeaders(), userAgent);
                if (kept != null) fromCopy.incrementAndGet();
                return kept;
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
                boolean playing = false, stale = false;
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
                    stale = o.optBoolean("stale");
                    error = o.optInt("error");
                } catch (Exception ignored) { // page still loading
                }
                if (switchedAt > 0 && !playing) {
                    long since = SystemClock.elapsedRealtime() - switchedAt;
                    boolean media = (startupPhases & 4) != 0; // Stage.MEDIA_METADATA_READY
                    if ((stale && since > SWITCH_STALL_MS) || (!media && since > SWITCH_GIVE_UP_MS)) {
                        trace("switch in page stalled; loading the page");
                        load(url);
                        return;
                    }
                }
                if (error != 0) {
                    fail("This channel couldn't play (error " + error + ")");
                    return;
                }
                if (playing) {
                    switchedAt = 0; // later stalls are the stream's, not the switch's
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
        if (s == PLAYING) trace("playing (" + refused.get() + " requests refused, "
                + fromCopy.get() + " scripts from the app's copy)");
        else if (s == FAILED || s == SIGN_IN) trace(s == FAILED ? "failed" : "sign-in required");
        state = s;
        stateMessage = message;
        listener.onState(s, message);
    }
}
