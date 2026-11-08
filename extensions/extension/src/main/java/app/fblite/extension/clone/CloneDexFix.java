package app.fblite.extension.clone;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.HashSet;
import java.util.Set;

import app.fblite.extension.settings.MorpheLog;
import app.fblite.extension.settings.MorpheSettings;

/**
 * Clone installs only. Runs at the very start of Application.attachBaseContext, before the app loads its code,
 * and rewrites the hardcoded original names in the dex the app extracted on an earlier start (see DexRewriter).
 * The first start after an install has nothing to rewrite yet; the second start is the first with the fix.
 */
final class CloneDexFix {
    private CloneDexFix() {
    }

    static void apply(Context base) {
        try {
            String self = base.getPackageName();
            if (CloneDiagnostics.ORIGINAL.equals(self)) return;
            File dexDir = new File(base.getApplicationInfo().dataDir, "dex");
            File[] files = dexDir.listFiles();
            if (files == null) return;
            boolean enabled = MorpheSettings.isEnabled(base, MorpheSettings.CLONE_DEX_FIX);
            if (enabled && self.length() != CloneDiagnostics.ORIGINAL.length()) {
                MorpheLog.diag("DEXFIX skipped: the clone package name must have " + CloneDiagnostics.ORIGINAL.length()
                        + " characters, it has " + self.length());
                return;
            }
            Set<String> authorities = enabled ? originalAuthorities(base, self) : new HashSet<String>();
            File lockFile = new File(dexDir, ".clone.lock");
            try (RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
                 FileChannel channel = raf.getChannel();
                 FileLock lock = channel.lock()) {
                for (File zip : files) {
                    String name = zip.getName();
                    if (zip.isFile() && name.startsWith("z-") && name.endsWith(".zip")) {
                        handle(dexDir, zip, self, enabled, authorities);
                    }
                }
            }
        } catch (Throwable t) {
            MorpheLog.diag("DEXFIX failed", t);
        }
    }

    private static void handle(File dexDir, File zip, String self, boolean enabled, Set<String> authorities) throws Exception {
        File marker = new File(dexDir, zip.getName() + ".clone");
        String state = self + "|" + zip.length() + "|" + zip.lastModified();
        if (!enabled) {
            if (marker.exists()) {
                // Switched off: drop the edited copy so the app extracts a clean one on this start.
                removeCompiled(dexDir, zip);
                zip.delete();
                marker.delete();
                MorpheLog.diag("DEXFIX off: removed the edited " + zip.getName() + "; the app extracts it again");
            }
            return;
        }
        if (marker.exists() && state.equals(read(marker))) return;

        File tmp = new File(dexDir, zip.getName() + ".tmp");
        long begin = System.currentTimeMillis();
        int changed = DexRewriter.rewriteZip(zip, tmp, CloneDiagnostics.ORIGINAL, self, authorities);
        if (changed == 0) {
            tmp.delete();
            write(marker, state);
            MorpheLog.diag("DEXFIX " + zip.getName() + ": nothing to change");
            return;
        }
        removeCompiled(dexDir, zip);
        zip.setWritable(true);
        zip.delete();
        if (!tmp.renameTo(zip)) throw new java.io.IOException("could not replace " + zip);
        zip.setReadOnly();
        write(marker, self + "|" + zip.length() + "|" + zip.lastModified());
        MorpheLog.diag("DEXFIX " + zip.getName() + ": " + changed + " strings now name " + self + " ("
                + (System.currentTimeMillis() - begin) + " ms, " + authorities.size() + " authorities)");
    }

    /** The authorities the app had before the rename: the clone's own, with the package part swapped back. */
    private static Set<String> originalAuthorities(Context base, String self) {
        Set<String> out = new HashSet<>();
        try {
            PackageInfo info = base.getPackageManager().getPackageInfo(self, PackageManager.GET_PROVIDERS);
            if (info.providers == null) return out;
            for (ProviderInfo provider : info.providers) {
                if (provider.authority == null) continue;
                for (String authority : provider.authority.split(";")) {
                    if (authority.equals(self) || authority.startsWith(self + ".")) {
                        out.add(CloneDiagnostics.ORIGINAL + authority.substring(self.length()));
                    }
                }
            }
        } catch (Throwable t) {
            MorpheLog.diag("DEXFIX could not read the authorities", t);
        }
        return out;
    }

    private static void removeCompiled(File dexDir, File zip) {
        String stem = zip.getName().substring(0, zip.getName().length() - ".zip".length());
        File[] abis = new File(dexDir, "oat").listFiles();
        if (abis == null) return;
        for (File abi : abis) {
            File[] compiled = abi.listFiles();
            if (compiled == null) continue;
            for (File f : compiled) {
                if (f.getName().startsWith(stem + ".")) f.delete();
            }
        }
    }

    private static String read(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int n = in.read(data);
            return new String(data, 0, Math.max(n, 0), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void write(File f, String text) throws java.io.IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(text.getBytes("UTF-8"));
        }
    }
}
