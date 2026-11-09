package app.fblite.extension.clone;

import android.os.Looper;

import app.fblite.extension.settings.MorpheLog;

/**
 * Clone installs only. The app's code names some content providers by their original authority
 * (com.facebook.lite.*). In the clone those names reach the original app's providers, whose permissions the
 * clone does not hold, and the SecurityException ends a background thread's work by crashing the process.
 * This drops such an exception when it is thrown on a background thread; the main thread is never touched.
 */
final class CloneGuard {
    private CloneGuard() {
    }

    /** Safe to call more than once; adds itself on top of whatever handler is current. */
    static void install() {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if (current instanceof Guard) return;
        Thread.setDefaultUncaughtExceptionHandler(new Guard(current));
    }

    private static final class Guard implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler previous;

        Guard(Thread.UncaughtExceptionHandler previous) {
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            if (contained(thread, error)) {
                MorpheLog.log("clone", "contained provider denial on thread " + thread.getName(), error);
                return;
            }
            if (previous != null) previous.uncaughtException(thread, error);
        }
    }

    private static boolean contained(Thread thread, Throwable error) {
        try {
            Looper main = Looper.getMainLooper();
            if (main != null && main.getThread() == thread) return false;
            for (Throwable t = error; t != null; t = t.getCause()) {
                String message = t.getMessage();
                if (t instanceof SecurityException && message != null && message.contains("opening provider")
                        && message.contains(CloneSupport.ORIGINAL + ".")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
