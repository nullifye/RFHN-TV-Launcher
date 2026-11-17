package my.rafhan.app.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DesktopChromeTest {
    private static final String WEBVIEW = "Mozilla/5.0 (Linux; Android 10; MiTV-MOOQ0 Build/QTG3; wv) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/159.0.8154.4 Mobile Safari/537.36";

    @Test
    public void sitesSeeChromeOnWindowsWithTheReducedVersion() {
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/159.0.0.0 Safari/537.36", DesktopChrome.userAgent(WEBVIEW));
    }

    @Test
    public void versionFollowsTheWebView() {
        assertEquals("160", DesktopChrome.major(WEBVIEW.replace("159.0.8154.4", "160.0.8200.1")));
        assertEquals("120", DesktopChrome.major("Mozilla/5.0 (Linux)")); // no version found
        assertEquals("120", DesktopChrome.major(null));
    }

    @Test
    public void webViewBrandBecomesGoogleChrome() {
        assertEquals("Google Chrome", DesktopChrome.brand("Android WebView"));
        assertEquals("Chromium", DesktopChrome.brand("Chromium"));
        assertEquals("Not_A Brand", DesktopChrome.brand("Not_A Brand"));
    }

    @Test
    public void windows11PlatformVersion() {
        assertEquals(true, Integer.parseInt(DesktopChrome.PLATFORM_VERSION.split("\\.")[0]) >= 13);
    }
}
