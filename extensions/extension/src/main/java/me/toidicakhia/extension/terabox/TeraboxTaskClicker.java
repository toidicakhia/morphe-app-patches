package me.toidicakhia.extension.terabox;

import android.os.Build;
import android.util.Log;
import android.webkit.WebView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps the TeraBox task-center page clicking itself.
 *
 * The page is remote, so instead of matching a fixed layout the injected script
 * looks for controls whose label mentions watching/claiming a reward, skips
 * destructive ones, and presses them one by one with a cooldown.
 */
@SuppressWarnings("unused")
public final class TeraboxTaskClicker {

    private static final String TAG = "TeraboxTaskClicker";

    private static final long POLL_INTERVAL_MS = 1500L;

    private static final int MAX_POLLS = 1200;

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

        webView.post(new Poller(webView));
    }

    private static final class Poller implements Runnable {

        private final WeakReference<WebView> webViewRef;

        private boolean wasAttached;

        private int polls;

        private int lastUrlHash;

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
                String url = webView.getUrl();
                if (url != null && isTaskPage(url)) {
                    if (url.hashCode() != lastUrlHash) {
                        lastUrlHash = url.hashCode();
                        Log.i(TAG, "Injecting the task clicker into " + url);
                    }
                    // The script guards itself, so repeated injection only
                    // restarts the clicker after a full page reload.
                    webView.evaluateJavascript(SCRIPT, null);
                } else if (url != null && url.hashCode() != lastUrlHash) {
                    lastUrlHash = url.hashCode();
                    Log.d(TAG, "Not a task page: " + url);
                }
            } catch (Throwable throwable) {
                Log.w(TAG, "Could not inject the task clicker", throwable);
            }

            if (++polls >= MAX_POLLS || isGone(webView)) {
                synchronized (ATTACHED) {
                    ATTACHED.remove(webView);
                }
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

    private static final String SCRIPT = String.join(" ",
            "(function(){",
            "if(window.__tbTaskClicker){return;}window.__tbTaskClicker=1;",
            "var POS=['watch','view ad','claim','collect','receive','get reward','earn','check in','sign in','daily','xem','nhận','lấy','điểm danh','quảng cáo','nhận thưởng','领取','領取','觀看','观看','获得','獲取','签到','簽到','받기','수령','출석','получить','смотреть','受け取','視聴','รับ','รับรางวัล'];",
            "var NEG=['buy','purchase','pay','subscribe','recharge','top up','upgrade','vip','premium','delete','remove','logout','sign out','log out','cancel','back','close','share','invite','follow','install','download','open app','claimed','already','completed','expired','mua','thanh toán','nâng cấp','xoá','xóa','đăng xuất','huỷ','hủy','chia sẻ','mời','cài đặt','tải xuống','đã nhận','đã lĩnh','hết hạn','购买','支付','订阅','刪除','删除','退出','取消','分享','邀请','邀請','下載','下载','已领取','已領取','已完成','설치','다운로드','구매','결제','삭제','로그아웃','купить','оплатить','удалить','выход','購入','支払','ダウンロード','インストール'];",
            "var SEL='button,[role=button],a,[class*=btn],[class*=Btn],[class*=button],[class*=claim],[class*=Claim],[class*=reward],[class*=Reward],[class*=task],[class*=Task],[class*=receive],[class*=Receive]';",
            "var last=new WeakMap(),attempts=new WeakMap(),COOLDOWN=8000,RETRY=120000,MIN_GAP=1100,MAX_PER_MINUTE=25,recent=[];",
            "function txt(el){try{var t=el.innerText||el.textContent||'';return (''+t).replace(/\\s+/g,' ').trim().toLowerCase();}catch(e){return '';}}",
            "function visible(el){try{var r=el.getBoundingClientRect();if(r.width<4||r.height<4){return false;}var s=window.getComputedStyle(el);return s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0'&&s.pointerEvents!=='none';}catch(e){return false;}}",
            "function enabled(el){try{if(el.disabled){return false;}if(el.getAttribute&&(el.getAttribute('aria-disabled')==='true'||el.getAttribute('disabled')!==null)){return false;}var raw=el.className||'';if(raw&&raw.baseVal!==undefined){raw=raw.baseVal;}raw=raw+' '+(el.getAttribute?(''+(el.getAttribute('data-status')||'')):'');return !/disabl|grey|gray|claimed|received|done|complet|finish|expired|vô hiệu|đã nhận|领取过|已完成/i.test(raw);}catch(e){return false;}}",
            "function score(el){var t=txt(el);if(!t||t.length>48){return 0;}for(var i=0;i<NEG.length;i++){if(t.indexOf(NEG[i])>=0){return 0;}}var s=0;for(var j=0;j<POS.length;j++){if(t.indexOf(POS[j])>=0){s+=3;break;}}var c=''+(el.className||'');if(/claim|reward|task|btn|button/i.test(c)){s+=1;}return s;}",
            "function deep(root,out){try{var all=root.querySelectorAll(SEL);for(var i=0;i<all.length;i++){out.push(all[i]);}var nodes=root.querySelectorAll('*');for(var j=0;j<nodes.length;j++){if(nodes[j].shadowRoot){deep(nodes[j].shadowRoot,out);}}var frames=root.querySelectorAll('iframe');for(var k=0;k<frames.length;k++){try{if(frames[k].contentDocument){deep(frames[k].contentDocument,out);}}catch(e){}}}catch(e){}}",
            "var busy=false,lastClick=0;",
            "function tick(){if(busy){return;}busy=true;try{var now=Date.now();if(now-lastClick<MIN_GAP){return;}recent=recent.filter(function(t){return now-t<60000;});if(recent.length>=MAX_PER_MINUTE){return;}var list=[],best=null,bestScore=0;deep(document,list);for(var i=0;i<list.length;i++){var el=list[i];if(!visible(el)||!enabled(el)){continue;}var sc=score(el);if(!sc){continue;}var tries=attempts.get(el)||0;if(tries>=4&&now-(last.get(el)||0)<RETRY){continue;}if(now-(last.get(el)||0)<COOLDOWN){continue;}if(sc>bestScore){best=el;bestScore=sc;}}if(best){last.set(best,now);attempts.set(best,(attempts.get(best)||0)+1);lastClick=now;recent.push(now);try{best.scrollIntoView({block:'center'});}catch(e){}try{best.click();}catch(e){}}}finally{busy=false;}}",
            "function start(){setInterval(tick,1200);try{new MutationObserver(function(){tick();}).observe(document.documentElement,{childList:true,subtree:true,characterData:true});}catch(e){}tick();}",
            "if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',start);}else{start();}",
            "})();"
    );
}
