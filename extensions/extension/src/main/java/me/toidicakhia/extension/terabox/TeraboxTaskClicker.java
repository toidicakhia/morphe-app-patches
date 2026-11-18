package me.toidicakhia.extension.terabox;

import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps the TeraBox task-center and check-in pages clicking themselves.
 *
 * Both pages are offline packs the app downloads, so the injected script
 * targets their own class names by CSS selector rather than by label: task card
 * buttons, the featured task card, the reward popups (including the "watch a
 * video for an extra reward" button) and the check-in calendar. Destructive
 * controls (complete, subscribe/remind, close, card exchange) are never part of
 * the list, and every press goes through a cooldown.
 */
@SuppressWarnings("unused")
public final class TeraboxTaskClicker {

    private static final String TAG = "TeraboxTaskClicker";

    private static final long POLL_INTERVAL_MS = 1500L;

    private static final int MAX_POLLS = 4800;

    /**
     * Pages that can host the reward task list. The Benefits entry may point at
     * a server-configured URL, so do not rely on `taskcenter` alone.
     */
    private static final String[] TASK_PAGE_HINTS = {
            "taskcenter",
            "benefit",
            "welfare",
            "reward",
            "bonus",
            "earn",
            "coin",
    };

    private static final Map<WebView, Boolean> ATTACHED =
            Collections.synchronizedMap(new WeakHashMap<WebView, Boolean>());

    /** Name of the bridge the injected script uses to report what it pressed. */
    private static final String HUD_BRIDGE = "tbTaskHud";

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

        webView.addJavascriptInterface(new HudBridge(webView), HUD_BRIDGE);
        webView.post(new Poller(webView));
    }

    /** Receives the label of the control the injected script just pressed. */
    private static final class HudBridge {

        private final WeakReference<WebView> webViewRef;

        HudBridge(WebView webView) {
            this.webViewRef = new WeakReference<>(webView);
        }

        @JavascriptInterface
        public void clicked(String label) {
            final WebView webView = webViewRef.get();
            if (webView != null) {
                TeraboxTaskHud.clicked(webView, label);
            }
        }
    }

    private static final class Poller implements Runnable {

        private final WeakReference<WebView> webViewRef;

        private boolean wasAttached;

        private int polls;

        private int lastUrlHash;

        private boolean paused;

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
                // Keep the page-side clicker paused while an ad is on screen,
                // otherwise the hidden task page would start another task in
                // the middle of the current ad.
                boolean adOnTop = TeraboxAdCloser.isAdOnTop();
                if (adOnTop != paused) {
                    paused = adOnTop;
                    Log.i(TAG, adOnTop ? "Ad on screen, clicker paused" : "Ad closed, clicker resumed");
                }
                webView.evaluateJavascript(
                        "window.__tbTaskPaused=" + (adOnTop ? "1" : "0") + ";",
                        null);

                String url = webView.getUrl();
                if (url != null && isTaskPage(url)) {
                    if (url.hashCode() != lastUrlHash) {
                        lastUrlHash = url.hashCode();
                        Log.i(TAG, "Injecting the task clicker into " + url);
                    }
                    // The script guards itself, so repeated injection only
                    // restarts the clicker after a full page reload.
                    webView.evaluateJavascript(SCRIPT, null);
                    TeraboxTaskHud.status(webView, adOnTop ? "watching ad" : "clicking");
                } else {
                    if (url != null && url.hashCode() != lastUrlHash) {
                        lastUrlHash = url.hashCode();
                        Log.d(TAG, "Not a task page: " + url);
                    }
                    TeraboxTaskHud.hide(webView);
                }
            } catch (Throwable throwable) {
                Log.w(TAG, "Could not inject the task clicker", throwable);
            }

            if (++polls >= MAX_POLLS || isGone(webView)) {
                synchronized (ATTACHED) {
                    ATTACHED.remove(webView);
                }
                TeraboxTaskHud.hide(webView);
                // Stop polling for ads, but never leave the clicker paused.
                webView.evaluateJavascript("window.__tbTaskPaused=0;", null);
                return;
            }

            webView.postDelayed(this, POLL_INTERVAL_MS);
        }

        private boolean isGone(WebView webView) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                return false;
            }
            if (webView.isAttachedToWindow()) {
                wasAttached = true;
                return false;
            }
            // The fragment may hand the WebView over before it is attached;
            // only treat a detach as final after it has been attached once.
            return wasAttached;
        }
    }

    private static boolean isTaskPage(String url) {
        String lower = url.toLowerCase();
        for (String hint : TASK_PAGE_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * CSS targets come from the offline packs the app downloads, captured on
     * 4.27.5. The task center still renders v3 (`.task-card .task-btn` with
     * `.video-btn`, `.to-do-btn`, a plain `.btn` for a receivable reward and
     * `.complete-btn` for an exhausted one); the check-in page is v5
     * (`.sign-in-new`, `.calendar-body .sign-flash`, `.popup-btn` and the
     * `.popup-bg-box` reward popups). The `is-*` and
     * `task-section` targets cover the v5 task list when the server switches to
     * it, and the `.task-top-card` / `.coin-wrap .sign-in` targets cover the v3
     * page shell. Nothing is matched by its label. `.complete-btn`,
     * `.is-disabled`, `.notClickable`, `.remind-btn`, close buttons, the
     * makeup-card rows and the `.toGet` card-exchange link are never pressed:
     * the first makeup row spends coins on a card.
     */
    private static final String SCRIPT = String.join(" ",
            "(function(){",
            "if(window.__tbTaskClicker){return;}window.__tbTaskClicker=1;",
            "var TARGETS=['.popup-bg-box .popup-box .video-btn','.popup-bg-box .popup-box .extra-button:not(.video)','.popup-bg-box .popup-box .btn','.popup-btn','.task-card .task-btn .video-btn','.task-card .task-btn .btn.to-do-btn','.task-card .task-btn .btn:not(.video-btn):not(.to-do-btn):not(.complete-btn)','.task-section__top > button:not([disabled])','.task-card > button.is-collect','.task-card > button.is-go','.task-top-card','.welfare-header-getCoinsCard','button.check-in-pill.is-unsigned','.sign-in-new .calendar-body .single .sign-flash','.sign-in-box .calendar-body .single .sign-flash','.sign-in-box .sub-title .title-left','.sign-in-box .sign-button','.expire-coin-box .view-video-btn','.coin-wrap .sign-in'];",
            "var last=new WeakMap(),attempts=new WeakMap(),COOLDOWN=12000,RETRY=60000,MIN_GAP=3000,MAX_PER_MINUTE=10,recent=[],lastSpot=null,lastSpotAt=0;",
            "function label(el){try{var t=el.innerText||el.textContent||'';return (''+t).replace(/\\s+/g,' ').trim();}catch(e){return '';}}",
            "function visible(el){try{var r=el.getBoundingClientRect();if(r.width<4||r.height<4){return false;}var s=window.getComputedStyle(el);return s.display!=='none'&&s.visibility!=='hidden'&&parseFloat(s.opacity||'1')!==0&&s.pointerEvents!=='none';}catch(e){return false;}}",
            "function enabled(el){try{if(el.disabled){return false;}if(el.getAttribute&&(el.getAttribute('aria-disabled')==='true'||el.getAttribute('disabled')!==null)){return false;}var raw=el.className||'';if(raw&&raw.baseVal!==undefined){raw=raw.baseVal;}raw=''+raw;return !/disabl|grey|gray|claimed|received|done|expired|notClickable/i.test(raw);}catch(e){return false;}}",
            "function ready(el,now){if((attempts.get(el)||0)>=2&&now-(last.get(el)||0)<RETRY){return false;}if(now-(last.get(el)||0)<COOLDOWN){return false;}if(lastSpot&&now-lastSpotAt<COOLDOWN){var rc=el.getBoundingClientRect();if(Math.abs((rc.left+rc.right)/2-lastSpot.x)<24&&Math.abs((rc.top+rc.bottom)/2-lastSpot.y)<24){return false;}}return true;}",
            "function target(now){for(var i=0;i<TARGETS.length;i++){var list;try{list=document.querySelectorAll(TARGETS[i]);}catch(e){continue;}for(var j=0;j<list.length;j++){var el=list[j];if(!visible(el)||!enabled(el)){continue;}if(!ready(el,now)){continue;}return el;}}return null;}",
            "var busy=false,lastClick=0;",
            "function tick(){if(window.__tbTaskPaused){return;}if(busy){return;}busy=true;try{var now=Date.now();if(now-lastClick<MIN_GAP){return;}recent=recent.filter(function(t){return now-t<60000;});if(recent.length>=MAX_PER_MINUTE){return;}var best=target(now);if(!best){return;}last.set(best,now);attempts.set(best,(attempts.get(best)||0)+1);lastClick=now;recent.push(now);try{var br=best.getBoundingClientRect();lastSpot={x:(br.left+br.right)/2,y:(br.top+br.bottom)/2};lastSpotAt=now;}catch(e){}try{best.scrollIntoView({block:'center'});}catch(e){}try{best.click();}catch(e){}try{window.tbTaskHud&&window.tbTaskHud.clicked(label(best));}catch(e){}}finally{busy=false;}}",
            "function start(){setInterval(tick,1200);var lastScan=0;try{new MutationObserver(function(){var n=Date.now();if(n-lastScan<800){return;}lastScan=n;tick();}).observe(document.documentElement,{childList:true,subtree:true,characterData:true});}catch(e){}tick();}",
            "if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',start);}else{start();}",
            "})();"
    );
}
