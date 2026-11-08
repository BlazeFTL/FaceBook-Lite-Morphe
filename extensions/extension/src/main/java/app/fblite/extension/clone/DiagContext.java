package app.fblite.extension.clone;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.util.concurrent.ConcurrentHashMap;

import app.fblite.extension.settings.MorpheLog;

/**
 * Base context of the application while clone diagnostics are on. It logs who asks the application for
 * its package name or paths, who starts or binds something, and which intents still point at the
 * original package. Each distinct caller is logged once.
 */
final class DiagContext extends ContextWrapper {
    private static final int MAX_DISTINCT = 500;
    private static final int MAX_CAPTURES = 4000;
    private static final ConcurrentHashMap<String, Boolean> SEEN = new ConcurrentHashMap<>();
    private static volatile int captures;

    DiagContext(Context base) {
        super(base);
    }

    @Override
    public String getPackageName() {
        note("getPackageName", null);
        return super.getPackageName();
    }

    @Override
    public PackageManager getPackageManager() {
        note("getPackageManager", null);
        return super.getPackageManager();
    }

    @Override
    public String getPackageResourcePath() {
        note("getPackageResourcePath", null);
        return super.getPackageResourcePath();
    }

    @Override
    public String getPackageCodePath() {
        note("getPackageCodePath", null);
        return super.getPackageCodePath();
    }

    @Override
    public int checkSelfPermission(String permission) {
        if (permission != null && permission.contains(CloneDiagnostics.ORIGINAL)) note("checkSelfPermission", permission);
        return super.checkSelfPermission(permission);
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        if (permission != null && permission.contains(CloneDiagnostics.ORIGINAL)) note("checkPermission", permission);
        return super.checkPermission(permission, pid, uid);
    }

    @Override
    public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
        if (name != null && name.contains(CloneDiagnostics.ORIGINAL)) note("getSharedPreferences", name);
        return super.getSharedPreferences(name, mode);
    }

    @Override
    public void startActivity(Intent intent) {
        note("startActivity", CloneDiagnostics.describe(intent));
        super.startActivity(intent);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        note("startActivity", CloneDiagnostics.describe(intent));
        super.startActivity(intent, options);
    }

    @Override
    public ComponentName startService(Intent service) {
        note("startService", CloneDiagnostics.describe(service));
        return super.startService(service);
    }

    @Override
    public boolean bindService(Intent service, ServiceConnection connection, int flags) {
        note("bindService", CloneDiagnostics.describe(service));
        return super.bindService(service, connection, flags);
    }

    @Override
    public void sendBroadcast(Intent intent) {
        note("sendBroadcast", CloneDiagnostics.describe(intent));
        super.sendBroadcast(intent);
    }

    @Override
    public void sendBroadcast(Intent intent, String receiverPermission) {
        note("sendBroadcast", CloneDiagnostics.describe(intent) + " permission=" + receiverPermission);
        super.sendBroadcast(intent, receiverPermission);
    }

    private static void note(String what, String detail) {
        try {
            if (SEEN.size() >= MAX_DISTINCT || captures >= MAX_CAPTURES) return;
            captures++;
            String callers = callers();
            if (callers == null) return;
            String key = what + "|" + detail + "|" + callers;
            if (SEEN.putIfAbsent(key, Boolean.TRUE) != null) return;
            MorpheLog.diag("CALL " + what + (detail == null ? "" : " " + detail) + " <- " + callers);
        } catch (Throwable ignored) {
            // Diagnostics never break the app.
        }
    }

    /** The first frames that belong to the app (not the framework or this extension), or null when there are none. */
    private static String callers() {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (StackTraceElement frame : stack) {
            String c = frame.getClassName();
            if (c.startsWith("java.") || c.startsWith("javax.") || c.startsWith("android.") || c.startsWith("androidx.")
                    || c.startsWith("dalvik.") || c.startsWith("com.android.") || c.startsWith("libcore.")
                    || c.startsWith("sun.") || c.startsWith("kotlin.") || c.startsWith("app.fblite.extension.")) {
                continue;
            }
            if (kept > 0) sb.append(" < ");
            sb.append(c).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            if (++kept == 3) break;
        }
        return kept == 0 ? null : sb.toString();
    }
}
