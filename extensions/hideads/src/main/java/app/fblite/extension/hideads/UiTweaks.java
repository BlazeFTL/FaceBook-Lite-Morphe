package app.fblite.extension.hideads;

import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hides tabs of the top bar (Friends, Reels, Marketplace) by editing the server-driven component tree after it is decoded,
 * the way sponsored posts are collapsed.
 *
 * The top bar's row of icons is not a list and not a view: it is a container of five to seven children, none with text but a
 * short badge ("15+", "3"), outside the feed container (id 30001). The tabs are taken by position: Home 0, Friends 1,
 * Messenger 2, Reels 3, Notifications 4, Marketplace 5 (as on the Home screen of 536.0.0.11.107; a layout with another
 * order, or with a tab missing, hides the wrong tab, so only a six child row is touched). A hidden tab is flagged hidden
 * (A2e) and given width 0 (A2A), and the tabs that stay share the row's width evenly. Everything is undone when the
 * setting is off. Settings are read by name from the Morphe preferences; they apply after a restart (the tree is
 * decoded again when the server sends the screen).
 *
 * Debug log, tag "tabs": every container that looks like a tab row (chain of ids, child widths and heights, texts), what was
 * changed, and why a candidate was left alone. If nothing is hidden, this is what shows why.
 */
public final class UiTweaks {
    private static final int FEED_CONTAINER_ID = 30001;
    private static final int TAB_COUNT = 6;
    private static final long SETTING_CHECK_MS = 1000;
    private static final String TAG = "tabs";

    private static boolean resolved;
    private static boolean disabled;
    private static Class<?> containerClass;
    private static Field fId, fParent, fWidth, fHeight, fHidden;
    private static Method childList;
    /** Child -> {hidden, width} before this class changed it. */
    private static final Map<Object, Object[]> CHANGED = Collections.synchronizedMap(new WeakHashMap<Object, Object[]>());
    private static final Map<Object, Boolean> LOGGED = Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());
    private static long checkedAt = -SETTING_CHECK_MS;
    private static boolean hideFriends, hideReels, hideMarketplace;

    private UiTweaks() {
    }

    /** Called after the app decoded a container (from {@link SponsoredPosts#afterDecode}). */
    static void afterDecode(Object component) {
        if (component == null || disabled) return;
        try {
            long now = SystemClock.uptimeMillis();
            if (now - checkedAt >= SETTING_CHECK_MS) {
                checkedAt = now;
                hideFriends = AdLog.setting("hide_tab_friends", false);
                hideReels = AdLog.setting("hide_tab_reels", false);
                hideMarketplace = AdLog.setting("hide_tab_marketplace", false);
            }
            boolean want = hideFriends || hideReels || hideMarketplace;
            if (!want && CHANGED.isEmpty() && !AdLog.enabled()) return;
            if (!resolved) resolve();
            if (!containerClass.isInstance(component)) return;
            Object[] kids = children(component);
            if (kids.length < 5 || kids.length > 7) return;
            if (insideFeed(component)) return;
            String shape = iconRow(kids);
            if (shape == null) return;
            if (AdLog.enabled() && LOGGED.put(component, Boolean.TRUE) == null) {
                AdLog.log(TAG, "TAB ROW candidate " + chain(component) + " children=" + kids.length + " " + shape);
            }
            if (kids.length != TAB_COUNT) return;
            apply(kids, now);
        } catch (Throwable t) {
            disabled = true;
            Log.e(OriginalClass.TAG, "Hiding tabs failed, turning it off", t);
        }
    }

    private static void apply(Object[] kids, long now) throws Exception {
        boolean[] hide = new boolean[TAB_COUNT];
        hide[1] = hideFriends;
        hide[3] = hideReels;
        hide[5] = hideMarketplace;
        int visible = 0;
        long total = 0;
        for (int i = 0; i < kids.length; i++) {
            Object[] before = CHANGED.get(kids[i]);
            total += before != null ? (Integer) before[1] : fWidth.getInt(kids[i]);
            if (!hide[i]) visible++;
        }
        if (visible == 0) return;
        if (total <= 0) {
            if (AdLog.enabled()) AdLog.log(TAG, "TAB ROW has no widths (total 0), left alone");
            return;
        }
        int each = (int) (total / visible);
        for (int i = 0; i < kids.length; i++) {
            Object child = kids[i];
            Object[] before = CHANGED.get(child);
            boolean changed;
            if (hide[i]) {
                if (before == null) CHANGED.put(child, new Object[] {fHidden.getBoolean(child), fWidth.getInt(child)});
                changed = !fHidden.getBoolean(child) || fWidth.getInt(child) != 0;
                fHidden.setBoolean(child, true);
                fWidth.setInt(child, 0);
            } else if (before != null) {
                // Restore, or share the row with the tabs that stay.
                boolean anyHidden = hideFriends || hideReels || hideMarketplace;
                if (anyHidden) {
                    changed = fWidth.getInt(child) != each;
                    fHidden.setBoolean(child, (Boolean) before[0]);
                    fWidth.setInt(child, each);
                } else {
                    changed = true;
                    fHidden.setBoolean(child, (Boolean) before[0]);
                    fWidth.setInt(child, (Integer) before[1]);
                    CHANGED.remove(child);
                }
            } else if (hideFriends || hideReels || hideMarketplace) {
                CHANGED.put(child, new Object[] {fHidden.getBoolean(child), fWidth.getInt(child)});
                changed = fWidth.getInt(child) != each;
                fWidth.setInt(child, each);
            } else {
                changed = false;
            }
            if (changed && AdLog.enabled()) {
                AdLog.log(TAG, "TAB " + i + (hide[i] ? " hidden" : " width " + each) + " (row of " + kids.length + ", total width " + total + ")");
            }
        }
    }

    /** Null unless every child is an icon cell: no text longer than 4 characters, at most 3 texts. Else a short description. */
    private static String iconRow(Object[] kids) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kids.length; i++) {
            int[] texts = {0};
            String[] last = {""};
            if (!iconCell(kids[i], 0, texts, last)) return null;
            sb.append("[").append(i).append(" w=").append(fWidth.get(kids[i])).append(" h=").append(fHeight.get(kids[i]))
                    .append(" #").append(fId.get(kids[i])).append(' ').append(kids[i].getClass().getSimpleName())
                    .append(last[0].isEmpty() ? "" : " \"" + last[0] + "\"").append("] ");
        }
        return sb.toString();
    }

    private static boolean iconCell(Object node, int depth, int[] texts, String[] last) {
        if (node == null || depth > 12) return true;
        String text = AdTrace.textOf(node);
        if (text != null) {
            String clean = AdTrace.clean(text).trim();
            if (!clean.isEmpty()) {
                texts[0]++;
                last[0] = clean;
                if (clean.length() > 4 || texts[0] > 3) return false;
            }
        }
        for (Object child : children(node)) if (!iconCell(child, depth + 1, texts, last)) return false;
        return true;
    }

    private static boolean insideFeed(Object node) throws Exception {
        Object p = node;
        for (int i = 0; p != null && i < 30; i++) {
            if (fId.getShort(p) == FEED_CONTAINER_ID) return true;
            p = fParent.get(p);
        }
        return false;
    }

    private static String chain(Object node) throws Exception {
        StringBuilder sb = new StringBuilder();
        Object p = node;
        for (int i = 0; p != null && i < 10; i++) {
            if (sb.length() > 0) sb.append(" < ");
            sb.append(AdTrace.label(p));
            p = fParent.get(p);
        }
        return sb.toString();
    }

    private static Object[] children(Object node) {
        return AdTrace.children(node);
    }

    private static void resolve() throws Exception {
        if (!AdTrace.ensure()) throw new IllegalStateException("The app's component classes were not found");
        ClassLoader app = UiTweaks.class.getClassLoader();
        Class<?> component = Class.forName("X.0hd", false, app);
        containerClass = Class.forName("X.0hg", false, app);
        childList = Class.forName("X.0hc", false, app).getMethod("ADt");
        fId = component.getDeclaredField("A1c");
        fParent = component.getDeclaredField("A19");
        fWidth = component.getDeclaredField("A2A");
        fHeight = component.getDeclaredField("A0j");
        fHidden = component.getDeclaredField("A2e");
        fId.setAccessible(true);
        fParent.setAccessible(true);
        fWidth.setAccessible(true);
        fHeight.setAccessible(true);
        fHidden.setAccessible(true);
        resolved = true;
    }
}
