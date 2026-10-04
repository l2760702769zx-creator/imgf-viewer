package com.mtr.mifviewer;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.tukaani.xz.XZInputStream;

/**
 * .imgf 文件解析器（imgfuse.py 的输出格式），v1.1 分块。
 * 纯 Java、无 Android 依赖，可在普通 JVM 上单测。
 *
 * 格式:
 *   magic 4B "IMGF"（旧文件 "MIF1" 仍兼容） | codec 1B (0=zlib 1=bz2 2=lzma)
 *   flags 1B (bit0=行滤波, bit1=分块) | n 4B | mlen 4B
 *   [分块时] cnum 4B + 每块 8B(csize, usize)
 *   manifest JSON | 压缩数据（分块时为各块拼接）
 * manifest: [{name,w,h,kind,chunk,offset,size}]
 *
 * v1.1 关键改进：查看任意一张图只解压其所在的块（~8MB），
 * 不再全量解压整个 blob。
 */
public class MifParser {

    public static final int FLAG_FILTERED = 1;
    public static final int FLAG_CHUNKED = 2;

    public static class ChunkInfo {
        public int coff;   // 在压缩 blob 中的偏移
        public int csize;  // 压缩后大小
        public int usize;  // 解压后大小
    }

    public static class Entry {
        public String name;
        public int w, h;
        public String kind;      // "raw" 或 "file"
        public int chunk;        // 所在块索引（v1.0 文件为 0）
        public int offset, size; // 在解压后块数据中的位置
    }

    public static class Bundle {
        public final List<Entry> entries = new ArrayList<>();
        private byte[] compressed;          // 全部压缩数据（各块拼接）
        private List<ChunkInfo> chunks;     // null 表示 v1.0 单块
        private int codec;
        // 只缓存最近解压的一块
        private int cachedChunk = -1;
        private byte[] cachedData;

        /** 按需解码第 i 张图：只解压其所在的块。raw 返回 ARGB int[]；file 返回原文件字节。 */
        public synchronized Decoded decode(int i) throws IOException {
            Entry e = entries.get(i);
            byte[] raw = chunkData(e.chunk);
            if (e.offset < 0 || e.size < 0 || e.offset + e.size > raw.length) {
                throw new IOException("manifest offset 越界: " + e.name);
            }
            byte[] seg = new byte[e.size];
            System.arraycopy(raw, e.offset, seg, 0, e.size);
            Decoded d = new Decoded();
            d.name = e.name;
            d.w = e.w;
            d.h = e.h;
            if ("file".equals(e.kind)) {
                d.fileBytes = seg;
            } else {
                d.argb = unfilter(seg, e.w, e.h);
            }
            return d;
        }

        /** 解压第 idx 块（带缓存）。 */
        private synchronized byte[] chunkData(int idx) throws IOException {
            if (idx == cachedChunk && cachedData != null) return cachedData;
            byte[] seg;
            if (chunks == null) {
                seg = compressed;
            } else {
                ChunkInfo ci = chunks.get(idx);
                if (ci.coff < 0 || ci.csize < 0 || ci.coff + ci.csize > compressed.length) {
                    throw new IOException("块表越界");
                }
                seg = new byte[ci.csize];
                System.arraycopy(compressed, ci.coff, seg, 0, ci.csize);
            }
            byte[] raw = decompress(seg, codec);
            cachedChunk = idx;
            cachedData = raw;
            return raw;
        }

        public boolean isChunked() { return chunks != null; }
        public int chunkCount() { return chunks == null ? 1 : chunks.size(); }
    }

    public static class Decoded {
        public String name;
        public int w, h;
        public byte[] fileBytes; // kind=file（JPEG/WebP/HEIC 原文）
        public int[] argb;       // kind=raw
    }

    public static Bundle parse(InputStream in) throws IOException {
        byte[] hdr = readFully(in, 14);
        boolean isNew = hdr[0] == 'I' && hdr[1] == 'M' && hdr[2] == 'G' && hdr[3] == 'F';
        boolean isOld = hdr[0] == 'M' && hdr[1] == 'I' && hdr[2] == 'F' && hdr[3] == '1';
        if (!isNew && !isOld) {
            throw new IOException("不是 .imgf 文件 (magic 不匹配)");
        }
        int codec = hdr[4] & 0xFF;
        int flags = hdr[5] & 0xFF;
        int n = u32(hdr, 6);
        int mlen = u32(hdr, 10);
        if (n < 0 || n > 100000 || mlen < 0 || mlen > 64 * 1024 * 1024) {
            throw new IOException("manifest 头异常");
        }
        boolean chunked = (flags & FLAG_CHUNKED) != 0;
        if (chunked && isOld) {
            throw new IOException("旧格式文件不应有分块标志");
        }

        List<ChunkInfo> chunks = null;
        if (chunked) {
            int cnum = u32(readFully(in, 4), 0);
            if (cnum <= 0 || cnum > 100000) throw new IOException("块数异常");
            chunks = new ArrayList<>(cnum);
            byte[] ct = readFully(in, cnum * 8);
            int coff = 0;
            for (int i = 0; i < cnum; i++) {
                ChunkInfo ci = new ChunkInfo();
                ci.csize = u32(ct, i * 8);
                ci.usize = u32(ct, i * 8 + 4);
                ci.coff = coff;
                if (ci.csize < 0 || ci.usize < 0 || ci.usize > 512 * 1024 * 1024) {
                    throw new IOException("块大小异常");
                }
                chunks.add(ci);
                coff += ci.csize;
            }
        }

        String manifest = new String(readFully(in, mlen), "UTF-8");
        byte[] compressed = readAll(in); // 只读，不解压

        Bundle b = new Bundle();
        b.compressed = compressed;
        b.chunks = chunks;
        b.codec = codec;
        for (Entry e : parseManifest(manifest)) {
            b.entries.add(e);
        }
        if (b.entries.size() != n) {
            throw new IOException("manifest 数量与头不一致");
        }
        return b;
    }

    // ---------- 解压 ----------

    private static byte[] decompress(byte[] data, int codec) throws IOException {
        InputStream raw = new java.io.ByteArrayInputStream(data);
        InputStream dec;
        switch (codec) {
            case 0: dec = new InflaterInputStream(raw); break;
            case 1: dec = new BZip2CompressorInputStream(raw); break;
            case 2: dec = new XZInputStream(raw); break;
            default: throw new IOException("未知 codec: " + codec);
        }
        try {
            return readAll(dec);
        } finally {
            dec.close();
        }
    }

    // ---------- 行滤波逆变换 ----------

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    /** seg: h 行，每行 1 字节 filter + w*3 字节。返回 ARGB int[]。 */
    static int[] unfilter(byte[] seg, int w, int h) throws IOException {
        int bpp = 3, bpr = w * bpp;
        if (seg.length != h * (1 + bpr)) {
            throw new IOException("raw 数据长度不符: " + seg.length);
        }
        int[] out = new int[w * h];
        byte[] prev = new byte[bpr];
        byte[] row = new byte[bpr];
        int pos = 0;
        for (int y = 0; y < h; y++) {
            int f = seg[pos++] & 0xFF;
            System.arraycopy(seg, pos, row, 0, bpr);
            pos += bpr;
            switch (f) {
                case 0: break;
                case 1:
                    for (int i = bpp; i < bpr; i++) row[i] = (byte) (row[i] + row[i - bpp]);
                    break;
                case 2:
                    for (int i = 0; i < bpr; i++) row[i] = (byte) (row[i] + prev[i]);
                    break;
                case 3:
                    for (int i = 0; i < bpr; i++) {
                        int a = i >= bpp ? row[i - bpp] & 0xFF : 0;
                        row[i] = (byte) (row[i] + ((a + (prev[i] & 0xFF)) >> 1));
                    }
                    break;
                case 4:
                    for (int i = 0; i < bpr; i++) {
                        int a = i >= bpp ? row[i - bpp] & 0xFF : 0;
                        int bb = prev[i] & 0xFF;
                        int c = i >= bpp ? prev[i - bpp] & 0xFF : 0;
                        row[i] = (byte) (row[i] + paeth(a, bb, c));
                    }
                    break;
                default: throw new IOException("未知 filter 类型: " + f);
            }
            for (int x = 0; x < w; x++) {
                int r = row[x * 3] & 0xFF, g = row[x * 3 + 1] & 0xFF, bl = row[x * 3 + 2] & 0xFF;
                out[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | bl;
            }
            byte[] tmp = prev; prev = row; row = tmp;
        }
        return out;
    }

    // ---------- 极简 manifest JSON 解析 ----------

    private static List<Entry> parseManifest(String json) throws IOException {
        List<Entry> list = new ArrayList<>();
        Parser p = new Parser(json);
        p.expect('[');
        p.ws();
        if (p.peek() == ']') { p.next(); return list; }
        while (true) {
            p.ws();
            Entry e = new Entry();
            p.expect('{');
            while (true) {
                p.ws();
                String key = p.string();
                p.ws(); p.expect(':'); p.ws();
                switch (key) {
                    case "name": e.name = p.string(); break;
                    case "kind": e.kind = p.string(); break;
                    case "w": e.w = p.number(); break;
                    case "h": e.h = p.number(); break;
                    case "chunk": e.chunk = p.number(); break;
                    case "offset": e.offset = p.number(); break;
                    case "size": e.size = p.number(); break;
                    default: p.skipValue(); break;
                }
                p.ws();
                char c = p.next();
                if (c == '}') break;
                if (c != ',') throw new IOException("manifest JSON 格式错误");
            }
            if (e.name == null || e.kind == null) throw new IOException("manifest 缺字段");
            list.add(e);
            p.ws();
            char c = p.next();
            if (c == ']') break;
            if (c != ',') throw new IOException("manifest JSON 格式错误");
        }
        return list;
    }

    private static class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { return i < s.length() ? s.charAt(i) : 0; }
        char next() throws IOException {
            if (i >= s.length()) throw new IOException("manifest JSON 截断");
            return s.charAt(i++);
        }
        void expect(char c) throws IOException {
            if (next() != c) throw new IOException("manifest JSON 格式错误，期望 " + c);
        }
        String string() throws IOException {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') break;
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(e); break;
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }
        int number() throws IOException {
            int j = i;
            while (j < s.length() && "-+0123456789.eE".indexOf(s.charAt(j)) >= 0) j++;
            try {
                return (int) Double.parseDouble(s.substring(i, j));
            } catch (NumberFormatException ex) {
                throw new IOException("manifest 数字格式错误");
            } finally { i = j; }
        }
        void skipValue() throws IOException {
            ws();
            char c = peek();
            if (c == '"') string();
            else if (c == '{') { int d = 0; do { c = next(); if (c == '{') d++; else if (c == '}') d--; } while (d > 0); }
            else if (c == '[') { int d = 0; do { c = next(); if (c == '[') d++; else if (c == ']') d--; } while (d > 0); }
            else number();
        }
    }

    // ---------- 工具 ----------

    private static int u32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
             | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException("文件截断");
            off += r;
        }
        return b;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) >= 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }
}
