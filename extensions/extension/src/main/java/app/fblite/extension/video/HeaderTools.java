package app.fblite.extension.video;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import app.fblite.extension.settings.MorpheLog;
import app.fblite.extension.settings.MorpheSettings;

/**
 * Long press on the "facebook" logo (top left of the Home screen) opens the Morphe settings.
 *
 * The logo is not a view this extension can name: the screen is drawn from server components, and the inspector shows
 * it as a plain, childless ViewGroup. So the press is watched on the window (a proxy around the activity's window
 * callback sees every touch and passes it on untouched), and the press must start in the logo's zone, top left under the
 * status bar. Before the settings open, the zone is drawn into a small bitmap and must hold the logo's blue, so a long
 * press on a back arrow or a title does nothing. With Debug logging on, every long press in the zone writes what is
 * there ({@link HeaderProbe}, tags "logo" and "header"), which is how the real element names are found.
 */
final class HeaderTools {
    private static final String SETTINGS = "app.fblite.extension.settings.SettingsActivity";
    private static final float REFERENCE_WIDTH = 1080f;
    /** The logo zone, on a 1080 px wide window: from the left edge to 34% of the width, one header row (105 px) under the status bar. */
    private static final float ZONE_RIGHT = 0.34f;
    private static final float ZONE_HEIGHT = 105f;
    private static final float BLUE_MIN = 0.02f;

    private static boolean pressing;
    private static float downX;
    private static float downY;
    private static WeakReference<Activity> pressActivity = new WeakReference<>(null);
    private static final Runnable LONG = new Runnable() {
        @Override
        public void run() {
            pressing = false;
            Activity activity = pressActivity.get();
            if (activity == null) return;
            try {
                fire(activity);
            } catch (Throwable t) {
                VideoFeatures.log("Logo long press failed", t);
            }
        }
    };

    private HeaderTools() {
    }

    /** Puts the watcher around the activity's window callback, once (it is put back if the app replaces the callback). */
    static void attach(Activity activity) {
        try {
            Window window = activity.getWindow();
            Window.Callback current = window.getCallback();
            if (current == null) return;
            if (Proxy.isProxyClass(current.getClass()) && Proxy.getInvocationHandler(current) instanceof Watcher) return;
            Window.Callback proxy = (Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                    new Class<?>[] {Window.Callback.class}, new Watcher(activity, current));
            window.setCallback(proxy);
        } catch (Throwable t) {
            VideoFeatures.log("Could not watch touches", t);
        }
    }

    private static final class Watcher implements InvocationHandler {
        private final WeakReference<Activity> activity;
        private final Window.Callback target;

        Watcher(Activity activity, Window.Callback target) {
            this.activity = new WeakReference<>(activity);
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && args[0] instanceof MotionEvent && "dispatchTouchEvent".equals(method.getName())) {
                Activity a = activity.get();
                if (a != null) {
                    try {
                        onTouch(a, (MotionEvent) args[0]);
                    } catch (Throwable t) {
                        // Never get in the way of a touch.
                    }
                }
            }
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
    }

    private static void onTouch(Activity activity, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP) SheetOptions.burst(activity);
        if (action == MotionEvent.ACTION_DOWN) {
            cancel();
            if (!MorpheSettings.isEnabled(activity, MorpheSettings.LOGO_SETTINGS)) return;
            View decor = activity.getWindow().getDecorView();
            int[] where = new int[2];
            decor.getLocationOnScreen(where);
            if (!inZone(decor, event.getRawX() - where[0], event.getRawY() - where[1])) return;
            pressing = true;
            downX = event.getRawX();
            downY = event.getRawY();
            pressActivity = new WeakReference<>(activity);
            VideoFeatures.MAIN.postDelayed(LONG, ViewConfiguration.getLongPressTimeout() + 150);
        } else if (pressing) {
            if (action == MotionEvent.ACTION_MOVE) {
                float slop = ViewConfiguration.get(activity).getScaledTouchSlop() * 2f;
                if (Math.abs(event.getRawX() - downX) > slop || Math.abs(event.getRawY() - downY) > slop) cancel();
            } else {
                cancel();
            }
        }
    }

    private static void cancel() {
        pressing = false;
        VideoFeatures.MAIN.removeCallbacks(LONG);
    }

    static int topInset(View decor) {
        if (Build.VERSION.SDK_INT >= 23) {
            WindowInsets insets = decor.getRootWindowInsets();
            if (insets != null) return insets.getSystemWindowInsetTop();
        }
        return Math.round(decor.getWidth() * 0.083f);
    }

    private static int[] zone(View decor) {
        int width = decor.getWidth();
        int top = topInset(decor);
        return new int[] {0, top, Math.round(width * ZONE_RIGHT), top + Math.round(width * ZONE_HEIGHT / REFERENCE_WIDTH)};
    }

    private static boolean inZone(View decor, float x, float y) {
        if (decor.getWidth() <= 0) return false;
        int[] z = zone(decor);
        return x >= z[0] && x <= z[2] && y >= z[1] && y <= z[3];
    }

    private static void fire(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        int[] where = new int[2];
        decor.getLocationOnScreen(where);
        float ratio = blueRatio(decor, zone(decor));
        // -1: the zone drew as one flat colour, so it cannot be checked (the window may not draw in software): trust the zone.
        boolean logo = ratio < 0 || ratio >= BLUE_MIN;
        MorpheLog.log("logo", "Long press in the logo zone at " + Math.round(downX - where[0]) + "," + Math.round(downY - where[1])
                + ", window " + decor.getWidth() + "x" + decor.getHeight() + ", blue share " + ratio + " -> " + (logo ? "opening settings" : "not the logo"));
        if (MorpheLog.enabled()) HeaderProbe.dump(activity, downX - where[0], downY - where[1]);
        if (!logo) return;
        decor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Intent intent = new Intent();
        intent.setClassName(activity, SETTINGS);
        activity.startActivity(intent);
    }

    /**
     * Share of the zone's pixels that are the logo's blue (#1877F2 in the light theme, a lighter blue in the dark one), or
     * -1 when the zone is one flat colour.
     */
    private static float blueRatio(View decor, int[] zone) {
        int w = zone[2] - zone[0];
        int h = zone[3] - zone[1];
        if (w <= 0 || h <= 0) return -1f;
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.translate(-zone[0], -zone[1]);
            decor.draw(canvas);
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            int blue = 0;
            int total = 0;
            int first = pixels[0];
            boolean flat = true;
            for (int i = 0; i < pixels.length; i += 2) {
                int p = pixels[i];
                if (p != first) flat = false;
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                if (b >= 180 && b - r >= 80 && b - g >= 40) blue++;
                total++;
            }
            if (flat) return -1f;
            return total == 0 ? -1f : blue / (float) total;
        } catch (Throwable t) {
            return -1f;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }
}
