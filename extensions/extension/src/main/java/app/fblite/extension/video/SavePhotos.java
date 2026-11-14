package app.fblite.extension.video;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.fblite.extension.settings.MorpheLog;
import app.fblite.extension.settings.MorpheSettings;

/**
 * "Save all photos": Facebook Lite's photo menu (Save, I don't want to see this, Find support or report photo) saves one photo
 * at a time. While that menu is open, a button "Save all photos (n)" shows right above it and saves every photo of the post
 * into Pictures/Facebook Lite.
 *
 * Where the post's photos are is not known for certain, so three sources are tried in turn:
 * 1. a horizontal list in the window (the photo viewer's pager): the picture addresses in all of its items;
 * 2. the picture on screen (large views read by value, as story photos are), then the list item (feed post) whose component
 *    tree holds the same picture, and every picture address of that item;
 * 3. the picture on screen alone (the button then says 1).
 * Addresses are grouped by picture (the path without the query), keeping the biggest size of each, in the order found.
 * Debug log, tag "photos": which source answered and every address (without its signature).
 */
final class SavePhotos {
    private static final Pattern SIZE = Pattern.compile("[ps](\\d{2,4})x(\\d{2,4})");
    private static final long MIN_AREA = 90_000;
    private static final long HUGE = Long.MAX_VALUE / 4;
    private static final long REFRESH_MS = 1500;

    private static View button;
    private static WeakReference<Activity> owner = new WeakReference<>(null);
    private static List<String> urls = new ArrayList<>();
    private static String baseOwner;
    private static long computedAt = -REFRESH_MS;

    private SavePhotos() {
    }

    static boolean active(Activity activity) {
        return MorpheSettings.isEnabled(activity, MorpheSettings.SAVE_ALL_PHOTOS);
    }

    /** Called from the window scan and after touches. Shows or hides the button. */
    static void update(Activity activity) {
        if (!active(activity)) {
            hide();
            return;
        }
        try {
            View root = activity.getWindow().getDecorView();
            int[] top = {Integer.MAX_VALUE};
            if (!photoMenu(root, top)) {
                hide();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - computedAt >= REFRESH_MS || urls.isEmpty()) {
                computedAt = now;
                compute(activity, root);
            }
            if (urls.size() < 1) {
                hide();
                return;
            }
            show(activity, root, top[0]);
        } catch (Throwable t) {
            VideoFeatures.log("Save all photos failed", t);
            hide();
        }
    }

    /** True when the photo menu (Save + Find support or report photo / I don't want to see this) is open; top gets the y of its first row. */
    private static boolean photoMenu(View root, int[] top) {
        boolean save = false;
        boolean other = false;
        int saveTop = Integer.MAX_VALUE;
        for (View row : SheetOptions.rowsIn(root)) {
            for (String text : Stories.viewTexts(row)) {
                String t = text.trim();
                int[] where = new int[2];
                row.getLocationInWindow(where);
                if (t.equals("save")) {
                    save = true;
                    saveTop = Math.min(saveTop, where[1]);
                }
                if (t.equals("find support or report photo") || t.equals("i don't want to see this")) {
                    other = true;
                    saveTop = Math.min(saveTop, where[1]);
                }
            }
        }
        top[0] = saveTop;
        return save && other;
    }

    // ------------------------------------------------------------------------------------ button

    private static void show(final Activity activity, View root, int sheetTop) {
        if (owner.get() != activity || button == null) create(activity);
        TextView view = (TextView) button;
        String label = "Save all photos (" + urls.size() + ")";
        if (!label.contentEquals(view.getText())) view.setText(label);
        view.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.AT_MOST));
        int gap = dp(activity, 12);
        view.setTranslationX((root.getWidth() - view.getMeasuredWidth()) / 2f);
        view.setTranslationY(Math.max(dp(activity, 8), sheetTop - view.getMeasuredHeight() - gap - dp(activity, 28)));
        view.setVisibility(View.VISIBLE);
        view.bringToFront();
    }

    private static void create(final Activity activity) {
        hide();
        TextView view = Overlay.mark(new TextView(activity));
        view.setTextColor(Color.WHITE);
        view.setTextSize(15f);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(activity, 22), dp(activity, 12), dp(activity, 22), dp(activity, 12));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFF1877F2);
        shape.setCornerRadius(dp(activity, 24));
        view.setBackground(shape);
        view.setElevation(dp(activity, 6));
        view.setContentDescription("Save all photos");
        view.setOnClickListener(v -> saveAll(activity));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
        ((ViewGroup) activity.getWindow().getDecorView()).addView(view, params);
        view.setVisibility(View.GONE);
        button = view;
        owner = new WeakReference<>(activity);
    }

    static void hide() {
        if (button != null && button.getVisibility() != View.GONE) button.setVisibility(View.GONE);
    }

    static void forget(Activity activity) {
        if (owner.get() == activity) {
            button = null;
            owner = new WeakReference<>(null);
        }
    }

    private static void saveAll(Activity activity) {
        List<String> list = new ArrayList<>(urls);
        String who = baseOwner;
        String base = FileNames.base(who, "Photo");
        MorpheLog.log("photos", "Saving " + list.size() + " photos as " + base);
        for (int i = 0; i < list.size(); i++) PhotoDownloader.start(activity, list.get(i), base + "-" + (i + 1));
        hide();
    }

    // ------------------------------------------------------------------------------------ finding the photos

    private static void compute(Activity activity, View root) {
        List<String> found = new ArrayList<>();
        String source = "none";
        baseOwner = null;
        try {
            List<View> pagers = FeedItems.pagersIn(root);
            // 1. The viewer's own horizontal list.
            for (View pager : pagers) {
                if (pager.getWidth() < root.getWidth() * 0.8f || pager.getHeight() < root.getHeight() * 0.4f || !FeedItems.isHorizontal(pager)) continue;
                List<String> all = new ArrayList<>();
                for (Object item : FeedItems.items(pager)) addUrls(FeedItems.treeOf(item), all);
                List<String> best = best(all);
                if (best.size() > found.size()) {
                    found = best;
                    source = "horizontal list with " + FeedItems.items(pager).length + " items";
                }
            }
            // 2. The picture on screen, then the feed item that holds it.
            String current = currentPhoto(root, pagers);
            if (found.size() < 2 && current != null) {
                String key = key(current);
                for (View pager : pagers) {
                    for (Object item : FeedItems.items(pager)) {
                        Object tree = FeedItems.treeOf(item);
                        if (tree == null) continue;
                        List<String> all = new ArrayList<>();
                        addUrls(tree, all);
                        boolean same = false;
                        for (String u : all) if (key(u).equals(key)) same = true;
                        if (!same) continue;
                        List<String> best = best(all);
                        if (best.size() > found.size()) {
                            found = best;
                            source = "feed item holding the picture on screen";
                            baseOwner = FeedItems.ownerName(tree);
                        }
                    }
                }
                // 3. The picture on screen alone.
                if (found.isEmpty()) {
                    found = new ArrayList<>();
                    found.add(current);
                    source = "picture on screen only";
                }
            }
        } catch (Throwable t) {
            VideoFeatures.log("Photo search failed", t);
        }
        urls = found;
        if (MorpheLog.enabled()) {
            StringBuilder sb = new StringBuilder("Photo menu open: source " + source + ", " + found.size() + " photos, owner " + baseOwner);
            for (String u : found) sb.append("\n  ").append(MorpheLog.url(u));
            MorpheLog.log("photos", sb.toString());
        }
    }

    /** The biggest picture on screen (a large view, read by value), or null. */
    private static String currentPhoto(View root, List<View> pagers) {
        try {
            String best = null;
            long bestScore = -1;
            List<View> large = new ArrayList<>();
            largeViews(root, root.getWidth(), root.getHeight(), large, 0);
            for (View view : large) {
                for (ObjectScan.Hit hit : ObjectScan.run(new Object[] {view}).urls) {
                    long score = StoryPhotos.score(hit.text);
                    if (score > bestScore) {
                        bestScore = score;
                        best = hit.text;
                    }
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void largeViews(View view, int width, int height, List<View> out, int depth) {
        if (view.getVisibility() != View.VISIBLE || depth > 30 || Overlay.OURS.equals(view.getTag())) return;
        if (FeedItems.isPager(view)) return;
        String name = view.getClass().getName();
        boolean container = name.startsWith("android.widget.LinearLayout") || name.startsWith("android.widget.FrameLayout")
                || name.startsWith("com.android.internal.") || name.equals("android.view.View");
        if (!container && view.getWidth() >= width * 0.8f && view.getHeight() >= height * 0.3f) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) largeViews(group.getChildAt(i), width, height, out, depth + 1);
        }
    }

    /** Picture addresses on Facebook's CDN held in strings of a component tree (fields, maps and lists of strings). */
    private static void addUrls(Object tree, List<String> out) {
        if (tree == null) return;
        walk(tree, 0, new int[] {0}, out);
    }

    private static void walk(Object node, int depth, int[] count, List<String> out) {
        if (node == null || depth > 30 || ++count[0] > 4000) return;
        for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    take(f.get(node), out, 0);
                } catch (Throwable ignored) {
                    // changed while reading
                }
            }
        }
        for (Object child : FeedItems.children(node)) walk(child, depth + 1, count, out);
    }

    private static void take(Object value, List<String> out, int level) {
        if (value == null || level > 1) return;
        if (value instanceof CharSequence) {
            String s = value.toString();
            if (s.startsWith("http") && isPicture(s)) out.add(s);
        } else if (value instanceof Map) {
            int n = 0;
            for (Object v : ((Map<?, ?>) value).values()) {
                if (++n > 64) break;
                take(v, out, level + 1);
            }
        } else if (value instanceof Iterable) {
            int n = 0;
            for (Object v : (Iterable<?>) value) {
                if (++n > 64) break;
                take(v, out, level + 1);
            }
        }
    }

    private static boolean isPicture(String url) {
        if (!(url.contains("fbcdn") || url.contains("scontent") || url.contains("fbsbx"))) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".mp4") || lower.contains(".m4s") || lower.contains("/emoji") || lower.contains("static.xx")) return false;
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp");
    }

    /** The picture's identity: its address without the query. */
    private static String key(String url) {
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        try {
            return URLDecoder.decode(base, "UTF-8");
        } catch (Throwable t) {
            return base;
        }
    }

    private static long area(String url) {
        Matcher m = SIZE.matcher(url);
        long area = HUGE;
        while (m.find()) {
            long a = Long.parseLong(m.group(1)) * Long.parseLong(m.group(2));
            if (a < area) area = a;
        }
        return area;
    }

    /** One address per picture: the biggest, in the order the pictures were found; thumbnails and avatars are left out. */
    private static List<String> best(List<String> all) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String url : all) {
            if (area(url) < MIN_AREA) continue;
            String k = key(url);
            String old = byKey.get(k);
            if (old == null || area(url) > area(old)) byKey.put(k, url);
        }
        return new ArrayList<>(byKey.values());
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
