package com.kizeo.kzstorereader.utils;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * PS3 PKG Extractor — port fiel de pkg_custom.py y Ps3PkgBuilder.kt
 *
 * Header layout exacto (0x80 bytes, big-endian):
 *   0x00  magic        uint32   = 0x7F504B47
 *   0x04  type         uint32   = 0x00000001 (custom)
 *   0x08  pkgInfoOff   uint32
 *   0x0C  unk1         uint32
 *   0x10  headSize     uint32
 *   0x14  itemCount    uint32
 *   0x18  packageSize  uint64
 *   0x20  dataOff      uint64   (= 0x140 en paquetes custom)
 *   0x28  dataSize     uint64
 *   0x30  contentID    [0x30]
 *   0x60  QADigest     [0x10]
 *   0x70  KLicensee    [0x10]
 *
 * FileHeader (0x20 bytes, big-endian):
 *   0x00  fileNameOff    uint32
 *   0x04  fileNameLength uint32
 *   0x08  fileOff        uint64
 *   0x10  fileSize       uint64
 *   0x18  flags          uint32
 *   0x1C  padding        uint32
 */
public class PkgExtractor {

    private static final String TAG = "PkgExtractor";

    private static final int  PKG_MAGIC  = 0x7F504B47;
    private static final int  PKG_NORMAL = 0x00000001;
    private static final int  PKG_SIGNED = 0x80000001;
    private static final int  TYPE_DIR   = 0x4;
    private static final int  HDR_SIZE   = 0x80;
    private static final int  FHDR_SIZE  = 0x20;
    private static final long MAX_CACHE  = 0x8000000L; // 128 MB

    public interface ProgressCallback {
        void onProgress(long done, long total);
        void onLog(String message);
        boolean isCancelled();
    }

    public static class ExtractResult {
        public final boolean success;
        public final String  contentId, outDir, error;
        public ExtractResult(boolean s, String c, String o, String e) {
            success = s; contentId = c; outDir = o; error = e;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Crypto — réplica exacta de pkg_custom.py / Ps3PkgBuilder
    // ══════════════════════════════════════════════════════════

    private static byte[] keyToContext(byte[] key) {
        byte[] ctx = new byte[0x40]; // 0x20..0x3F = cero
        for (int i = 0; i < 8; i++) {
            ctx[i]      = key[i];
            ctx[i +  8] = key[i];
            ctx[i + 16] = key[i + 8];
            ctx[i + 24] = key[i + 8];
        }
        return ctx;
    }

    private static void manipulate(byte[] ctx) {
        long n = 0;
        for (int i = 0x38; i < 0x40; i++) n = (n << 8) | (ctx[i] & 0xFFL);
        n = (n + 1L) & 0xFFFFFFFFFFFFFFFFL;
        for (int i = 0x3F; i >= 0x38; i--) { ctx[i] = (byte)(n & 0xFF); n >>>= 8; }
    }

    /** Stream cipher: SHA1(ctx[0..3F]) XOR input, incrementa counter. ctx se modifica. */
    private static byte[] pkgCrypt(byte[] ctx, byte[] input, int inOffset, int length) {
        byte[] out = new byte[length];
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            int off = 0, rem = length;
            while (rem > 0) {
                int chunk = Math.min(rem, 0x10);
                sha1.reset();
                byte[] hash = sha1.digest(ctx);
                for (int i = 0; i < chunk; i++) {
                    out[off] = (byte)(hash[i] ^ input[inOffset + off]);
                    off++;
                }
                manipulate(ctx);
                rem -= chunk;
            }
        } catch (Exception e) {
            Log.e(TAG, "pkgCrypt", e);
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════
    //  Extracción principal
    // ══════════════════════════════════════════════════════════

    public static ExtractResult extract(String pkgPath, String outBaseDir, ProgressCallback cb) {
        try {
            File pkgFile = new File(pkgPath);
            if (!pkgFile.exists()) return fail("File not found: " + pkgPath);

            // 1. Leer header
            byte[] hdrRaw = new byte[HDR_SIZE];
            try (FileInputStream fis = new FileInputStream(pkgFile)) {
                if (fis.read(hdrRaw) < HDR_SIZE) return fail("File too small");
            }

            ByteBuffer hdr = ByteBuffer.wrap(hdrRaw).order(ByteOrder.BIG_ENDIAN);
            int  magic     = hdr.getInt(0x00);
            int  type      = hdr.getInt(0x04);
            int  itemCount = hdr.getInt(0x14);
            long dataOff   = hdr.getLong(0x20);
            long dataSize  = hdr.getLong(0x28);

            byte[] contentIDRaw = new byte[0x30];
            byte[] qaDigestRaw  = new byte[0x10];
            hdr.position(0x30); hdr.get(contentIDRaw);
            hdr.position(0x60); hdr.get(qaDigestRaw);

            // Validaciones
            if (magic != PKG_MAGIC)
                return fail(String.format("Invalid magic: 0x%08X (expected 0x7F504B47)", magic));
            if (type == PKG_SIGNED)
                return fail("Signed/retail PKG (0x80000001) not supported");
            if (type != PKG_NORMAL)
                return fail(String.format("Unsupported type: 0x%08X", type));
            if (itemCount <= 0)
                return fail("Empty PKG (itemCount=0)");
            if (dataOff <= 0 || dataSize <= 0)
                return fail(String.format("Invalid offsets: dataOff=0x%X dataSize=0x%X", dataOff, dataSize));

            String contentId = nullTerminated(contentIDRaw);
            log(cb, "Content-ID: " + contentId + " | Items: " + itemCount
                    + " | " + String.format("%.5f", dataSize / 1_048_576.0) + " MB");

            // 2. Leer y descifrar bloque descriptor
            // pkg_custom.py: dataEnc = data[dataOff : dataOff + 0x200 * itemCount]
            int descReadLen = 0x200 * itemCount;
            byte[] descEnc = new byte[descReadLen];
            int actualRead;
            try (RandomAccessFile raf = new RandomAccessFile(pkgFile, "r")) {
                raf.seek(dataOff);
                actualRead = raf.read(descEnc, 0, descReadLen);
            }
            if (actualRead < descReadLen) {
                byte[] tmp = new byte[actualRead];
                System.arraycopy(descEnc, 0, tmp, 0, actualRead);
                descEnc = tmp;
                descReadLen = actualRead;
            }

            byte[] descCtx = keyToContext(qaDigestRaw);
            byte[] decData  = pkgCrypt(descCtx, descEnc, 0, descReadLen);

            // 3. Parsear FileHeaders del bloque descifrado
            List<PkgEntry> entries = new ArrayList<>();
            for (int i = 0; i < itemCount; i++) {
                int base = FHDR_SIZE * i;
                if (base + FHDR_SIZE > decData.length) break;
                ByteBuffer fb = ByteBuffer.wrap(decData, base, FHDR_SIZE)
                        .order(ByteOrder.BIG_ENDIAN);
                int  nameOff = fb.getInt();
                int  nameLen = fb.getInt();
                long fileOff = fb.getLong();
                long fileSz  = fb.getLong();
                int  flags   = fb.getInt();

                String name = "";
                if (nameLen > 0 && nameOff >= 0 && nameOff + nameLen <= decData.length) {
                    name = new String(decData, nameOff, nameLen, "UTF-8");
                }
                // Eliminar path traversal — igual que pkg_custom.py
                name = name.replace("../../", "")
                           .replace("../../../", "")
                           .replaceAll("^[/\\\\]+", "");

                boolean isDir = (flags & 0xFF) == TYPE_DIR;
                entries.add(new PkgEntry(name, fileOff, fileSz, isDir));
                log(cb, (isDir ? "  DIR  " : "  FILE ") + name
                        + (isDir ? "" : "  (" + fileSz + " B)"));
            }

            // 4. Directorio de salida
            File outDir = new File(outBaseDir, contentId);
            outDir.mkdirs();
            log(cb, "Destination: " + outDir.getAbsolutePath());

            long totalBytes = 0;
            for (PkgEntry e : entries) if (!e.isDir) totalBytes += e.size;

            // 5. Descifrar y extraer contenido
            if (dataSize > MAX_CACHE) {
                extractLarge(pkgFile, outDir, entries, dataOff, dataSize, qaDigestRaw, totalBytes, cb);
            } else {
                extractSmall(pkgFile, outDir, entries, dataOff, dataSize, qaDigestRaw, totalBytes, cb);
            }

            log(cb, "✔ Done — " + itemCount + " items → " + outDir.getAbsolutePath());
            return new ExtractResult(true, contentId, outDir.getAbsolutePath(), "");

        } catch (Exception ex) {
            Log.e(TAG, "extract()", ex);
            return fail("Exception: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    // ── PKG pequeño (≤ 128 MB): descifrado en memoria ─────────────────────────

    private static void extractSmall(File pkg, File outDir, List<PkgEntry> entries,
                                      long dataOff, long dataSize,
                                      byte[] qaDigest, long totalBytes,
                                      ProgressCallback cb) throws Exception {
        byte[] encData = new byte[(int) dataSize];
        try (RandomAccessFile raf = new RandomAccessFile(pkg, "r")) {
            raf.seek(dataOff);
            raf.readFully(encData);
        }
        byte[] ctx     = keyToContext(qaDigest);
        byte[] decData = pkgCrypt(ctx, encData, 0, (int) dataSize);

        long done = 0;
        for (PkgEntry e : entries) {
            if (cb != null && cb.isCancelled()) return;
            if (e.name.isEmpty()) continue;
            File dest = new File(outDir, e.name.replace('/', File.separatorChar));
            if (e.isDir) {
                dest.mkdirs();
            } else {
                dest.getParentFile().mkdirs();
                int fOff = (int) e.offset;
                int fSz  = (int) e.size;
                if (fOff >= 0 && fOff + fSz <= decData.length) {
                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        fos.write(decData, fOff, fSz);
                    }
                } else {
                    log(cb, "⚠ Skip out-of-range: " + e.name);
                }
                done += e.size;
                if (cb != null) cb.onProgress(done, totalBytes);
            }
        }
    }

    // ── PKG grande (> 128 MB): streaming con archivo temporal ─────────────────

    private static void extractLarge(File pkg, File outDir, List<PkgEntry> entries,
                                      long dataOff, long dataSize,
                                      byte[] qaDigest, long totalBytes,
                                      ProgressCallback cb) throws Exception {
        File tempFile = new File(outDir.getParentFile(), "_ps3dec.bin");
        byte[] ctx     = keyToContext(qaDigest);
        int    chunkSz = 0x400000; // 4 MB

        // Descifrar PKG completo al temporal
        try (RandomAccessFile in  = new RandomAccessFile(pkg, "r");
             FileOutputStream out = new FileOutputStream(tempFile)) {
            in.seek(dataOff);
            long rem = dataSize, written = 0;
            byte[] buf = new byte[chunkSz];
            while (rem > 0) {
                if (cb != null && cb.isCancelled()) break;
                int n = (int) Math.min(chunkSz, rem);
                in.readFully(buf, 0, n);
                byte[] dec = pkgCrypt(ctx, buf, 0, n);
                out.write(dec, 0, n);
                rem     -= n;
                written += n;
                if (cb != null) cb.onProgress(written, dataSize);
            }
        }

        // Extraer archivos desde el temporal descifrado
        long done = 0;
        try (RandomAccessFile dec = new RandomAccessFile(tempFile, "r")) {
            byte[] buf = new byte[65536];
            for (PkgEntry e : entries) {
                if (cb != null && cb.isCancelled()) break;
                if (e.name.isEmpty()) continue;
                File dest = new File(outDir, e.name.replace('/', File.separatorChar));
                if (e.isDir) {
                    dest.mkdirs();
                } else {
                    dest.getParentFile().mkdirs();
                    dec.seek(e.offset);
                    long sz = e.size;
                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        while (sz > 0) {
                            int r = (int) Math.min(buf.length, sz);
                            dec.readFully(buf, 0, r);
                            fos.write(buf, 0, r);
                            sz -= r;
                        }
                    }
                    done += e.size;
                    if (cb != null) cb.onProgress(done, totalBytes);
                }
            }
        } finally {
            tempFile.delete();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String nullTerminated(byte[] b) {
        int z = -1;
        for (int i = 0; i < b.length; i++) { if (b[i] == 0) { z = i; break; } }
        try { return new String(b, 0, z == -1 ? b.length : z, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static void log(ProgressCallback cb, String msg) {
        Log.d(TAG, msg);
        if (cb != null) cb.onLog(msg);
    }

    private static ExtractResult fail(String msg) {
        Log.e(TAG, "FAIL: " + msg);
        return new ExtractResult(false, "", "", msg);
    }

    static class PkgEntry {
        final String name; final long offset, size; final boolean isDir;
        PkgEntry(String n, long o, long s, boolean d) { name=n; offset=o; size=s; isDir=d; }
    }
}
