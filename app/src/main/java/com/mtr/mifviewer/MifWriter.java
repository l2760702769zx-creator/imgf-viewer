package com.mtr.mifviewer;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

/**
 * .imgf 融合器（imgfuse.py fuse 的 Android 实现），v1.1 分块。
 * 解码用系统 BitmapFactory；JPEG/WebP/HEIC/AVIF 存原文；
 * 其余转 RGB24 + 行滤波，分块压缩。
 *
 * v1.1 改进：分块后查看时只需解压对应块，不再要求全量解压。
 */
public class MifWriter {

    public static final int CODEC_ZLIB = 0;
    public static final int CODEC_BZ2 = 1;
    public static final int CODEC_LZMA = 2;

    public static final String[] CODEC_NAMES = {"zlib", "bz2", "lzma"};

    public static final int FLAG_FILTERED = 1;
    public static final int FLAG_CHUNKED = 2;

    /** 每块解压后上限（8MB，手机友好）。 */
    public static final int CHUNK_MAX_BYTES = 8 * 1024 * 1024;
    /** 每块图片数上限。 */
    public static final int CHUNK_MAX_IMAGES = 64;

    /** 输入：一张待融合的图片。fileBytes 非空表示有损格式存原文。 */
    public static class ImageInput {
        public String name;
        public byte[] fileBytes; // JPEG/WebP/HEIC/AVIF 原文（kind=file）
        public Bitmap bitmap;    // 其余格式解码后的位图（kind=raw）
        public int originalSize;
    }

    public interface Progress {
        void onFile(int idx, int total, String name);
    }

    public static class Result {
        public int fused;
        public int skipped;
        public long inBytes;
        public long outBytes;
        public int chunks;
    }

    /** 有损格式按魔数识别，存原文不解码。 */
    public static boolean isPassthrough(byte[] d) {
        if (d == null || d.length < 2) return false;
        // JPEG
        if (d[0] == (byte) 0xFF && d[1] == (byte) 0xD8) return true;
        if (d.length < 12) return false;
        // WebP: RIFF....WEBP
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return true;
        }
        // HEIC/HEIF/AVIF: ....ftyp + brand
        if (d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
            String brand = new String(d, 8, 4);
            return brand.equals("heic") || brand.equals("heix")
                    || brand.equals("hevc") || brand.equals("heim")
                    || brand.equals("heis") || brand.equals("mif1")
                    || brand.equals("msf1") || brand.equals("avif")
                    || brand.equals("avis");
        }
        return false;
    }

    private static class Prepared {
        final String name;
        final int w, h;
        final String kind;
        final byte[] data;
        final int originalSize;
        Prepared(String name, int w, int h, String kind, byte[] data, int originalSize) {
            this.name = name; this.w = w; this.h = h;
            this.kind = kind; this.data = data; this.originalSize = originalSize;
        }
    }

    public static Result fuse(List<ImageInput> inputs, int codec, OutputStream out,
                              Progress progress) throws IOException {
        List<ImageInput> ok = new ArrayList<>();
        for (ImageInput in : inputs) {
            if (in.fileBytes != null || in.bitmap != null) ok.add(in);
        }
        if (ok.isEmpty()) throw new IOException("没有可用图片");

        // ---- 阶段 1：行滤波（并行；手机上最多 2 线程，控制内存） ----
        int nThreads = Math.min(2, Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        List<Future<Prepared>> futures = new ArrayList<>(ok.size());
        for (int i = 0; i < ok.size(); i++) {
            final ImageInput in = ok.get(i);
            final int idx = i;
            futures.add(pool.submit(() -> {
                byte[] data;
                int w = 0, h = 0;
                String kind;
                if (in.fileBytes != null) {
                    data = in.fileBytes;
                    kind = "file";
                } else {
                    w = in.bitmap.getWidth();
                    h = in.bitmap.getHeight();
                    data = filterBitmap(in.bitmap);
                    in.bitmap.recycle();
                    in.bitmap = null;
                    kind = "raw";
                }
                if (progress != null) progress.onFile(idx, ok.size(), in.name);
                return new Prepared(in.name, w, h, kind, data, in.originalSize);
            }));
        }
        pool.shutdown();
        List<Prepared> prepared = new ArrayList<>(ok.size());
        try {
            for (Future<Prepared> f : futures) prepared.add(f.get());
        } catch (Exception e) {
            throw new IOException("滤波失败: " + e.getMessage());
        }

        // ---- 阶段 2：分块组装 ----
        List<byte[]> cchunks = new ArrayList<>();
        List<Integer> usizes = new ArrayList<>();
        List<String> mentries = new ArrayList<>();
        ByteArrayOutputStream curBuf = new ByteArrayOutputStream();
        List<Prepared> curList = new ArrayList<>();
        List<int[]> curOffs = new ArrayList<>(); // 每块内条目的 offset,size
        long totalIn = 0;

        for (Prepared p : prepared) {
            totalIn += p.originalSize;
            if ((curBuf.size() + p.data.length > CHUNK_MAX_BYTES
                    || curList.size() >= CHUNK_MAX_IMAGES) && !curList.isEmpty()) {
                sealChunk(curBuf, curList, curOffs, cchunks, usizes, mentries, codec);
                curBuf = new ByteArrayOutputStream();
                curList = new ArrayList<>();
                curOffs = new ArrayList<>();
            }
            curOffs.add(new int[]{curBuf.size(), p.data.length});
            curList.add(p);
            curBuf.write(p.data);
        }
        if (!curList.isEmpty()) {
            sealChunk(curBuf, curList, curOffs, cchunks, usizes, mentries, codec);
        }

        // ---- 写文件 ----
        StringBuilder manifest = new StringBuilder("[");
        for (int i = 0; i < mentries.size(); i++) {
            if (i > 0) manifest.append(",");
            manifest.append(mentries.get(i));
        }
        manifest.append("]");
        byte[] mjson = manifest.toString().getBytes("UTF-8");

        out.write('I'); out.write('M'); out.write('G'); out.write('F');
        out.write(codec);
        out.write(FLAG_FILTERED | FLAG_CHUNKED);
        writeU32(out, mentries.size());
        writeU32(out, mjson.length);
        writeU32(out, cchunks.size());
        for (int i = 0; i < cchunks.size(); i++) {
            writeU32(out, cchunks.get(i).length);
            writeU32(out, usizes.get(i));
        }
        out.write(mjson);
        long cblobLen = 0;
        for (byte[] c : cchunks) {
            out.write(c);
            cblobLen += c.length;
        }
        out.flush();

        Result r = new Result();
        r.fused = mentries.size();
        r.skipped = inputs.size() - mentries.size();
        r.inBytes = totalIn;
        r.chunks = cchunks.size();
        r.outBytes = 18L + cchunks.size() * 8L + mjson.length + cblobLen;
        return r;
    }

    private static void sealChunk(ByteArrayOutputStream curBuf, List<Prepared> curList,
                                  List<int[]> curOffs, List<byte[]> cchunks,
                                  List<Integer> usizes, List<String> mentries,
                                  int codec) throws IOException {
        byte[] raw = curBuf.toByteArray();
        byte[] c = compress(raw, codec);
        int ci = cchunks.size();
        cchunks.add(c);
        usizes.add(raw.length);
        for (int i = 0; i < curList.size(); i++) {
            Prepared p = curList.get(i);
            int[] os = curOffs.get(i);
            mentries.add("{\"name\":" + jsonStr(p.name)
                    + ",\"w\":" + p.w
                    + ",\"h\":" + p.h
                    + ",\"kind\":\"" + p.kind + "\""
                    + ",\"chunk\":" + ci
                    + ",\"offset\":" + os[0]
                    + ",\"size\":" + os[1]
                    + "}");
        }
    }

    // ---------- 压缩 ----------

    private static byte[] compress(byte[] data, int codec) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        OutputStream cos;
        switch (codec) {
            case CODEC_ZLIB:
                cos = new DeflaterOutputStream(bos, new Deflater(9));
                break;
            case CODEC_BZ2:
                cos = new BZip2CompressorOutputStream(bos);
                break;
            case CODEC_LZMA: {
                LZMA2Options opts = new LZMA2Options();
                // 手机堆内存有限：preset 6（8MB 字典，约 80MB 内存）。
                // preset 9 需要 600MB+，会在 Android 上 OOM（实测）。
                opts.setPreset(6);
                cos = new XZOutputStream(bos, opts);
                break;
            }
            default:
                throw new IOException("未知 codec");
        }
        cos.write(data);
        cos.close();
        return bos.toByteArray();
    }

    // ---------- PNG 行滤波（每行选最优） ----------

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    private static byte[] filterBitmap(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        byte[] rgb = new byte[w * h * 3];
        for (int i = 0; i < w * h; i++) {
            int c = px[i];
            rgb[i * 3] = (byte) (c >> 16);
            rgb[i * 3 + 1] = (byte) (c >> 8);
            rgb[i * 3 + 2] = (byte) c;
        }
        return filterRows(rgb, w, h);
    }

    /** 纯函数：RGB24 → 行滤波后数据（JVM 可单测）。 */
    public static byte[] filterRows(byte[] rgb, int w, int h) {
        int bpp = 3, bpr = w * bpp;
        byte[] out = new byte[h * (1 + bpr)];
        byte[] prev = new byte[bpr];
        byte[] row = new byte[bpr];
        byte[] fbuf = new byte[bpr];
        int pos = 0;
        for (int y = 0; y < h; y++) {
            System.arraycopy(rgb, y * bpr, row, 0, bpr);
            int bestF = 0, bestScore = Integer.MAX_VALUE;
            byte[] bestBuf = null;
            for (int f = 0; f < 5; f++) {
                int score = 0;
                for (int i = 0; i < bpr; i++) {
                    int v = row[i] & 0xFF;
                    int fv;
                    switch (f) {
                        case 0: fv = v; break;
                        case 1: fv = (v - (i >= bpp ? row[i - bpp] & 0xFF : 0)) & 0xFF; break;
                        case 2: fv = (v - (prev[i] & 0xFF)) & 0xFF; break;
                        case 3: {
                            int a = i >= bpp ? row[i - bpp] & 0xFF : 0;
                            fv = (v - ((a + (prev[i] & 0xFF)) >> 1)) & 0xFF;
                            break;
                        }
                        default: {
                            int a = i >= bpp ? row[i - bpp] & 0xFF : 0;
                            int b = prev[i] & 0xFF;
                            int c = i >= bpp ? prev[i - bpp] & 0xFF : 0;
                            fv = (v - paeth(a, b, c)) & 0xFF;
                            break;
                        }
                    }
                    fbuf[i] = (byte) fv;
                    score += fv < 128 ? fv : 256 - fv;
                    if (score >= bestScore) break; // 早停
                }
                if (score < bestScore) {
                    bestScore = score;
                    bestF = f;
                    bestBuf = fbuf;
                    fbuf = new byte[bpr];
                }
            }
            out[pos++] = (byte) bestF;
            System.arraycopy(bestBuf, 0, out, pos, bpr);
            pos += bpr;
            byte[] tmp = prev; prev = row; row = tmp;
        }
        return out;
    }

    // ---------- 工具 ----------

    private static void writeU32(OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
