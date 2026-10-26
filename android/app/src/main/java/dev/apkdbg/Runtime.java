package dev.apkdbg;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * The bundled agent runtime. The native payload ships as .so files in
 * nativeLibraryDir (so they can be executed): libprimeagent.so,
 * libpythonexe.so, libpython3.x.so and friends, and libpythonhome.so, which is
 * really a tar.gz of PYTHONHOME that is unpacked into app storage once per
 * install.
 */
final class Runtime {
    interface Progress { void report(String msg); }

    private Runtime() { }

    static boolean bundled(Context c) {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        return new File(lib, "libprimeagent.so").isFile() && new File(lib, "libpythonhome.so").isFile();
    }

    /** Unpack PYTHONHOME if this install hasn't yet, and return it. */
    static synchronized File ensurePythonHome(Context c, Progress progress) throws IOException {
        File home = new File(c.getFilesDir(), "pyhome");
        File stamp = new File(home, ".installed");
        String want = installStamp(c);
        if (stamp.isFile() && want.equals(read(stamp))) return home;

        progress.report("Unpacking Python (first run only)…");
        deleteTree(home);
        home.mkdirs();
        File archive = new File(c.getApplicationInfo().nativeLibraryDir, "libpythonhome.so");
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive), 1 << 16))) {
            untar(in, home);
        }
        try (OutputStream o = new FileOutputStream(stamp)) {
            o.write(want.getBytes(StandardCharsets.UTF_8));
        }
        return home;
    }

    private static String installStamp(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.lastUpdateTime + "/" + pi.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** A small ustar/GNU tar reader: regular files, directories, symlinks skipped, long names. */
    static void untar(InputStream in, File dest) throws IOException {
        String root = dest.getCanonicalPath() + File.separator;
        byte[] header = new byte[512];
        byte[] buf = new byte[1 << 16];
        String longName = null;
        while (true) {
            if (!readFully(in, header)) return;
            if (isZero(header)) return;
            String name = cstring(header, 0, 100);
            String prefix = cstring(header, 345, 155);
            long size = octal(header, 124, 12);
            char kind = (char) header[156];
            int mode = (int) octal(header, 100, 8);
            if (longName != null) { name = longName; longName = null; }
            else if (!prefix.isEmpty()) name = prefix + "/" + name;

            if (kind == 'L') { // GNU long name: the data is the next entry's name
                byte[] n = new byte[(int) size];
                readFully(in, n);
                skip(in, pad(size));
                longName = cstring(n, 0, n.length);
                continue;
            }
            File out = new File(dest, name);
            if (!(out.getCanonicalPath() + File.separator).startsWith(root) && !out.getCanonicalPath().equals(dest.getCanonicalPath())) {
                throw new IOException("bad path in archive: " + name);
            }
            if (kind == '5') {
                out.mkdirs();
            } else if (kind == '0' || kind == '\0') {
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    long left = size;
                    while (left > 0) {
                        int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (r < 0) throw new IOException("truncated archive");
                        o.write(buf, 0, r);
                        left -= r;
                    }
                }
                if ((mode & 0111) != 0) out.setExecutable(true, true);
                skip(in, pad(size));
                continue;
            }
            // pax headers, links and anything else: skip the data
            skip(in, size + pad(size));
        }
    }

    private static long pad(long size) { return (512 - (size % 512)) % 512; }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) {
                if (off == 0) return false;
                throw new IOException("truncated archive");
            }
            off += r;
        }
        return true;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] tmp = new byte[8192];
        while (n > 0) {
            int r = in.read(tmp, 0, (int) Math.min(tmp.length, n));
            if (r < 0) throw new IOException("truncated archive");
            n -= r;
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstring(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte x = b[i];
            if (x == 0 || x == ' ') { if (v > 0) break; else continue; }
            if (x < '0' || x > '7') break;
            v = v * 8 + (x - '0');
        }
        return v;
    }

    private static String read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
