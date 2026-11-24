package me.toidicakhia.extension.terabox;

/**
 * The script injected into the task-center pages.
 *
 * TeraBox ships two task-center layouts at the same time (the server A/B tests
 * them), so the script understands both:
 *
 * <ul>
 *   <li>the 4.29 "Gold center" (<code>taskcenter-v2</code>), whose class names
 *       come from the offline pack the app downloads,</li>
 *   <li>the older pack, kept for devices still served it.</li>
 * </ul>
 *
 * The script is idempotent: it installs itself once per page, exposes
 * {@code window.__tbTick} (forced tick) and {@code window.__tbTaskStop}
 * (teardown), and reads {@code window.__tbTaskPaused} which the poller sets
 * while an ad covers the page.
 *
 * Sections, in order: redirect guard, selectors, legacy-pack helpers, DOM
 * helpers, matching, tick, bootstrap.
 */
final class TeraboxTaskScript {

    private TeraboxTaskScript() {
    }

    static final String SOURCE = String.join(" ",
            "(function(){",
            "if(window.__tbTaskClicker){return;}window.__tbTaskClicker=1;",

            // --- redirect guard -------------------------------------------------
            // A mis-tapped ad or task card must not hand the screen to the Play
            // Store or a browser: TeraBox would go to the background and the
            // clicker would pause until the user comes back.
            "try{window.open=function(){note('blocked a web popup');return null;};}catch(e){}",
            "try{document.addEventListener('click',function(ev){try{var a=ev.target&&ev.target.closest?ev.target.closest('a[href]'):null;if(!a)return;var h=''+(a.getAttribute('href')||'');if(/^market:|^intent:|play\\.google\\.com/i.test(h)){ev.preventDefault();ev.stopPropagation();note('blocked an ad redirect');}}catch(e2){}},true);}catch(e){}",

            // --- selectors ------------------------------------------------------
            // Sentinels handled by their own matcher instead of querySelectorAll.
            "var WATCH_GROUP='__WATCH_AD_GROUP__';",
            "var WATCH_CARD='__WATCH_CARD__';",
            "var SIGN_ENTRY='.coin-wrap .sign-in';",
            "var CHECK_PILL='button.check-in-pill.is-unsigned';",
            "var WATCH_SELECTOR='.task-btn-wrap .btn.btn-go,.task-btn-wrap .btn.btn-collect';",

            // A card is a watch-video task when its own text says so.
            "var WATCH_WORDS=/xem video|xem clip|xem quảng cáo|xem quang cao|watch (a |an )?video|watch (a )?clip|watch ad|观看视频|看视频|观看广告|動画|동영상|ver video|assistir|смотреть видео|reklam izle/i;",
            "var BAD_CARD=/chia sẻ|chia se|lưu tệp|luu tep|thợ đào|tho dao|đào vàng|dao vang|mời bạn|moi ban|mua|nạp|nap tien|đổi thẻ|doi the|subscribe|purchase|upgrade|premium|invite|share|save|uninstall|gỡ cài đặt|go cai dat|cài đặt|đánh giá|rate|tải app|download/i;",

            // Targets that spend an ad when pressed.
            "var AD_TARGETS=[WATCH_GROUP,WATCH_CARD,'button.tc-v2-lucky-bag__cta:not(.is-completed)','.task-top-card','.welfare-header-getCoinsCard','.expire-coin-box .view-video-btn','.tc-lucky-bag__cta:not(.is-completed)','.tc-lb-dialog__cta'];",

            "var TARGETS=[",
            // Popups first: they cover the page.
            "'.base-popup-card .base-popup-btn',",
            "'.popup-card .popup-btn',",
            "'.popup-bg-box .popup-box .video-btn:not(.notClickable)',",
            "'.popup-bg-box .popup-box .btn:not(.notClickable)',",
            "'.popup-btn',",
            "'.reward-toast .extra-button.video',",
            "'.reward-toast .extra-button:not(.video)',",
            // Gold center: free claim, check-in, video chain, daily watch task.
            "'.tc-task-card-v2 .task-btn-wrap .btn.btn-collect',",
            "CHECK_PILL,",
            "'button.tc-v2-lucky-bag__cta:not(.is-completed)',",
            "WATCH_CARD,",
            // Check-in page.
            "'.calendar-body .sign-flash',",
            // Older pack.
            "SIGN_ENTRY,",
            "'.sign-in-box .sub-title .title-left',",
            "'.sign-in-box .sign-button',",
            "WATCH_GROUP,",
            "'.task-top-card',",
            "'.welfare-header-getCoinsCard',",
            "'.expire-coin-box .view-video-btn',",
            "'.tc-lucky-bag__cta:not(.is-completed)',",
            "'.tc-lb-dialog__cta'",
            "];",

            "var MODALS='.popup-bg-box,.popup-mask,.dialog-mask,.base-popup-mask,.base-popup-card,.popup-card,.auto-sign-rewards-mask,.continuous-sign-in-popup,.sign-rules,.makeup-card-content,.task-sheet-mask,.record-drawer-mask,.guide-ad-mask,.reward-toast,.watch-video-popup,.tc-v2-lucky-bag-poll-mask,[role=dialog],.modal';",
            "var LOADERS='.loading-box,.el-m-toast__spinner,.task-card__loading';",

            // --- timing ---------------------------------------------------------
            // COOLDOWN per control, DEAD after a press that changed nothing,
            // BLOCK after a task that never completed, SWEEP when the page has
            // had no candidate for a while.
            "var TICK=500,MIN_TICK=200,SETTLE=300,VERIFY=1500,MIN_GAP=1000,PER_MIN=10,COOLDOWN=6000,DEAD=45000,BLOCK=1800000,SWEEP=45000,AD_GAP=4000,AD_TRIES=3,SIGN_TRIES=3,SIGN_GAP=15000,SIGN_IDLE=8000,CLOSE_TRIES=3,CLOSE_GAP=4000;",
            "var dead={},blocked={},adTries={},lastAt={},lastNote='',noteAt=0,recent=[],scrolled='',pending=null,url=(window.location&&window.location.href)||'',pageAt=Date.now(),aliveAt=0,loadStart=0,quietSince=0,adGap=0,signAt=0,signTries=0,closeTries=0,closeAt=0,busy=false,lastClick=0,lastSig='',lastRun=0,lastScan=0,lastDumpAt=0,timer=0,observer=null;",

            // --- DOM helpers ----------------------------------------------------
            "function label(el){try{return (''+((el.innerText||el.textContent)||'')).replace(/\\s+/g,' ').trim();}catch(e){return '';}}",
            "function cls(el){try{var c=el&&el.className;return ''+((c&&c.baseVal!==undefined)?c.baseVal:c||'');}catch(e){return '';}}",
            "function note(m){try{var n=Date.now();if(m===lastNote&&n-noteAt<5000)return;lastNote=m;noteAt=n;window.tbTaskHud&&window.tbTaskHud.note(m);}catch(e){}}",
            "function hud(t){try{window.tbTaskHud&&window.tbTaskHud.clicked(t);}catch(e){}}",
            "function visible(el){try{var r=el.getBoundingClientRect();if(r.width<4||r.height<4)return false;var s=window.getComputedStyle(el);return s.display!=='none'&&s.visibility!=='hidden'&&parseFloat(s.opacity||'1')!==0;}catch(e){return false;}}",
            "function enabled(el){try{if(el.disabled)return false;if(el.getAttribute&&(el.getAttribute('aria-disabled')==='true'||el.getAttribute('disabled')!==null))return false;if(/disabl|notClickable|is-disabled|is-completed|complete-btn|grey|gray|claimed|received|expired/i.test(cls(el)))return false;if(el.closest&&el.closest('.adx,.adx-two,.notClickable'))return false;if(/mua gói|nap tien|nạp tiền|đăng ký|dang ky|subscribe|purchase|top ?up|recharge|upgrade|đổi thẻ|doi the|exchange|share|chia sẻ|invite|mời bạn|uninstall|gỡ cài đặt/i.test(label(el)))return false;return true;}catch(e){return false;}}",
            "function onScreen(el){try{var r=el.getBoundingClientRect(),h=window.innerHeight||1e9,w=window.innerWidth||1e9;return r.bottom>0&&r.right>0&&r.top<h&&r.left<w;}catch(e){return false;}}",
            "function covered(el){try{var r=el.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;if(x<0||y<0||x>(window.innerWidth||1e9)||y>(window.innerHeight||1e9))return false;var top=document.elementFromPoint(x,y);if(!top||top===el||el.contains&&el.contains(top)||top.contains&&top.contains(el))return false;if(top.getAttribute&&top.getAttribute('role')==='dialog')return true;for(var p=top;p&&p!==document.body;p=p.parentElement){if(/popup-bg-box|popup-mask|dialog-mask|base-popup-mask|base-popup-card|popup-card|auto-sign-rewards-mask|continuous-sign-in-popup|sign-rules|makeup-card-content|task-sheet-mask|record-drawer-mask|guide-ad-mask|reward-toast|watch-video-popup|poll-mask/.test(cls(p)))return true;}}catch(e){}return false;}",
            "function loading(){try{var n=document.querySelectorAll(LOADERS);for(var i=0;i<n.length;i++){var r=n[i].getBoundingClientRect();if(visible(n[i])&&r.width>=40&&r.height>=40)return true;}}catch(e){}return false;}",

            // --- candidate matching ---------------------------------------------
            "function watchGroup(){var groups;try{groups=document.querySelectorAll('.tc-task-group');}catch(e){return null;}for(var i=0;i<groups.length;i++){var head=groups[i].querySelector('.group-title');if(!head)continue;var text=(''+((head.innerText||head.textContent)||'')).replace(/\\s+/g,' ').trim();if(WATCH_WORDS.test(text))return groups[i];}return null;}",
            "function watchTargets(now,i){var g=watchGroup();if(!g)return [];var list;try{list=g.querySelectorAll(WATCH_SELECTOR);}catch(e){return [];}var out=[];for(var j=0;j<list.length;j++){var el=list[j];if(visible(el)&&enabled(el)&&ready(el,i,now))out.push(el);}return out;}",
            "function watchDone(){try{var g=watchGroup();if(!g)return false;var list=g.querySelectorAll('.task-btn-wrap .btn');if(!list||!list.length)return false;for(var i=0;i<list.length;i++){if(enabled(list[i]))return false;}return true;}catch(e){return false;}}",
            "function watchCardTargets(now,i){var cards;try{cards=document.querySelectorAll('.tc-task-card-v2');}catch(e){return [];}var out=[];for(var j=0;j<cards.length;j++){var card=cards[j];var text=label(card);if(!text||!WATCH_WORDS.test(text)||BAD_CARD.test(text))continue;var btn=card.querySelector('.task-btn-wrap .btn.btn-go');if(!btn)continue;if(visible(btn)&&enabled(btn)&&ready(btn,i,now))out.push(btn);}return out;}",
            "function sigOf(){var o=[];for(var i=0;i<TARGETS.length;i++){try{o.push(TARGETS[i]===WATCH_GROUP?watchTargets(Date.now(),i).length:TARGETS[i]===WATCH_CARD?watchCardTargets(Date.now(),i).length:document.querySelectorAll(TARGETS[i]).length);}catch(e){o.push('x');}}return o.join(',');}",
            "function keyOf(el,i){var idx=0,p=el.parentElement;if(p){var s=p.children;for(var k=0;k<s.length;k++){if(s[k]===el){idx=k;break;}}}return i+'#'+idx+'#'+label(el).slice(0,24);}",
            "function opensAd(i){return AD_TARGETS.indexOf(TARGETS[i])>=0;}",
            "function ready(el,i,now){var k=keyOf(el,i);if(blocked[k]&&now-blocked[k]<BLOCK)return false;if(dead[k]&&now-dead[k]<DEAD)return false;if(now-(lastAt[k]||0)<COOLDOWN)return false;if(TARGETS[i]===SIGN_ENTRY&&now-signAt<SIGN_GAP)return false;return true;}",
            "function pick(now){for(var i=0;i<TARGETS.length;i++){var t=TARGETS[i];if(t===SIGN_ENTRY&&signTries>=SIGN_TRIES)continue;if(t===WATCH_GROUP&&watchDone())continue;if(t===CHECK_PILL&&window.tbTaskHud&&window.tbTaskHud.canCheckIn&&!window.tbTaskHud.canCheckIn())continue;var list;if(t===WATCH_GROUP){list=watchTargets(now,i);}else if(t===WATCH_CARD){list=watchCardTargets(now,i);}else{try{list=document.querySelectorAll(t);}catch(e){continue;}}for(var j=0;j<list.length;j++){var el=list[j];if(!visible(el)||!enabled(el)||!ready(el,i,now))continue;return {el:el,i:i,key:keyOf(el,i),checkIn:t===CHECK_PILL};}}return null;}",

            // --- check-in -------------------------------------------------------
            "function signDone(){try{var href=(window.location&&window.location.href)||'';if(href.indexOf('signin')<0)return false;var cells=document.querySelectorAll('.calendar-body .single');if(cells.length){if(document.querySelector('.sign-flash'))return false;return true;}if(!document.querySelector('.calendar-body')&&!document.querySelector('.sign-in-box'))return false;var m=document.querySelectorAll(MODALS);for(var i=0;i<m.length;i++){if(visible(m[i]))return false;}if(document.querySelector('.sign-flash'))return false;for(var j=0;j<cells.length;j++){if(cells[j].querySelector('img'))return true;}return false;}catch(e){return false;}}",
            "function closePage(){try{var f=document.createElement('iframe');f.style.display='none';f.src='dubox://page/'+Date.now()+'/closePage?params=%7B%7D';document.documentElement.appendChild(f);setTimeout(function(){try{f.remove();}catch(e){}},1000);}catch(e){}try{setTimeout(function(){try{if((window.location&&window.location.href||'').indexOf('signin')>=0&&window.history&&window.history.length>1)window.history.back();}catch(e){}},900);}catch(e){}try{setTimeout(function(){try{if((window.location&&window.location.href||'').indexOf('signin')>=0&&window.tbTaskHud&&window.tbTaskHud.closePage)window.tbTaskHud.closePage();}catch(e){}},2600);}catch(e){}}",

            // --- diagnostics ----------------------------------------------------
            // Logged to logcat when the page shows nothing this script knows.
            "function pageOutline(){try{var out=[],sels=['button','div.btn','.tc-task-card-v2','button.tc-v2-lucky-bag__cta','button.check-in-pill','a'],seen={};for(var i=0;i<sels.length;i++){var list=document.querySelectorAll(sels[i]);for(var j=0;j<list.length&&out.length<10;j++){var el=list[j];if(!visible(el))continue;var t=label(el).slice(0,24);if(!t||seen[t])continue;seen[t]=1;out.push(t);}}}catch(e){}return out.join(' | ');}",

            // --- tick -----------------------------------------------------------
            "function tick(){",
            "var href=(window.location&&window.location.href)||'';",
            "if(pending&&(sigOf()!==pending.sig||href!==pending.url))pending=null;",
            "if(window.__tbTaskPaused){pending=null;quietSince=0;note('ad on screen, waiting');return;}",
            "if(busy)return;",
            "var now=Date.now();",
            "if(now-lastRun<MIN_TICK)return;lastRun=now;",
            "busy=true;",
            "try{",
            "if(href!==url){url=href;pageAt=now;scrolled='';pending=null;quietSince=0;aliveAt=0;closeTries=0;closeAt=0;}",
            "if(document.hidden){note('page is hidden');return;}",
            "if(pending&&now-pending.at>=(pending.ad?VERIFY*3:VERIFY)){dead[pending.key]=now;pending=null;note('that control had no effect, skipping it for a while');}",
            "if(now-pageAt<SETTLE){note('page just loaded');return;}",
            "var cand=pick(now);",
            "if(!cand&&loading()){if(!loadStart)loadStart=now;if(now-loadStart<3000){note('waiting for the page to finish loading');return;}}",
            "loadStart=0;",
            "if(cand)aliveAt=now;else if(!aliveAt)aliveAt=now;",
            "if(!cand){",
            "if(!quietSince)quietSince=now;",
            "if(now-aliveAt>=SWEEP){dead={};aliveAt=now;note('retrying the controls that failed');return;}",
            "if(now-quietSince>=SIGN_IDLE&&signDone()&&closeTries<CLOSE_TRIES&&now-closeAt>=CLOSE_GAP){closeTries++;closeAt=now;closePage();note('check-in complete, closing the page');return;}",
            "if(now-lastDumpAt>=15000){lastDumpAt=now;var o=pageOutline();if(o&&window.tbTaskHud&&window.tbTaskHud.log)window.tbTaskHud.log(o);}",
            "note('no matching control on this page');",
            "return;",
            "}",
            "quietSince=now;",
            "if(!onScreen(cand.el)&&scrolled!==cand.key){scrolled=cand.key;try{cand.el.scrollIntoView({block:'center'});}catch(e){try{cand.el.scrollIntoView();}catch(e2){}}note('scrolling to the control');return;}",
            "scrolled='';",
            "if(covered(cand.el)){note('waiting, a modal is on top');return;}",
            "var ad=opensAd(cand.i);",
            "if(ad&&(adTries[cand.key]||0)>=AD_TRIES){blocked[cand.key]=now;note('that task did not complete, leaving it');return;}",
            "if(ad&&now-adGap<AD_GAP){note('waiting for the last ad to open');return;}",
            "if(now-lastClick<MIN_GAP)return;",
            "recent=recent.filter(function(t){return now-t<60000;});",
            "if(recent.length>=PER_MIN){note('rate limit reached');return;}",
            "if(cand.checkIn&&window.tbTaskHud&&window.tbTaskHud.allowCheckIn&&!window.tbTaskHud.allowCheckIn()){note('check-in already pressed');return;}",
            "if(ad)adGap=now;",
            "if(ad)adTries[cand.key]=(adTries[cand.key]||0)+1;",
            "lastAt[cand.key]=now;lastClick=now;recent.push(now);",
            "if(TARGETS[cand.i]===SIGN_ENTRY){signTries++;signAt=now;}",
            "pending={key:cand.key,ad:ad,sig:sigOf(),url:href,at:now};",
            "try{cand.el.click();}catch(e){}",
            "hud(label(cand.el)||cand.key);",
            "}finally{busy=false;}}",

            // --- bootstrap ------------------------------------------------------
            "function start(){lastSig=sigOf();window.__tbTick=function(){lastRun=0;tick();};window.__tbTaskStop=function(){try{clearInterval(timer);}catch(e){}try{if(observer)observer.disconnect();}catch(e){}window.__tbTaskClicker=0;};timer=setInterval(tick,TICK);try{observer=new MutationObserver(function(){var n=Date.now();if(n-lastScan<200)return;lastScan=n;if(document.hidden)return;var s=sigOf();if(s!==lastSig){lastSig=s;quietSince=0;scrolled='';}tick();});observer.observe(document.documentElement,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['class','disabled','aria-disabled']});}catch(e){}tick();}",
            "if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',start);}else{start();}",
            "})();"
    );
}
