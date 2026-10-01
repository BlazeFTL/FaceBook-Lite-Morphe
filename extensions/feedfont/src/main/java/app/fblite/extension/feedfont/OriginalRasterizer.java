package app.fblite.extension.feedfont;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import dalvik.system.DexFile;
import dalvik.system.PathClassLoader;

/**
 * The app's own glyph rasterizer, used for icons and characters the system font cannot draw.
 *
 * The replacement takes the name X.0eF, so the original is loaded again from the app's secondary dex,
 * in a PathClassLoader whose parent hides only X.0eF. Every other class it uses resolves through the
 * app's class loader, so it shares the atlas classes with the rest of the app.
 */
public final class OriginalRasterizer {
    private static final String TAG = "FbLiteFeedFont";
    private static final String RASTERIZER = "X.0eF";

    private static Constructor<?> constructor;
    private static Method rasterize;
    private static boolean failed;

    private final Object instance;

    private OriginalRasterizer(Object instance) {
        this.instance = instance;
    }

    /** Creates the original rasterizer with the same constructor arguments, or returns null. */
    public static synchronized OriginalRasterizer create(Object[] args) {
        if (!load()) return null;
        try {
            return new OriginalRasterizer(constructor.newInstance(args));
        } catch (Throwable t) {
            fail("Could not create the original rasterizer", t);
            return null;
        }
    }

    /** Returns the X.1In the original A03 returns, or null. */
    public Object rasterize(byte[] data, char c) {
        try {
            return rasterize.invoke(instance, data, c);
        } catch (Throwable t) {
            Log.e(TAG, "Original rasterizer failed", t);
            return null;
        }
    }

    private static boolean load() {
        if (constructor != null) return true;
        if (failed) return false;

        try {
            final ClassLoader app = OriginalRasterizer.class.getClassLoader();
            ClassLoader hideReplacement = new ClassLoader(app) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (name.equals(RASTERIZER)) throw new ClassNotFoundException(name);
                    return super.loadClass(name, resolve);
                }
            };

            // X.09K is the app's class loader. A0A is its instance, A02 its dex files (null slots included).
            Class<?> loaderClass = Class.forName("X.09K", false, app);
            Object loader = loaderClass.getField("A0A").get(null);
            DexFile[] dexFiles = (DexFile[]) loaderClass.getField("A02").get(loader);

            for (DexFile dex : dexFiles) {
                if (dex == null || dex.getName().endsWith(".apk")) continue;
                Class<?> original;
                try {
                    original = new PathClassLoader(dex.getName(), hideReplacement).loadClass(RASTERIZER);
                } catch (ClassNotFoundException e) {
                    continue;
                }
                Class<?> atlasHolder = Class.forName("X.0e1", false, app);
                Class<?> windowManager = Class.forName("com.moblica.common.xmob.ui.WindowManager", false, app);
                constructor = original.getConstructor(atlasHolder, windowManager, int.class, int.class, int.class,
                        int.class, boolean.class, boolean.class, boolean.class);
                rasterize = original.getMethod("A03", byte[].class, char.class);
                return true;
            }
            fail("Original rasterizer not found", null);
        } catch (Throwable t) {
            fail("Could not load the original rasterizer", t);
        }
        return false;
    }

    private static void fail(String message, Throwable t) {
        failed = true;
        Log.e(TAG, message, t);
    }
}
