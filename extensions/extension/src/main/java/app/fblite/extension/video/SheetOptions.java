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

/**
 * Hides chosen rows of the app's bottom sheets, the reel's 3 dot menu first (Save reel, Copy link, Playback speed ...).
 *
 * A bottom sheet is a view group as wide as the window that ends at the bottom and holds a thin handle. Inside it, a row is
 * a wide view (at least 80% of the window, 4% to 22% of its width high) whose texts hold one of the known option names; it is
 * set to GONE, and a card that holds only that row goes with it. A row or card that names two options is looked into, never
 * hidden whole. Texts are read by value (as the profile redirect does), so it works whether the rows are TextViews or
 * server components. The names are English.
 *
 * Runs in the window scan, and again 40 to 900 ms after every touch (a sheet opens right after a tap, and the rows should
 * not show for long). Debug log, tag "sheet": the sheet found and its rows with their texts, so rows that did not match can
 * be added.
 */
final class SheetOptions {
    private static final long[] BURST_MS = {40, 120, 260, 500, 900};
    private static final String[] OTHER_KNOWN = {"save", "i don't want to see this", "find support or report photo"};

    private static final Map<View, String> HIDDEN = new WeakHashMap<>();
    private static String lastLog = "";

    private SheetOptions() {
    }

    private static Set<String> wanted(Activity activity) {
        Set<String> out = new HashSet<>();
        if (!MorpheSettings.isEnabled(activity, MorpheSettings.HIDE_MENU_ITEMS)) return out;
        for (int i = 0; i < MorpheSettings.MENU_LABELS.length; i++) {
            if (MorpheSettings.preferences(activity).getBoolean(MorpheSettings.menuKey(i), false)) {
                out.add(MorpheSettings.MENU_LABELS[i].toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    static boolean active(Activity activity) {
        return MorpheSettings.isEnabled(activity, MorpheSettings.HIDE_MENU_ITEMS);
    }

    /** Checks the window again a few times just after a touch. */
    static void burst(final Activity activity) {
        if (!active(activity)) return;
        for (long delay : BURST_MS) {
            VideoFeatures.MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        update(activity);
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
        // Rows hidden earlier whose option is no longer hidden come back.
        for (Map.Entry<View, String> entry : new ArrayList<>(HIDDEN.entrySet())) {
            if (!hide.contains(entry.getValue()) && entry.getKey() != null) {
                entry.getKey().setVisibility(View.VISIBLE);
                HIDDEN.remove(entry.getKey());
            }
        }
        if (hide.isEmpty()) return;
        View root = activity.getWindow().getDecorView();
        View sheet = findSheet(root, root.getWidth(), root.getHeight(), 0);
        if (sheet == null) return;
        List<View> rows = new ArrayList<>();
        collectRows(sheet, root.getWidth(), rows, 0);
        StringBuilder log = MorpheLog.enabled() ? new StringBuilder("Sheet " + Overlay.describe(sheet) + "\n") : null;
        for (View row : rows) {
            List<String> texts = Stories.viewTexts(row);
            String label = optionOf(texts, hide);
            if (log != null) log.append("  row ").append(row.getWidth()).append('x').append(row.getHeight()).append(" texts ").append(texts)
                    .append(label != null ? " -> hidden" : "").append('\n');
            if (label != null && row.getVisibility() != View.GONE) {
                row.setVisibility(View.GONE);
                HIDDEN.put(row, label);
            }
        }
        if (log != null && !log.toString().equals(lastLog)) {
            lastLog = log.toString();
            MorpheLog.log("sheet", lastLog);
        }
    }

    /** The wanted option the texts name, when the row names exactly one known option; else null. */
    private static String optionOf(List<String> texts, Set<String> hide) {
        String found = null;
        int known = 0;
        for (String text : texts) {
            String t = text.trim();
            boolean option = isOption(t);
            if (option) known++;
            if (hide.contains(t)) found = t;
        }
        return known == 1 ? found : null;
    }

    private static boolean isOption(String text) {
        for (String label : MorpheSettings.MENU_LABELS) if (label.toLowerCase(Locale.ROOT).equals(text)) return true;
        for (String label : OTHER_KNOWN) if (label.equals(text)) return true;
        return false;
    }

    /** A view group as wide as the window, ending at the bottom, with a thin handle view near its top. */
    static View findSheet(View view, int width, int height, int depth) {
        if (view.getVisibility() != View.VISIBLE || depth > 40 || Overlay.OURS.equals(view.getTag())) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = findSheet(group.getChildAt(i), width, height, depth + 1);
                if (found != null) return found;
            }
            int[] where = new int[2];
            group.getLocationInWindow(where);
            if (group.getWidth() >= width * 0.9f && group.getHeight() >= width * 0.15f && group.getHeight() <= height * 0.9f
                    && where[1] > height * 0.1f && !FeedItems.isPager(group) && hasHandle(group, width, 0)) {
                return group;
            }
        }
        return null;
    }

    private static boolean hasHandle(ViewGroup group, int width, int depth) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child.getHeight() > 0 && child.getHeight() <= width * 0.02f && child.getWidth() >= width * 0.05f
                    && child.getWidth() <= width * 0.2f) {
                return true;
            }
            if (depth < 2 && child instanceof ViewGroup && hasHandle((ViewGroup) child, width, depth + 1)) return true;
        }
        return false;
    }

    /** Wide, row-high views of the sheet, outermost first; a view that is row sized is not looked into when it names one option. */
    static void collectRows(View view, int width, List<View> out, int depth) {
        if (depth > 12 || view.getVisibility() != View.VISIBLE || Overlay.OURS.equals(view.getTag())) return;
        boolean rowSized = view.getWidth() >= width * 0.8f && view.getHeight() >= width * 0.04f && view.getHeight() <= width * 0.22f;
        if (rowSized) {
            out.add(view);
            // A card with two or more rows is row sized too: its rows are found by looking into it.
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectRows(group.getChildAt(i), width, out, depth + 1);
        }
    }
}
