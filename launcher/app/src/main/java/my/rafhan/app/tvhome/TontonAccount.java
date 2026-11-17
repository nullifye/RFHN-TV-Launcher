package my.rafhan.app.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebStorage;


/**
 * The Tonton account the Tonton channels play with. Signing in happens on Tonton's own page
 * (TontonSignInActivity); TV Home never sees the password. The session lives in the WebView's
 * cookies and storage, shared by every WebView in the app, so Live TV's player uses it too.
 */
final class TontonAccount {
    static final String ORIGIN = "https://watch.tonton.com.my";
    static final String SIGN_IN = ORIGIN + "/login";
    private static final String PREFS = "live_tv", SIGNED_IN = "tonton_signed_in";
    // The cookies' domains: the site's own and its parent.
    private static final String[] COOKIE_URLS = {ORIGIN, "https://tonton.com.my"};

    /** True when the page holds a Tonton login token (MediaHQ keeps it in SHARED_DEVICE.loginToken). */
    static final String HAS_TOKEN = "(function(){try{var d=JSON.parse(localStorage.getItem('SHARED_DEVICE')||'{}');"
            + "return typeof d.loginToken==='string'&&d.loginToken.length>8;}catch(e){return false;}})()";

    private TontonAccount() {}

    /** Tonton's sign-in (its id.tonton.com.my host, or the site's /login page). */
    static boolean isSignInPage(android.net.Uri u) {
        String host = u.getHost(), path = u.getPath() == null ? "" : u.getPath();
        return "id.tonton.com.my".equals(host)
                || ("watch.tonton.com.my".equals(host) && (path.startsWith("/login") || path.startsWith("/signin")));
    }

    static boolean signedIn(Context ctx) {
        return prefs(ctx).getBoolean(SIGNED_IN, false);
    }

    static void setSignedIn(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(SIGNED_IN, on).apply();
    }

    /**
     * Forgets what's left of the session: cookies, storage the WebView can delete, and the saved
     * state. Tonton's login token is in its localStorage, which only a page on its site can clear:
     * signing out goes through TontonSignInActivity's sign-out, which clears it and then calls this.
     */
    static void signOut(Context ctx) {
        WebStorage.getInstance().deleteOrigin(ORIGIN);
        CookieManager cm = CookieManager.getInstance();
        for (String url : COOKIE_URLS) {
            String all = cm.getCookie(url);
            if (all == null) continue;
            for (String part : all.split(";")) {
                String name = part.split("=", 2)[0].trim();
                if (name.isEmpty()) continue;
                cm.setCookie(url, name + "=; Max-Age=0; Path=/");
                cm.setCookie(url, name + "=; Max-Age=0; Path=/; Domain=.tonton.com.my");
            }
        }
        cm.flush();
        setSignedIn(ctx, false);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
