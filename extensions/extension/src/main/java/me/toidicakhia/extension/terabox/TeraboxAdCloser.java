package me.toidicakhia.extension.terabox;

import android.app.Activity;
import android.app.Application;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Closes every kind of ad TeraBox 4.29.0 can show.
 *
 * The ad plays normally, so the completion reported to the ad network is real;
 * this only removes the end card that waits for a close button.
 *
 * <h2>What is on screen</h2>
 *
 * The Benefits placement runs through MAX, whose waterfall reaches the other
 * networks in this build. Each one presents its ad in its own Activity, and
 * some stack browser, landing-page or MRAID Activities on top of the player:
 *
 * <table>
 *   <tr><th>Network</th><th>Activities</th><th>How it leaves</th></tr>
 *   <tr><td>MAX / AppLovin</td><td>adview.AppLovinFullscreenActivity,
 *       AppLovinFullscreenImmersiveActivity</td>
 *       <td>own close button, then BACK</td></tr>
 *   <tr><td>Pangle / ByteDance</td><td>openadsdk.activity.*
 *       (TTRewardVideo, TTFullScreenVideo, TTAd, landing/playable/website,
 *       TTDelegate)</td><td>skip/close button after the countdown, then
 *       BACK for each stacked layer</td></tr>
 *   <tr><td>Meta</td><td>facebook.ads.AudienceNetworkActivity</td>
 *       <td>close button, BACK</td></tr>
 *   <tr><td>AdMob</td><td>gms.ads.AdActivity</td><td>close button, BACK</td></tr>
 *   <tr><td>Moloco</td><td>Vast, Mraid, StaticAd, FullscreenWebview</td>
 *       <td>skip button, BACK</td></tr>
 *   <tr><td>MBridge / Mintegral</td><td>MBRewardVideoActivity,
 *       MBCommonActivity, LoadingActivity</td>
 *       <td>mbridge_*_iv_close / mbridge_full_rl_close, BACK</td></tr>
 *   <tr><td>Smaato</td><td>RewardedInterstitial, InterstitialAd,
 *       SmaatoSdkBrowser</td><td>smaato_sdk_*_close / skip, BACK</td></tr>
 *   <tr><td>Vungle / Liftoff</td><td>internal.ui.VungleActivity</td>
 *       <td>its onBackPressed runs the close callback</td></tr>
 *   <tr><td>InMobi</td><td>rendering.InMobiAdActivity</td><td>BACK</td></tr>
 *   <tr><td>Bigo</td><td>ads.api.* (Ad, Companion, Landscape, Popup,
 *       Landing), ad.splash.*</td><td>bigo_ad_btn_close / _skip, BACK;
 *       splash closes itself after the countdown</td></tr>
 *   <tr><td>AdMaster, Vlion, Brandio, PubNative</td>
 *       <td>*Activity / browser</td><td>BACK</td></tr>
 *   <tr><td>Mars ADX</td><td>adx.ui.AdxRewardExoVideo, AdxInterstitial*,
 *       H5Interstitial</td><td>BACK</td></tr>
 *   <tr><td>TeraBox native ad</td><td>ads.natives.SingleNativeAdSceneActivity</td>
 *       <td>onBackPressed invokes the static onAdClose the H5 waits for</td></tr>
 * </table>
 *
 * <h2>How it closes</h2>
 *
 * Every layer is asked to leave the way a user would, in this order:
 *
 * <ol>
 *   <li>the SDK's own close/skip control ({@link #dismissAdUi}), found by
 *       resource id name or by its label, so the SDK runs its close path and
 *       reports {@code onAdClosed} to the mediation,</li>
 *   <li>{@code onBackPressed()}, the modern path most SDKs hook,</li>
 *   <li>a real BACK key event, for SDKs that only watch key dispatch,</li>
 *   <li>{@code finish()}, for whatever ignored all of the above.</li>
 * </ol>
 *
 * A layer gets a short grace period between steps, and the loop re-reads the
 * stack each pass, so a landing page collapsing onto the player is followed by
 * the player's own close instead of being skipped. Splash ads have no reward to
 * wait for, so they are closed right after their countdown.
 *
 * The app's follow-up native ad is dismissed the same way: its own
 * {@code onBackPressed()} is what invokes the static {@code onAdClose}
 * callback the H5 reward flow waits for, so {@code finish()} alone would
 * close the screen without ever answering the page.
 */
@SuppressWarnings("unused")
public final class TeraboxAdCloser {

    private static final String TAG = "TeraboxAdCloser";

    /** Time a layer gets to react to BACK before the next step. */
    private static final long BACK_GRACE_MS = 160L;

    /**
     * Close a rewarded ad that never reported a reward after this long. Short
     * enough to unblock the task flow, long enough that a slow network still
     * has a chance to deliver the callback.
     */
    private static final long STUCK_AD_TIMEOUT_MS = 75_000L;

    /**
     * Full-screen native ad the app shows after a reward (H5 `showNativeADCount`).
     * Its {@code onBackPressed()} invokes the {@code onAdClose} callback the
     * reward flow is waiting for.
     */
    private static final String EXTRA_NATIVE_ACTIVITY =
            "com.dubox.drive.ads.natives.SingleNativeAdSceneActivity";

    /** Let the native ad render before dismissing it, like a user skipping it. */
    private static final long EXTRA_NATIVE_DELAY_MS = 900L;

    private static final long EXTRA_NATIVE_POLL_MS = 200L;

    private static final int EXTRA_NATIVE_POLLS = 100;

    private static volatile long lastRewardAt = Long.MIN_VALUE;

    /** When the current ad surface appeared; used by the stuck-ad watchdog. */
    private static volatile long adSince = Long.MIN_VALUE;

    /** End of the current follow-up-native-ad watch, so rewards do not stack loops. */
    private static volatile long nativeWatchUntil = Long.MIN_VALUE;

    /** True while a close pass is unwinding the stack. */
    private static volatile boolean closing;

    /**
     * Full-screen ad activities taken from the 4.29.0 manifest. The Benefits
     * placement runs through MAX, whose waterfall reaches Mars ADX, Pangle,
     * Meta, Moloco, MBridge, Smaato, Vungle and Bigo as well.
     *
     * Most entries are package prefixes rather than class names: a network
     * usually has several players (rewarded, interstitial, MRAID, landing page,
     * web view) and leaving one out strands the stack on that screen.
     */
    private static final String[] AD_ACTIVITY_PREFIXES = {
            // MAX / AppLovin fullscreen player and its web view.
            "com.applovin.adview.",
            "com.applovin.sdk.AppLovinWebViewActivity",
            // Pangle / ByteDance: TTRewardVideoActivity, TTFullScreenVideoActivity,
            // TTAdActivity, express players, landing pages and TTDelegateActivity.
            "com.bytedance.sdk.openadsdk.activity.",
            // Meta Audience Network (rewarded and interstitial share this one).
            "com.facebook.ads.AudienceNetworkActivity",
            // AdMob.
            "com.google.android.gms.ads.AdActivity",
            // Moloco: VastActivity, MraidActivity, StaticAdActivity,
            // FullscreenWebviewActivity.
            "com.moloco.",
            // MBridge / Mintegral: reward player, bridge and loading screens.
            "com.mbridge.msdk.",
            // Smaato: rewarded, interstitial and the in-ad browser.
            "com.smaato.sdk.",
            // Vungle / Liftoff: VungleActivity extends the SDK's AdActivity.
            "com.vungle.ads.internal.ui.",
            // InMobi.
            "com.inmobi.ads.rendering.InMobiAdActivity",
            // Bigo: ad, companion, landscape, popup, landing and MRAID video.
            "sg.bigo.ads.",
            // AdMaster.
            "cc.admaster.android.proxy.api.",
            // PubNative / Verve.
            "net.pubnative.lite.sdk.rewarded.",
            "net.pubnative.lite.sdk.interstitial.",
            "net.pubnative.lite.sdk.utils.browser.BrowserActivity",
            // Mars ADX and the Menta source (also reached directly by the app).
            "com.mars.united.international.ads.adx.",
            "com.mars.united.international.ads.adsource.",
            // Vlion / Menta.
            "com.advlion.ad.",
            // Brandio.
            "com.brandio.ads.",
    };

    /**
     * Activities that share a network's package but are not the ad itself:
     * debug consoles, consent forms and the "why this ad" feedback screen.
     * Closing them would interrupt the waterfall instead of finishing it.
     */
    private static final String[] NOT_AD_ACTIVITIES = {
            "com.dubox.drive.ads.AdDebugActivity",
            "com.applovin.mediation.Max",
            "com.google.android.gms.ads.OutOfContextTestingActivity",
            "com.google.android.gms.ads.NotificationHandlerActivity",
            "net.pubnative.lite.sdk.consent.UserConsentActivity",
            "net.pubnative.lite.sdk.contentinfo.AdFeedbackActivity",
            "sg.bigo.ads.controller.form.",
    };

    /**
     * Splash ads have no reward to wait for: they run a countdown and then wait
     * for the user to skip. They are closed right after the countdown, which is
     * also what keeps an app-open ad from holding the whole screen.
     */
    private static final String[] SPLASH_ACTIVITY_PREFIXES = {
            "sg.bigo.ads.ad.splash.",
            "com.mars.united.international.ads.adsource.interstitial.MentaSplashAdActivity",
    };

    /**
     * Labels a close/skip control can carry, in every language the app ships.
     * Resource id names are matched separately and take priority.
     */
    private static final String[] CLOSE_WORDS = {
            "close", "skip", "dismiss", "no thanks", "not now",
            "đóng", "bỏ qua", "để sau",
            "关闭", "跳过", "跳過", "略過",
            "閉じる", "スキップ", "닫기", "건너뛰기",
            "cerrar", "saltar", "omitir",
            "fechar", "pular",
            "fermer", "passer", "ignorer",
            "schließen", "überspringen", "weiter",
            "chiudere", "salta",
            "закрыть", "пропустить",
            "kapat", "atla", "geç",
            "tutup", "lewati",
            "ปิด", "ข้าม",
            "إغلاق", "تخطي",
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Cached reflection handles; resolved once, retried on failure. */
    private static volatile Method stackMethod;

    private static volatile Method currentMethod;

    private static volatile long nextResolveAt = Long.MIN_VALUE;

    private static volatile boolean reflectionWarned;

    /** Lifecycle callbacks are registered once per process. */
    private static volatile boolean installed;

    private TeraboxAdCloser() {
    }

    /**
     * Watches ad Activities for the ones that have no reward to wait for.
     *
     * Called from the task clicker, which is the only hook this extension gets
     * early enough to see an app-open splash ad. Safe to call repeatedly.
     */
    public static void install(Application application) {
        if (application == null || installed) {
            return;
        }
        installed = true;
        try {
            application.registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override
                        public void onActivityCreated(Activity activity, Bundle state) {
                        }

                        @Override
                        public void onActivityStarted(Activity activity) {
                        }

                        @Override
                        public void onActivityResumed(Activity activity) {
                            onAdShown(activity);
                        }

                        @Override
                        public void onActivityPaused(Activity activity) {
                        }

                        @Override
                        public void onActivityStopped(Activity activity) {
                        }

                        @Override
                        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
                        }

                        @Override
                        public void onActivityDestroyed(Activity activity) {
                        }
                    });
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not watch the activity lifecycle", throwable);
        }
    }

    /** Schedules the close of an ad that is not waiting for a reward callback. */
    private static void onAdShown(final Activity activity) {
        if (activity == null) {
            return;
        }
        final String name = activity.getClass().getName();
        if (!isSplash(name)) {
            return;
        }
        Log.i(TAG, "Splash ad shown: " + name);
        adSince = SystemClock.elapsedRealtime();
        // The countdown of the splash ads in this build is three to five seconds.
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isGone(activity) || !isOnTop(activity)) {
                    return;
                }
                Log.i(TAG, "Closing the splash ad");
                if (!dismissAdUi(activity)) {
                    backPress(activity);
                }
                MAIN.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        finishQuietly(activity);
                    }
                }, BACK_GRACE_MS * 4);
            }
        }, 4_500L);
    }

    /** Called from the injected reward callback, before the app handles it. */
    public static void close() {
        lastRewardAt = SystemClock.elapsedRealtime();
        // The reward arrived, so a stuck ad must not be force-closed any more.
        adSince = Long.MIN_VALUE;
        // The app starts its extra native ad from the reward callback, so it is
        // not on top yet; poll for it while the player is being closed.
        watchExtraNativeAd();
        // Give the app a moment to handle the reward callback before the ad goes away.
        startClose(false, 220L);
    }

    /**
     * Unwinds the ad stack one layer at a time. Overlapping rewards share the
     * pass that is already running instead of stacking a second one, which
     * would fight over the same Activities.
     */
    private static void startClose(final boolean force, long delayMs) {
        if (closing) {
            return;
        }
        closing = true;
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                stepClose(0, null, 0, force);
            }
        }, delayMs);
    }

    /**
     * One close step. The top of the stack is re-read every pass: if it is the
     * same Activity as the previous step, it moves to the next (harsher) way of
     * leaving; if it changed, the new layer starts again from a BACK press.
     */
    private static void stepClose(final int passes, final Activity layer,
                                  final int layerStep, final boolean force) {
        final Activity top = currentAdActivity();
        if (top == null) {
            closing = false;
            // A stuck-ad force pass only looks for SDK ads, so the follow-up
            // native ad would otherwise keep the clicker paused forever.
            if (force) {
                Activity nativeAd = findExtraNativeAd();
                if (nativeAd != null) {
                    Log.w(TAG, "Follow-up native ad is stuck, closing it");
                    dismissNativeLater(nativeAd, 0L);
                }
            }
            return;
        }
        // Upper bound on close passes, so a misbehaving SDK cannot loop forever.
        if (passes >= 16) {
            Log.w(TAG, "Stopping the ad close after " + passes + " passes");
            closing = false;
            return;
        }

        // Identity, not equality: the same Activity surviving a step is the
        // signal to escalate, a different one means a new layer to unwind.
        final boolean sameLayer = top == layer;
        final int step = sameLayer ? layerStep : 0;
        // Close control, BACK, key event, then finish: a layer that survives
        // six of those is left alone instead of being fought over.
        if (!force && step >= 6) {
            Log.w(TAG, "Ad ignored BACK and finish: " + top.getClass().getName());
            closing = false;
            return;
        }

        String name = top.getClass().getName();
        if (force) {
            Log.i(TAG, "Finishing " + name + " (no reward)");
            finishQuietly(top);
        } else if (step == 0) {
            // Every network has its own close path; pressing its control keeps
            // the callback (`onAdClosed`) the mediation waits for.
            if (!dismissAdUi(top)) {
                Log.i(TAG, "Back press on " + name);
                backPress(top);
            }
        } else if (step == 1) {
            Log.i(TAG, "Back press on " + name);
            backPress(top);
        } else if (step == 2) {
            Log.i(TAG, "Sending BACK to " + name);
            sendBack(top);
        } else {
            Log.i(TAG, "Finishing " + name);
            finishQuietly(top);
        }

        final int nextPasses = passes + 1;
        final int nextStep = step + 1;
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                stepClose(nextPasses, top, nextStep, force);
            }
        }, BACK_GRACE_MS);
    }

    /**
     * Some rewarded networks never deliver the reward callback when an ad is
     * stuck or the mediation waterfall fails. Called from the task poller, this
     * closes the ad after a generous timeout so the flow cannot hang forever.
     */
    public static void watchStuckAd() {
        if (!isAdOnTop()) {
            adSince = Long.MIN_VALUE;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // Guard the sentinel: `now - Long.MIN_VALUE` overflows, which used to
        // make this branch unreachable for an ad that never rewarded at all.
        boolean noReward = lastRewardAt == Long.MIN_VALUE
                || now - lastRewardAt > STUCK_AD_TIMEOUT_MS;
        if (adSince == Long.MIN_VALUE) {
            adSince = now;
        } else if (now - adSince > STUCK_AD_TIMEOUT_MS && noReward) {
            Log.w(TAG, "Ad did not report a reward, closing it");
            adSince = Long.MIN_VALUE;
            startClose(true, 0L);
        }
    }

    /**
     * True while any ad, or the app's follow-up native ad, is still in the
     * Activity stack. The task clicker pauses itself then, so a second task
     * cannot start another ad request while one is still playing.
     */
    public static boolean isAdOnTop() {
        for (Activity activity : activities()) {
            if (isGone(activity)) {
                continue;
            }
            if (isAdSurface(activity.getClass().getName())) {
                return true;
            }
        }
        // Fallback when the host does not expose its Activity list.
        Activity activity = currentActivity();
        return activity != null && isAdSurface(activity.getClass().getName());
    }

    /**
     * The Activity currently playing an SDK ad, or {@code null}. Used to put
     * the "watching ad" pill on top of the ad itself.
     */
    public static Activity currentAdActivity() {
        List<Activity> all = activities();
        for (int i = all.size() - 1; i >= 0; i--) {
            Activity activity = all.get(i);
            if (isGone(activity)) {
                continue;
            }
            if (isAdActivity(activity.getClass().getName())) {
                return activity;
            }
        }
        // Fallback when the host does not expose its Activity list.
        Activity current = currentActivity();
        return current != null && isAdActivity(current.getClass().getName()) ? current : null;
    }

    /** True while the H5 page may still be processing a reward it just received. */
    public static boolean recentlyRewarded() {
        // The sentinel must be checked first: subtracting Long.MIN_VALUE wraps
        // around and would report a fresh reward before any ad ever ran.
        // 2.5 s keeps the clicker paused while the H5 answers the reward and refreshes.
        return lastRewardAt != Long.MIN_VALUE
                && SystemClock.elapsedRealtime() - lastRewardAt < 2_500L;
    }

    /**
     * True when {@code activity} is the one the user is actually looking at.
     * A "watch video" task can open another full-screen WebView or a video
     * player; while that covers the task page, its clicker must not keep
     * starting tasks in the background.
     */
    public static boolean isOnTop(Activity activity) {
        return activity != null && activity == currentActivity();
    }

    /**
     * The app starts its extra native ad from the reward callback, so it is not
     * on top yet when {@link #close()} runs. Poll for it for a few seconds.
     */
    private static void watchExtraNativeAd() {
        long now = SystemClock.elapsedRealtime();
        // A second reward arriving while the native ad is still expected must
        // not start another polling loop.
        if (now < nativeWatchUntil) {
            return;
        }
        nativeWatchUntil = now + EXTRA_NATIVE_POLL_MS * EXTRA_NATIVE_POLLS + 1000L;
        MAIN.postDelayed(new Runnable() {

            private int polls;

            @Override
            public void run() {
                Activity activity = findExtraNativeAd();
                if (activity != null) {
                    Log.i(TAG, "Extra native ad shown, closing it in " +
                            EXTRA_NATIVE_DELAY_MS + " ms");
                    nativeWatchUntil = Long.MIN_VALUE;
                    dismissNativeLater(activity, EXTRA_NATIVE_DELAY_MS);
                    return;
                }

                if (++polls < EXTRA_NATIVE_POLLS) {
                    MAIN.postDelayed(this, EXTRA_NATIVE_POLL_MS);
                } else {
                    nativeWatchUntil = Long.MIN_VALUE;
                }
            }
        }, EXTRA_NATIVE_POLL_MS);
    }

    /** Finds the app's follow-up native ad anywhere in the stack, not only on top. */
    private static Activity findExtraNativeAd() {
        List<Activity> all = activities();
        for (int i = all.size() - 1; i >= 0; i--) {
            Activity activity = all.get(i);
            if (activity != null &&
                    EXTRA_NATIVE_ACTIVITY.equals(activity.getClass().getName())) {
                return activity;
            }
        }
        Activity current = currentActivity();
        if (current != null && EXTRA_NATIVE_ACTIVITY.equals(current.getClass().getName())) {
            return current;
        }
        return null;
    }

    /**
     * Dismisses the native ad through its own close path, then finishes it if
     * it is still there. The activity's {@code onBackPressed()} invokes the
     * static {@code onAdClose} callback, which is exactly what the H5 reward
     * flow is waiting for; {@code finish()} on its own skips that callback.
     */
    private static void dismissNativeLater(final Activity activity, long delayMs) {
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isGone(activity)) {
                    return;
                }
                Log.i(TAG, "Closing the follow-up native ad");
                backPress(activity);
                MAIN.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        finishQuietly(activity);
                    }
                }, BACK_GRACE_MS * 2);
            }
        }, delayMs);
    }

    /**
     * The SDK-backed way to leave an ad. {@code Activity.onBackPressed()}
     * delegates to the modern OnBackPressedDispatcher, which many reward
     * networks use instead of the older key-event callbacks.
     */
    private static void backPress(Activity activity) {
        try {
            Method method = Activity.class.getMethod("onBackPressed");
            method.invoke(activity);
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not call onBackPressed(), falling back to key events", throwable);
            sendBack(activity);
        }
    }

    /** Delivers a real BACK key event for SDKs that only watch key dispatch. */
    private static void sendBack(Activity activity) {
        try {
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not send BACK to the ad activity", throwable);
            finishQuietly(activity);
        }
    }

    private static void finishQuietly(Activity activity) {
        try {
            if (!isGone(activity)) {
                activity.finish();
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not finish the ad activity", throwable);
        }
    }

    /** An Activity that is leaving, or already gone. */
    private static boolean isGone(Activity activity) {
        return activity == null || activity.isFinishing() || activity.isDestroyed();
    }

    /** Copy of the host's Activity stack, oldest first, top last. */
    private static List<Activity> activities() {
        Method method = stackMethod();
        if (method == null) {
            return new ArrayList<>();
        }
        try {
            Object value = method.invoke(null);
            if (value instanceof List) {
                List<?> raw = (List<?>) value;
                List<Activity> out = new ArrayList<>(raw.size());
                for (Object item : raw) {
                    if (item instanceof Activity) {
                        out.add((Activity) item);
                    }
                }
                return out;
            }
        } catch (Throwable throwable) {
            warnReflection(throwable);
        }
        return new ArrayList<>();
    }

    private static Activity currentActivity() {
        Method method = currentMethod();
        if (method == null) {
            return null;
        }
        try {
            Object value = method.invoke(null);
            return value instanceof Activity ? (Activity) value : null;
        } catch (Throwable throwable) {
            warnReflection(throwable);
            return null;
        }
    }

    /** The host's obfuscated stack accessor: `____` returns the Activity list. */
    private static Method stackMethod() {
        Method method = stackMethod;
        if (method == null) {
            return resolve("____", true);
        }
        return method;
    }

    /** The host's obfuscated current-activity accessor: `______`. */
    private static Method currentMethod() {
        Method method = currentMethod;
        if (method == null) {
            return resolve("______", false);
        }
        return method;
    }

    /**
     * Resolves one of the host's accessors on
     * {@code com.dubox.drive.ActivityLifecycleManager} and caches it. The poller
     * asks for the stack every 600 ms, so a failed lookup is throttled instead
     * of being retried (and logged) on every call.
     */
    private static Method resolve(String name, boolean stack) {
        long now = SystemClock.elapsedRealtime();
        if (now < nextResolveAt) {
            return null;
        }
        try {
            Method method = Class.forName("com.dubox.drive.ActivityLifecycleManager")
                    .getDeclaredMethod(name);
            method.setAccessible(true);
            if (stack) {
                stackMethod = method;
            } else {
                currentMethod = method;
            }
            return method;
        } catch (Throwable throwable) {
            // Retry reflection at most every five seconds while the class is
            // not resolvable yet.
            nextResolveAt = now + 5_000L;
            warnReflection(throwable);
            return null;
        }
    }

    private static void warnReflection(Throwable throwable) {
        if (reflectionWarned) {
            return;
        }
        reflectionWarned = true;
        Log.w(TAG, "Could not read the Activity stack", throwable);
    }

    /**
     * Finds and presses the SDK's own close/skip control, if the ad shows one.
     *
     * The lookups happen on the live view tree, so they also cover the networks
     * whose resource names are not exported in the app's resource table: the
     * id name is read from the view itself.
     */
    private static boolean dismissAdUi(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null) {
                return false;
            }
            Resources resources = activity.getResources();
            Candidate best = new Candidate();
            scanForClose(decor, resources, decor.getHeight(), best, 0);
            if (best.view == null) {
                return false;
            }
            Log.i(TAG, "Pressing the ad's close control (" + best.idName + ")");
            if (best.view.performClick()) {
                return true;
            }
            best.view.callOnClick();
            return true;
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not press the ad's close control", throwable);
            return false;
        }
    }

    /** Highest-scoring close affordance found while walking the tree. */
    private static final class Candidate {

        private View view;

        private int score;

        private String idName;
    }

    private static void scanForClose(View view, Resources resources, int screenHeight,
                                     Candidate best, int depth) {
        if (view == null || depth > 24 || view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view.isShown()) {
            String idName = idNameOf(view, resources);
            int score = closeScore(view, idName, screenHeight);
            if (score > best.score) {
                best.view = view;
                best.score = score;
                best.idName = idName;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                scanForClose(group.getChildAt(i), resources, screenHeight, best, depth + 1);
            }
        }
    }

    /**
     * How much a view looks like the ad's close control. A matching resource id
     * wins over a matching content description, which wins over a matching
     * label; a control in the top band of the ad adds two, because that is
     * where the close button sits and the creative fills the rest.
     */
    private static int closeScore(View view, String idName, int screenHeight) {
        if (!view.isClickable() || !view.isEnabled()) {
            return 0;
        }
        int score = 0;
        if (idName != null && isCloseIdName(idName)) {
            score = 10;
        }
        if (score == 0) {
            CharSequence description = view.getContentDescription();
            if (description != null && looksLikeClose(description.toString())) {
                score = 6;
            }
        }
        if (score == 0 && view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && looksLikeClose(text.toString())) {
                score = 4;
            }
        }
        if (score > 0 && isNearTop(view, screenHeight)) {
            score += 2;
        }
        return score;
    }

    /** Close controls sit in the top band of the ad, the creative fills the rest. */
    private static boolean isNearTop(View view, int screenHeight) {
        if (screenHeight <= 0) {
            return false;
        }
        try {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            return location[1] < screenHeight * 0.4f;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static String idNameOf(View view, Resources resources) {
        try {
            int id = view.getId();
            if (id == View.NO_ID || resources == null) {
                return null;
            }
            return resources.getResourceEntryName(id);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * True for the ids every network uses for its close control, e.g.
     * `mbridge_full_iv_close`, `smaato_sdk_video_skip_button`,
     * `bigo_ad_btn_close`, `tt_reward_full_skip`. Countdown timers share the
     * wording but are labels, not buttons, so they are rejected.
     */
    static boolean isCloseIdName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("count")) {
            return false;
        }
        return lower.contains("close") || lower.contains("skip") || lower.contains("dismiss");
    }

    /** True when a short label is a close/skip/dismiss affordance. */
    static boolean looksLikeClose(String label) {
        if (label == null) {
            return false;
        }
        String value = label.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > 24) {
            return false;
        }
        if ("×".equals(value) || "✕".equals(value) || "✖".equals(value) || "✗".equals(value)
                || "x".equals(value)) {
            return true;
        }
        for (String word : CLOSE_WORDS) {
            if (value.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSplash(String name) {
        return startsWithAny(name, SPLASH_ACTIVITY_PREFIXES);
    }

    /** An ad Activity, or the app's follow-up native ad. */
    private static boolean isAdSurface(String name) {
        return EXTRA_NATIVE_ACTIVITY.equals(name) || isAdActivity(name);
    }

    private static boolean isAdActivity(String name) {
        return !startsWithAny(name, NOT_AD_ACTIVITIES)
                && startsWithAny(name, AD_ACTIVITY_PREFIXES);
    }

    private static boolean startsWithAny(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
