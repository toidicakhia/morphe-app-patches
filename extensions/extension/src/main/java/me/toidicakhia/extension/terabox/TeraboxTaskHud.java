package me.toidicakhia.extension.terabox;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Overlays for the task automation: a status card on the task-center activity
 * and a small pill on the ad activity that covers it.
 *
 * The card keeps three lines so the two kinds of feedback never overwrite each
 * other: the current state ("clicking", "watching ad"), the control that was
 * pressed last, and - when the clicker is idle - the reason it is waiting.
 * Presses are counted across activities, because the task center is recreated
 * often enough that a per-Activity counter would keep restarting at 1.
 */
@SuppressWarnings("unused")
public final class TeraboxTaskHud {

    private static final int BACKGROUND = 0xD9101418;

    private static final int ACCENT = 0xFF7BE3A1;

    /** Used while the clicker is deliberately idle (ad on screen, rate limit). */
    private static final int ACCENT_WAIT = 0xFFE8C46A;

    private static final int TEXT = 0xFFEDEFF2;

    private static final int MUTED = 0xFF9BA5AF;

    private static final int MAX_LABEL_LENGTH = 60;

    /** Presses made by the automation in this process. */
    private static final AtomicInteger PRESSES = new AtomicInteger();

    private static final Map<Activity, Holder> HOLDERS =
            Collections.synchronizedMap(new WeakHashMap<Activity, Holder>());

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * The ad plays in its own Activity, which covers the task page and its HUD.
     * The pill is non-touchable, so the SDK's own skip/close controls still
     * receive every event.
     */
    private static WeakReference<Activity> adHost = new WeakReference<>(null);

    private static TextView adView;

    private TeraboxTaskHud() {
    }

    static void status(WebView webView, String status) {
        final Activity activity = activityOf(webView);
        final Holder holder = holder(activity, true);
        if (holder == null) {
            return;
        }
        holder.attachTo(activity);
        holder.setStatus(status);
    }

    static void clicked(WebView webView, final String label) {
        final Holder holder = holder(activityOf(webView), false);
        if (holder == null || label == null) {
            return;
        }
        holder.clicked(label);
    }

    static void note(WebView webView, final String reason) {
        final Holder holder = holder(activityOf(webView), false);
        if (holder == null || reason == null) {
            return;
        }
        holder.note(reason);
    }

    static void hide(WebView webView) {
        final Activity activity = activityOf(webView);
        if (activity == null) {
            return;
        }

        final Holder holder;
        synchronized (HOLDERS) {
            holder = HOLDERS.remove(activity);
        }
        if (holder != null) {
            holder.detach();
        }
    }

    /**
     * Fallback close for the check-in page, used when its own {@code closePage}
     * bridge does not answer. Only the sign-in page may be finished: it is a
     * dedicated page, while the task center itself is not.
     */
    static void finishPage(final WebView webView) {
        if (webView == null) {
            return;
        }
        final Activity activity = activityOf(webView);
        if (activity == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = webView.getUrl();
                    if (url == null || !url.toLowerCase().contains("taskcenter/signin")) {
                        return;
                    }
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        activity.finish();
                    }
                } catch (Throwable ignored) {
                    // The page (or its activity) can go away in between.
                }
            }
        });
    }

    /** The holder of an activity, created and registered only when asked. */
    private static Holder holder(Activity activity, boolean create) {
        if (activity == null) {
            return null;
        }
        synchronized (HOLDERS) {
            Holder holder = HOLDERS.get(activity);
            if (holder == null && create) {
                holder = Holder.create(activity);
                if (holder != null) {
                    HOLDERS.put(activity, holder);
                }
            }
            return holder;
        }
    }

    /**
     * Shows (or keeps) the "watching ad" pill on the Activity that is playing
     * the ad. It is re-added if the ad redraws its decor.
     */
    static void adOverlay(Activity activity, final String text) {
        if (activity == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (adHost.get() != activity) {
                        removeFromParent(adView);
                        adView = null;
                        adHost = new WeakReference<>(activity);
                    }
                    if (adView == null) {
                        // Application context so the pill does not keep the ad
                        // Activity alive after it is finished.
                        adView = createAdView(activity.getApplicationContext());
                    }
                    if (adView == null) {
                        return;
                    }
                    if (!text.equals(adView.getText().toString())) {
                        adView.setText(text);
                    }
                    final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                    if (decor == null) {
                        return;
                    }
                    if (adView.getParent() != decor) {
                        removeFromParent(adView);
                        decor.addView(adView, adParams(activity));
                        adView.bringToFront();
                    }
                } catch (Throwable ignored) {
                    // The ad window can go away between the post and this run.
                }
            }
        });
    }

    static void hideAdOverlay() {
        if (adView == null && adHost.get() == null) {
            return;
        }
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                removeFromParent(adView);
                adView = null;
                adHost = new WeakReference<>(null);
            }
        });
    }

    private static TextView createAdView(Context context) {
        try {
            final TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            view.setTextColor(ACCENT);
            view.setPadding(dp(context, 12), dp(context, 5), dp(context, 12), dp(context, 5));
            view.setClickable(false);
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
            view.setSingleLine(true);

            final GradientDrawable background = new GradientDrawable();
            background.setColor(BACKGROUND);
            background.setCornerRadius(dp(context, 10));
            view.setBackground(background);
            return view;
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static FrameLayout.LayoutParams adParams(Context context) {
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(context, 28);
        return params;
    }

    private static void removeFromParent(View view) {
        try {
            if (view == null) {
                return;
            }
            final ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) {
                parent.removeView(view);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    /** Walk a WebView's (possibly wrapped) context to the hosting Activity. */
    static Activity activityOf(WebView webView) {
        if (webView == null) {
            return null;
        }

        Context context = webView.getContext();
        while (context != null) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            if (!(context instanceof ContextWrapper)) {
                return null;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static final class Holder {

        private final LinearLayout root;

        private final TextView title;

        private final TextView action;

        private final TextView note;

        private final FrameLayout.LayoutParams params;

        private String status = "";

        private String lastAction = "waiting for a task button";

        private String lastNote = "";

        private Holder(LinearLayout root, TextView title, TextView action, TextView note,
                       FrameLayout.LayoutParams params) {
            this.root = root;
            this.title = title;
            this.action = action;
            this.note = note;
            this.params = params;
            this.action.setText(lastAction);
        }

        static Holder create(Activity activity) {
            try {
                // Application context so the view does not keep the activity
                // alive through the holder map.
                final Context context = activity.getApplicationContext();

                final LinearLayout root = new LinearLayout(context);
                root.setOrientation(LinearLayout.VERTICAL);
                root.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));
                root.setClickable(false);
                root.setFocusable(false);

                final GradientDrawable background = new GradientDrawable();
                background.setColor(BACKGROUND);
                background.setCornerRadius(dp(context, 8));
                root.setBackground(background);

                final TextView title = new TextView(context);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                title.setTextColor(ACCENT);
                title.setText("Auto task");
                root.addView(title);

                final TextView action = new TextView(context);
                action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                action.setTextColor(TEXT);
                root.addView(action);

                final TextView note = new TextView(context);
                note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                note.setTextColor(MUTED);
                note.setVisibility(View.GONE);
                root.addView(note);

                final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                params.gravity = Gravity.TOP | Gravity.START;
                params.leftMargin = dp(context, 8);
                params.topMargin = dp(context, 40);

                return new Holder(root, title, action, note, params);
            } catch (Throwable throwable) {
                return null;
            }
        }

        /**
         * Re-adds the card when the activity redraws its decor (theme change,
         * window replacement): a stale parent would otherwise hide it for good.
         */
        void attachTo(Activity activity) {
            try {
                final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
                if (decor == null || root.getParent() == decor) {
                    return;
                }
                removeFromParent(root);
                decor.addView(root, params);
            } catch (Throwable ignored) {
            }
        }

        void setStatus(String value) {
            if (value == null || value.equals(status)) {
                return;
            }
            status = value;
            title.setText("Auto task · " + value);
            title.setTextColor(isWaiting(value) ? ACCENT_WAIT : ACCENT);
        }

        void clicked(final String label) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    lastAction = PRESSES.incrementAndGet() + " · " + shorten(label);
                    action.setText(lastAction);
                    // A press answers the previous wait reason.
                    lastNote = "";
                    note.setVisibility(View.GONE);
                }
            });
        }

        void note(final String reason) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    final String value = shorten(reason);
                    if (value.equals(lastNote)) {
                        return;
                    }
                    lastNote = value;
                    note.setText(value);
                    note.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
                }
            });
        }

        void detach() {
            removeFromParent(root);
        }

        /** States where nothing is being pressed on purpose. */
        private static boolean isWaiting(String value) {
            return value.startsWith("watching")
                    || value.startsWith("waiting")
                    || value.startsWith("reward received")
                    || value.startsWith("rate limit")
                    || value.startsWith("no matching control");
        }

        private static String shorten(String label) {
            final String value = label.replaceAll("\\s+", " ").trim();
            return value.length() > MAX_LABEL_LENGTH
                    ? value.substring(0, MAX_LABEL_LENGTH) + "…"
                    : value;
        }
    }
}
