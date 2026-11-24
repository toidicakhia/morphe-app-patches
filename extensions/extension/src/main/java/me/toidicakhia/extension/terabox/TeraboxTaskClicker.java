package me.toidicakhia.extension.terabox;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps the TeraBox task-center, Gold-center and check-in pages clicking
 * themselves.
 *
 * This class is the policy: when to inject, when to pause, and how the page and
 * the HUD are kept in sync. The script itself lives in {@link TeraboxTaskScript}
 * because it is page knowledge, not app glue.
 *
 * The clicker runs while the page is in front. It pauses when an ad covers it,
 * when another Activity (a video player, a full-screen page) is on top, and
 * while the H5 answers a reward. Each pause is mirrored to the page as
 * {@code window.__tbTaskPaused}, which the script re-reads on every tick.
 */
@SuppressWarnings("unused")
public final class TeraboxTaskClicker {

    private static final String TAG = "TeraboxTaskClicker";

    /**
     * The check-in pill navigates away, so it is not pressed on every tick.
     * Read by both the counter and its preview, so it stays named.
     */
    private static final int MAX_CHECK_IN_PRESSES = 3;

    /**
     * Pages that can host the reward task list. The Gold center lives at
     * `/wap/commercial/taskcenter` and `/wap/commercial/pointcenter`, the
     * check-in at `/wap/taskcenter/signin`; older builds used other paths.
     */
    private static final String[] PAGE_URL_HINTS = {
            "taskcenter",
            "pointcenter",
            "benefit",
            "welfare",
            "reward",
            "bonus",
            "earn",
            "coin",
            "gold",
    };

    private static final Map<WebView, Boolean> ATTACHED =
            Collections.synchronizedMap(new WeakHashMap<WebView, Boolean>());

    private static final AtomicInteger CHECK_IN_PRESSES = new AtomicInteger();

    private TeraboxTaskClicker() {
    }

    /**
     * Called from the hybrid bridge setup before the page starts loading.
     * Safe to call more than once per WebView.
     */
    public static void attach(WebView webView) {
        if (webView == null) {
            return;
        }

        synchronized (ATTACHED) {
            if (ATTACHED.containsKey(webView)) {
                return;
            }
            ATTACHED.put(webView, Boolean.TRUE);
        }

        // The injected script reports presses through this name.
        webView.addJavascriptInterface(new HudBridge(webView), "tbTaskHud");
        // The closer needs the lifecycle to see splash ads, which run before
        // any task page exists.
        Activity host = TeraboxTaskHud.activityOf(webView);
        if (host != null) {
            TeraboxAdCloser.install(host.getApplication());
        }
        webView.post(new Poller(webView));
    }

    private static boolean isTaskPage(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String hint : PAGE_URL_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** Receives what the injected script pressed, and its requests. */
    private static final class HudBridge {

        private final WeakReference<WebView> webViewRef;

        HudBridge(WebView webView) {
            this.webViewRef = new WeakReference<>(webView);
        }

        @JavascriptInterface
        public void clicked(String label) {
            WebView webView = webViewRef.get();
            if (webView != null) {
                TeraboxTaskHud.clicked(webView, label);
            }
        }

        /** Why the script is not pressing anything right now. */
        @JavascriptInterface
        public void note(String reason) {
            WebView webView = webViewRef.get();
            if (webView != null) {
                TeraboxTaskHud.note(webView, reason);
            }
        }

        /**
         * The script is about to press a control that spends a rewarded ad, so
         * the closer must not treat that ad as an interstitial and cut it short.
         */
        @JavascriptInterface
        public void expectingAd() {
            TeraboxAdCloser.expectReward();
        }

        /**
         * The check-in page could not close itself through its own bridge, so
         * the host Activity is finished instead.
         */
        @JavascriptInterface
        public void closePage() {
            WebView webView = webViewRef.get();
            if (webView != null) {
                TeraboxTaskHud.finishPage(webView);
            }
        }

        /**
         * One-shot guard for the header check-in pill. The script is re-created
         * on every navigation, so this has to live on the Java side.
         */
        @JavascriptInterface
        public synchronized boolean allowCheckIn() {
            if (CHECK_IN_PRESSES.get() >= MAX_CHECK_IN_PRESSES) {
                return false;
            }
            CHECK_IN_PRESSES.incrementAndGet();
            return true;
        }

        /** Same limit, without consuming one of the presses. */
        @JavascriptInterface
        public boolean canCheckIn() {
            return CHECK_IN_PRESSES.get() < MAX_CHECK_IN_PRESSES;
        }

        /** Page outline for diagnosing a layout the script does not know yet. */
        @JavascriptInterface
        public void log(String message) {
            Log.i(TAG, "page: " + message);
        }
    }

    /** Per-WebView poll loop: keeps the page injected and the HUD current. */
    private static final class Poller implements Runnable {

        private final WeakReference<WebView> webViewRef;

        private int polls;

        private int pollsSinceInjection;

        /** Hash of the URL the script was injected for. */
        private String injectedUrl;

        /** Set once the WebView has been attached, so a detach is final. */
        private boolean attachedOnce;

        /** The script is injected once per URL, not on every poll. */
        private boolean injected;

        private TeraboxTaskHud.State state = TeraboxTaskHud.State.CLICKING;

        /** Set only once an ad has actually covered this page. */
        private boolean sawAd;

        Poller(WebView webView) {
            this.webViewRef = new WeakReference<>(webView);
        }

        @Override
        public void run() {
            WebView webView = webViewRef.get();
            if (webView == null) {
                return;
            }

            try {
                syncState(webView);
                loadScript(webView);
            } catch (Throwable throwable) {
                Log.w(TAG, "Could not inject the task clicker", throwable);
            }

            // Ten minutes of polling at 600 ms per turn.
            if (++polls >= 12_000 || isGone(webView)) {
                stop(webView);
                return;
            }

            webView.postDelayed(this, 600L);
        }

        /**
         * Works out why the clicker should be idle and mirrors that to the page
         * and to the pill shown on the ad window.
         */
        private void syncState(WebView webView) {
            boolean adOnTop = TeraboxAdCloser.isAdOnTop();
            // Also lets the closer give up on an ad whose reward callback never
            // arrived, so a failed network cannot leave the task flow hanging.
            TeraboxAdCloser.watchStuckAd();
            Activity host = TeraboxTaskHud.activityOf(webView);
            boolean otherPage = host != null && !TeraboxAdCloser.isOnTop(host);

            // `recentlyRewarded()` is global and can still be true from an ad
            // watched elsewhere. Only honour it here after an ad showed on this
            // page, otherwise opening Benefits right after a reward reports
            // "reward received" and pauses for no reason.
            if (adOnTop) {
                sawAd = true;
            } else if (!TeraboxAdCloser.recentlyRewarded()) {
                sawAd = false;
            }
            boolean holding = sawAd && TeraboxAdCloser.recentlyRewarded();

            TeraboxTaskHud.State next = adOnTop ? TeraboxTaskHud.State.WATCHING_AD
                    : otherPage ? TeraboxTaskHud.State.WAITING_FOR_PAGE
                    : holding ? TeraboxTaskHud.State.REWARD_WAIT
                    : TeraboxTaskHud.State.CLICKING;
            boolean wasPaused = state != TeraboxTaskHud.State.CLICKING;
            boolean paused = next != TeraboxTaskHud.State.CLICKING;
            if (next != state) {
                state = next;
                Log.i(TAG, (paused ? "Clicker paused: " : "Clicker resumed: ")
                        + next.label);
            }

            // Re-send the pause flag every poll in case the page reloaded; on
            // resume nudge the page instead of waiting for its next heartbeat.
            if (paused || wasPaused) {
                String flags = "window.__tbTaskPaused=" + (paused ? "1" : "0") + ";";
                if (wasPaused && !paused) {
                    flags += "window.__tbTick&&window.__tbTick();";
                }
                webView.evaluateJavascript(flags, null);
            }

            Activity adActivity = TeraboxAdCloser.currentAdActivity();
            if (adActivity != null) {
                TeraboxTaskHud.adOverlay(adActivity);
            } else {
                TeraboxTaskHud.hideAdOverlay();
            }
        }

        /** Injects the script once per URL, with a periodic keepalive. */
        private void loadScript(WebView webView) {
            String url = webView.getUrl();
            if (url == null || !isTaskPage(url)) {
                injected = false;
                pollsSinceInjection = 0;
                if (url != null && !url.equals(injectedUrl)) {
                    injectedUrl = url;
                    Log.d(TAG, "Not a task page: " + url);
                }
                TeraboxTaskHud.hide(webView);
                return;
            }

            if (!url.equals(injectedUrl)) {
                injectedUrl = url;
                injected = false;
                pollsSinceInjection = 0;
                Log.i(TAG, "Injecting the task clicker into " + url);
            }

            // The script guards itself; the keepalive only restarts it after a
            // silent reload that did not change the URL.
            if (!injected || ++pollsSinceInjection >= 12) {
                webView.evaluateJavascript(TeraboxTaskScript.SOURCE, null);
                injected = true;
                pollsSinceInjection = 0;
            }
            TeraboxTaskHud.state(webView, state);
        }

        /** Detaches the HUD, stops the script and never leaves the page paused. */
        private void stop(WebView webView) {
            synchronized (ATTACHED) {
                ATTACHED.remove(webView);
            }
            TeraboxTaskHud.hide(webView);
            TeraboxTaskHud.hideAdOverlay();
            // Clearing the interval matters: a detached WebView keeps its JS
            // timers alive and would otherwise keep pressing in the background.
            webView.evaluateJavascript(
                    "window.__tbTaskPaused=0;window.__tbTaskStop&&window.__tbTaskStop();", null);
        }

        private boolean isGone(WebView webView) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                return false;
            }
            if (webView.isAttachedToWindow()) {
                attachedOnce = true;
                return false;
            }
            // The fragment may hand the WebView over before it is attached;
            // only treat a detach as final after it has been attached once.
            return attachedOnce;
        }
    }
}
