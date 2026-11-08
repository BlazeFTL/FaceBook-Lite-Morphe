package app.fblite.extension.clone;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Makes the app's own code name the clone instead of the original. Facebook Lite keeps hardcoded names
 * (its content provider authorities and content:// URIs, and its bare package name) in a dex file the patcher
 * cannot edit, so this edits the dex the app has already extracted, in place and byte for byte: the
 * replacement has the same length as the original name, so nothing moves. Plain Java, no Android classes.
 */
final class DexRewriter {
    private DexRewriter() {
    }

    /**
     * Rewrites one dex image. A string is changed when it is exactly the original package name, exactly one of
     * the original authorities, or a content:// URI whose authority is one of them.
     *
     * @return the number of strings changed
     */
    static int rewrite(byte[] dex, String original, String replacement, Set<String> authorities) {
        if (original.length() != replacement.length()) throw new IllegalArgumentException("length differs");
        ByteBuffer b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        if (dex.length < 0x70 || b.getInt(0) != 0x0A786564) return 0;
        int count = b.getInt(0x38);
        int table = b.getInt(0x3C);
        byte[] from = original.getBytes();
        byte[] to = replacement.getBytes();
        int changed = 0;
        for (int i = 0; i < count; i++) {
            int p = b.getInt(table + i * 4);
            while ((dex[p] & 0x80) != 0) p++;
            int start = p + 1;
            int end = start;
            while (end < dex.length && dex[end] != 0) end++;
            if (end - start < from.length) continue;
            int at = start;
            boolean uri = startsWith(dex, start, end, "content://");
            if (uri) at = start + "content://".length();
            if (!startsWith(dex, at, end, original)) continue;
            int nameEnd = at;
            while (nameEnd < end && dex[nameEnd] != '/') nameEnd++;
            String name = new String(dex, at, nameEnd - at, java.nio.charset.StandardCharsets.UTF_8);
            boolean wanted = (!uri && name.equals(original)) || authorities.contains(name);
            if (!wanted) continue;
            if (!uri && !name.equals(original) && nameEnd != end) continue;
            System.arraycopy(to, 0, dex, at, to.length);
            changed++;
        }
        if (changed > 0) fixHeader(dex);
        return changed;
    }

    /** Rewrites every .dex in a zip into another zip. Returns the number of strings changed over all of them. */
    static int rewriteZip(File in, File out, String original, String replacement, Set<String> authorities) throws IOException {
        int total = 0;
        try (ZipFile zip = new ZipFile(in); ZipOutputStream os = new ZipOutputStream(new FileOutputStream(out))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream is = zip.getInputStream(entry)) {
                    data = readAll(is, entry.getSize());
                }
                if (!entry.isDirectory() && entry.getName().endsWith(".dex")) {
                    total += rewrite(data, original, replacement, authorities);
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                copy.setTime(entry.getTime());
                if (entry.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(data.length);
                    copy.setCompressedSize(data.length);
                    copy.setCrc(crc.getValue());
                }
                os.putNextEntry(copy);
                os.write(data);
                os.closeEntry();
            }
        }
        return total;
    }

    static void fixHeader(byte[] dex) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(dex, 32, dex.length - 32);
            System.arraycopy(sha1.digest(), 0, dex, 12, 20);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Adler32 adler = new Adler32();
        adler.update(dex, 12, dex.length - 12);
        long sum = adler.getValue();
        dex[8] = (byte) sum;
        dex[9] = (byte) (sum >> 8);
        dex[10] = (byte) (sum >> 16);
        dex[11] = (byte) (sum >> 24);
    }

    private static boolean startsWith(byte[] data, int start, int end, String prefix) {
        if (end - start < prefix.length()) return false;
        for (int k = 0; k < prefix.length(); k++) {
            if (data[start + k] != (byte) prefix.charAt(k)) return false;
        }
        return true;
    }

    private static byte[] readAll(InputStream in, long size) throws IOException {
        if (size >= 0 && size < Integer.MAX_VALUE) {
            byte[] data = new byte[(int) size];
            int done = 0;
            while (done < data.length) {
                int n = in.read(data, done, data.length - done);
                if (n < 0) break;
                done += n;
            }
            if (done == data.length) return data;
            return java.util.Arrays.copyOf(data, done);
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
