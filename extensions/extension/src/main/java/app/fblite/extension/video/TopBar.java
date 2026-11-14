package app.fblite.extension.video;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.fblite.extension.settings.MorpheLog;
import app.fblite.extension.settings.MorpheSettings;

/**
 * Hides tabs of the top bar (Friends, Reels, Marketplace).
 *
 * From the log of 536.0.0.11.107: the tab row is six sibling views (X.0jp, clickable) of 180x118 px on a 1080 px wide window,
 * at the same top (y=193), next to the logo, three round buttons and the feed's refresh layout. They are found by shape, not
 * by name: six children of one view group, each about a sixth of the window wide and 5% to 14% of its width high, on one row
 * in the top quarter. In order: Home 0, Friends 1, Messenger 2, Reels 3, Notifications 4, Marketplace 5.
 *
 * A hidden tab is set to GONE, and each tab that stays is moved (translationX) so the stayed tabs share the row evenly,
 * whatever number stays. Everything is put back when the switches are off. It is applied before every frame (a pre-draw
 * listener that only looks at one view group's children), so the tabs never show at start.
 *
 * Debug log, tag "tabs": the row found (sizes) and what was hidden; or, once per screen, why no row was found.
 */
final class TopBar {
    private static final Map<Activity, Boolean> ATTACHED = new WeakHashMap<>();
    private static final Map<View, Boolean> CHANGED = new WeakHashMap<>();
    private static WeakReference<ViewGroup> rowParent = new WeakReference<>(null);
    private static String lastLog = "";

    private TopBar() {
    }

    /** Puts a pre-draw listener on the window, once per activity. */
    static void attach(final Activity activity) {
        if (ATTACHED.containsKey(activity)) return;
        ATTACHED.put(activity, Boolean.TRUE);
        final View decor = activity.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                try {
                    update(activity);
                } catch (Throwable t) {
                    VideoFeatures.log("Hiding tabs failed", t);
                }
                return true;
            }
        });
    }

    private static boolean[] wanted(Activity activity) {
        return new boolean[] {
                false,
                MorpheSettings.isEnabled(activity, MorpheSettings.HIDE_TAB_FRIENDS),
                false,
                MorpheSettings.isEnabled(activity, MorpheSettings.HIDE_TAB_REELS),
                false,
                MorpheSettings.isEnabled(activity, MorpheSettings.HIDE_TAB_MARKETPLACE)};
    }

    static void update(Activity activity) {
        boolean[] hide = wanted(activity);
        boolean any = hide[1] || hide[3] || hide[5];
        if (!any && CHANGED.isEmpty()) return;
        View root = activity.getWindow().getDecorView();
        int width = root.getWidth();
        if (width <= 0) return;
        ViewGroup parent = rowParent.get();
        List<View> tabs = parent != null && parent.isAttachedToWindow() ? tabsOf(parent, width, root.getHeight()) : null;
        if (tabs == null) {
            parent = findParent(root, width, root.getHeight(), 0);
            tabs = parent == null ? null : tabsOf(parent, width, root.getHeight());
            rowParent = new WeakReference<>(parent);
        }
        if (tabs == null) return;
        int n = 0;
        for (int i = 0; i < tabs.size(); i++) if (!hide[i] || !any) n++;
        if (n == 0) return;
        int slot = 0;
        for (int i = 0; i < tabs.size(); i++) {
            View tab = tabs.get(i);
            if (any && hide[i]) {
                if (tab.getVisibility() != View.GONE) tab.setVisibility(View.GONE);
                CHANGED.put(tab, Boolean.TRUE);
                continue;
            }
            if (any) {
                float target = (slot + 0.5f) * width / n;
                float shift = target - (tab.getLeft() + tab.getWidth() / 2f);
                if (tab.getTranslationX() != shift) tab.setTranslationX(shift);
                if (tab.getVisibility() != View.VISIBLE) tab.setVisibility(View.VISIBLE);
                CHANGED.put(tab, Boolean.TRUE);
                slot++;
            } else if (CHANGED.containsKey(tab)) {
                tab.setTranslationX(0f);
                tab.setVisibility(View.VISIBLE);
                CHANGED.remove(tab);
            }
        }
        if (MorpheLog.enabled()) {
            String line = "Tab row in " + parent.getClass().getName() + ": hidden " + (hide[1] ? "Friends " : "") + (hide[3] ? "Reels " : "")
                    + (hide[5] ? "Marketplace" : "") + ", " + n + " stay";
            if (!line.equals(lastLog)) {
                lastLog = line;
                MorpheLog.log("tabs", line);
            }
        }
    }

    /** The view group that holds the six tab views. */
    private static ViewGroup findParent(View view, int width, int height, int depth) {
        if (depth > 12 || !(view instanceof ViewGroup) || Overlay.OURS.equals(view.getTag())) return null;
        ViewGroup group = (ViewGroup) view;
        if (tabsOf(group, width, height) != null) return group;
        if (FeedItems.isPager(group)) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup found = findParent(group.getChildAt(i), width, height, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** Exactly six cells of a sixth of the window, on one row in the top quarter, left to right; else null. */
    private static List<View> tabsOf(ViewGroup group, int width, int height) {
        List<View> cells = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            int w = child.getWidth();
            int h = child.getHeight();
            if (Math.abs(w - width / 6f) <= width * 0.03f && h >= width * 0.05f && h <= width * 0.14f && child.getTop() < height * 0.25f) {
                cells.add(child);
            }
        }
        if (cells.size() != 6) return null;
        int top = cells.get(0).getTop();
        for (View cell : cells) if (Math.abs(cell.getTop() - top) > 3) return null;
        Collections.sort(cells, new Comparator<View>() {
            @Override
            public int compare(View a, View b) {
                return Integer.compare(a.getLeft(), b.getLeft());
            }
        });
        return cells;
    }
}
