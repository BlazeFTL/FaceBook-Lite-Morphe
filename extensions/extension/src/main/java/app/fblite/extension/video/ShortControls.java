package app.fblite.extension.video;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.MediaController;

import java.lang.ref.WeakReference;
import java.util.Locale;

import app.fblite.extension.settings.MorpheLog;
import app.fblite.extension.settings.MorpheSettings;

/**
 * Facebook Lite gives a seek bar and the back / forward 10 second buttons only to videos of a minute or more. This
 * draws the same controls over reels and full screen videos that are shorter: a seek bar along the bottom of the
 * video, and, while the video is paused or for a moment after a touch, back 10, play / pause and forward 10 in the
 * middle. It talks to the video's own player (MediaPlayerControl), so seeking is the app's own seeking.
 *
 * The views sit on the window's decor view like the download button, and are hidden while a comment sheet or
 * another list is drawn over the video.
 */
final class ShortControls {
    /** Videos at least this long already have the app's own controls. */
    private static final int MAX_MS = 60_000;
    private static final int SKIP_MS = 10_000;
    private static final long TICK_MS = 50;
    private static final long ROW_HOLD_MS = 3000;
    private static final long PAUSE_DEBOUNCE_MS = 700;
    private static final long COVER_CHECK_MS = 150;
    private static final float REEL_ASPECT = 1.5f;

    private static WeakReference<Activity> owner = new WeakReference<>(null);
    private static WeakReference<View> target = new WeakReference<>(null);
    private static Bar bar;
    private static Glyph back;
    private static Glyph toggle;
    private static Glyph forward;
    private static boolean shown;
    private static boolean ticking;
    private static boolean rowShown;
    private static long pausedSince;
    private static long touchedAt;
    private static long coverCheckedAt;
    private static boolean covered;
    private static final Overlay.Probe PROBE_MID = new Overlay.Probe();
    private static final Overlay.Probe PROBE_BAR = new Overlay.Probe();
    private static final Rect RECT = new Rect();
    private static final int[] LOCATION = new int[2];

    private ShortControls() {
    }

    /** Called from the scan with the video on screen (playing, or paused), or null when there is none. */
    static void update(Activity activity, View video) {
        try {
            if (video == null || !eligible(activity, video)) {
                hide();
                return;
            }
            if (owner.get() != activity || bar == null) create(activity);
            target = new WeakReference<>(video);
            if (!refresh()) {
                hide();
                return;
            }
            if (!shown) {
                shown = true;
                if (MorpheLog.enabled()) MorpheLog.log("controls", "Shown for " + Overlay.describe(video));
            }
            if (!ticking) {
                ticking = true;
                VideoFeatures.MAIN.postDelayed(TICK, TICK_MS);
            }
        } catch (Throwable t) {
            hide();
            VideoFeatures.log("Short video controls failed", t);
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
                VideoFeatures.log("Short video controls failed", t);
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
        if (bar != null) {
            bar.setVisibility(View.GONE);
            back.setVisibility(View.GONE);
            toggle.setVisibility(View.GONE);
            forward.setVisibility(View.GONE);
        }
        if (shown && MorpheLog.enabled()) MorpheLog.log("controls", "Hidden");
        shown = false;
        rowShown = false;
        pausedSince = 0;
        if (!covered) {
            PROBE_MID.reset();
            PROBE_BAR.reset();
        }
    }

    static void hideNow() {
        hide();
        covered = false;
        coverCheckedAt = 0;
        PROBE_MID.reset();
        PROBE_BAR.reset();
    }

    static void forget(Activity activity) {
        if (owner.get() != activity) return;
        hideNow();
        owner = new WeakReference<>(null);
        target = new WeakReference<>(null);
        bar = null;
        back = null;
        toggle = null;
        forward = null;
    }

    // ------------------------------------------------------------------------------------ state

    private static MediaController.MediaPlayerControl playerOf(View video) {
        Object player = VideoFeatures.playerOf(video);
        return player instanceof MediaController.MediaPlayerControl ? (MediaController.MediaPlayerControl) player : null;
    }

    /** A reel, or a video that fills the screen, whose length is under a minute. */
    private static boolean eligible(Activity activity, View video) {
        MediaController.MediaPlayerControl player = playerOf(video);
        if (player == null || video.getWidth() <= 0 || video.getHeight() <= 0) return false;
        int duration;
        try {
            duration = player.getDuration();
        } catch (Throwable t) {
            return false;
        }
        if (duration <= 0 || duration >= MAX_MS) return false;
        View decor = activity.getWindow().getDecorView();
        boolean tall = video.getHeight() >= video.getWidth() * REEL_ASPECT;
        boolean full = decor.getHeight() > 0 && video.getHeight() >= decor.getHeight() * 0.8f;
        return tall || full;
    }

    private static boolean mostlyVisible(View video) {
        return video.getHeight() > 0 && video.getGlobalVisibleRect(RECT) && RECT.height() >= video.getHeight() * 0.5f;
    }

    /** Moves the views onto the video and updates them. False when the controls should not be on screen. */
    private static boolean refresh() {
        Activity activity = owner.get();
        View video = target.get();
        if (activity == null || video == null || bar == null || !video.isAttachedToWindow() || !video.isShown()) return false;
        MediaController.MediaPlayerControl player = playerOf(video);
        if (player == null || !mostlyVisible(video)) return false;
        int duration = player.getDuration();
        int position = player.getCurrentPosition();
        boolean playing = player.isPlaying();
        if (duration <= 0 || duration >= MAX_MS) return false;

        video.getLocationInWindow(LOCATION);
        int vx = LOCATION[0];
        int vy = LOCATION[1];
        int vw = video.getWidth();
        int vh = video.getHeight();
        Context context = activity;
        int barHeight = dp(context, 48);
        int barTop = vy + vh - barHeight;
        float midX = vx + vw / 2f;
        float midY = vy + vh / 2f;
        if (coveredNow(video, midX, midY, barTop + barHeight - dp(context, 14))) return false;

        long now = SystemClock.uptimeMillis();
        if (playing) pausedSince = 0;
        else if (pausedSince == 0) pausedSince = now;
        boolean paused = !playing && now - pausedSince >= PAUSE_DEBOUNCE_MS;
        boolean row = paused || now - touchedAt < ROW_HOLD_MS || bar.dragging;

        ViewGroup.LayoutParams params = bar.getLayoutParams();
        if (params.width != vw || params.height != barHeight) {
            params.width = vw;
            params.height = barHeight;
            bar.setLayoutParams(params);
        }
        bar.setTranslationX(vx);
        bar.setTranslationY(barTop);
        if (!bar.dragging) bar.set(position, duration);
        bar.showTime = paused || bar.dragging || now - touchedAt < ROW_HOLD_MS;
        bar.invalidate();
        if (bar.getVisibility() != View.VISIBLE) {
            bar.setVisibility(View.VISIBLE);
            bar.bringToFront();
        }

        int small = dp(context, 46);
        int big = dp(context, 62);
        int gap = dp(context, 20);
        float left = midX - (small + gap + big + gap + small) / 2f;
        place(back, left, midY - small / 2f, small);
        place(toggle, left + small + gap, midY - big / 2f, big);
        place(forward, left + small + gap + big + gap, midY - small / 2f, small);
        toggle.setPlaying(playing);
        int visibility = row ? View.VISIBLE : View.GONE;
        if (back.getVisibility() != visibility) {
            back.setVisibility(visibility);
            toggle.setVisibility(visibility);
            forward.setVisibility(visibility);
            if (row) {
                back.bringToFront();
                toggle.bringToFront();
                forward.bringToFront();
            }
        }
        rowShown = row;
        return true;
    }

    private static void place(View view, float x, float y, int size) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.width != size || params.height != size) {
            params.width = size;
            params.height = size;
            view.setLayoutParams(params);
        }
        view.setTranslationX(x);
        view.setTranslationY(y);
    }

    /**
     * True while a large list that is not the video's own (the comment sheet's comments, a menu) is drawn over the
     * middle of the video or over the bar. Checked every 150 ms.
     */
    private static boolean coveredNow(View video, float midX, float midY, float barY) {
        long now = SystemClock.uptimeMillis();
        if (now - coverCheckedAt < COVER_CHECK_MS) return covered;
        coverCheckedAt = now;
        boolean result = false;
        try {
            Activity activity = owner.get();
            if (activity != null && MorpheSettings.isEnabled(activity, MorpheSettings.HIDE_OVER_COMMENTS)) {
                View pager = FeedItems.pagerOf(video);
                boolean mid = PROBE_MID.covering(video, pager, midX, midY) != null;
                boolean low = PROBE_BAR.covering(video, pager, midX, barY) != null;
                result = mid || low;
            }
        } catch (Throwable t) {
            VideoFeatures.log("Short controls cover check failed", t);
        }
        if (result != covered) {
            covered = result;
            if (MorpheLog.enabled()) MorpheLog.log("controls", result ? "Covered by another layer" : "Uncovered");
        }
        return covered;
    }

    // ------------------------------------------------------------------------------------ actions

    private static void seekTo(int milliseconds) {
        View video = target.get();
        if (video == null) return;
        MediaController.MediaPlayerControl player = playerOf(video);
        if (player == null) return;
        try {
            int duration = player.getDuration();
            player.seekTo(Math.max(0, Math.min(milliseconds, Math.max(0, duration - 100))));
        } catch (Throwable t) {
            VideoFeatures.log("Seek failed", t);
        }
    }

    private static void onGlyph(int kind) {
        View video = target.get();
        if (video == null) return;
        MediaController.MediaPlayerControl player = playerOf(video);
        if (player == null) return;
        touchedAt = SystemClock.uptimeMillis();
        try {
            if (kind == Glyph.BACK) {
                seekTo(player.getCurrentPosition() - SKIP_MS);
            } else if (kind == Glyph.FORWARD) {
                seekTo(player.getCurrentPosition() + SKIP_MS);
            } else if (player.isPlaying()) {
                player.pause();
            } else {
                player.start();
            }
        } catch (Throwable t) {
            VideoFeatures.log("Control tap failed", t);
        }
    }

    // ------------------------------------------------------------------------------------ views

    private static void create(Activity activity) {
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        if (bar != null) {
            decor.removeView(bar);
            decor.removeView(back);
            decor.removeView(toggle);
            decor.removeView(forward);
        }
        bar = Overlay.mark(new Bar(activity));
        back = Overlay.mark(new Glyph(activity, Glyph.BACK));
        toggle = Overlay.mark(new Glyph(activity, Glyph.TOGGLE));
        forward = Overlay.mark(new Glyph(activity, Glyph.FORWARD));
        back.setContentDescription("Back 10 seconds");
        toggle.setContentDescription("Play or pause");
        forward.setContentDescription("Forward 10 seconds");
        View[] all = {bar, back, toggle, forward};
        for (View view : all) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(activity, 40), dp(activity, 40),
                    Gravity.TOP | Gravity.START);
            view.setVisibility(View.GONE);
            decor.addView(view, params);
        }
        owner = new WeakReference<>(activity);
        shown = false;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static String time(int milliseconds) {
        int seconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** The seek bar: a thin track with a thumb, the time above it while paused or touched. */
    private static final class Bar extends View {
        private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;
        private int position;
        private int duration;
        boolean dragging;
        boolean showTime;

        Bar(Context context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            shade.setColor(0x40000000);
            track.setColor(0x80FFFFFF);
            fill.setColor(0xFFFFFFFF);
            thumb.setColor(0xFFFFFFFF);
            label.setColor(0xFFFFFFFF);
            label.setTextSize(13f * density);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setShadowLayer(3f * density, 0f, 0f, 0xB0000000);
            setClickable(true);
        }

        void set(int positionMs, int durationMs) {
            position = positionMs;
            duration = durationMs;
        }

        private float fraction() {
            return duration <= 0 ? 0f : Math.max(0f, Math.min(1f, position / (float) duration));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float pad = 14f * density;
            float left = pad;
            float right = getWidth() - pad;
            float cy = getHeight() - 14f * density;
            float height = (dragging ? 4f : 3f) * density;
            rect.set(left, cy - height / 2f - density, right, cy + height / 2f + density);
            canvas.drawRoundRect(rect, height, height, shade);
            rect.set(left, cy - height / 2f, right, cy + height / 2f);
            canvas.drawRoundRect(rect, height / 2f, height / 2f, track);
            float x = left + (right - left) * fraction();
            rect.set(left, cy - height / 2f, x, cy + height / 2f);
            canvas.drawRoundRect(rect, height / 2f, height / 2f, fill);
            canvas.drawCircle(x, cy, (dragging ? 8f : 5f) * density, thumb);
            if (showTime) {
                canvas.drawText(time(position) + " / " + time(duration), pad, cy - 16f * density, label);
            }
        }

        private void moveTo(float x) {
            float pad = 14f * density;
            float width = Math.max(1f, getWidth() - 2f * pad);
            float fraction = Math.max(0f, Math.min(1f, (x - pad) / width));
            position = Math.round(duration * fraction);
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    touchedAt = SystemClock.uptimeMillis();
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    moveTo(event.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (dragging) {
                        touchedAt = SystemClock.uptimeMillis();
                        moveTo(event.getX());
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        moveTo(event.getX());
                        seekTo(position);
                    }
                    dragging = false;
                    touchedAt = SystemClock.uptimeMillis();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }

    /** One round button of the middle row: back 10, play / pause, or forward 10. */
    private static final class Glyph extends View {
        static final int BACK = 0;
        static final int TOGGLE = 1;
        static final int FORWARD = 2;

        private final int kind;
        private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint digits = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF arc = new RectF();
        private boolean playing;

        Glyph(Context context, final int kind) {
            super(context);
            this.kind = kind;
            disc.setColor(0x8C000000);
            stroke.setColor(0xFFFFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            solid.setColor(0xFFFFFFFF);
            digits.setColor(0xFFFFFFFF);
            digits.setTextAlign(Paint.Align.CENTER);
            digits.setTypeface(Typeface.DEFAULT_BOLD);
            setClickable(true);
            setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onGlyph(kind);
                }
            });
        }

        void setPlaying(boolean value) {
            if (playing == value) return;
            playing = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawCircle(cx, cy, r, disc);
            if (kind == TOGGLE) {
                path.reset();
                if (playing) {
                    canvas.drawRoundRect(cx - 0.32f * r, cy - 0.36f * r, cx - 0.1f * r, cy + 0.36f * r, 0.05f * r, 0.05f * r, solid);
                    canvas.drawRoundRect(cx + 0.1f * r, cy - 0.36f * r, cx + 0.32f * r, cy + 0.36f * r, 0.05f * r, 0.05f * r, solid);
                } else {
                    path.moveTo(cx - 0.22f * r, cy - 0.4f * r);
                    path.lineTo(cx - 0.22f * r, cy + 0.4f * r);
                    path.lineTo(cx + 0.44f * r, cy);
                    path.close();
                    canvas.drawPath(path, solid);
                }
                return;
            }
            drawSkip(canvas, cx, cy, r, kind == FORWARD);
        }

        private void drawSkip(Canvas canvas, float cx, float cy, float r, boolean clockwise) {
            float radius = r * 0.55f;
            arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
            stroke.setStrokeWidth(r * 0.12f);
            float start = -45f;
            float sweep = 280f;
            canvas.save();
            if (!clockwise) canvas.scale(-1f, 1f, cx, cy);
            canvas.drawArc(arc, start, sweep, false, stroke);
            double angle = Math.toRadians(start + sweep);
            float px = cx + radius * (float) Math.cos(angle);
            float py = cy + radius * (float) Math.sin(angle);
            float dx = (float) -Math.sin(angle);
            float dy = (float) Math.cos(angle);
            float s = r * 0.3f;
            float bx = px - dx * s * 0.3f;
            float by = py - dy * s * 0.3f;
            path.reset();
            path.moveTo(px + dx * s * 0.9f, py + dy * s * 0.9f);
            path.lineTo(bx - dy * s * 0.8f, by + dx * s * 0.8f);
            path.lineTo(bx + dy * s * 0.8f, by - dx * s * 0.8f);
            path.close();
            canvas.drawPath(path, solid);
            canvas.restore();
            digits.setTextSize(r * 0.52f);
            canvas.drawText("10", cx, cy + r * 0.18f, digits);
        }
    }
}
