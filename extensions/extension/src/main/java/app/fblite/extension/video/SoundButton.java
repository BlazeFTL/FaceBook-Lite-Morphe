package app.fblite.extension.video;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.fblite.extension.settings.MorpheLog;
import app.fblite.extension.settings.MorpheSettings;

/**
 * Facebook Lite draws its speaker (mute) button only for longer videos, and hides it while a video is paused. This
 * puts a speaker in the top right corner of every reel and full screen video, playing or paused, unless the app's own
 * speaker is on screen. A tap presses the app's own speaker view (R.id.sound_image_view), so the sound and the app's
 * state change the way the app does it, even when that view is hidden at the time (it is shown for the moment of the
 * click).
 */
final class SoundButton {
    private static final float REEL_ASPECT = 1.5f;
    private static final long COVER_CHECK_MS = 150;
    private static final long TICK_MS = 100;

    private static WeakReference<Activity> owner = new WeakReference<>(null);
    private static WeakReference<View> target = new WeakReference<>(null);
    private static Icon icon;
    private static boolean shown;
    private static boolean ticking;
    private static boolean muted;
    private static boolean covered;
    private static long coverCheckedAt;
    private static long clickedAt;
    private static Drawable.ConstantState lastState;
    private static WeakReference<View> lastSoundFor = new WeakReference<>(null);
    private static String lastReason = "";
    private static final Overlay.Probe PROBE = new Overlay.Probe();
    private static final Rect FRAME = new Rect();
    private static final Rect RECT = new Rect();
    private static final int[] LOCATION = new int[2];

    private SoundButton() {
    }

    /** Called from the scan with the video on screen (playing or paused), or null when there is none. */
    static void update(Activity activity, View video) {
        try {
            if (video == null) {
                hide();
                return;
            }
            if (owner.get() != activity || icon == null) create(activity);
            target = new WeakReference<>(video);
            if (!refresh()) {
                hide();
                return;
            }
            if (!shown) {
                shown = true;
                if (MorpheLog.enabled()) MorpheLog.log("sound", "Shown for " + Overlay.describe(video));
            }
            if (!ticking) {
                ticking = true;
                VideoFeatures.MAIN.postDelayed(TICK, TICK_MS);
            }
        } catch (Throwable t) {
            hide();
            VideoFeatures.log("Sound button failed", t);
        }
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            if (!shown) return;
            boolean ok;
            try {
                ok = refresh();
            } catch (Throwable t) {
                ok = false;
                VideoFeatures.log("Sound button failed", t);
            }
            if (!ok) {
                hide();
                return;
            }
            ticking = true;
            VideoFeatures.MAIN.postDelayed(this, TICK_MS);
        }
    };

    static void hide() {
        VideoFeatures.MAIN.removeCallbacks(TICK);
        ticking = false;
        if (icon != null) icon.setVisibility(View.GONE);
        if (shown && MorpheLog.enabled()) MorpheLog.log("sound", "Hidden (" + lastReason + ")");
        shown = false;
        if (!covered) PROBE.reset();
    }

    static void hideNow() {
        hide();
        covered = false;
        coverCheckedAt = 0;
        PROBE.reset();
    }

    static void forget(Activity activity) {
        if (owner.get() != activity) return;
        hideNow();
        owner = new WeakReference<>(null);
        target = new WeakReference<>(null);
        icon = null;
    }

    private static boolean fail(String reason) {
        lastReason = reason;
        return false;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int idOf(Activity activity, String name) {
        try {
            return activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The app's own speaker view of this video, or null. */
    private static ImageView soundOf(Activity activity, View video) {
        int id = idOf(activity, "sound_image_view");
        if (id == 0) return null;
        View pager = FeedItems.pagerOf(video);
        View item = pager != null ? FeedItems.childOf(pager, video) : null;
        if (item != null) {
            View found = item.findViewById(id);
            if (found instanceof ImageView) return (ImageView) found;
        }
        View scope = video;
        for (int level = 0; level < 8 && scope != null; level++) {
            View found = scope.findViewById(id);
            if (found instanceof ImageView) return (ImageView) found;
            ViewParent parent = scope.getParent();
            scope = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static boolean refresh() {
        Activity activity = owner.get();
        View video = target.get();
        if (activity == null || video == null || icon == null || !video.isAttachedToWindow() || !video.isShown()) {
            return fail("video not on screen");
        }
        if (video.getWidth() <= 0 || video.getHeight() <= 0) return fail("video has no size");
        View decor = activity.getWindow().getDecorView();
        boolean tall = video.getHeight() >= video.getWidth() * REEL_ASPECT;
        boolean full = decor.getHeight() > 0 && video.getHeight() >= decor.getHeight() * 0.8f;
        if (!tall && !full) return fail("not a reel or full screen video");
        if (!video.getGlobalVisibleRect(RECT) || RECT.height() < video.getHeight() * 0.5f) return fail("video mostly off screen");

        ImageView sound = soundOf(activity, video);
        if (sound == null) return fail("no speaker view in the video");
        if (MorpheLog.enabled() && lastSoundFor.get() != video) {
            lastSoundFor = new WeakReference<>(video);
            sound.getLocationInWindow(LOCATION);
            MorpheLog.log("sound", "Speaker view of the app at " + LOCATION[0] + "," + LOCATION[1] + " " + sound.getWidth() + "x"
                    + sound.getHeight() + " showing=" + ShortControls.showing(sound) + " clickable=" + sound.isClickable());
        }
        // Follow the app's own speaker when it changes by itself (the app's button was used).
        Drawable drawable = sound.getDrawable();
        Drawable.ConstantState state = drawable == null ? null : drawable.getConstantState();
        if (state != null && lastState != null && state != lastState && SystemClock.uptimeMillis() - clickedAt > 400) muted = !muted;
        if (state != null) lastState = state;
        if (ShortControls.showing(sound)) return fail("the app's own speaker is showing");

        video.getLocationInWindow(LOCATION);
        int vx = LOCATION[0];
        int vy = LOCATION[1];
        int vw = video.getWidth();
        decor.getWindowVisibleDisplayFrame(FRAME);
        int size = dp(activity, 40);
        int margin = dp(activity, 12);
        float x = vx + vw - size - margin;
        float y = Math.max(vy, FRAME.top) + margin;
        if (coveredNow(video, x + size / 2f, y + size / 2f)) return fail("covered by another layer");

        ViewGroup.LayoutParams params = icon.getLayoutParams();
        if (params.width != size || params.height != size) {
            params.width = size;
            params.height = size;
            icon.setLayoutParams(params);
        }
        icon.setTranslationX(x);
        icon.setTranslationY(y);
        icon.setMuted(muted);
        if (icon.getVisibility() != View.VISIBLE) {
            icon.setVisibility(View.VISIBLE);
            icon.bringToFront();
        }
        return true;
    }

    private static boolean coveredNow(View video, float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (now - coverCheckedAt < COVER_CHECK_MS) return covered;
        coverCheckedAt = now;
        boolean result = false;
        try {
            Activity activity = owner.get();
            if (activity != null && MorpheSettings.isEnabled(activity, MorpheSettings.HIDE_OVER_COMMENTS)) {
                result = PROBE.covering(video, FeedItems.pagerOf(video), x, y) != null;
            }
        } catch (Throwable t) {
            VideoFeatures.log("Sound button cover check failed", t);
        }
        covered = result;
        return covered;
    }

    // ------------------------------------------------------------------------------------ action

    private static void onTap() {
        Activity activity = owner.get();
        View video = target.get();
        if (activity == null || video == null) return;
        final ImageView sound = soundOf(activity, video);
        if (sound == null) return;
        // The app may ignore a press on a view it hides, so it is shown for the moment of the press.
        final List<Object[]> saved = new ArrayList<>();
        View v = sound;
        while (v != null) {
            if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.99f) {
                saved.add(new Object[] {v, v.getVisibility(), v.getAlpha()});
                v.setVisibility(View.VISIBLE);
                v.setAlpha(1f);
            }
            ViewParent parent = v.getParent();
            v = parent instanceof View && parent != video.getRootView() ? (View) parent : null;
        }
        clickedAt = SystemClock.uptimeMillis();
        boolean pressed = sound.performClick();
        muted = !muted;
        if (icon != null) icon.setMuted(muted);
        if (MorpheLog.enabled()) {
            MorpheLog.log("sound", "Tap: pressed the app's speaker view = " + pressed + ", now " + (muted ? "muted" : "sound on")
                    + ", views shown for the press " + saved.size());
        }
        VideoFeatures.MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                for (Object[] entry : saved) {
                    View view = (View) entry[0];
                    view.setVisibility((Integer) entry[1]);
                    view.setAlpha((Float) entry[2]);
                }
                Drawable drawable = sound.getDrawable();
                if (drawable != null) lastState = drawable.getConstantState();
            }
        }, 120);
    }

    // ------------------------------------------------------------------------------------ view

    private static void create(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (icon != null && icon.getParent() instanceof ViewGroup) ((ViewGroup) icon.getParent()).removeView(icon);
        icon = Overlay.mark(new Icon(activity));
        icon.setContentDescription("Mute or unmute");
        icon.setVisibility(View.GONE);
        decor.addView(icon, new FrameLayout.LayoutParams(dp(activity, 40), dp(activity, 40), Gravity.TOP | Gravity.START));
        owner = new WeakReference<>(activity);
        shown = false;
    }

    /** A round speaker button: a speaker with waves, or with a cross when muted. */
    private static final class Icon extends View {
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF arc = new RectF();
        private boolean mutedNow;

        Icon(Context context) {
            super(context);
            disc.setColor(0x8C000000);
            solid.setColor(0xFFFFFFFF);
            stroke.setColor(0xFFFFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            setClickable(true);
            setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onTap();
                }
            });
        }

        void setMuted(boolean value) {
            if (mutedNow == value) return;
            mutedNow = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawCircle(cx, cy, r, disc);
            path.reset();
            path.moveTo(cx - 0.50f * r, cy - 0.18f * r);
            path.lineTo(cx - 0.28f * r, cy - 0.18f * r);
            path.lineTo(cx + 0.02f * r, cy - 0.46f * r);
            path.lineTo(cx + 0.02f * r, cy + 0.46f * r);
            path.lineTo(cx - 0.28f * r, cy + 0.18f * r);
            path.lineTo(cx - 0.50f * r, cy + 0.18f * r);
            path.close();
            canvas.drawPath(path, solid);
            stroke.setStrokeWidth(r * 0.1f);
            if (mutedNow) {
                float x0 = cx + 0.22f * r;
                float x1 = cx + 0.58f * r;
                canvas.drawLine(x0, cy - 0.18f * r, x1, cy + 0.18f * r, stroke);
                canvas.drawLine(x0, cy + 0.18f * r, x1, cy - 0.18f * r, stroke);
            } else {
                arc.set(cx - 0.22f * r, cy - 0.3f * r, cx + 0.3f * r, cy + 0.3f * r);
                canvas.drawArc(arc, -50f, 100f, false, stroke);
                arc.set(cx - 0.28f * r, cy - 0.52f * r, cx + 0.5f * r, cy + 0.52f * r);
                canvas.drawArc(arc, -50f, 100f, false, stroke);
            }
        }
    }
}
