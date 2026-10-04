#!/usr/bin/env python3
"""
imgfuse.py —— 图片无损融合 / 分割

把 N 张图片融合成单个 .imgf 文件，需要看时再无损拆回。
设计目标：用 CPU 时间换存储空间（性能换空间）。

原理
----
* PNG / BMP / GIF：解码成原始 RGB24，按行做 PNG 滤波（每行从
  None/Sub/Up/Average/Paeth 里选最优），全部拼进一个数据块，
  再用 lzma / bz2 / zlib 整体压缩。跨图的字典匹配对连拍、
  截图序列这类相似图片特别有效。
* JPEG：本身是有损压缩，解码再无损存只会更大，所以直接存原文件
  字节（不碰它，保证 1 字节不差）。

文件格式 .imgf
-------------
  magic 4B  "IMGF"（旧文件 "MIF1" 仍可读取）
  codec 1B  0=zlib 1=bz2 2=lzma
  flags 1B  bit0=1 表示 raw 数据经过行滤波
  n     4B  图片数量
  mlen  4B  manifest JSON 长度
  manifest  JSON: [{name,w,h,kind,offset,size}...]
            kind "raw" = 滤波后 RGB24, kind "file" = 原文件字节
            offset/size 指解压后数据块中的位置
  blob  剩余全部 = 压缩后的数据块

零依赖单文件（只用标准库，自带纯 Python PNG/JPEG/BMP/GIF 解码器与 PNG 编码器）。

用法
----
  融合:  python3 imgfuse.py fuse a.png b.jpg c.bmp -o pack.imgf
         python3 imgfuse.py fuse *.png -o pack.imgf --codec lzma -p 9 -e
  批量:  python3 imgfuse.py fuse ./photos/ -o out/
         # photos/ 的每个子目录各融成一个 .imgf，photos/ 自身的图片另融成 photos.imgf
  拆分:  python3 imgfuse.py split pack.imgf -o out/
  查看:  python3 imgfuse.py info pack.imgf
"""

import argparse
import bz2
import io
import json
import lzma
import os
import struct
import sys
import zlib


MAGIC = b"IMGF"
MAGIC_OLD = b"MIF1"  # 兼容 2026-10-04 前生成的旧文件
CODECS = {"zlib": 0, "bz2": 1, "lzma": 2}
CODEC_NAMES = {v: k for k, v in CODECS.items()}


class _ImgError(Exception):
    pass


class _Unsupported(_ImgError):
    pass


def _png_unfilter(raw, h, bpr, xbytes):
    rows = []
    prev = bytearray(bpr)
    p = 0
    for _ in range(h):
        f = raw[p]
        p += 1
        cur = bytearray(raw[p:p + bpr])
        p += bpr
        if f == 1:
            for i in range(xbytes, bpr):
                cur[i] = (cur[i] + cur[i - xbytes]) & 0xFF
        elif f == 2:
            for i in range(bpr):
                cur[i] = (cur[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(bpr):
                a = cur[i - xbytes] if i >= xbytes else 0
                cur[i] = (cur[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(bpr):
                a = cur[i - xbytes] if i >= xbytes else 0
                b = prev[i]
                c = prev[i - xbytes] if i >= xbytes else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                cur[i] = (cur[i] + pr) & 0xFF
        elif f != 0:
            raise _ImgError("bad png filter")
        rows.append(cur)
        prev = cur
    return rows


def _decode_png(data, rgb=False):
    pos = 8
    n = len(data)
    w = h = bitd = ct = inter = None
    plte = None
    idat = bytearray()
    while pos + 8 <= n:
        ln = int.from_bytes(data[pos:pos + 4], "big")
        typ = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b"IHDR":
            w = int.from_bytes(chunk[0:4], "big")
            h = int.from_bytes(chunk[4:8], "big")
            bitd = chunk[8]
            ct = chunk[9]
            inter = chunk[12]
        elif typ == b"PLTE":
            plte = chunk
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
    if inter:
        raise _Unsupported("interlaced PNG")
    if ct == 0:
        ch = 1
    elif ct == 2:
        ch = 3
    elif ct == 3:
        ch = 1
    elif ct == 4:
        ch = 2
    elif ct == 6:
        ch = 4
    else:
        raise _Unsupported("png color type")
    raw = zlib.decompress(bytes(idat))
    if bitd >= 8:
        sb = bitd // 8
        xbytes = ch * sb
        bpr = w * xbytes
        rows = _png_unfilter(raw, h, bpr, xbytes)
        px = bytearray()
        for row in rows:
            px += row[0::sb] if sb == 2 else row
    else:
        bpr = (w * ch * bitd + 7) // 8
        rows = _png_unfilter(raw, h, bpr, 1)
        px = bytearray()
        scale = 255 // ((1 << bitd) - 1)
        for row in rows:
            bits = "".join(f"{b:08b}" for b in row)
            for i in range(w * ch):
                px.append(int(bits[i * bitd:(i + 1) * bitd], 2) * scale)
    gray = []
    if rgb:
        out = bytearray()
        if ct in (0, 4):
            for i in range(0, len(px), ch):
                g = px[i]
                out += bytes((g, g, g))
        elif ct == 3:
            if plte is None:
                raise _ImgError("palette png without PLTE")
            for i in range(len(px)):
                j = px[i] * 3
                out += bytes((plte[j], plte[j + 1], plte[j + 2]))
        else:
            for i in range(0, len(px), ch):
                out += bytes((px[i], px[i + 1], px[i + 2]))
        return w, h, bytes(out), w, h, 1
    if ct in (0, 4):
        gray = list(px[0::ch])
    elif ct == 3:
        if plte is None:
            raise _ImgError("palette png without PLTE")
        for i in range(0, len(px), ch):
            j = px[i] * 3
            r, g, b = plte[j], plte[j + 1], plte[j + 2]
            gray.append((77 * r + 150 * g + 29 * b) >> 8)
    else:
        for i in range(0, len(px), ch):
            r, g, b = px[i], px[i + 1], px[i + 2]
            gray.append((77 * r + 150 * g + 29 * b) >> 8)
    return w, h, gray, w, h, 1


# ---------- BMP ----------


def _decode_bmp(data, rgb=False):
    off = int.from_bytes(data[10:14], "little")
    w = int.from_bytes(data[18:22], "little", signed=True)
    h = int.from_bytes(data[22:26], "little", signed=True)
    bpp = int.from_bytes(data[28:30], "little")
    comp = int.from_bytes(data[30:34], "little")
    if comp != 0 or bpp not in (24, 32):
        raise _Unsupported("only uncompressed 24/32-bit bmp")
    flip = h > 0
    h = abs(h)
    bypp = bpp // 8
    stride = ((w * bypp + 3) // 4) * 4
    if rgb:
        out = bytearray()
        for row in range(h):
            y = (h - 1 - row) if flip else row
            base = off + y * stride
            for x in range(w):
                o = base + x * bypp
                out += bytes((data[o + 2], data[o + 1], data[o]))
        return w, h, bytes(out), w, h, 1
    gray = [0] * (w * h)
    for row in range(h):
        y = (h - 1 - row) if flip else row
        base = off + y * stride
        for x in range(w):
            o = base + x * bypp
            b, g, r = data[o], data[o + 1], data[o + 2]
            gray[row * w + x] = (77 * r + 150 * g + 29 * b) >> 8
    return w, h, gray, w, h, 1


# ---------- GIF（第一帧） ----------


def _lzw_decode(comp, min_code, expect):
    d = comp
    pos = 0
    acc = 0
    nbits = 0

    def read(k):
        nonlocal pos, acc, nbits
        while nbits < k:
            if pos >= len(d):
                raise _ImgError("lzw truncated")
            acc |= d[pos] << nbits
            pos += 1
            nbits += 8
        v = acc & ((1 << k) - 1)
        acc >>= k
        nbits -= k
        return v

    clear = 1 << min_code
    eoi = clear + 1
    out = bytearray()
    while True:
        code_size = min_code + 1
        table = {i: bytes([i]) for i in range(clear)}
        nxt = eoi + 1
        prev = None
        while True:
            code = read(code_size)
            if code == clear:
                break
            if code == eoi:
                return list(out[:expect])
            if code in table:
                entry = table[code]
            elif code == nxt and prev is not None:
                entry = prev + prev[:1]
            else:
                raise _ImgError("bad lzw code")
            out += entry
            if prev is not None:
                table[nxt] = prev + entry[:1]
                nxt += 1
                if nxt == (1 << code_size) and code_size < 12:
                    code_size += 1
            prev = entry
            if len(out) >= expect:
                return list(out[:expect])


def _decode_gif(data, rgb=False):
    w = int.from_bytes(data[6:8], "little")
    h = int.from_bytes(data[8:10], "little")
    packed = data[10]
    pos = 13
    gct = None
    if packed & 0x80:
        k = 2 ** ((packed & 0x07) + 1)
        gct = data[pos:pos + 3 * k]
        pos += 3 * k
    n = len(data)
    while pos < n:
        b = data[pos]
        pos += 1
        if b == 0x21:
            pos += 1
            while True:
                sz = data[pos]
                pos += 1
                if sz == 0:
                    break
                pos += sz
        elif b == 0x2C:
            iw = int.from_bytes(data[pos + 4:pos + 6], "little")
            ih = int.from_bytes(data[pos + 6:pos + 8], "little")
            ip = data[pos + 8]
            pos += 9
            pal = gct
            if ip & 0x80:
                k = 2 ** ((ip & 0x07) + 1)
                pal = data[pos:pos + 3 * k]
                pos += 3 * k
            if pal is None:
                raise _ImgError("gif without palette")
            interlaced = bool(ip & 0x40)
            min_code = data[pos]
            pos += 1
            comp = bytearray()
            while True:
                sz = data[pos]
                pos += 1
                if sz == 0:
                    break
                comp += data[pos:pos + sz]
                pos += sz
            idx = _lzw_decode(bytes(comp), min_code, iw * ih)
            if interlaced:
                px = [0] * (iw * ih)
                p = 0
                for start, step in ((0, 8), (4, 8), (2, 4), (1, 2)):
                    for yy in range(start, ih, step):
                        for x in range(iw):
                            px[yy * iw + x] = idx[p]
                            p += 1
            else:
                px = idx
            gray = []
            if rgb:
                out = bytearray()
                for i in px:
                    out += bytes((pal[3 * i], pal[3 * i + 1], pal[3 * i + 2]))
                return w, h, bytes(out), w, h, 1
            for i in px:
                r, g, b = pal[3 * i], pal[3 * i + 1], pal[3 * i + 2]
                gray.append((77 * r + 150 * g + 29 * b) >> 8)
            return w, h, gray, w, h, 1
        elif b == 0x3B:
            break
    raise _ImgError("no image in gif")


# ---------- 统一入口 ----------


def load_rgb(path):
    """返回 (w, h, RGB24 bytes)。JPEG 不支持（调用方应存原文件字节）。"""
    with open(path, "rb") as f:
        data = f.read()
    if data[:2] == b"\xff\xd8":
        raise _Unsupported("jpeg has no lossless rgb path; store file bytes")
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        w, h, px, _, _, _ = _decode_png(data, rgb=True)
        return w, h, px
    if data[:2] == b"BM":
        w, h, px, _, _, _ = _decode_bmp(data, rgb=True)
        return w, h, px
    if data[:6] in (b"GIF89a", b"GIF87a"):
        w, h, px, _, _, _ = _decode_gif(data, rgb=True)
        return w, h, px
    raise _Unsupported("unknown image format")


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f}{unit}"
        n /= 1024
    return f"{n:.1f}TB"


# ---------- PNG 编码器（纯 Python，用于 split 输出） ----------

def _chunk(typ, data):
    c = typ + data
    return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c))


def _paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def _filter_row(ftype, row, prev, bpp):
    n = len(row)
    out = bytearray(n)
    if ftype == 0:
        out[:] = row
    elif ftype == 1:  # Sub
        for i in range(n):
            a = row[i - bpp] if i >= bpp else 0
            out[i] = (row[i] - a) & 0xFF
    elif ftype == 2:  # Up
        for i in range(n):
            out[i] = (row[i] - prev[i]) & 0xFF
    elif ftype == 3:  # Average
        for i in range(n):
            a = row[i - bpp] if i >= bpp else 0
            out[i] = (row[i] - ((a + prev[i]) >> 1)) & 0xFF
    else:  # Paeth
        for i in range(n):
            a = row[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            out[i] = (row[i] - _paeth(a, b, c)) & 0xFF
    return out


def _filter_best(row, prev, bpp):
    """每行试 5 种滤波，选残差绝对值和最小的。"""
    best = None
    best_score = None
    for f in range(5):
        fr = _filter_row(f, row, prev, bpp)
        s = sum(v if v < 128 else 256 - v for v in fr)
        if best_score is None or s < best_score:
            best_score = s
            best = (f, fr)
    return best


def encode_png(w, h, rgb, filt="best"):
    """rgb: w*h*3 bytes。返回完整 PNG 文件字节。"""
    bpp = 3
    raw = bytearray()
    prev = bytearray(w * bpp)
    for y in range(h):
        row = rgb[y * w * bpp:(y + 1) * w * bpp]
        if filt == "best":
            f, fr = _filter_best(row, prev, bpp)
        else:
            f, fr = 4, _filter_row(4, row, prev, bpp)
        raw.append(f)
        raw += fr
        prev = bytearray(row)
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", ihdr)
            + _chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + _chunk(b"IEND", b""))


def _unfilter_rows(blob, w, h):
    """blob: h 行，每行 1 字节 filter + w*3 字节。返回 RGB24。"""
    bpp = 3
    bpr = w * bpp
    out = bytearray()
    prev = bytearray(bpr)
    pos = 0
    for _ in range(h):
        f = blob[pos]
        pos += 1
        row = bytearray(blob[pos:pos + bpr])
        pos += bpr
        if f == 1:
            for i in range(bpp, bpr):
                row[i] = (row[i] + row[i - bpp]) & 0xFF
        elif f == 2:
            for i in range(bpr):
                row[i] = (row[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(bpr):
                a = row[i - bpp] if i >= bpp else 0
                row[i] = (row[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(bpr):
                a = row[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                row[i] = (row[i] + _paeth(a, b, c)) & 0xFF
        elif f != 0:
            raise _ImgError(f"bad filter type {f}")
        out += row
        prev = row
    return bytes(out)


# ---------- 压缩 ----------

def _compress(data, codec, level, extreme):
    if codec == 0:
        return zlib.compress(data, level)
    if codec == 1:
        return bz2.compress(data, level)
    kw = {"preset": level}
    if extreme:
        kw["extreme"] = True
    return lzma.compress(data, **kw)


def _decompress(data, codec):
    if codec == 0:
        return zlib.decompress(data)
    if codec == 1:
        return bz2.decompress(data)
    return lzma.decompress(data)


# ---------- fuse ----------

def _is_jpeg(path):
    with open(path, "rb") as f:
        return f.read(2) == b"\xff\xd8"


def _collect_images(inputs):
    """输入路径展开成文件列表（目录只取直接子文件，不递归）。"""
    files = []
    for p in inputs:
        if os.path.isdir(p):
            for fn in sorted(os.listdir(p)):
                fp = os.path.join(p, fn)
                if os.path.isfile(fp):
                    files.append(fp)
        else:
            files.append(p)
    return files


def _check_args(a):
    level = a.level
    if a.codec == "lzma" and not (0 <= level <= 9):
        print("lzma preset 取值 0-9", file=sys.stderr)
        sys.exit(1)
    if a.codec == "zlib" and not (1 <= level <= 9):
        print("zlib level 取值 1-9", file=sys.stderr)
        sys.exit(1)
    if a.codec == "bz2" and not (1 <= level <= 9):
        print("bz2 level 取值 1-9", file=sys.stderr)
        sys.exit(1)


def _fuse_files(files, output, a, fatal=True):
    """把 files 融合成单个 output .imgf。返回 True/False。"""
    codec = CODECS[a.codec]
    level = a.level
    blob = bytearray()
    manifest = []
    total_in = 0
    filt = a.filter

    for idx, p in enumerate(files):
        if not os.path.isfile(p):
            print(f"[{idx + 1}/{len(files)}] {p}: 不存在，跳过")
            continue
        name = os.path.basename(p)
        total_in += os.path.getsize(p)
        if _is_jpeg(p):
            with open(p, "rb") as f:
                fb = f.read()
            manifest.append({"name": name, "w": 0, "h": 0, "kind": "file",
                             "offset": len(blob), "size": len(fb)})
            blob += fb
            print(f"[{idx + 1}/{len(files)}] {name}: JPEG 存原文 {human(len(fb))}")
            continue
        try:
            w, h, rgb = load_rgb(p)
        except (_Unsupported, _ImgError) as e:
            print(f"[{idx + 1}/{len(files)}] {name}: 跳过（{e}）")
            continue
        # 行滤波
        bpp = 3
        fb = bytearray()
        prev = bytearray(w * bpp)
        for y in range(h):
            row = rgb[y * w * bpp:(y + 1) * w * bpp]
            if filt == "best":
                f, fr = _filter_best(row, prev, bpp)
            else:
                f, fr = 4, _filter_row(4, row, prev, bpp)
            fb.append(f)
            fb += fr
            prev = bytearray(row)
        manifest.append({"name": name, "w": w, "h": h, "kind": "raw",
                         "offset": len(blob), "size": len(fb)})
        blob += fb
        print(f"[{idx + 1}/{len(files)}] {name}: {w}x{h} RGB "
              f"{human(w * h * 3)} -> 滤波后 {human(len(fb))}")

    if not manifest:
        msg = "没有可用图片"
        if fatal:
            print(msg, file=sys.stderr)
            sys.exit(1)
        print(f"{output}: {msg}，已跳过")
        return False

    print(f"压缩中（{a.codec} level={level}"
          f"{' extreme' if a.extreme and a.codec == 'lzma' else ''}）...")
    cblob = _compress(bytes(blob), codec, level, a.extreme)
    flags = 1  # 行滤波已应用

    mjson = json.dumps(manifest, ensure_ascii=False).encode("utf-8")
    hdr = (MAGIC + bytes([codec, flags]) + struct.pack(">II", len(manifest), len(mjson)))
    with open(output, "wb") as f:
        f.write(hdr + mjson + cblob)

    out_size = 14 + len(mjson) + len(cblob)
    print(f"完成：{len(manifest)} 张图，原文共 {human(total_in)}，"
          f"融合后 {human(out_size)}（{out_size / total_in * 100:.1f}%）")
    print(f"输出：{output}")
    return True


def cmd_fuse(a):
    _check_args(a)
    out = a.output
    if out.endswith(os.sep) or os.path.isdir(out):
        # ---- 批量模式：输入文件夹的每个子目录各融成一个 .imgf ----
        os.makedirs(out, exist_ok=True)
        groups = []  # (组名, [文件])
        for p in a.inputs:
            if os.path.isdir(p):
                ap = os.path.abspath(p)
                subs = sorted(d for d in os.listdir(ap)
                              if os.path.isdir(os.path.join(ap, d)))
                own = [os.path.join(ap, fn) for fn in sorted(os.listdir(ap))
                       if os.path.isfile(os.path.join(ap, fn))]
                for s in subs:
                    sp = os.path.join(ap, s)
                    files = [os.path.join(sp, fn) for fn in sorted(os.listdir(sp))
                             if os.path.isfile(os.path.join(sp, fn))]
                    if files:
                        groups.append((s, files))
                    else:
                        print(f"跳过空目录：{sp}")
                if own:
                    groups.append((os.path.basename(ap), own))
            elif os.path.isfile(p):
                groups.append((os.path.splitext(os.path.basename(p))[0], [p]))
            else:
                print(f"跳过不存在的路径：{p}")
        if not groups:
            print("没有可融合的图片", file=sys.stderr)
            sys.exit(1)
        used = set()
        done = 0
        for name, files in groups:
            base, i = name, 2
            while base.lower() in used:
                base = f"{name}_{i}"
                i += 1
            used.add(base.lower())
            dest = os.path.join(out, base + ".imgf")
            print(f"\n=== {name}（{len(files)} 个文件）-> {dest} ===")
            if _fuse_files(files, dest, a, fatal=False):
                done += 1
        print(f"\n批量完成：{done}/{len(groups)} 个 .imgf -> {out}/")
    else:
        files = _collect_images(a.inputs)
        if not files:
            print("没有输入文件", file=sys.stderr)
            sys.exit(1)
        _fuse_files(files, out, a)


# ---------- split ----------

def _read_bundle(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] not in (MAGIC, MAGIC_OLD):
        raise _ImgError("not a .imgf file")
    codec = data[4]
    flags = data[5]
    n, mlen = struct.unpack(">II", data[6:14])
    manifest = json.loads(data[14:14 + mlen].decode("utf-8"))
    cblob = data[14 + mlen:]
    blob = _decompress(cblob, codec)
    return manifest, blob, flags


def cmd_split(a):
    manifest, blob, flags = _read_bundle(a.bundle)
    os.makedirs(a.output, exist_ok=True)
    for m in manifest:
        seg = blob[m["offset"]:m["offset"] + m["size"]]
        if m["kind"] == "file":
            # JPEG 原文：扩展名保持原样
            out = os.path.join(a.output, m["name"])
            with open(out, "wb") as f:
                f.write(seg)
        else:
            rgb = _unfilter_rows(seg, m["w"], m["h"])
            base = os.path.splitext(m["name"])[0] + ".png"
            out = os.path.join(a.output, base)
            with open(out, "wb") as f:
                f.write(encode_png(m["w"], m["h"], rgb))
        print(f"  {m['name']} -> {out} ({human(len(seg))})")
    print(f"拆分完成：{len(manifest)} 张 -> {a.output}/")


def cmd_info(a):
    with open(a.bundle, "rb") as f:
        data = f.read()
    if data[:4] not in (MAGIC, MAGIC_OLD):
        print("不是 .imgf 文件", file=sys.stderr)
        sys.exit(1)
    codec = CODEC_NAMES[data[4]]
    n, mlen = struct.unpack(">II", data[6:14])
    manifest = json.loads(data[14:14 + mlen].decode("utf-8"))
    cblob = data[14 + mlen:]
    blob = _decompress(data[4], cblob) if False else None
    # 解压只为统计解压后大小
    try:
        raw_size = len(_decompress(cblob, data[4]))
    except Exception:
        raw_size = -1
    print(f"文件：{a.bundle}（{human(len(data))}）")
    print(f"编码：{codec}，图片数：{n}")
    if raw_size >= 0:
        print(f"压缩前数据块：{human(raw_size)}，压缩后：{human(len(cblob))}，"
              f"比 {len(cblob) / raw_size * 100:.1f}%")
    print(f"{'文件名':<28} {'尺寸':<12} {'类型':<6} {'数据'}")
    for m in manifest:
        dim = f"{m['w']}x{m['h']}" if m["kind"] == "raw" else "-"
        print(f"{m['name']:<28} {dim:<12} {m['kind']:<6} {human(m['size'])}")


def main():
    ap = argparse.ArgumentParser(description="图片无损融合/分割：用 CPU 时间换空间")
    sub = ap.add_subparsers(dest="cmd", required=True)

    f = sub.add_parser("fuse", help="融合多张图为单个 .imgf")
    f.add_argument("inputs", nargs="+", help="图片文件（可混目录）")
    f.add_argument("-o", "--output", required=True,
                   help="输出 .imgf 文件；若为目录（以 / 结尾或已存在）则进入批量模式")
    f.add_argument("--codec", choices=["zlib", "bz2", "lzma"], default="lzma",
                   help="压缩算法（默认 lzma，压得最小最慢）")
    f.add_argument("--level", "-p", type=int, default=9,
                   help="压缩等级（默认 9，越大越慢越小）")
    f.add_argument("-e", "--extreme", action="store_true",
                   help="lzma extreme 模式（更慢，压得更小）")
    f.add_argument("--filter", choices=["best", "paeth"], default="best",
                   help="行滤波策略：best 逐行试 5 种（慢但小），paeth 只用 Paeth（快）")

    s = sub.add_parser("split", help="把 .imgf 拆回图片")
    s.add_argument("bundle", help=".imgf 文件")
    s.add_argument("-o", "--output", default="fused_out", help="输出目录")

    i = sub.add_parser("info", help="查看 .imgf 内容")
    i.add_argument("bundle", help=".imgf 文件")

    a = ap.parse_args()
    if a.cmd == "fuse":
        cmd_fuse(a)
    elif a.cmd == "split":
        cmd_split(a)
    elif a.cmd == "info":
        cmd_info(a)


if __name__ == "__main__":
    main()
