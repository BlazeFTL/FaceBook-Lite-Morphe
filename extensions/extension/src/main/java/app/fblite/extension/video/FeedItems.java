package app.fblite.extension.video;

import android.view.View;
import android.view.ViewParent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the app's lists (X.0zb) and the component trees that hold what is on screen: which post or reel a
 * video belongs to, and who posted it, for the name of a downloaded file. Names are for Facebook Lite
 * 530.0.0.8.106, the same ones the hide-ads extension uses. Everything here returns null or an empty result
 * when something does not match, and never throws.
 */
final class FeedItems {
    private static boolean resolved;
    private static boolean broken;
    private static Class<?> pagerClass;
    private static Method childList;
    private static Method horizontal;
    private static final Map<Class<?>, Method> TEXT = new HashMap<>();
    private static final Map<Class<?>, Field[]> FIELDS = new HashMap<>();
    private static final Pattern MARKUP = Pattern.compile("\\\\[\\s\\S]");
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1f]");

    private FeedItems() {
    }

    private static boolean resolve(ClassLoader loader) {
        if (resolved) return true;
        if (broken) return false;
        try {
            pagerClass = Class.forName("X.0zb", false, loader);
            childList = Class.forName("X.0gn", false, loader).getMethod("AF0");
            horizontal = pagerClass.getMethod("AQS");
            resolved = true;
        } catch (Throwable t) {
            broken = true;
        }
        return resolved;
    }

    /** The list that holds the view, or null. */
    static View pagerOf(View view) {
        if (!resolve(view.getClass().getClassLoader())) return null;
        ViewParent p = view.getParent();
        while (p != null) {
            if (pagerClass.isInstance(p)) return (View) p;
            p = p.getParent();
        }
        return null;
    }

    /** The view's ancestor that is a direct child of the list (the view of one item), or null. */
    static View childOf(View pager, View view) {
        View child = view;
        while (child != null && child.getParent() != pager) {
            ViewParent p = child.getParent();
            child = p instanceof View ? (View) p : null;
        }
        return child;
    }

    /** The adapter position of an item view, which is its index in the item list; -1 when unknown. */
    static int positionOf(View pager, View child) {
        try {
            return (Integer) pager.getClass().getMethod("getChildAdapterPosition", View.class).invoke(pager, child);
        } catch (Throwable t) {
            return -1;
        }
    }

    static boolean isPager(Object view) {
        return resolved && pagerClass.isInstance(view);
    }

    /** True when the list pages one item at a time (Reels): its snap handler's mode is not 0. */
    static boolean isSnapping(View pager) {
        try {
            Object snap = read(pager, "A07");
            return snap != null && ((Integer) read(snap, "A02")) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isHorizontal(View pager) {
        try {
            return (Boolean) horizontal.invoke(pager);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The list's items (X.0z8), copied. */
    static Object[] items(View pager) {
        try {
            Object layout = read(pager, "A05");
            Object list = layout == null ? null : read(layout, "A0E");
            return list instanceof List ? snapshot((List<?>) list) : new Object[0];
        } catch (Throwable t) {
            return new Object[0];
        }
    }

    static int currentIndex(View pager) {
        try {
            return (Integer) read(read(pager, "A05"), "A03");
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The component tree an item holds, or null. */
    static Object treeOf(Object item) {
        try {
            Object wrapper = read(item, "A0B");
            return wrapper == null ? null : read(wrapper, "A03");
        } catch (Throwable t) {
            return null;
        }
    }

    /** A copy made by index: some of the app's lists do not support toArray, and all of them change while read. */
    static Object[] snapshot(List<?> list) {
        int n = list.size();
        List<Object> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            try {
                out.add(list.get(i));
            } catch (Throwable t) {
                break;
            }
        }
        return out.toArray();
    }

    static Object[] children(Object node) {
        try {
            if (!childList.getDeclaringClass().isInstance(node)) return new Object[0];
            Object list = childList.invoke(node);
            return list instanceof List ? snapshot((List<?>) list) : new Object[0];
        } catch (Throwable t) {
            return new Object[0];
        }
    }

    /** The text of a text component (method AIW), as the app stores it with markup, or null. */
    private static String rawText(Object node) {
        Class<?> c = node.getClass();
        Method m;
        if (TEXT.containsKey(c)) {
            m = TEXT.get(c);
        } else {
            try {
                m = c.getMethod("AIW");
            } catch (NoSuchMethodException e) {
                m = null;
            }
            TEXT.put(c, m);
        }
        if (m == null) return null;
        try {
            Object s = m.invoke(node);
            return s == null ? null : s.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Removes the app's style markup (a backslash and one character) and control characters. */
    static String clean(String raw) {
        if (raw == null) return "";
        String s = MARKUP.matcher(raw).replaceAll("");
        s = CONTROL.matcher(s).replaceAll("");
        return s.trim();
    }

    private static final String[] NOT_A_NAME = {"follow", "following", "like", "comment", "share", "send message", "more", "sponsored",
            "reply", "see more", "join", "subscribe"};

    /** The first text of the tree that can be a person's or page's name. */
    static String ownerName(Object tree) {
        List<String> found = new ArrayList<>();
        collectText(tree, 0, found);
        for (String text : found) {
            if (text.length() < 2 || text.length() > 80) continue;
            boolean letter = false;
            for (int i = 0; i < text.length(); i++) letter |= Character.isLetter(text.charAt(i));
            if (!letter || text.startsWith("#")) continue;
            String lower = text.toLowerCase(java.util.Locale.ROOT);
            boolean skip = false;
            for (String word : NOT_A_NAME) skip |= lower.equals(word);
            if (!skip) return text;
        }
        return null;
    }

    private static void collectText(Object node, int depth, List<String> out) {
        if (node == null || depth > 30 || out.size() >= 8) return;
        String text = clean(rawText(node));
        if (!text.isEmpty()) out.add(text);
        for (Object child : children(node)) collectText(child, depth + 1, out);
    }

    /**
     * The item whose tree refers to one of the needles: the same object, or a string equal to a string needle,
     * in a field of any node or in a map a node holds. Null when none does.
     */
    static Object findTree(Object[] items, Object... needles) {
        for (Object item : items) {
            Object tree = treeOf(item);
            if (tree != null && contains(tree, 0, new int[] {0}, needles)) return tree;
        }
        return null;
    }

    private static boolean contains(Object node, int depth, int[] count, Object[] needles) {
        if (node == null || depth > 40 || ++count[0] > 4000) return false;
        for (Field f : fields(node.getClass())) {
            try {
                Object v = f.get(node);
                if (v == null) continue;
                if (matches(v, needles)) return true;
                if (v instanceof Map) {
                    int n = 0;
                    for (Object value : ((Map<?, ?>) v).values()) {
                        if (++n > 64) break;
                        if (value != null && matches(value, needles)) return true;
                    }
                }
            } catch (Throwable ignored) {
                // Changed while reading.
            }
        }
        for (Object child : children(node)) if (contains(child, depth + 1, count, needles)) return true;
        return false;
    }

    private static boolean matches(Object value, Object[] needles) {
        for (Object needle : needles) {
            if (needle == null) continue;
            if (value == needle) return true;
            if (needle instanceof String && value instanceof String && value.equals(needle)) return true;
        }
        return false;
    }

    private static Field[] fields(Class<?> c) {
        Field[] cached = FIELDS.get(c);
        if (cached != null) return cached;
        List<Field> all = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    all.add(f);
                } catch (Throwable ignored) {
                }
            }
        }
        cached = all.toArray(new Field[0]);
        FIELDS.put(c, cached);
        return cached;
    }

    private static Object read(Object target, String name) throws Exception {
        return ObjectScan.field(target, name);
    }
}
