package app.fblite.extension.video;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import app.fblite.extension.settings.MorpheLog;

/**
 * Finds out whether something drawn above a video (a comment sheet, a menu, a dim layer) covers a point of the
 * screen. The download button lives on the window's decor view, above everything, so it has to be hidden by hand
 * when another layer opens over the video.
 *
 * The test is a paint-order hit test: the topmost view that paints at the point, ignoring this extension's own
 * views. If it is not part of the video's own layer (the video, or the list that holds it), the point is covered.
 */
final class Overlay {
    /** Tag put on every view this extension adds to the window; the hit test skips them. */
    static final String OURS = "morphe-overlay";
    private static final int MAX_DEPTH = 40;
    private static final int TRACE_DEPTH = 5;
    private static final int TRACE_NODES = 90;
    private static final int[] LOC = new int[2];
    private static String lastTrace = "";

    private Overlay() {
    }

    static <T extends View> T mark(T view) {
        view.setTag(OURS);
        return view;
    }

    private static boolean ours(View view) {
        return OURS.equals(view.getTag());
    }

    /** The topmost painting view at the window point that is not part of the video's layer, or null. */
    static View covering(View video, View pager, float x, float y) {
        View root = video.getRootView();
        if (root == null) return null;
        View hit = topPainter(root, x, y, 0);
        if (hit == null) return null;
        View own = pager != null ? pager : video;
        if (within(hit, own) || within(own, hit)) return null;
        return hit;
    }

    /** True when {@code view} is {@code ancestor} or sits below it. */
    private static boolean within(View view, View ancestor) {
        View v = view;
        while (v != null) {
            if (v == ancestor) return true;
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return false;
    }

    private static boolean contains(View view, float x, float y) {
        if (view.getWidth() <= 0 || view.getHeight() <= 0) return false;
        view.getLocationInWindow(LOC);
        return x >= LOC[0] && x < LOC[0] + view.getWidth() && y >= LOC[1] && y < LOC[1] + view.getHeight();
    }

    private static View topPainter(View view, float x, float y, int depth) {
        if (depth > MAX_DEPTH || view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.05f || ours(view)
                || !contains(view, x, y)) {
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (View child : topDown(group)) {
                View hit = topPainter(child, x, y, depth + 1);
                if (hit != null) return hit;
            }
        }
        return paints(view) ? view : null;
    }

    /** Children from the one drawn last to the one drawn first (index order, raised by elevation). */
    private static List<View> topDown(ViewGroup group) {
        int n = group.getChildCount();
        List<View> children = new ArrayList<>(n);
        boolean raised = false;
        for (int i = n - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            children.add(child);
            if (child.getZ() != 0f) raised = true;
        }
        if (raised) {
            Collections.sort(children, new Comparator<View>() {
                @Override
                public int compare(View a, View b) {
                    return Float.compare(b.getZ(), a.getZ());
                }
            });
        }
        return children;
    }

    /** Does this view paint something at its own bounds? A plain View without a background does not. */
    private static boolean paints(View view) {
        if (view.getBackground() != null) return true;
        if (view instanceof ViewGroup) return !view.willNotDraw() || FeedItems.isPager(view);
        return view.getClass() != View.class;
    }

    // ------------------------------------------------------------------------------------ logging

    static String describe(View view) {
        if (view == null) return "null";
        view.getLocationInWindow(LOC);
        String name = view.getClass().getName();
        return name + " " + view.getWidth() + "x" + view.getHeight() + "@" + LOC[0] + "," + LOC[1]
                + (view.getAlpha() < 1f ? " a=" + view.getAlpha() : "")
                + (view.getZ() != 0f ? " z=" + view.getZ() : "")
                + (view.getBackground() != null ? " bg" : "")
                + (view instanceof ViewGroup ? " children=" + ((ViewGroup) view).getChildCount() : "")
                + (FeedItems.isPager(view) ? " LIST" : "");
    }

    /** The view and its ancestors up to the window, one line. */
    static String chain(View view) {
        StringBuilder sb = new StringBuilder();
        View v = view;
        for (int i = 0; v != null && i < 8; i++) {
            if (sb.length() > 0) sb.append(" < ");
            sb.append(describe(v));
            ViewParent p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return sb.toString();
    }

    /**
     * Logs the window's top layers when they change (debug log only). This is how a new layer such as the comment
     * sheet shows up in the log: its class, bounds and position among the other layers.
     */
    static void trace(View root) {
        if (!MorpheLog.enabled() || root == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            int[] budget = {TRACE_NODES};
            dump(root, 0, sb, budget);
            String now = sb.toString();
            if (now.equals(lastTrace)) return;
            lastTrace = now;
            MorpheLog.log("cover", "LAYERS changed:\n" + now);
        } catch (Throwable t) {
            MorpheLog.log("cover", "Layer trace failed", t);
        }
    }

    private static void dump(View view, int depth, StringBuilder sb, int[] budget) {
        if (budget[0]-- <= 0 || view.getVisibility() != View.VISIBLE || ours(view)) return;
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(describe(view)).append('\n');
        if (depth >= TRACE_DEPTH || !(view instanceof ViewGroup) || FeedItems.isPager(view)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) dump(group.getChildAt(i), depth + 1, sb, budget);
    }
}
