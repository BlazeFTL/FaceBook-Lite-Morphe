package app.fblite.extension.video;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * A floating button over the video that is playing, which saves it to Movies/Facebook Lite, see
 * {@link VideoDownloader}. On Reels it sits in the column of Like, Comment and Share, one slot above Like.
 */
final class DownloadButton {
    /**
     * Reels layout, measured on a 702 px wide screenshot of the video area and scaled by width: the action
     * column is centered 35 px from the right edge, anchored to the bottom, and the Like icon starts 320 px
     * above the bottom. The button's center is one slot (about 96 px) above the Like icon.
     */
    private static final float REFERENCE_WIDTH = 702f;
    private static final float COLUMN_CENTER_FROM_RIGHT = 35f;
    private static final float BUTTON_CENTER_FROM_BOTTOM = 392f;
    private static final float REEL_BUTTON_SIZE = 54f;
    /** A video this much taller than wide is a Reel (feed videos are at most 4:5). */
    private static final float REEL_ASPECT = 1.5f;

    private static ImageView button;
    private static WeakReference<Activity> owner = new WeakReference<>(null);
    private static WeakReference<View> target = new WeakReference<>(null);
    private static final Rect RECT = new Rect();

    private DownloadButton() {
    }

    static void show(Activity activity, View video) {
        if (owner.get() != activity || button == null) create(activity);
        target = new WeakReference<>(video);
        if (!video.getGlobalVisibleRect(RECT)) {
            hide();
            return;
        }
        int size;
        int left;
        int top;
        if (isReel(video)) {
            float unit = RECT.width() / REFERENCE_WIDTH;
            size = Math.max(dp(activity, 32), Math.round(REEL_BUTTON_SIZE * unit));
            left = RECT.right - Math.round(COLUMN_CENTER_FROM_RIGHT * unit) - size / 2;
            top = RECT.bottom - Math.round(BUTTON_CENTER_FROM_BOTTOM * unit) - size / 2;
        } else {
            size = dp(activity, 44);
            int margin = dp(activity, 12);
            // Right edge, a third of the way down: clear of the full screen player's controls.
            top = RECT.top + Math.max(margin, Math.min(RECT.height() / 3, RECT.height() - size - margin));
            left = RECT.right - size - margin;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) button.getLayoutParams();
        if (params.width != size || params.height != size || params.leftMargin != left || params.topMargin != top) {
            params.width = size;
            params.height = size;
            params.leftMargin = left;
            params.topMargin = top;
            button.setLayoutParams(params);
        }
        button.setVisibility(View.VISIBLE);
        button.bringToFront();
    }

    static void hide() {
        if (button != null) button.setVisibility(View.GONE);
    }

    static void forget(Activity activity) {
        if (owner.get() == activity) {
            owner = new WeakReference<>(null);
            button = null;
        }
    }

    private static void create(final Activity activity) {
        int size = dp(activity, 44);
        ImageView view = new ImageView(activity);
        view.setImageResource(android.R.drawable.stat_sys_download);
        view.setColorFilter(0xFFFFFFFF);
        int pad = dp(activity, 10);
        view.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(0x99000000);
        view.setBackground(background);
        view.setContentDescription(vi() ? "Tải video" : "Download video");
        view.setOnClickListener(v -> download(activity));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
        ((ViewGroup) activity.getWindow().getDecorView()).addView(view, params);
        view.setVisibility(View.GONE);
        button = view;
        owner = new WeakReference<>(activity);
    }

    private static void download(Activity activity) {
        View video = target.get();
        if (video == null) return;
        try {
            if (Build.VERSION.SDK_INT < 29 && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0x4d6f);
                return;
            }
            VideoDownloader.Source source = source(video);
            if (source == null) {
                toast(activity, vi() ? "Không lấy được link video này" : "This video has no downloadable link");
                return;
            }
            VideoDownloader.start(activity, source);
        } catch (Throwable t) {
            VideoFeatures.log("Download failed", t);
            toast(activity, vi() ? "Tải video thất bại" : "Download failed");
        }
    }

    /** The progressive MP4 URL, the video id the server sent and the objects that may hold a DASH manifest. Live videos give null. */
    private static VideoDownloader.Source source(View video) throws Exception {
        Class<?> videoView = VideoFeatures.videoViewClass();
        if (Boolean.TRUE.equals(VideoFeatures.field(video, videoView, "A0m"))) return null;
        String url = null;
        String id = null;
        Object command = null;
        // Full screen player: its show-video command.
        for (Class<?> c = video.getClass(); c != null && c != videoView; c = c.getSuperclass()) {
            if (c.getName().endsWith("FBFullScreenVideoView")) {
                command = VideoFeatures.field(video, c, "A05");
                if (command != null) {
                    url = (String) field(command, "A0G");
                    id = (String) field(command, "A0D");
                }
            }
        }
        // Inline, feed and Reels players: their props.
        Object props = VideoFeatures.field(video, videoView, "A0F");
        if (props != null && isEmpty(url)) {
            url = (String) field(props, "A0c");
            id = (String) field(props, "A0b");
        }
        if (isEmpty(url) || !url.startsWith("http")) url = null;
        return new VideoDownloader.Source(url, id, props, command);
    }

    private static boolean isReel(View video) {
        if (video.getWidth() <= 0 || video.getHeight() < video.getWidth() * REEL_ASPECT) return false;
        for (Class<?> c = video.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().endsWith("FBFullScreenVideoView")) return false;
        }
        return true;
    }

    private static Object field(Object target, String name) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static boolean vi() {
        return "vi".equals(Locale.getDefault().getLanguage());
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
