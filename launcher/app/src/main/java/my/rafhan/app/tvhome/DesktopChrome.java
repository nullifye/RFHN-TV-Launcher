package my.rafhan.app.tvhome;

import android.annotation.SuppressLint;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How Live TV's browser presents itself to the channel sites (RTMKlik and Tonton alike): Google
 * Chrome on Windows 11, x64, the same major version as the TV's WebView (so what the sites expect
 * of that version is what the engine does). Tonton plays only on the computers it supports and
 * shows an "install our app" page to Android; both sites get the same identity.
 *
 * The user agent string is Chrome's reduced one: Windows is always "Windows NT 10.0" there and the
 * version is "major.0.0.0". Windows 11 shows only in the Client Hints (platform version 13 or
 * more; 19.0.0 is Windows 11 24H2 and 25H2).
 */
final class DesktopChrome {
    private DesktopChrome() {}

    static final String PLATFORM_VERSION = "19.0.0";
    private static final Pattern CHROME = Pattern.compile("Chrome/(\\d+)");

    /** The WebView's Chrome major version from its own user agent, or "120" if it isn't there. */
    static String major(String webViewAgent) {
        Matcher m = CHROME.matcher(webViewAgent == null ? "" : webViewAgent);
        return m.find() ? m.group(1) : "120";
    }

    /** Chrome's user agent on Windows, at the WebView's major version. */
    static String userAgent(String webViewAgent) {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + major(webViewAgent) + ".0.0.0 Safari/537.36";
    }

    /** The brand Chrome lists where the WebView lists its own ("Android WebView" is Google Chrome). */
    static String brand(String webViewBrand) {
        return "Android WebView".equals(webViewBrand) ? "Google Chrome" : webViewBrand;
    }

    // navigator.platform would say "Linux armv7l"; Chrome on Windows says "Win32" (64-bit too).
    // Defined on the prototype like the browser's own, before any of the page's scripts run.
    static final String PLATFORM_SCRIPT = "Object.defineProperty(Navigator.prototype,'platform',"
            + "{get:function(){return 'Win32';},configurable:true,enumerable:true});";

    /**
     * Applies the identity to {@code w}: its user agent, Client Hints and navigator.platform.
     * WebView also sends an X-Requested-With header naming the app on every request; Google
     * cancelled its removal and WebView offers no way to drop it.
     */
    // RestrictedApi: setFormFactors isn't public in androidx.webkit yet (kept to its own library
    // group), but WebView supports it (checked through its feature). Without it the form factor
    // hint says "Mobile", contradicting the rest. If a webkit update changes it, the build breaks.
    @SuppressLint("RestrictedApi")
    static void apply(WebView w) {
        WebSettings ws = w.getSettings();
        ws.setUserAgentString(userAgent(WebSettings.getDefaultUserAgent(w.getContext())));
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            UserAgentMetadata own = WebSettingsCompat.getUserAgentMetadata(ws);
            List<UserAgentMetadata.BrandVersion> brands = new ArrayList<>();
            for (UserAgentMetadata.BrandVersion b : own.getBrandVersionList()) {
                brands.add(new UserAgentMetadata.BrandVersion.Builder(b).setBrand(brand(b.getBrand())).build());
            }
            UserAgentMetadata.Builder windows = new UserAgentMetadata.Builder(own)
                    .setBrandVersionList(brands).setMobile(false).setPlatform("Windows")
                    .setPlatformVersion(PLATFORM_VERSION).setArchitecture("x86").setBitness(64)
                    .setWow64(false).setModel("");
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
                windows.setFormFactors(Collections.singletonList("Desktop"));
            }
            WebSettingsCompat.setUserAgentMetadata(ws, windows.build());
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(w, PLATFORM_SCRIPT, Collections.singleton("*"));
        }
    }
}
