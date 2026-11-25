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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
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
 *   <li>a real tap on the close X in the top-right corner, for playable and
 *       end-card creatives that draw it inside their WebView,</li>
 *   <li>{@code onBackPressed()}, the modern path most SDKs hook,</li>
 *   <li>a real BACK key event, for SDKs that only watch key dispatch,</li>
 *   <li>{@code finish()}, for whatever ignored all of the above.</li>
 * </ol>
 *
 * A layer gets a short grace period between steps, and the loop re-reads the
 * stack each pass, so a landing page collapsing onto the player is followed by
 * the player's own close instead of being skipped. Splash ads have no reward to
 * wait for, so they are closed right after their countdown, and the app's own
 * app-open ad is treated the same way.
 *
 * <h2>Ads nobody waits for</h2>
 *
 * Only a control the clicker pressed spends a reward; the clicker says so
 * through {@link #expectReward()}. An ad that appears without that (an
 * interstitial between pages, a landing page the rewarded ad opens, an
 * app-open ad, a failed fill) is closed a few seconds after it appears instead
 * of holding the screen until the reward timeout, and every ad gets its own
 * clock so a reward from the previous one cannot mask it.
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

    /** The ad surface {@link #adSince} belongs to, so a new ad gets a new clock. */
    private static volatile Activity adSeen;

    /**
     * End of the window in which an ad is expected to report a reward, set when
     * the clicker presses a control that spends one.
     */
    private static volatile long rewardExpectedUntil = Long.MIN_VALUE;

    /**
     * The Activity the pending reward currently belongs to. It follows the
     * rewarded layer through the waterfall: a MAX loading screen can hand the
     * video off to another network's player, and that player owes the reward.
     * A landing page the ad opens is a different Activity; it owes no reward
     * and must not inherit the rewarded ad's timeout.
     */
    private static volatile Activity rewardExpectedAd;

    /**
     * End of the window in which an ad counts as the app's own app-open ad,
     * set when the host Activity that shows it resumes.
     */
    private static volatile long appOpenUntil = Long.MIN_VALUE;

    /** End of the current follow-up-native-ad watch, so rewards do not stack loops. */
    private static volatile long nativeWatchUntil = Long.MIN_VALUE;

    /** True while a close pass is unwinding the stack. */
    private static volatile boolean closing;

    /** When that pass started, so one that never reports back cannot block the next. */
    private static volatile long closingAt = Long.MIN_VALUE;

    /**
     * The transparent host the app starts to show a cold/hot app-open ad. The ad
     * itself is an ordinary ad Activity; this only says "one is coming".
     */
    private static final String APP_OPEN_HOST = "com.dubox.drive.ui.ColdAdHelperActivity";

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
            // The playable and express components live under other subpackages.
            "com.bytedance.sdk.openadsdk.",
            // Meta Audience Network (rewarded and interstitial share this one).
            "com.facebook.ads.AudienceNetworkActivity",
            // AdMob, including the ad manager and test activities. The consent
            // and notification handlers are excluded below.
            "com.google.android.gms.ads.",
            // Moloco: VastActivity, MraidActivity, StaticAdActivity,
            // FullscreenWebviewActivity.
            "com.moloco.",
            // MBridge / Mintegral: reward player, bridge and loading screens.
            "com.mbridge.msdk.",
            // Smaato: rewarded, interstitial and the in-ad browser.
            "com.smaato.sdk.",
            // Vungle / Liftoff: VungleActivity extends the SDK's AdActivity.
            "com.vungle.ads.internal.ui.",
            // InMobi: every rendering activity, including MRAID and video.
            "com.inmobi.ads.rendering.",
            // Bigo: ad, companion, landscape, popup, landing and MRAID video.
            "sg.bigo.ads.",
            // AdMaster, including the MRAID and landing-page activities.
            "cc.admaster.android.",
            // PubNative / Verve: rewarded, interstitial, MRAID, VAST and the
            // in-ad browser.
            "net.pubnative.lite.sdk.",
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

    /**
     * Wording an ad SDK uses once the reward has been granted. Seeing it means
     * the end card is only waiting for a close, even if the reward callback the
     * mediation normally forwards never reached {@link #close()}.
     */
    private static final String[] REWARD_GRANTED_WORDS = {
            "reward granted", "reward earned", "reward received", "reward unlocked",
            "đã nhận thưởng", "nhận thưởng thành công",
            "奖励已发放", "奖励已到账", "已获得奖励",
            "報酬を獲得", "特典を獲得",
            "보상을 받", "보상 지급 완료",
            "recompensa recibida", "recompensa obtenida",
            "récompense reçue", "récompense obtenue",
            "recompensa recebida",
            "belohnung erhalten",
            "premio ricevuto",
            "награда получена",
            "ödül kazanıldı",
    };

    /**
     * Wording of a rewarded ad, before it pays. An AdMob rewarded video shows
     * this while it plays; seeing it means the ad owes a reward even if the
     * clicker did not announce the spend (some sign-in/guide-ad popups open
     * their own ad through the H5).
     */
    private static final String[] REWARD_AD_WORDS = {
            "reward", "rewarded",
            "phần thưởng", "nhận thưởng",
            "奖励", "报酬", "報酬", "特典",
            "보상",
            "recompensa", "récompense", "belohnung", "premio", "награда", "ödül",
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
        if (APP_OPEN_HOST.equals(name)) {
            // The app-open ad has not been started yet; remember that one is
            // coming so the ad Activity itself is not mistaken for a reward ad.
            appOpenUntil = SystemClock.elapsedRealtime() + 15_000L;
            return;
        }
        if (!isAdSurface(name)) {
            return;
        }
        if (!isSplash(name) && !appOpenAd()) {
            return;
        }
        Log.i(TAG, (isSplash(name) ? "Splash ad shown: " : "App-open ad shown: ") + name);
        adSince = SystemClock.elapsedRealtime();
        // The splash countdown in this build is three to five seconds, and the
        // app-open ad has the same kind of mandatory display time.
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isGone(activity) || !isOnTop(activity)) {
                    return;
                }
                // An AdMob rewarded ad can start inside the app-open window.
                // Its reward UI means it is not the app-open ad: leave it to
                // the reward timeout instead of cutting it after 4.5 s.
                if (!isSplash(name) && showsRewardedAd(activity)) {
                    Log.i(TAG, "Reward ad shown, leaving it to the reward timeout");
                    return;
                }
                Log.i(TAG, "Closing the ad that nobody waits for");
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

    /**
     * The clicker pressed a control that spends a rewarded ad, so this ad owes a
     * reward. Until that reward arrives the ad is left alone; without it the
     * watchdog would treat a rewarded video as an interstitial and cut it short.
     */
    public static void expectReward() {
        rewardExpectedUntil = SystemClock.elapsedRealtime() + 90_000L;
        rewardExpectedAd = null;
    }

    /** Called from the injected reward callback, before the app handles it. */
    public static void close() {
        lastRewardAt = SystemClock.elapsedRealtime();
        // The reward arrived, so a stuck ad must not be force-closed any more.
        adSince = Long.MIN_VALUE;
        // Nor is any other ad still expected to report one.
        rewardExpectedUntil = Long.MIN_VALUE;
        rewardExpectedAd = null;
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
        long now = SystemClock.elapsedRealtime();
        if (closing) {
            // A pass that never reports back would otherwise block every later
            // ad, which looks exactly like "the closer stopped working".
            if (now - closingAt < 5_000L) {
                return;
            }
            Log.w(TAG, "The last close pass stopped reporting, starting a new one");
        }
        closing = true;
        closingAt = now;
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
            if (isExtraNativeAd(top)) {
                // Its onBackPressed() is what invokes the onAdClose callback
                // the H5 reward flow waits for; finish() alone skips it.
                Log.i(TAG, "Closing the follow-up native ad (no reward)");
                dismissNativeLater(top, 0L);
                closing = false;
                return;
            }
            Log.i(TAG, "Finishing " + name + " (no reward)");
            finishQuietly(top);
        } else if (isExtraNativeAd(top)) {
            // BACK first: the native ad's own back handler answers the H5,
            // then a second pass finishes whatever is left.
            if (step == 0) {
                Log.i(TAG, "Back press on the native ad");
                backPress(top);
            } else if (step == 1) {
                Log.i(TAG, "Back press on the native ad");
                backPress(top);
            } else {
                Log.i(TAG, "Finishing the native ad");
                finishQuietly(top);
            }
        } else if (step == 0) {
            // Every network has its own close path; pressing its control keeps
            // the callback (`onAdClosed`) the mediation waits for.
            if (!dismissAdUi(top)) {
                // Playable and end-card creatives draw their X inside a WebView
                // or an unnamed ImageView, so the view lookup above finds
                // nothing. Tap the corner where that X always sits before
                // falling back to BACK.
                tapCloseCorner(top);
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
     * Called from the task poller. An ad that owes a reward gets the full
     * timeout before it is force-closed, while one that nobody asked for - an
     * interstitial between pages, an app-open ad, a failed fill - is closed a
     * few seconds after it appears, so it cannot hold the screen.
     */
    public static void watchStuckAd() {
        Activity top = currentAdActivity();
        if (top == null) {
            // The follow-up native ad is not an SDK ad surface but it holds the
            // screen just the same.
            top = findExtraNativeAd();
        }
        if (top == null) {
            adSince = Long.MIN_VALUE;
            adSeen = null;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        // Every ad gets its own clock: without this, a reward from the previous
        // ad masked the next one and it was never closed.
        if (top != adSeen || adSince == Long.MIN_VALUE) {
            Log.i(TAG, "Ad on top: " + top.getClass().getName());
            adSeen = top;
            adSince = now;
            // The pending reward follows the rewarded layer. MAX can answer a
            // request with one network's loading screen and then hand the video
            // to another network's player; the player becomes the top ad surface
            // and is the one still owing the reward. Keeping the reward bound to
            // the earlier layer made every later layer fall through to the 6 s
            // interstitial grace, cutting the video before it could pay. A
            // landing page or in-ad browser the ad opens is the exception: it
            // has its own X and no reward to report, so it keeps the short grace.
            if (rewardExpected() && !isLandingPage(top)) {
                rewardExpectedAd = top;
            }
            return;
        }
        // The sentinel must be checked before subtracting: `now - Long.MIN_VALUE`
        // overflows, which used to make this branch unreachable for an ad that
        // never rewarded at all.
        if (lastRewardAt != Long.MIN_VALUE && lastRewardAt >= adSince) {
            // This ad was rewarded; the close pass runs from close(). Retry only
            // when that pass did not actually take the ad away, and at most once
            // every ten seconds so a stubborn ad is not fought over on each poll.
            // The sentinel is checked before subtracting, as anywhere else here.
            boolean passIdle = closingAt == Long.MIN_VALUE || now - closingAt > 10_000L;
            if (now - lastRewardAt > 10_000L && passIdle) {
                Log.w(TAG, "Ad stayed after its reward, closing it again");
                startClose(true, 0L);
            }
            return;
        }
        // The ad itself says the reward was granted, but close() never ran
        // (some mediation callbacks bypass the injected listener). The end card
        // is only waiting for a close, so do not sit on it for the whole reward
        // timeout: treat it as rewarded and run the normal close ladder.
        if (now - adSince >= 1_500L && showsRewardGranted(top)) {
            Log.i(TAG, "Ad announced the reward, closing its end card");
            lastRewardAt = now;
            adSince = now;
            startClose(false, 500L);
            return;
        }
        // The app's follow-up native ad is never a long rewarded video. If it
        // is still here after 20 s, its close callback is not coming.
        if (isExtraNativeAd(top) && now - adSince >= 20_000L) {
            Log.w(TAG, "Follow-up native ad stayed for 20 s, closing it");
            adSince = now;
            dismissNativeLater(top, 0L);
            return;
        }
        // The end card can be any network in the MAX waterfall (the Shopee
        // card in the capture is Pangle, not AdMob). Once it shows its X, it
        // is not going to report anything else: click that X instead of
        // leaving it until the reward timeout.
        if (now - adSince >= 20_000L && hasCloseControl(top)) {
            Log.i(TAG, "Ad end card is closable, closing it");
            lastRewardAt = now;
            adSince = now;
            startClose(false, 0L);
            return;
        }
        // A closable end card still on screen after 35 s is stuck. Close it
        // instead of leaving it until the 75 s network timeout. 35 s still
        // covers a full 30 s rewarded video.
        if (now - adSince >= 35_000L && hasCloseControl(top)) {
            Log.w(TAG, "Ad stayed for 35 s with a close control, closing it");
            lastRewardAt = now;
            adSince = now;
            startClose(true, 0L);
            return;
        }
        // When the X is drawn inside the creative there is no native control
        // to detect. Cap any rewarded ad at 45 s so an HTML end card cannot
        // hold the task flow either.
        if (now - adSince >= 45_000L && (rewardExpected() || showsRewardedAd(top))) {
            Log.w(TAG, "Rewarded ad stayed for 45 s, closing it");
            lastRewardAt = now;
            adSince = now;
            startClose(true, 0L);
            return;
        }
        // A rewarded ad the clicker did not announce (a sign-in popup opens its
        // own ad through the H5) still owes a reward. Its own UI says so, so do
        // not cut it after the interstitial grace. AdMob is the network those
        // popups use, so a pending reward always covers its AdActivity too.
        boolean owesReward = rewardExpectedFor(top)
                || (rewardExpected() && isAdMobAdActivity(top))
                || (now - adSince >= 1_500L && showsRewardedAd(top));
        // No reward is due for an interstitial, a landing page or an app-open
        // ad, so a short grace is enough for those.
        long limit = owesReward ? STUCK_AD_TIMEOUT_MS : 6_000L;
        if (now - adSince > limit) {
            Log.w(TAG, "Ad did not report a reward after " + (now - adSince) + " ms, closing it");
            adSince = now;
            startClose(true, 0L);
        }
    }

    /** True while the clicker is waiting for the reward of the ad it just opened. */
    private static boolean rewardExpected() {
        return rewardExpectedUntil != Long.MIN_VALUE
                && SystemClock.elapsedRealtime() < rewardExpectedUntil;
    }

    /** True when the pending reward belongs to {@code ad}, not to a page it opened. */
    private static boolean rewardExpectedFor(Activity ad) {
        return rewardExpected() && !isLandingPage(ad)
                && (rewardExpectedAd == null || rewardExpectedAd == ad);
    }

    /**
     * A landing page or in-ad browser opened from a rewarded ad. It is still an
     * ad surface, but it owes no reward of its own: it has its own close X and
     * the reward is reported by the player underneath it.
     */
    private static boolean isLandingPage(Activity activity) {
        if (activity == null) {
            return false;
        }
        String name = activity.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("landing") || name.contains("browser")
                || name.contains("website") || name.contains("webpage");
    }

    /** True for the app's own app-open ad: it follows the host and owes no reward. */
    private static boolean appOpenAd() {
        return appOpenUntil != Long.MIN_VALUE
                && SystemClock.elapsedRealtime() < appOpenUntil
                && !rewardExpected();
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
     * The Activity currently playing an ad, or the app's follow-up native ad,
     * or {@code null}. Used to put the "watching ad" pill on top of the ad
     * itself and to close the topmost ad surface.
     */
    public static Activity currentAdActivity() {
        List<Activity> all = activities();
        for (int i = all.size() - 1; i >= 0; i--) {
            Activity activity = all.get(i);
            if (isGone(activity)) {
                continue;
            }
            if (isAdSurface(activity.getClass().getName())) {
                return activity;
            }
        }
        // Fallback when the host does not expose its Activity list.
        Activity current = currentActivity();
        return current != null && isAdSurface(current.getClass().getName()) ? current : null;
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

    /** True for the app's own full-screen native ad scene. */
    private static boolean isExtraNativeAd(Activity activity) {
        return activity != null && EXTRA_NATIVE_ACTIVITY.equals(activity.getClass().getName());
    }

    /** True for AdMob's rewarded/interstitial player, the placement the task uses. */
    private static boolean isAdMobAdActivity(Activity activity) {
        if (activity == null) {
            return false;
        }
        String name = activity.getClass().getName();
        return name.equals("com.google.android.gms.ads.AdActivity")
                || name.startsWith("com.google.android.gms.ads.admanager.");
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
        Candidate best = findCloseCandidate(activity);
        if (best == null) {
            return false;
        }
        Log.i(TAG, "Pressing the ad's close control (" + best.idName + ", score " + best.score + ")");
        if (best.view.performClick()) {
            return true;
        }
        if (best.view.callOnClick()) {
            return true;
        }
        // Some networks draw the X and handle it in onTouchEvent, so a
        // synthesized performClick() never reaches their close path.
        Log.i(TAG, "Close control ignored the click, tapping it");
        return tapView(best.view);
    }

    /**
     * The best close control, or null. A WebView is not a close control:
     * performClick() on it presses the center of the creative, which opens the
     * ad instead of closing it.
     */
    private static Candidate findCloseCandidate(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null) {
                return null;
            }
            Resources resources = activity.getResources();
            Candidate best = new Candidate();
            scanForClose(decor, resources, decor.getWidth(), decor.getHeight(), best, 0);
            if (best.view == null || best.view instanceof WebView) {
                return null;
            }
            return best;
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not read the ad's close control", throwable);
            return null;
        }
    }

    /** True when the ad currently shows a control that can close it. */
    private static boolean hasCloseControl(Activity activity) {
        return findCloseCandidate(activity) != null;
    }

    /**
     * Delivers a real tap to the top-right corner, where playable ads and end
     * cards draw their close X inside the creative. This is only used when the
     * view tree has no close control to press; a synthetic tap is what a user
     * would do to that X.
     */
    private static boolean tapCloseCorner(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor == null) {
                return false;
            }
            // The X in an HTML creative sits near the top-right of the WebView.
            // Using the WebView as the reference keeps a title/status bar above
            // the creative from pushing the tap too high.
            View target = largestWebView(decor);
            if (target == null) {
                target = decor;
            }
            int[] location = new int[2];
            target.getLocationOnScreen(location);
            int width = target.getWidth();
            int height = target.getHeight();
            if (width <= 0 || height <= 0) {
                return false;
            }
            float x = location[0] + width * 0.92f;
            float y = location[1] + height * 0.035f;
            return tapScreen(decor, x, y, "close corner of " + activity.getClass().getName());
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not tap the close corner", throwable);
            return false;
        }
    }

    /** Delivers a real tap to the center of a close control. */
    private static boolean tapView(View view) {
        try {
            if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
                return false;
            }
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            float x = location[0] + view.getWidth() / 2f;
            float y = location[1] + view.getHeight() / 2f;
            return tapScreen(view.getRootView(), x, y, "close control");
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not tap the close control", throwable);
            return false;
        }
    }

    private static boolean tapScreen(View root, float screenX, float screenY, String what) {
        try {
            int[] rootLocation = new int[2];
            root.getLocationOnScreen(rootLocation);
            float x = screenX - rootLocation[0];
            float y = screenY - rootLocation[1];
            long downAt = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(downAt, downAt + 60L, MotionEvent.ACTION_UP, x, y, 0);
            try {
                root.dispatchTouchEvent(down);
                root.dispatchTouchEvent(up);
            } finally {
                down.recycle();
                up.recycle();
            }
            Log.i(TAG, "Tapped " + what + " at " + Math.round(x) + "," + Math.round(y));
            return true;
        } catch (Throwable throwable) {
            Log.w(TAG, "Could not tap " + what, throwable);
            return false;
        }
    }

    /** The biggest visible WebView, i.e. the ad creative, or null. */
    private static View largestWebView(View root) {
        View[] best = new View[1];
        long[] area = new long[]{-1};
        largestWebView(root, best, area, 0);
        return best[0];
    }

    private static void largestWebView(View view, View[] best, long[] area, int depth) {
        if (view == null || depth > 24 || view.getVisibility() != View.VISIBLE || !view.isShown()) {
            return;
        }
        if (view instanceof WebView) {
            long size = (long) view.getWidth() * view.getHeight();
            if (size > area[0]) {
                area[0] = size;
                best[0] = view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                largestWebView(group.getChildAt(i), best, area, depth + 1);
            }
        }
    }

    /** Highest-scoring close affordance found while walking the tree. */
    private static final class Candidate {

        private View view;

        private int score;

        private String idName;
    }

    private static void scanForClose(View view, Resources resources, int screenWidth, int screenHeight,
                                     Candidate best, int depth) {
        if (view == null || depth > 24 || view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view.isShown()) {
            String idName = idNameOf(view, resources);
            int score = closeScore(view, idName, screenWidth, screenHeight);
            if (score > best.score) {
                best.view = view;
                best.score = score;
                best.idName = idName;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                scanForClose(group.getChildAt(i), resources, screenWidth, screenHeight, best, depth + 1);
            }
        }
    }

    /** True when the ad activity itself shows that the reward was granted. */
    private static boolean showsRewardGranted(Activity activity) {
        return showsRewardWord(activity, REWARD_GRANTED_WORDS);
    }

    /** True when the ad activity shows rewarded-ad wording. */
    private static boolean showsRewardedAd(Activity activity) {
        return showsRewardWord(activity, REWARD_AD_WORDS);
    }

    private static boolean showsRewardWord(Activity activity, String[] words) {
        try {
            View decor = activity.getWindow().getDecorView();
            return decor != null && showsRewardWord(decor, 0, words);
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static boolean showsRewardWord(View view, int depth, String[] words) {
        if (view == null || depth > 24 || view.getVisibility() != View.VISIBLE || !view.isShown()) {
            return false;
        }
        CharSequence text = null;
        if (view instanceof TextView) {
            text = ((TextView) view).getText();
        }
        if (text == null) {
            text = view.getContentDescription();
        }
        if (text != null) {
            String value = text.toString().toLowerCase(Locale.ROOT);
            for (String word : words) {
                if (value.contains(word)) {
                    return true;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                if (showsRewardWord(group.getChildAt(i), depth + 1, words)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How much a view looks like the ad's close control. A matching resource id
     * wins over a matching content description, which wins over a matching
     * label; a control in the top band of the ad adds two, because that is
     * where the close button sits and the creative fills the rest.
     */
    private static int closeScore(View view, String idName, int screenWidth, int screenHeight) {
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
        if (score == 0 && isCloseCorner(view, screenWidth, screenHeight)) {
            // An unnamed X in the top-right corner: playable/end-card close
            // buttons are often plain ImageViews with no id or description.
            score = 1;
        }
        if (score > 0 && isNearTop(view, screenHeight)) {
            score += 2;
        }
        return score;
    }

    /** A small clickable control in the top-right corner of the ad. */
    private static boolean isCloseCorner(View view, int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            return false;
        }
        try {
            int[] location = new int[2];
            view.getLocationOnScreen(location);
            int width = view.getWidth();
            int height = view.getHeight();
            if (width <= 0 || height <= 0) {
                return false;
            }
            int right = location[0] + width;
            return location[1] >= 0
                    && location[1] <= screenHeight * 0.18f
                    && right >= screenWidth * 0.82f
                    && width <= screenWidth * 0.22f
                    && height <= screenHeight * 0.14f;
        } catch (Throwable throwable) {
            return false;
        }
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
