package me.toidicakhia.extension.terabox;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Closes the rewarded-ad surface once the SDK has granted the reward.
 *
 * The ad plays normally, so the completion reported to the ad network is real;
 * this only removes the end card that waits for a close button.
 *
 * Every rewarded network in TeraBox 4.28.5 presents its ad in an Activity, some
 * stacking browser, landing-page or MRAID Activities on top of the player.
 * Finishing only the top one leaves the player, or the mediator's "showing"
 * state, behind. The whole stack is therefore unwound one layer at a time,
 * top-down, and each layer is asked to leave the way a user would:
 *
 * <ol>
 *   <li>{@code onBackPressed()}: the modern path, and the one the SDK's own
 *       close logic hangs off ({@code onAdClosed}),</li>
 *   <li>a real BACK key event, for SDKs that only watch key dispatch,</li>
 *   <li>{@code finish()}, for whatever ignored both.</li>
 * </ol>
 *
 * A layer gets a short grace period between steps, and the loop re-reads the
 * stack each pass, so a landing page collapsing onto the player is followed by
 * the player's own close instead of being skipped.
 *
 * The app's follow-up native ad is dismissed the same way: its own
 * {@code onBackPressed()} is what invokes the static {@code onAdClose}
 * callback the H5 reward flow waits for, so {@code finish()} alone would
 * close the screen without ever answering the page.
 */
@SuppressWarnings("unused")
public final class TeraboxAdCloser {

    private static final String TAG = "TeraboxAdCloser";

    /** Give the app a moment to handle the reward callback before the ad goes away. */
    private static final long CLOSE_DELAY_MS = 220L;

    /** Time a layer gets to react to BACK before the next step. */
    private static final long BACK_GRACE_MS = 160L;

    /** Upper bound on close passes, so a misbehaving SDK cannot loop forever. */
    private static final int CLOSE_PASS_BUDGET = 16;

    /** Steps a single ad Activity gets (back press, key event, finish, wait). */
    private static final int STEPS_PER_LAYER = 5;

    /**
     * Close a rewarded ad that never reported a reward after this long. Short
     * enough to unblock the task flow, long enough that a slow network still
     * has a chance to deliver the callback.
     */
    private static final long STUCK_AD_TIMEOUT_MS = 75_000L;

    private static final String ACTIVITY_MANAGER = "com.dubox.drive.ActivityLifecycleManager";

    /** The stack and current-activity accessors, by their obfuscated names. */
    private static final String STACK_METHOD = "____";
    private static final String CURRENT_METHOD = "______";

    /** Retry reflection at most this often while the class is not resolvable yet. */
    private static final long REFLECT_RETRY_MS = 5_000L;

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

    /** Keep the clicker paused while the H5 answers the reward and refreshes. */
    private static final long REWARD_HOLD_MS = 2_500L;

    private static volatile long lastRewardAt = Long.MIN_VALUE;

    /** When the current ad surface appeared; used by the stuck-ad watchdog. */
    private static volatile long adSince = Long.MIN_VALUE;

    /** End of the current follow-up-native-ad watch, so rewards do not stack loops. */
    private static volatile long nativeWatchUntil = Long.MIN_VALUE;

    /** True while a close pass is unwinding the stack. */
    private static volatile boolean closing;

    /**
     * Full-screen ad activities taken from the 4.28.5 manifest. The Benefits
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
            "sg.bigo.ads.ad.splash.",
            "sg.bigo.ads.controller.form.",
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Cached reflection handles; resolved once, retried on failure. */
    private static volatile Method stackMethod;

    private static volatile Method currentMethod;

    private static volatile long nextResolveAt = Long.MIN_VALUE;

    private static volatile boolean reflectionWarned;

    private TeraboxAdCloser() {
    }

    /** Called from the injected reward callback, before the app handles it. */
    public static void close() {
        lastRewardAt = SystemClock.elapsedRealtime();
        // The reward arrived, so a stuck ad must not be force-closed any more.
        adSince = Long.MIN_VALUE;
        // The app starts its extra native ad from the reward callback, so it is
        // not on top yet; poll for it while the player is being closed.
        watchExtraNativeAd();
        startClose(false, CLOSE_DELAY_MS);
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
        if (passes >= CLOSE_PASS_BUDGET) {
            Log.w(TAG, "Stopping the ad close after " + passes + " passes");
            closing = false;
            return;
        }

        // Identity, not equality: the same Activity surviving a step is the
        // signal to escalate, a different one means a new layer to unwind.
        final boolean sameLayer = top == layer;
        final int step = sameLayer ? layerStep : 0;
        if (!force && step >= STEPS_PER_LAYER) {
            Log.w(TAG, "Ad ignored BACK and finish: " + top.getClass().getName());
            closing = false;
            return;
        }

        String name = top.getClass().getName();
        if (force) {
            Log.i(TAG, "Finishing " + name + " (no reward)");
            finishQuietly(top);
        } else if (step == 0) {
            Log.i(TAG, "Back press on " + name);
            backPress(top);
        } else if (step == 1) {
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
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
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
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
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
        return lastRewardAt != Long.MIN_VALUE
                && SystemClock.elapsedRealtime() - lastRewardAt < REWARD_HOLD_MS;
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
                if (activity.isFinishing() || activity.isDestroyed()) {
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
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                activity.finish();
            }
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not finish the ad activity", throwable);
        }
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

    private static Method stackMethod() {
        Method method = stackMethod;
        if (method == null) {
            return resolve(STACK_METHOD, true);
        }
        return method;
    }

    private static Method currentMethod() {
        Method method = currentMethod;
        if (method == null) {
            return resolve(CURRENT_METHOD, false);
        }
        return method;
    }

    /**
     * Resolves one of the host's obfuscated accessors and caches it. The poller
     * asks for the stack every 600 ms, so a failed lookup is throttled instead
     * of being retried (and logged) on every call.
     */
    private static Method resolve(String name, boolean stack) {
        long now = SystemClock.elapsedRealtime();
        if (now < nextResolveAt) {
            return null;
        }
        try {
            Method method = Class.forName(ACTIVITY_MANAGER).getDeclaredMethod(name);
            method.setAccessible(true);
            if (stack) {
                stackMethod = method;
            } else {
                currentMethod = method;
            }
            return method;
        } catch (Throwable throwable) {
            nextResolveAt = now + REFLECT_RETRY_MS;
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

    /** An ad Activity, or the app's follow-up native ad. */
    private static boolean isAdSurface(String name) {
        return EXTRA_NATIVE_ACTIVITY.equals(name) || isAdActivity(name);
    }

    private static boolean isAdActivity(String name) {
        for (String excluded : NOT_AD_ACTIVITIES) {
            if (name.startsWith(excluded)) {
                return false;
            }
        }
        for (String prefix : AD_ACTIVITY_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
