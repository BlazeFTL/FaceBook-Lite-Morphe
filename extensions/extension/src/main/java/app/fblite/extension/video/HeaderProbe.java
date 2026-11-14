package app.fblite.extension.video;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

import app.fblite.extension.settings.MorpheLog;

/**
 * Debug log helper: writes down what is at a point of the screen and in the header band (logo row and tab row), so the
 * real element names can be found. Written on a long press in the logo zone when Debug logging is on.
 *
 * Tag "logo": the views at the point, outermost first (class, size, position, id name, content description, text,
 * accessibility provider). Tag "header": every view in the header band; the accessibility nodes a view offers (the
 * inspector's childless "ViewGroup" nodes are probably these); and the server components (X.0hd) that any view in the band
 * holds in a field, as a tree with id, width, height, hidden flag and text.
 */
final class HeaderProbe {
    private static final int BAND_ROW = 330;
    private static final int MAX_LINES = 220;
    private static Class<?> componentClass;
    private static Field fId;
    private static Field fWidth;
    private static Field fHeight;
    private static Field fHidden;

    private HeaderProbe() {
    }

    static void dump(Activity activity, float x, float y) {
        try {
            View root = activity.getWindow().getDecorView();
            StringBuilder at = new StringBuilder("Views at " + Math.round(x) + "," + Math.round(y) + ", outermost first:\n");
            hitChain(root, x, y, 0, at);
            MorpheLog.log("logo", at.toString());

            float unit = root.getWidth() / 1080f;
            int bandBottom = HeaderTools.topInset(root) + Math.round(BAND_ROW * unit);
            StringBuilder band = new StringBuilder("Views in the header band (above y=" + bandBottom + "):\n");
            int[] lines = {0};
            IdentityHashMap<Object, Boolean> components = new IdentityHashMap<>();
            StringBuilder comp = new StringBuilder();
            bandWalk(root, bandBottom, 0, band, lines, components, comp);
            MorpheLog.log("header", band.toString());
            if (comp.length() > 0) MorpheLog.log("header", "Components held by views in the band:\n" + comp);
            else MorpheLog.log("header", "No server component is held in a field of a view in the band (depth 2)");
        } catch (Throwable t) {
            MorpheLog.log("header", "Probe failed", t);
        }
    }

    // ------------------------------------------------------------------------------------ views

    private static String line(View v) {
        int[] where = new int[2];
        v.getLocationInWindow(where);
        StringBuilder sb = new StringBuilder();
        sb.append(v.getClass().getName()).append(' ').append(v.getWidth()).append('x').append(v.getHeight())
                .append('@').append(where[0]).append(',').append(where[1]);
        if (v.getId() != View.NO_ID) {
            String name;
            try {
                name = v.getResources().getResourceEntryName(v.getId());
            } catch (Throwable t) {
                name = "0x" + Integer.toHexString(v.getId());
            }
            sb.append(" id=").append(name);
        }
        if (v.getContentDescription() != null) sb.append(" desc=\"").append(v.getContentDescription()).append('"');
        if (v instanceof TextView && ((TextView) v).getText().length() > 0) sb.append(" text=\"").append(((TextView) v).getText()).append('"');
        if (v.isClickable()) sb.append(" clickable");
        if (v.getVisibility() != View.VISIBLE) sb.append(" vis=").append(v.getVisibility());
        if (v.getBackground() != null) sb.append(" bg");
        if (FeedItems.isPager(v)) sb.append(" LIST");
        if (v instanceof ViewGroup) sb.append(" children=").append(((ViewGroup) v).getChildCount());
        AccessibilityNodeProvider provider = v.getAccessibilityNodeProvider();
        if (provider != null) sb.append(" a11yProvider=").append(provider.getClass().getName());
        return sb.toString();
    }

    private static void hitChain(View view, float x, float y, int depth, StringBuilder out) {
        if (depth > 40 || view.getVisibility() != View.VISIBLE || Overlay.OURS.equals(view.getTag())) return;
        int[] where = new int[2];
        view.getLocationInWindow(where);
        if (x < where[0] || x >= where[0] + view.getWidth() || y < where[1] || y >= where[1] + view.getHeight()) return;
        for (int i = 0; i < depth; i++) out.append("  ");
        out.append(line(view)).append('\n');
        AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider != null) virtual(provider, -1, 0, new int[] {0}, out);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) hitChain(group.getChildAt(i), x, y, depth + 1, out);
        }
    }

    private static void bandWalk(View view, int bandBottom, int depth, StringBuilder out, int[] lines,
                                 IdentityHashMap<Object, Boolean> seen, StringBuilder comp) {
        if (depth > 16 || lines[0] >= MAX_LINES || Overlay.OURS.equals(view.getTag())) return;
        int[] where = new int[2];
        view.getLocationInWindow(where);
        if (where[1] >= bandBottom) return;
        lines[0]++;
        for (int i = 0; i < depth; i++) out.append("  ");
        out.append(line(view)).append('\n');
        if (!FeedItems.isPager(view)) heldComponents(view, seen, comp);
        if (view instanceof ViewGroup && !FeedItems.isPager(view)) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) bandWalk(group.getChildAt(i), bandBottom, depth + 1, out, lines, seen, comp);
        }
    }

    // ------------------------------------------------------------------------------------ accessibility nodes

    /** Virtual accessibility nodes of a view (what the inspector lists), through the provider, with their screen bounds. */
    private static void virtual(AccessibilityNodeProvider provider, int id, int depth, int[] count, StringBuilder out) {
        if (depth > 5 || count[0]++ > 80) return;
        try {
            AccessibilityNodeInfo info = provider.createAccessibilityNodeInfo(id);
            if (info == null) return;
            Rect bounds = new Rect();
            info.getBoundsInScreen(bounds);
            for (int i = 0; i < depth + 2; i++) out.append("  ");
            out.append("a11y#").append(id).append(' ').append(info.getClassName()).append(" bounds=").append(bounds.flattenToString());
            if (info.getText() != null) out.append(" text=\"").append(info.getText()).append('"');
            if (info.getContentDescription() != null) out.append(" desc=\"").append(info.getContentDescription()).append('"');
            if (info.getViewIdResourceName() != null) out.append(" viewId=").append(info.getViewIdResourceName());
            if (info.isClickable()) out.append(" clickable");
            out.append(" children=").append(info.getChildCount()).append('\n');
            Method childId = AccessibilityNodeInfo.class.getMethod("getChildId", int.class);
            for (int i = 0; i < info.getChildCount(); i++) {
                long cid = (Long) childId.invoke(info, i);
                virtual(provider, (int) (cid >> 32), depth + 1, count, out);
            }
        } catch (Throwable t) {
            for (int i = 0; i < depth + 2; i++) out.append("  ");
            out.append("a11y#").append(id).append(" not readable: ").append(t.getClass().getSimpleName()).append('\n');
        }
    }

    // ------------------------------------------------------------------------------------ components

    private static boolean resolveComponent() {
        if (componentClass != null) return true;
        try {
            ClassLoader loader = HeaderProbe.class.getClassLoader();
            componentClass = Class.forName("X.0hd", false, loader);
            fId = componentClass.getDeclaredField("A1c");
            fWidth = componentClass.getDeclaredField("A2A");
            fHeight = componentClass.getDeclaredField("A0j");
            fHidden = componentClass.getDeclaredField("A2e");
            fId.setAccessible(true);
            fWidth.setAccessible(true);
            fHeight.setAccessible(true);
            fHidden.setAccessible(true);
            return true;
        } catch (Throwable t) {
            componentClass = null;
            return false;
        }
    }

    /** Components held (directly, or one object further) in the fields of a view that is part of the app. */
    private static void heldComponents(View view, IdentityHashMap<Object, Boolean> seen, StringBuilder out) {
        if (!resolveComponent() || out.length() > 24000) return;
        for (Class<?> c = view.getClass(); c != null && c != View.class && c != ViewGroup.class && c != Object.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.startsWith("android.") || name.startsWith("java.")) break;
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    Object value = f.get(view);
                    if (value == null) continue;
                    if (componentClass.isInstance(value)) {
                        dumpComponent(value, c.getSimpleName() + "." + f.getName(), seen, out);
                    } else if (ObjectScan.isAppObject(value)) {
                        for (Class<?> k = value.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                            if (k.getName().startsWith("android.") || k.getName().startsWith("java.")) break;
                            for (Field g : k.getDeclaredFields()) {
                                if (Modifier.isStatic(g.getModifiers()) || g.getType().isPrimitive()) continue;
                                try {
                                    g.setAccessible(true);
                                    Object inner = g.get(value);
                                    if (inner != null && componentClass.isInstance(inner)) {
                                        dumpComponent(inner, c.getSimpleName() + "." + f.getName() + "." + g.getName(), seen, out);
                                    }
                                } catch (Throwable ignored) {
                                    // next field
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // next field
                }
            }
        }
    }

    private static void dumpComponent(Object node, String where, IdentityHashMap<Object, Boolean> seen, StringBuilder out) {
        if (seen.put(node, Boolean.TRUE) != null) return;
        out.append("from ").append(where).append(":\n");
        int[] lines = {0};
        tree(node, 0, lines, out);
    }

    private static void tree(Object node, int depth, int[] lines, StringBuilder out) {
        if (node == null || depth > 10 || lines[0]++ > 140) return;
        for (int i = 0; i < depth; i++) out.append("  ");
        out.append(node.getClass().getName());
        try {
            out.append(" #").append(fId.get(node)).append(" w=").append(fWidth.get(node)).append(" h=").append(fHeight.get(node));
            if ((Boolean) fHidden.get(node)) out.append(" HIDDEN");
        } catch (Throwable t) {
            // not a full component
        }
        String text = FeedItems.textOf(node);
        if (text != null) out.append(" text=\"").append(text).append('"');
        Object[] children = FeedItems.children(node);
        if (children.length > 0) out.append(" children=").append(children.length);
        out.append('\n');
        for (Object child : children) tree(child, depth + 1, lines, out);
    }
}
