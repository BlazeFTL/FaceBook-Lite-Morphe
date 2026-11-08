package app.fblite.extension.clone;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the code in a dex file that mentions the original package name or asks the system who the app is.
 * Facebook Lite keeps almost all its code in a secondary dex the patcher cannot read, so the app reads its
 * own copy of that dex at run time. Plain Java, no Android classes.
 */
final class DexScan {
    interface Sink {
        void line(String text);
    }

    private static final String[] IDENTITY_METHODS = {
            "getPackageInfo", "getInstallerPackageName", "getInstallSourceInfo", "checkSignatures",
            "getPackagesForUid", "getNameForUid", "getApplicationInfo", "getCallingUid", "getCallingPackage",
            "getLaunchIntentForPackage", "getApplicationLabel", "getSigningInfo"
    };

    private static final int MAX_STRING_HITS = 400;
    private static final int MAX_CALL_HITS = 250;
    private static final int WINDOW = 14;

    private final ByteBuffer b;
    private final Sink sink;
    private final String original;
    private final String[] others;

    private int stringCount, stringOff, typeOff, protoOff, methodCount, methodOff, classCount, classOff;

    private DexScan(byte[] data, String original, String[] others, Sink sink) {
        this.b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        this.original = original;
        this.others = others;
        this.sink = sink;
    }

    static void scan(String label, byte[] data, String original, String[] others, Sink sink) {
        try {
            new DexScan(data, original, others, sink).run(label);
        } catch (Throwable t) {
            sink.line("dexscan " + label + " FAILED " + t);
        }
    }

    private void run(String label) {
        if (b.limit() < 0x70 || b.getInt(0) != 0x0A786564) {
            sink.line("dexscan " + label + " not a dex file");
            return;
        }
        stringCount = b.getInt(0x38);
        stringOff = b.getInt(0x3C);
        typeOff = b.getInt(0x44);
        protoOff = b.getInt(0x4C);
        methodCount = b.getInt(0x58);
        methodOff = b.getInt(0x5C);
        classCount = b.getInt(0x60);
        classOff = b.getInt(0x64);
        int typeCount = b.getInt(0x40);

        // Pass 1: strings that name the original package, and class names that start with it.
        byte[] needle = original.getBytes();
        byte[][] otherNeedles = new byte[others.length][];
        for (int i = 0; i < others.length; i++) otherNeedles[i] = others[i].getBytes();
        byte[] fbpn = "FBPN".getBytes();

        // kind: 0 none, 1 bare, 2 path, 3 prefix, 4 other bare package, 5 marker
        byte[] kind = new byte[stringCount];
        int[] classNames = new int[1];
        Set<String> descriptors = new HashSet<>();
        String descriptorPrefix = "L" + original.replace('.', '/') + "/";
        for (int t = 0; t < typeCount; t++) {
            int s = b.getInt(typeOff + t * 4);
            String d = str(s);
            if (d.startsWith(descriptorPrefix)) descriptors.add(d);
        }
        int[] counts = new int[6];
        for (int i = 0; i < stringCount; i++) {
            int start = dataStart(i);
            int end = start;
            while (end < b.limit() && b.get(end) != 0) end++;
            int length = end - start;
            if (length < 4) continue;
            if (indexOf(start, end, needle) >= 0) {
                String s = str(i);
                if (s.equals(original)) kind[i] = 1;
                else if (s.startsWith(original + ".") || s.startsWith(original + ":")) {
                    if (descriptors.contains("L" + s.replace('.', '/') + ";")) {
                        classNames[0]++;
                        continue;
                    }
                    kind[i] = 3;
                } else kind[i] = 2;
            } else {
                for (byte[] other : otherNeedles) {
                    if (length == other.length && indexOf(start, end, other) == start) {
                        kind[i] = 4;
                        break;
                    }
                }
                if (kind[i] == 0 && indexOf(start, end, fbpn) >= 0) kind[i] = 5;
            }
            if (kind[i] != 0) counts[kind[i]]++;
        }
        sink.line("dexscan " + label + " strings=" + stringCount + " bare=" + counts[1] + " path=" + counts[2]
                + " prefix=" + counts[3] + " otherPackage=" + counts[4] + " FBPN=" + counts[5]
                + " classNamesSkipped=" + classNames[0]);

        // Pass 2: every method's code.
        boolean[] identity = new boolean[methodCount];
        int identityCount = 0;
        for (int m = 0; m < methodCount; m++) {
            int at = methodOff + m * 8;
            String name = str(b.getInt(at + 4));
            for (String wanted : IDENTITY_METHODS) {
                if (name.equals(wanted)) {
                    String owner = str(b.getInt(typeOff + (b.getShort(at) & 0xFFFF) * 4));
                    if (owner.startsWith("Landroid/")) {
                        identity[m] = true;
                        identityCount++;
                    }
                    break;
                }
            }
        }

        int stringHits = 0, callHits = 0;
        for (int c = 0; c < classCount; c++) {
            int def = classOff + c * 32;
            int dataOff = b.getInt(def + 24);
            if (dataOff == 0) continue;
            String owner = str(b.getInt(typeOff + b.getInt(def) * 4));
            int[] p = {dataOff};
            int staticFields = uleb(p), instanceFields = uleb(p), direct = uleb(p), virtual = uleb(p);
            for (int f = 0; f < staticFields + instanceFields; f++) {
                uleb(p);
                uleb(p);
            }
            for (int pass = 0; pass < 2; pass++) {
                int methods = pass == 0 ? direct : virtual;
                int index = 0;
                for (int k = 0; k < methods; k++) {
                    index += uleb(p);
                    uleb(p);
                    int code = uleb(p);
                    if (code == 0) continue;
                    if (stringHits >= MAX_STRING_HITS && callHits >= MAX_CALL_HITS) return;
                    int[] used = scanCode(code, kind, identity);
                    if (used == null) continue;
                    String where = owner + "->" + str(b.getInt(methodOff + index * 8 + 4)) + proto(index);
                    for (int u = 0; u + 2 < used.length; u += 3) {
                        if (used[u] == 0) {
                            if (callHits++ >= MAX_CALL_HITS) continue;
                            sink.line("dexscan CALL " + describeMethod(used[u + 1]) + " in " + where
                                    + "  near: " + nearby(code, used[u + 2]));
                        } else {
                            if (stringHits++ >= MAX_STRING_HITS) continue;
                            int s = used[u + 1];
                            sink.line("dexscan " + new String[]{"", "BARE", "PATH", "PREFIX", "OTHERPKG", "FBPN"}[kind[s]]
                                    + " \"" + cut(str(s)) + "\" in " + where + "  near: " + nearby(code, used[u + 2]));
                        }
                    }
                }
            }
        }
        sink.line("dexscan " + label + " done: identityMethodsInDex=" + identityCount + " stringHits=" + stringHits
                + " callHits=" + callHits + (stringHits > MAX_STRING_HITS || callHits > MAX_CALL_HITS ? " (capped)" : ""));
    }

    /** Triples of (0 call | 1 string, index, code unit offset); null when the method has none. */
    private int[] scanCode(int code, byte[] kind, boolean[] identity) {
        int size = b.getInt(code + 12);
        int base = code + 16;
        List<Integer> found = null;
        int i = 0;
        while (i < size) {
            int unit = b.getShort(base + i * 2) & 0xFFFF;
            int op = unit & 0xFF;
            if (unit == 0x0100) {
                i += (b.getShort(base + (i + 1) * 2) & 0xFFFF) * 2 + 4;
                continue;
            }
            if (unit == 0x0200) {
                i += (b.getShort(base + (i + 1) * 2) & 0xFFFF) * 4 + 2;
                continue;
            }
            if (unit == 0x0300) {
                int width = b.getShort(base + (i + 1) * 2) & 0xFFFF;
                int count = (b.getShort(base + (i + 2) * 2) & 0xFFFF) | ((b.getShort(base + (i + 3) * 2) & 0xFFFF) << 16);
                i += (int) (((long) count * width + 1) / 2) + 4;
                continue;
            }
            if (op == 0x1A || op == 0x1B) {
                int s = op == 0x1A ? b.getShort(base + (i + 1) * 2) & 0xFFFF : b.getInt(base + (i + 1) * 2);
                if (s >= 0 && s < kind.length && kind[s] != 0) {
                    if (found == null) found = new ArrayList<>();
                    found.add(1);
                    found.add(s);
                    found.add(i);
                }
            } else if ((op >= 0x6E && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
                int m = b.getShort(base + (i + 1) * 2) & 0xFFFF;
                if (m < identity.length && identity[m]) {
                    if (found == null) found = new ArrayList<>();
                    found.add(0);
                    found.add(m);
                    found.add(i);
                }
            }
            i += width(op);
        }
        if (found == null) return null;
        int[] out = new int[found.size()];
        for (int k = 0; k < out.length; k++) out[k] = found.get(k);
        return out;
    }

    /** The methods called in the few instructions after a hit: tells equals/startsWith from a lookup. */
    private String nearby(int code, int from) {
        int size = b.getInt(code + 12);
        int base = code + 16;
        StringBuilder sb = new StringBuilder();
        int i = from;
        int steps = 0;
        int guard = 0;
        while (i < size && steps < WINDOW && guard++ < 400) {
            int unit = b.getShort(base + i * 2) & 0xFFFF;
            int op = unit & 0xFF;
            if (unit == 0x0100 || unit == 0x0200 || unit == 0x0300) break;
            if ((op >= 0x6E && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
                int m = b.getShort(base + (i + 1) * 2) & 0xFFFF;
                if (sb.length() > 0) sb.append(" ; ");
                sb.append(describeMethod(m));
            } else if (op == 0x0E || op == 0x0F || op == 0x10 || op == 0x11 || op == 0x27) {
                break;
            }
            i += width(op);
            steps++;
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private String describeMethod(int m) {
        int at = methodOff + m * 8;
        String owner = str(b.getInt(typeOff + (b.getShort(at) & 0xFFFF) * 4));
        int dot = owner.lastIndexOf('/');
        owner = dot >= 0 ? owner.substring(dot + 1) : owner;
        return owner + "." + str(b.getInt(at + 4));
    }

    private String proto(int m) {
        try {
            int at = methodOff + m * 8;
            int proto = protoOff + (b.getShort(at + 2) & 0xFFFF) * 12;
            String ret = str(b.getInt(typeOff + b.getInt(proto + 4) * 4));
            int params = b.getInt(proto + 8);
            StringBuilder sb = new StringBuilder("(");
            if (params != 0) {
                int count = b.getInt(params);
                for (int k = 0; k < count; k++) {
                    sb.append(str(b.getInt(typeOff + (b.getShort(params + 4 + k * 2) & 0xFFFF) * 4)));
                }
            }
            return sb.append(')').append(ret).toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private int dataStart(int stringIndex) {
        int p = b.getInt(stringOff + stringIndex * 4);
        while ((b.get(p) & 0x80) != 0) p++;
        return p + 1;
    }

    private String str(int stringIndex) {
        int start = dataStart(stringIndex);
        int end = start;
        while (end < b.limit() && b.get(end) != 0) end++;
        byte[] bytes = new byte[end - start];
        for (int k = 0; k < bytes.length; k++) bytes[k] = b.get(start + k);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private int indexOf(int start, int end, byte[] needle) {
        int last = end - needle.length;
        outer:
        for (int i = start; i <= last; i++) {
            for (int k = 0; k < needle.length; k++) {
                if (b.get(i + k) != needle[k]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private int uleb(int[] p) {
        int result = 0;
        int shift = 0;
        while (true) {
            int v = b.get(p[0]++) & 0xFF;
            result |= (v & 0x7F) << shift;
            if ((v & 0x80) == 0) return result;
            shift += 7;
        }
    }

    private static String cut(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    private static int width(int op) {
        if (op == 0x00 || op == 0x01 || op == 0x04 || op == 0x07 || (op >= 0x0A && op <= 0x12)
                || op == 0x1D || op == 0x1E || op == 0x21 || op == 0x27 || op == 0x28
                || (op >= 0x3E && op <= 0x43) || op == 0x73 || op == 0x79 || op == 0x7A
                || (op >= 0x7B && op <= 0x8F) || (op >= 0xB0 && op <= 0xCF) || (op >= 0xE3 && op <= 0xF9)) {
            return 1;
        }
        if (op == 0x03 || op == 0x06 || op == 0x09 || op == 0x14 || op == 0x17 || op == 0x1B || op == 0x24
                || op == 0x25 || op == 0x26 || op == 0x2A || op == 0x2B || op == 0x2C
                || (op >= 0x6E && op <= 0x72) || (op >= 0x74 && op <= 0x78) || op == 0xFC || op == 0xFD) {
            return 3;
        }
        if (op == 0x18) return 5;
        if (op == 0xFA || op == 0xFB) return 4;
        return 2;
    }
}
