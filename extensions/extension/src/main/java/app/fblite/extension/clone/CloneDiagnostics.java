package app.fblite.extension.clone;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import app.fblite.extension.settings.MorpheLog;
import app.fblite.extension.settings.MorpheSettings;

/**
 * Clone diagnostics. Switched on in the Morphe settings screen ("Clone diagnostics log"). Writes tag
 * "clone" lines to the Morphe log: what the clone looks like to the system, what is left in its manifest
 * that names the original package, every place in the app's dex that holds the original package name or
 * asks the system who the app is, who calls the application context at run time, the intents sent, the
 * app's own warnings and errors from logcat, and crashes.
 */
public final class CloneDiagnostics {
    /** The package the patched app was built from. */
    static final String ORIGINAL = "com.facebook.lite";

    private static final String[] OTHER_PACKAGES = {
            "com.facebook.katana", "com.facebook.orca", "com.facebook.mlite"
    };
    private static final String SCAN_SIGNATURE = "clone_dexscan_signature";
    private static final int MAX_LOGCAT_LINES = 3000;

    private static boolean started;
    private static boolean guarded;
    private static boolean installed;

    private CloneDiagnostics() {
    }

    /** Called at the start of Application.attachBaseContext; the base context is not attached yet. */
    @SuppressWarnings("unused")
    public static void install(Application application) {
        try {
            if (installed) return;
            installed = true;
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle state) {
                    MorpheLog.init(activity);
                    if (!guarded) {
                        guarded = true;
                        // The app may have set its own crash handler since; go on top of it.
                        if (!ORIGINAL.equals(activity.getPackageName())) CloneGuard.install();
                    }
                    if (!MorpheLog.cloneEnabled()) return;
                    begin(activity);
                    MorpheLog.diag("ACTIVITY created " + activity.getClass().getName() + " " + describe(activity.getIntent()));
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    if (MorpheLog.cloneEnabled()) MorpheLog.diag("ACTIVITY resumed " + activity.getClass().getName());
                }

                @Override
                public void onActivityStarted(Activity activity) {
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                }
            });
        } catch (Throwable ignored) {
            // Diagnostics never break the app.
        }
    }

    /** Called by MorpheSettings at the start of attachBaseContext, before the app loads its code. */
    public static Context wrap(Context base) {
        try {
            if (!ORIGINAL.equals(base.getPackageName())) {
                CloneDexFix.apply(base);
                CloneGuard.install();
            }
        } catch (Throwable ignored) {
            // Never break app startup.
        }
        return base;
    }

    private static synchronized void begin(final Activity activity) {
        if (started) return;
        started = true;
        final Context context = activity.getApplicationContext() != null ? activity.getApplicationContext() : activity;
        final boolean logWasEmpty = MorpheLog.file(context).length() == 0;
        Thread worker = new Thread(() -> {
            try {
                report(context);
            } catch (Throwable t) {
                MorpheLog.diag("ENV report failed", t);
            }
            try {
                crashLog();
                logcat();
            } catch (Throwable t) {
                MorpheLog.diag("logcat setup failed", t);
            }
            try {
                scanDex(context, logWasEmpty);
            } catch (Throwable t) {
                MorpheLog.diag("DEXSCAN failed", t);
            }
            try {
                scanNative(context, logWasEmpty);
            } catch (Throwable t) {
                MorpheLog.diag("NATIVESCAN failed", t);
            }
            attestationProbe();
            watchPrefsLater(context);
        }, "morphe-clone-diag");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.setDaemon(true);
        worker.start();
    }

    private static void report(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        String self = c.getPackageName();
        boolean renamed = !ORIGINAL.equals(self);
        PackageInfo info = pm.getPackageInfo(self, 0);
        String installer = null;
        try {
            installer = Build.VERSION.SDK_INT >= 30 ? pm.getInstallSourceInfo(self).getInstallingPackageName()
                    : pm.getInstallerPackageName(self);
        } catch (Throwable ignored) {
        }
        MorpheLog.diag("ENV package=" + self + " original=" + ORIGINAL + " renamed=" + renamed
                + " version=" + info.versionName + " (" + (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode) + ")"
                + " targetSdk=" + c.getApplicationInfo().targetSdkVersion + " uid=" + android.os.Process.myUid()
                + " process=" + processName() + " installer=" + installer);
        MorpheLog.diag("ENV dataDir=" + c.getApplicationInfo().dataDir + " sourceDir=" + c.getApplicationInfo().sourceDir
                + " filesDir=" + c.getFilesDir());

        try {
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= 28) {
                PackageInfo signed = pm.getPackageInfo(self, PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = signed.signingInfo == null ? new Signature[0] : signed.signingInfo.getApkContentsSigners();
            } else {
                signatures = pm.getPackageInfo(self, PackageManager.GET_SIGNATURES).signatures;
            }
            for (Signature signature : signatures) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) hex.append(String.format("%02x", b));
                MorpheLog.diag("ENV signer sha256=" + hex);
            }
        } catch (Throwable t) {
            MorpheLog.diag("ENV signer unreadable " + t);
        }

        for (String other : concat(new String[]{ORIGINAL}, OTHER_PACKAGES)) {
            if (other.equals(self)) continue;
            try {
                PackageInfo other_info = pm.getPackageInfo(other, 0);
                MorpheLog.diag("VISIBLE " + other + " installed version=" + other_info.versionName);
            } catch (PackageManager.NameNotFoundException e) {
                MorpheLog.diag("VISIBLE " + other + " not installed or hidden from this app");
            }
        }

        try {
            PackageInfo providers = pm.getPackageInfo(self, PackageManager.GET_PROVIDERS);
            int count = providers.providers == null ? 0 : providers.providers.length;
            MorpheLog.diag("MANIFEST providers=" + count);
            if (providers.providers != null) {
                for (ProviderInfo provider : providers.providers) {
                    String left = renamed && has(provider.authority) ? " LEFTOVER-ORIGINAL" : "";
                    MorpheLog.diag("MANIFEST provider authority=" + provider.authority + " class=" + provider.name
                            + " process=" + provider.processName + left);
                }
            }
        } catch (Throwable t) {
            MorpheLog.diag("MANIFEST providers unreadable " + t);
        }
        try {
            PackageInfo permissions = pm.getPackageInfo(self, PackageManager.GET_PERMISSIONS);
            if (permissions.permissions != null) {
                for (android.content.pm.PermissionInfo permission : permissions.permissions) {
                    String left = renamed && has(permission.name) ? " LEFTOVER-ORIGINAL" : "";
                    MorpheLog.diag("MANIFEST permission declared " + permission.name + left);
                }
            }
            if (permissions.requestedPermissions != null) {
                for (String requested : permissions.requestedPermissions) {
                    if (has(requested)) MorpheLog.diag("MANIFEST permission requested " + requested + (renamed ? " LEFTOVER-ORIGINAL" : ""));
                }
            }
        } catch (Throwable t) {
            MorpheLog.diag("MANIFEST permissions unreadable " + t);
        }
        try {
            int processesLeft = 0;
            PackageInfo components = pm.getPackageInfo(self, PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS);
            for (android.content.pm.ComponentInfo[] group : new android.content.pm.ComponentInfo[][]{components.activities, components.services, components.receivers}) {
                if (group == null) continue;
                for (android.content.pm.ComponentInfo component : group) {
                    if (renamed && component.processName != null && has(component.processName)) {
                        processesLeft++;
                        MorpheLog.diag("MANIFEST process LEFTOVER-ORIGINAL " + component.processName + " in " + component.name);
                    }
                }
            }
            MorpheLog.diag("MANIFEST components activities=" + len(components.activities) + " services=" + len(components.services)
                    + " receivers=" + len(components.receivers) + " processNamesLeftover=" + processesLeft);
        } catch (Throwable t) {
            MorpheLog.diag("MANIFEST components unreadable " + t);
        }
    }

    private static void scanDex(Context c, boolean logWasEmpty) throws Exception {
        File dir = new File(c.getApplicationInfo().dataDir, "dex");
        File[] files = dir.listFiles();
        if (files == null) files = new File[0];
        Arrays.sort(files);
        StringBuilder signature = new StringBuilder(c.getPackageName());
        for (File f : files) signature.append('|').append(f.getName()).append(':').append(f.length());
        signature.append("|apk:").append(new File(c.getPackageCodePath()).length());
        SharedPreferences prefs = MorpheSettings.preferences(c);
        if (!logWasEmpty && signature.toString().equals(prefs.getString(SCAN_SIGNATURE, null))) {
            MorpheLog.diag("DEXSCAN skipped, same dex files as the earlier scan in this log");
            return;
        }
        int scanned = 0;
        scanned += scanZipOrDex(new File(c.getPackageCodePath()), "apk");
        for (File f : files) {
            if (f.isFile() && (f.getName().endsWith(".zip") || f.getName().endsWith(".dex") || f.getName().endsWith(".jar"))) {
                scanned += scanZipOrDex(f, "dex/" + f.getName());
            }
        }
        if (scanned == 0) {
            MorpheLog.diag("DEXSCAN found no dex in " + dir + " (files: " + files.length + "). Open the app once, wait for it to start, then restart it.");
            return;
        }
        prefs.edit().putString(SCAN_SIGNATURE, signature.toString()).apply();
    }

    private static final java.util.List<Object> PREF_LISTENERS = new java.util.ArrayList<>();
    private static final java.util.Set<String> PREFS_WATCHED = new java.util.HashSet<>();
    private static int prefEvents;

    /** Looks for the saved settings files now, after 30 s and after 2 minutes (the app creates some late). */
    private static void watchPrefsLater(final Context c) {
        watchPrefs(c);
        Thread later = new Thread(() -> {
            try {
                Thread.sleep(30000);
                watchPrefs(c);
                Thread.sleep(90000);
                watchPrefs(c);
            } catch (Throwable ignored) {
            }
        }, "morphe-clone-prefs");
        later.setPriority(Thread.MIN_PRIORITY);
        later.setDaemon(true);
        later.start();
    }

    /**
     * Messenger's login reads the saved value "device_id" and gets nothing in a clone. This lists every saved
     * settings file with its identity-like keys (names only, the length of device_id, never other values) and
     * logs each later change to those files, to show which file holds device_id and when it is written.
     */
    private static void watchPrefs(final Context c) {
        File[] files = new File(c.getApplicationInfo().dataDir, "shared_prefs").listFiles();
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            String fileName = f.getName();
            if (!fileName.endsWith(".xml")) continue;
            final String name = fileName.substring(0, fileName.length() - 4);
            synchronized (PREFS_WATCHED) {
                if (!PREFS_WATCHED.add(name)) continue;
            }
            try {
                final SharedPreferences prefs = c.getSharedPreferences(name, Context.MODE_PRIVATE);
                java.util.Map<String, ?> all = prefs.getAll();
                StringBuilder keys = new StringBuilder();
                for (String key : all.keySet()) {
                    if (interestingKey(key)) {
                        keys.append(key);
                        if (key.equals("device_id")) keys.append("(len=").append(String.valueOf(all.get(key)).length()).append(')');
                        keys.append(',');
                    }
                }
                MorpheLog.diag("PREFS file=" + name + " bytes=" + f.length() + " keys=" + all.size() + " identityKeys=" + keys);
                SharedPreferences.OnSharedPreferenceChangeListener listener = (changed, key) -> {
                    if (prefEvents++ >= 400) return;
                    String extra = "";
                    if ("device_id".equals(key)) extra = " len=" + String.valueOf(changed.getString(key, null)).length();
                    MorpheLog.diag("PREFS change " + name + " key=" + key + extra);
                };
                synchronized (PREF_LISTENERS) {
                    PREF_LISTENERS.add(listener);
                }
                prefs.registerOnSharedPreferenceChangeListener(listener);
            } catch (Throwable t) {
                MorpheLog.diag("PREFS " + name + " unreadable " + t);
            }
        }
    }

    private static boolean interestingKey(String key) {
        return key.contains("device") || key.contains("machine") || key.contains("phone") || key.contains("uid")
                || key.equals("user_id") || key.contains("msys");
    }

    /**
     * Facebook Lite's login for Messenger needs a hardware-attested key and its log shows that generating it fails.
     * This makes the same kind of key by itself, so a clone and a normal install can be compared.
     */
    private static void attestationProbe() {
        if (Build.VERSION.SDK_INT < 24) return;
        probeKey("plain", false);
        probeKey("attested", true);
    }

    private static void probeKey(String label, boolean attested) {
        String alias = "morphe_probe_" + label;
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
            android.security.keystore.KeyGenParameterSpec.Builder spec = new android.security.keystore.KeyGenParameterSpec.Builder(
                    alias, android.security.keystore.KeyProperties.PURPOSE_SIGN)
                    .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256);
            if (attested) spec.setAttestationChallenge(new byte[]{1, 2, 3, 4});
            generator.initialize(spec.build());
            generator.generateKeyPair();
            MorpheLog.diag("ATTEST " + label + " key ok");
        } catch (Throwable t) {
            MorpheLog.diag("ATTEST " + label + " key failed", t);
        } finally {
            try {
                java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidKeyStore");
                store.load(null);
                store.deleteEntry(alias);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Native libraries (the app's and its downloaded modules) that hold the original package name as text. */
    private static void scanNative(Context c, boolean logWasEmpty) throws Exception {
        File root = new File(c.getApplicationInfo().dataDir);
        java.util.List<File> libs = new java.util.ArrayList<>();
        for (String folder : new String[]{"lib-compressed", "app_modules", "lib"}) {
            collectLibs(new File(root, folder), libs, 0);
        }
        long total = 0;
        for (File f : libs) total += f.length();
        String signature = libs.size() + ":" + total;
        SharedPreferences prefs = MorpheSettings.preferences(c);
        if (!logWasEmpty && signature.equals(prefs.getString("clone_native_signature", null))) {
            MorpheLog.diag("NATIVESCAN skipped, same libraries as the earlier scan in this log");
            return;
        }
        byte[] needle = ORIGINAL.getBytes("UTF-8");
        int filesWithHits = 0;
        for (File f : libs) {
            if (f.length() > 96L * 1024 * 1024) continue;
            byte[] data = new byte[(int) f.length()];
            try (InputStream in = new FileInputStream(f)) {
                int done = 0;
                while (done < data.length) {
                    int n = in.read(data, done, data.length - done);
                    if (n < 0) break;
                    done += n;
                }
            }
            int hits = 0;
            int at = 0;
            while (hits < 6 && (at = indexOf(data, needle, at)) >= 0) {
                int start = at;
                while (start > 0 && at - start < 60 && data[start - 1] >= 0x20 && data[start - 1] < 0x7f) start--;
                int end = at + needle.length;
                while (end < data.length && end - at < 100 && data[end] >= 0x20 && data[end] < 0x7f) end++;
                MorpheLog.diag("NATIVE " + f.getAbsolutePath().substring(root.getAbsolutePath().length()) + " @" + at + " \""
                        + new String(data, start, end - start, "UTF-8") + "\"");
                hits++;
                at = end;
            }
            if (hits > 0) filesWithHits++;
        }
        MorpheLog.diag("NATIVESCAN done: libraries=" + libs.size() + " withOriginalName=" + filesWithHits);
        prefs.edit().putString("clone_native_signature", signature).apply();
    }

    private static void collectLibs(File dir, java.util.List<File> out, int depth) {
        File[] files = dir.listFiles();
        if (files == null || depth > 9 || out.size() > 150) return;
        for (File f : files) {
            if (f.isDirectory()) collectLibs(f, out, depth + 1);
            else if (f.getName().endsWith(".so")) out.add(f);
        }
    }

    private static int indexOf(byte[] data, byte[] needle, int from) {
        int last = data.length - needle.length;
        outer:
        for (int i = from; i <= last; i++) {
            if (data[i] != needle[0]) continue;
            for (int k = 1; k < needle.length; k++) {
                if (data[i + k] != needle[k]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int scanZipOrDex(File file, String label) {
        int scanned = 0;
        DexScan.Sink sink = text -> MorpheLog.diag(text);
        try {
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".dex") || name.contains("/")) continue;
                    byte[] data;
                    try (InputStream in = zip.getInputStream(entry)) {
                        data = readAll(in, entry.getSize());
                    }
                    DexScan.scan(label + "!" + name, data, ORIGINAL, OTHER_PACKAGES, sink);
                    scanned++;
                }
            }
            return scanned;
        } catch (java.util.zip.ZipException notZip) {
            // A bare dex file.
        } catch (Throwable t) {
            MorpheLog.diag("DEXSCAN " + label + " failed " + t);
            return scanned;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = readAll(in, file.length());
            DexScan.scan(label, data, ORIGINAL, OTHER_PACKAGES, sink);
            return 1;
        } catch (Throwable t) {
            MorpheLog.diag("DEXSCAN " + label + " failed " + t);
            return scanned;
        }
    }

    private static byte[] readAll(InputStream in, long size) throws java.io.IOException {
        if (size > 0 && size < Integer.MAX_VALUE) {
            byte[] data = new byte[(int) size];
            int done = 0;
            while (done < data.length) {
                int n = in.read(data, done, data.length - done);
                if (n < 0) break;
                done += n;
            }
            if (done == data.length) return data;
            return Arrays.copyOf(data, done);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static void crashLog() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            MorpheLog.diag("CRASH in thread " + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** The app's own warnings and errors. Many devices hide them; then there are simply no lines. */
    private static void logcat() {
        Thread reader = new Thread(() -> {
            Process process = null;
            try {
                process = new ProcessBuilder("logcat", "-v", "time", "--pid=" + android.os.Process.myPid(), "*:W")
                        .redirectErrorStream(true).start();
                try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                    String line;
                    int lines = 0;
                    while ((line = in.readLine()) != null && lines < MAX_LOGCAT_LINES) {
                        if (line.contains("morphe-clone-diag")) continue;
                        MorpheLog.diag("LOGCAT " + (line.length() > 400 ? line.substring(0, 400) : line));
                        lines++;
                    }
                }
            } catch (Throwable t) {
                MorpheLog.diag("LOGCAT unavailable " + t);
            } finally {
                if (process != null) process.destroy();
            }
        }, "morphe-clone-logcat");
        reader.setPriority(Thread.MIN_PRIORITY);
        reader.setDaemon(true);
        reader.start();
    }

    /** An intent without its extras' values: target, action, data host and the names of the extras. */
    static String describe(Intent intent) {
        if (intent == null) return "intent=null";
        StringBuilder sb = new StringBuilder("intent[");
        boolean original = false;
        try {
            if (intent.getComponent() != null) {
                sb.append("component=").append(intent.getComponent().flattenToShortString());
                if (ORIGINAL.equals(intent.getComponent().getPackageName())) original = true;
            }
            if (intent.getPackage() != null) {
                sb.append(" package=").append(intent.getPackage());
                if (ORIGINAL.equals(intent.getPackage())) original = true;
            }
            if (intent.getAction() != null) sb.append(" action=").append(intent.getAction());
            Uri data = intent.getData();
            if (data != null) sb.append(" data=").append(data.getScheme()).append("://").append(data.getAuthority());
            if (intent.getType() != null) sb.append(" type=").append(intent.getType());
            sb.append(" flags=0x").append(Integer.toHexString(intent.getFlags()));
            Bundle extras = intent.getExtras();
            if (extras != null) {
                sb.append(" extras=");
                for (String key : extras.keySet()) {
                    sb.append(key);
                    Object value = extras.get(key);
                    if (value instanceof String && (has((String) value) || ((String) value).startsWith("com.facebook"))) {
                        sb.append('=').append(value);
                    }
                    sb.append(',');
                }
            }
        } catch (Throwable t) {
            sb.append(" unreadable ").append(t);
        }
        sb.append(']');
        if (original) sb.append(" TARGETS-ORIGINAL");
        return sb.toString();
    }

    /** True when s names the original package, not a longer name that merely starts with it (such as the clone's). */
    private static boolean has(String s) {
        if (s == null) return false;
        int at = s.indexOf(ORIGINAL);
        while (at >= 0) {
            int next = at + ORIGINAL.length();
            if (next >= s.length() || !Character.isLetterOrDigit(s.charAt(next)) && s.charAt(next) != '_') return true;
            at = s.indexOf(ORIGINAL, at + 1);
        }
        return false;
    }

    private static int len(Object[] a) {
        return a == null ? 0 : a.length;
    }

    private static String processName() {
        try {
            if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName();
        } catch (Throwable ignored) {
        }
        return "?";
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
