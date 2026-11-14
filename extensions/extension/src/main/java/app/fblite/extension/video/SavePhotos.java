package app.fblite.extension.video;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
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
 * at a time. While that menu is open, a fourth row "Save all photos (n)" is added at its bottom, in the menu's own look, and
 * saves every photo of the post into Pictures/Facebook Lite.
 *
 * The menu is a view group (handle row + one 136 px row per option, from the log). The menu is moved up by one row and the
 * freed strip at the bottom holds the new row (a view of ours on top of the window), so the app's layout is not touched.
 *
 * Where the post's photos are is not known for certain, so sources are tried in turn:
 * 1. a horizontal list in the window (a viewer's pager): the picture addresses in all of its items;
 * 2. the picture on screen (large views, read by value), then the feed item (list item) whose tree holds the same picture,
 *    and every picture address in that item;
 * 3. every picture address found by value in the layers of the window (the viewer screen), when that gives more;
 * 4. the picture on screen alone.
 * Addresses are grouped by picture (the path without the query), keeping the biggest size of each, in the order found.
 * Debug log, tag "photos": the source that answered, every address (without its signature), and per source how many.
 */
final class SavePhotos {
    private static final Pattern SIZE = Pattern.compile("[ps](\\d{2,4})x(\\d{2,4})");
    private static final long MIN_AREA = 90_000;
    private static final long HUGE = Long.MAX_VALUE / 4;
    private static final long REFRESH_MS = 1500;

    private static Row row;
    private static WeakReference<Activity> owner = new WeakReference<>(null);
    private static WeakReference<ViewGroup> movedSheet = new WeakReference<>(null);
    private static List<String> urls = new ArrayList<>();
    private static String baseOwner;
    private static long computedAt = -REFRESH_MS;
    private static String lastLog = "";

    private SavePhotos() {
    }

    static boolean active(Activity activity) {
        return MorpheSettings.isEnabled(activity, MorpheSettings.SAVE_ALL_PHOTOS);
    }

    /** The photo menu: the view group that holds the Save row and a Find support / I don't want to see this row. */
    private static final class Menu {
        ViewGroup sheet;
        int rowHeight;
    }

    private static Menu findMenu(View root) {
        View save = null;
        View other = null;
        for (View candidate : SheetOptions.rowsIn(root)) {
            for (String text : Stories.viewTexts(candidate)) {
                String t = SheetOptions.norm(text);
                if (t.equals("save")) save = candidate;
                if (t.equals("find support or report photo") || t.equals("i don't want to see this")) other = candidate;
            }
        }
        if (save == null || other == null || !(save.getParent() instanceof ViewGroup) || save.getParent() != other.getParent()) return null;
        Menu menu = new Menu();
        menu.sheet = (ViewGroup) save.getParent();
        menu.rowHeight = save.getHeight();
        return menu;
    }

    /** Called from the window scan and after touches. Shows or hides the new row. */
    static void update(Activity activity) {
        if (!active(activity)) {
            hide();
            return;
        }
        try {
            View root = activity.getWindow().getDecorView();
            Menu menu = findMenu(root);
            if (menu == null || menu.rowHeight <= 0) {
                hide();
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - computedAt >= REFRESH_MS || urls.isEmpty()) {
                computedAt = now;
                compute(activity, root);
            }
            if (urls.isEmpty()) {
                hide();
                return;
            }
            show(activity, root, menu);
        } catch (Throwable t) {
            VideoFeatures.log("Save all photos failed", t);
            hide();
        }
    }

    // ------------------------------------------------------------------------------------ the row

    private static void show(final Activity activity, View root, Menu menu) {
        ViewGroup sheet = menu.sheet;
        // The menu slides in; the row is added once it stands still (translation 0, or already moved by one row).
        float ty = sheet.getTranslationY();
        if (ty != 0f && ty != -menu.rowHeight) return;
        if (owner.get() != activity || row == null) create(activity);
        ViewGroup old = movedSheet.get();
        if (old != null && old != sheet) old.setTranslationY(0f);
        sheet.setTranslationY(-menu.rowHeight);
        movedSheet = new WeakReference<>(sheet);
        int[] where = new int[2];
        sheet.getLocationInWindow(where);
        row.setLabel("Save all photos (" + urls.size() + ")");
        row.setTranslationX(where[0]);
        row.setTranslationY(where[1] + sheet.getHeight());
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params.width != sheet.getWidth() || params.height != menu.rowHeight) {
            params.width = sheet.getWidth();
            params.height = menu.rowHeight;
            row.setLayoutParams(params);
        }
        row.setVisibility(View.VISIBLE);
        row.bringToFront();
    }

    private static void create(final Activity activity) {
        hide();
        boolean night = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        Row view = Overlay.mark(new Row(activity, night));
        view.setOnClickListener(v -> saveAll(activity));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100,
                android.view.Gravity.TOP | android.view.Gravity.LEFT);
        ((ViewGroup) activity.getWindow().getDecorView()).addView(view, params);
        view.setVisibility(View.GONE);
        row = view;
        owner = new WeakReference<>(activity);
    }

    static void hide() {
        ViewGroup sheet = movedSheet.get();
        if (sheet != null) sheet.setTranslationY(0f);
        movedSheet = new WeakReference<>(null);
        if (row != null && row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
    }

    static void forget(Activity activity) {
        if (owner.get() == activity) {
            row = null;
            owner = new WeakReference<>(null);
        }
    }

    private static void saveAll(Activity activity) {
        List<String> list = new ArrayList<>(urls);
        String base = FileNames.base(baseOwner, "Photo");
        MorpheLog.log("photos", "Saving " + list.size() + " photos as " + base);
        for (int i = 0; i < list.size(); i++) PhotoDownloader.start(activity, list.get(i), base + "-" + (i + 1));
        hide();
        // Close the menu the way Back does.
        long t = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0));
        activity.dispatchKeyEvent(new KeyEvent(t, t + 30, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0));
    }

    /** A row in the look of the menu's rows: white (or dark) with a thin line above, an icon and a label. */
    private static final class Row extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path icon = new Path();
        private final boolean night;
        private String label = "";

        Row(Activity activity, boolean night) {
            super(activity);
            this.night = night;
            setClickable(true);
            setContentDescription("Save all photos");
        }

        void setLabel(String text) {
            if (!text.equals(label)) {
                label = text;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float u = w / 1080f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(night ? 0xFF242526 : 0xFFFFFFFF);
            canvas.drawRect(0, 0, w, h, paint);
            paint.setColor(night ? 0xFF3E4042 : 0xFFE4E6EB);
            canvas.drawRect(0, 0, w, Math.max(1f, 2f * u), paint);
            int ink = night ? 0xFFE4E6EB : 0xFF1C1E21;
            paint.setColor(ink);
            paint.setTextSize(44f * u);
            canvas.drawText(label, 100f * u, h / 2f + 15f * u, paint);
            // Download icon: an arrow into a tray, 38 px wide at x=29.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f * u);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            float cx = 48f * u;
            float cy = h / 2f;
            icon.reset();
            icon.moveTo(cx, cy - 16f * u);
            icon.lineTo(cx, cy + 8f * u);
            icon.moveTo(cx - 10f * u, cy - 2f * u);
            icon.lineTo(cx, cy + 8f * u);
            icon.lineTo(cx + 10f * u, cy - 2f * u);
            icon.moveTo(cx - 19f * u, cy + 8f * u);
            icon.lineTo(cx - 19f * u, cy + 18f * u);
            icon.lineTo(cx + 19f * u, cy + 18f * u);
            icon.lineTo(cx + 19f * u, cy + 8f * u);
            canvas.drawPath(icon, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    // ------------------------------------------------------------------------------------ finding the photos

    private static void compute(Activity activity, View root) {
        List<String> found = new ArrayList<>();
        String source = "none";
        StringBuilder detail = MorpheLog.enabled() ? new StringBuilder() : null;
        baseOwner = null;
        try {
            List<View> pagers = FeedItems.pagersIn(root);
            // 1. A viewer's own horizontal list.
            for (View pager : pagers) {
                if (pager.getWidth() < root.getWidth() * 0.8f || pager.getHeight() < root.getHeight() * 0.4f || !FeedItems.isHorizontal(pager)) continue;
                List<String> all = new ArrayList<>();
                for (Object item : FeedItems.items(pager)) all.addAll(picturesIn(FeedItems.treeOf(item)));
                List<String> best = best(all);
                if (detail != null) detail.append("\n  horizontal list: ").append(all.size()).append(" addresses, ").append(best.size()).append(" photos");
                if (best.size() > found.size()) {
                    found = best;
                    source = "horizontal list";
                }
            }
            // 2. The picture on screen, then the feed item that holds it.
            List<String> screen = screenPictures(root);
            String current = biggest(screen);
            if (detail != null) detail.append("\n  on screen: ").append(screen.size()).append(" addresses");
            if (current != null) {
                String key = key(current);
                for (View pager : pagers) {
                    Object[] items = FeedItems.items(pager);
                    for (int i = 0; i < items.length; i++) {
                        Object tree = FeedItems.treeOf(items[i]);
                        if (tree == null) continue;
                        List<String> all = picturesIn(tree);
                        if (all.isEmpty()) continue;
                        boolean same = false;
                        for (String u : all) if (key(u).equals(key)) same = true;
                        if (detail != null) detail.append("\n  item ").append(i).append(": ").append(all.size()).append(" addresses").append(same ? " (holds the picture on screen)" : "");
                        if (!same) continue;
                        List<String> best = best(all);
                        if (best.size() > found.size()) {
                            found = best;
                            source = "feed item holding the picture on screen";
                            baseOwner = FeedItems.ownerName(tree);
                        }
                    }
                }
            }
            // 3. Every picture found by value in the window's layers.
            List<String> layers = best(screen);
            if (layers.size() > found.size()) {
                found = layers;
                source = "pictures found in the window's layers";
            }
            // 4. The picture on screen alone.
            if (found.isEmpty() && current != null) {
                found = new ArrayList<>();
                found.add(current);
                source = "picture on screen only";
            }
        } catch (Throwable t) {
            VideoFeatures.log("Photo search failed", t);
        }
        urls = found;
        if (detail != null) {
            StringBuilder sb = new StringBuilder("Photo menu open: source " + source + ", " + found.size() + " photos, owner " + baseOwner + detail);
            for (String u : found) sb.append("\n  ").append(MorpheLog.url(u));
            String text = sb.toString();
            if (!text.equals(lastLog)) {
                lastLog = text;
                MorpheLog.log("photos", text);
            }
        }
    }

    /** Picture addresses found by value in a tree (fields, children, texts and JSON texts, as for videos). */
    private static List<String> picturesIn(Object tree) {
        List<String> out = new ArrayList<>();
        if (tree == null) return out;
        for (ObjectScan.Hit hit : ObjectScan.run(new Object[] {tree}).urls) if (isPicture(hit.text)) out.add(hit.text);
        return out;
    }

    /** Picture addresses held by the window's layers (views outside the lists that cover most of the window). */
    private static List<String> screenPictures(View root) {
        List<String> out = new ArrayList<>();
        List<View> layers = new ArrayList<>();
        layers(root, root.getWidth(), root.getHeight(), layers, 0);
        for (View layer : layers) out.addAll(picturesIn(layer));
        return out;
    }

    private static void layers(View view, int width, int height, List<View> out, int depth) {
        if (view.getVisibility() != View.VISIBLE || depth > 12 || Overlay.OURS.equals(view.getTag()) || FeedItems.isPager(view)) return;
        if (depth >= 3 && view.getWidth() >= width * 0.95f && view.getHeight() >= height * 0.5f) {
            out.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) layers(group.getChildAt(i), width, height, out, depth + 1);
        }
    }

    private static String biggest(List<String> all) {
        String best = null;
        long bestScore = -1;
        for (String url : all) {
            long score = StoryPhotos.score(url);
            if (score > bestScore) {
                bestScore = score;
                best = url;
            }
        }
        return best;
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
            if (!isPicture(url) || area(url) < MIN_AREA) continue;
            String k = key(url);
            String old = byKey.get(k);
            if (old == null || area(url) > area(old)) byKey.put(k, url);
        }
        return new ArrayList<>(byKey.values());
    }
}
