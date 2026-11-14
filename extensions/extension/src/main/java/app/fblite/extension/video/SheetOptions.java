package app.fblite.extension.video;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.fblite.extension.settings.MorpheLog;
import app.fblite.extension.settings.MorpheSettings;
import app.fblite.extension.settings.PatchedFeatures;

/**
 * Hides chosen rows of the app's bottom sheets, the reel's 3 dot menu first (Save reel, Copy link, Playback speed ...).
 *
 * The sheets are not in a list: a menu is a layer of its own in the window (outside the lists), made of the app's views.
 * A row is a wide view (at least 80% of the window, 4% to 22% of its width high) outside any list whose texts, read by
 * value (as the profile redirect does), hold one of the known option names. It is set to GONE; a card that holds only that row
 * goes with it. A row or card that names two options is looked into, never hidden whole. The names are English.
 *
 * Runs in the window scan, and again 40 to 900 ms after every touch (a menu opens right after a tap, and the rows should not
 * show for long).
 *
 * Debug log: tag "sheet" lists the rows with their texts whenever they change and one is a known option; tag "layer" dumps,
 * once, any wide view that ends at the bottom of the window outside the lists (what a sheet looks like), with the texts of
 * its views, so a row that does not match can be added.
 */
final class SheetOptions {
    private static final long[] BURST_MS = {40, 120, 260, 500, 900};
    private static final String[] OTHER_KNOWN = {"save", "i don't want to see this", "find support or report photo"};

    private static final Map<View, String> HIDDEN = new WeakHashMap<>();
    private static String lastRows = "";
    private static String lastLayer = "";

    private SheetOptions() {
    }

    static boolean active(Activity activity) {
        // The gate has no switch of its own (the options do), so it is the patch itself that turns this on.
        return PatchedFeatures.allowed(MorpheSettings.HIDE_MENU_ITEMS);
    }

    private static Set<String> wanted(Activity activity) {
        Set<String> out = new HashSet<>();
        if (!active(activity)) return out;
        for (int i = 0; i < MorpheSettings.MENU_LABELS.length; i++) {
            if (MorpheSettings.preferences(activity).getBoolean(MorpheSettings.menuKey(i), false)) {
                out.add(MorpheSettings.MENU_LABELS[i].toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** Checks the window again a few times just after a touch. */
    static void burst(final Activity activity) {
        boolean wantSheets = active(activity);
        boolean wantPhotos = SavePhotos.active(activity);
        if (!wantSheets && !wantPhotos) return;
        for (long delay : BURST_MS) {
            VideoFeatures.MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (SheetOptions.active(activity)) update(activity);
                        if (SavePhotos.active(activity)) SavePhotos.update(activity);
                    } catch (Throwable t) {
                        VideoFeatures.log("Sheet options failed", t);
                    }
                }
            }, delay);
        }
    }

    static void update(Activity activity) {
        if (!active(activity)) return;
        Set<String> hide = wanted(activity);
        for (Map.Entry<View, String> entry : new ArrayList<>(HIDDEN.entrySet())) {
            if (!hide.contains(entry.getValue()) && entry.getKey() != null) {
                entry.getKey().setVisibility(View.VISIBLE);
                HIDDEN.remove(entry.getKey());
            }
        }
        View root = activity.getWindow().getDecorView();
        int width = root.getWidth();
        if (width <= 0) return;
        List<View> rows = rowsIn(root);
        boolean known = false;
        StringBuilder log = MorpheLog.enabled() ? new StringBuilder("Rows outside the lists:\n") : null;
        for (View row : rows) {
            List<String> texts = Stories.viewTexts(row);
            if (texts.isEmpty()) continue;
            String label = optionOf(texts, hide);
            boolean option = false;
            for (String text : texts) if (isOption(norm(text))) option = true;
            if (option) known = true;
            if (log != null) log.append("  ").append(row.getWidth()).append('x').append(row.getHeight()).append(' ').append(row.getClass().getSimpleName())
                    .append(' ').append(texts).append(label != null ? " -> hidden" : "").append('\n');
            if (label != null && row.getVisibility() != View.GONE) {
                row.setVisibility(View.GONE);
                HIDDEN.put(row, label);
            }
        }
        if (log != null) {
            if (known && !log.toString().equals(lastRows)) {
                lastRows = log.toString();
                MorpheLog.log("sheet", lastRows);
            }
            layerProbe(root);
        }
    }

    /** Wide, row-high views outside the lists, outermost first. */
    static List<View> rowsIn(View root) {
        List<View> out = new ArrayList<>();
        collectRows(root, root.getWidth(), out, 0);
        return out;
    }

    private static void collectRows(View view, int width, List<View> out, int depth) {
        if (depth > 40 || view.getVisibility() != View.VISIBLE || Overlay.OURS.equals(view.getTag()) || FeedItems.isPager(view)) return;
        boolean rowSized = view.getWidth() >= width * 0.8f && view.getHeight() >= width * 0.04f && view.getHeight() <= width * 0.22f;
        if (rowSized) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectRows(group.getChildAt(i), width, out, depth + 1);
        }
    }

    /** The wanted option the texts name, when the row names exactly one known option; else null. */
    private static String optionOf(List<String> texts, Set<String> hide) {
        String found = null;
        int known = 0;
        for (String text : texts) {
            String t = norm(text);
            if (isOption(t)) known++;
            if (hide.contains(t)) found = t;
        }
        return known == 1 ? found : null;
    }

    /** Lower case, without the app's text markup (control characters) and extra spaces. */
    static String norm(String text) {
        return text.replaceAll("[\\p{Cntrl}]", "").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isOption(String text) {
        for (String label : MorpheSettings.MENU_LABELS) if (label.toLowerCase(Locale.ROOT).equals(text)) return true;
        for (String label : OTHER_KNOWN) if (label.equals(text)) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------ debug

    /** Dumps, once per shape, wide views that end at the bottom of the window outside the lists (what a sheet looks like). */
    private static void layerProbe(View root) {
        StringBuilder out = new StringBuilder();
        probe(root, root.getWidth(), root.getHeight(), 0, out);
        String dump = out.toString();
        if (dump.isEmpty() || dump.equals(lastLayer)) return;
        lastLayer = dump;
        MorpheLog.log("layer", "Bottom layer outside the lists:\n" + dump);
    }

    private static boolean probe(View view, int width, int height, int depth, StringBuilder out) {
        if (depth > 40 || view.getVisibility() != View.VISIBLE || Overlay.OURS.equals(view.getTag()) || FeedItems.isPager(view)) return false;
        int[] where = new int[2];
        view.getLocationInWindow(where);
        boolean bottom = view.getWidth() >= width * 0.9f && view.getHeight() >= width * 0.1f && view.getHeight() <= height * 0.6f
                && where[1] + view.getHeight() >= height * 0.8f && depth >= 3;
        if (bottom) {
            dump(view, 0, new int[] {0}, out);
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (probe(group.getChildAt(i), width, height, depth + 1, out)) return true;
        }
        return false;
    }

    private static void dump(View view, int depth, int[] count, StringBuilder out) {
        if (depth > 14 || count[0]++ > 120 || view.getVisibility() != View.VISIBLE) return;
        int[] where = new int[2];
        view.getLocationInWindow(where);
        for (int i = 0; i < depth; i++) out.append("  ");
        out.append(view.getClass().getName()).append(' ').append(view.getWidth()).append('x').append(view.getHeight()).append('@')
                .append(where[0]).append(',').append(where[1]);
        List<String> texts = Stories.viewTexts(view);
        if (!texts.isEmpty() && texts.size() <= 6) out.append(' ').append(texts);
        if (view.isClickable()) out.append(" clickable");
        out.append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) dump(group.getChildAt(i), depth + 1, count, out);
        }
    }
}
