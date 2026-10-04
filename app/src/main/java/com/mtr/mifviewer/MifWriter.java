package com.mtr.mifviewer;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

/**
 * .imgf 融合器（imgfuse.py fuse 的 Android 实现）。
 * 解码用系统 BitmapFactory；JPEG 存原文；其余转 RGB24 + 行滤波后压缩。
 */
public class MifWriter {

    public static final int CODEC_ZLIB = 0;
    public static final int CODEC_BZ2 = 1;
    public static final int CODEC_LZMA = 2;

    public static final String[] CODEC_NAMES = {"zlib", "bz2", "lzma"};

    /** 输入：一张待融合的图片。jpegBytes 非空表示 JPEG 存原文。 */
    public static class ImageInput {
        public String name;
        public byte[] jpegBytes; // JPEG 原文（kind=file）
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
    }

    public static Result fuse(List<ImageInput> inputs, int codec, OutputStream out,
                              Progress progress) throws IOException {
        List<ImageInput> ok = new ArrayList<>();
        for (ImageInput in : inputs) {
            if (in.jpegBytes != null || in.bitmap != null) ok.add(in);
        }

        ByteArrayOutputStream blob = new ByteArrayOutputStream();
        StringBuilder manifest = new StringBuilder("[");
        long totalIn = 0;
        int done = 0;
        for (int i = 0; i < ok.size(); i++) {
            ImageInput in = ok.get(i);
            if (progress != null) progress.onFile(i, ok.size(), in.name);
            byte[] data;
            int w = 0, h = 0;
            String kind;
            if (in.jpegBytes != null) {
                data = in.jpegBytes;
                kind = "file";
            } else {
                w = in.bitmap.getWidth();
                h = in.bitmap.getHeight();
                data = filterBitmap(in.bitmap);
                in.bitmap.recycle();
                in.bitmap = null;
                kind = "raw";
            }
            totalIn += in.originalSize;
            if (done > 0) manifest.append(",");
            manifest.append("{\"name\":").append(jsonStr(in.name))
                    .append(",\"w\":").append(w)
                    .append(",\"h\":").append(h)
                    .append(",\"kind\":\"").append(kind).append("\"")
                    .append(",\"offset\":").append(blob.size())
                    .append(",\"size\":").append(data.length)
                    .append("}");
            blob.write(data);
            done++;
        }
        manifest.append("]");

        if (done == 0) throw new IOException("没有可用图片");

        byte[] mjson = manifest.toString().getBytes("UTF-8");
        byte[] cblob = compress(blob.toByteArray(), codec);

        out.write('I'); out.write('M'); out.write('G'); out.write('F');
        out.write(codec);
        out.write(1); // flags: 行滤波已应用
        writeU32(out, done);
        writeU32(out, mjson.length);
        out.write(mjson);
        out.write(cblob);
        out.flush();

        Result r = new Result();
        r.fused = done;
        r.skipped = inputs.size() - done;
        r.inBytes = totalIn;
        r.outBytes = 14L + mjson.length + cblob.length;
        return r;
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
