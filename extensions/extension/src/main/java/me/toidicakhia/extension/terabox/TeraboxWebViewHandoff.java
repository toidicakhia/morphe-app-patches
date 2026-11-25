package me.toidicakhia.extension.terabox;

import java.util.Locale;

/**
 * Keeps the WebView hand-off blocker from swallowing TeraBox's own pages.
 *
 * <p>{@code WebViewClient.r(WebView, String)} hands every URL the WebView will
 * not load itself to another app, and returning {@code true} stops that
 * hand-off. Blocking the store and browser this way is wanted, but the app's
 * own {@code terabox://} and {@code dubox://} links are not hand-offs to
 * another app: they open in-app pages such as the short-drama player, the
 * video tab and the novel reader. Blocking those too made a tap on a video
 * element on a hybrid page do nothing.
 */
@SuppressWarnings("unused")
public final class TeraboxWebViewHandoff {

    private TeraboxWebViewHandoff() {
    }

    /** True for the app's own deep links, which must keep their original handling. */
    public static boolean isInAppPage(String url) {
        if (url == null) {
            return false;
        }
        String value = url.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("terabox://") || value.startsWith("dubox://");
    }
}
