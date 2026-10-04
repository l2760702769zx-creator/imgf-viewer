# IMGF Viewer

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)]()
[![Language](https://img.shields.io/badge/language-Java-orange.svg)]()

[中文版](README_zh-CN.md)

An Android app that **losslessly fuses multiple images into a single `.imgf` file** — and splits them back whenever you want to view them. Trade CPU time for storage space.


## Why?

Phone albums accumulate near-duplicates: burst shots, re-downloaded images, screenshots saved twice. `.imgf` packs them into one file with strong lossless compression, so you keep everything while using less space. When you want to look, the app splits them back — pixel-perfect.

Unlike steganography tools, nothing is hidden inside another image: `.imgf` is an honest archive format with a JSON manifest, so every bundled image stays individually addressable.

## Features

- **Fuse** — pick multiple images, choose a codec (lzma / bz2 / zlib), save as `.imgf`
- **View** — tap any `.imgf` in your file manager to open it directly; swipe through images, pinch to zoom
- **Export** — save all bundled images back to your gallery (JPEGs restored byte-for-byte)
- **In-app log** — built-in log viewer with crash capture (no `adb` needed)
- **Zero-account, offline** — everything runs on-device

## The `.imgf` format

```
magic 4B  "IMGF"          (legacy files use "MIF1", still readable)
codec 1B  0=zlib 1=bz2 2=lzma
flags 1B  bit0 = rows are PNG-filtered
n     4B  image count (big-endian)
mlen  4B  manifest JSON length (big-endian)
manifest  JSON: [{name, w, h, kind, offset, size}]
blob      compressed payload
```

- `kind: "raw"` — PNG/BMP/GIF decoded to RGB24, PNG-filtered per row (best of None/Sub/Up/Average/Paeth), concatenated
- `kind: "file"` — JPEG/WebP/HEIC/AVIF stored as original bytes (re-encoding a lossy format only makes it bigger; detected by magic bytes, not extension)

Since v1.1, data is split into **chunks** (~8 MiB each, compressed independently), so viewing any image only decompresses its chunk instead of the whole bundle.

Full spec: [docs/FORMAT.md](docs/FORMAT.md). Reference implementation in pure-Python (zero dependencies): [tools/imgfuse.py](tools/imgfuse.py).

## Compression

Measured on two similar 800×600 images (38.5 KB total):

| codec | output | ratio |
|-------|--------|-------|
| zlib -9 | 28.7 KB | 74% |
| bz2 -9 | 12.7 KB | 33% |
| lzma | 13.7 KB | 35% |

Similar images (bursts, screenshot sequences) benefit from cross-image dictionary matching. On Android, lzma uses preset 6 (8 MB dictionary) to stay within the ~256 MB heap limit — preset 9 needs 600 MB+ and will OOM on phones.

## Build

Requires Android SDK (API 34) and JDK 17+.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or just use Gradle directly:

```bash
gradle assembleDebug
```

## Python tool

```bash
# fuse
python3 tools/imgfuse.py fuse a.png b.jpg ./photos/ -o pack.imgf
# batch: one .imgf per subdirectory
python3 tools/imgfuse.py fuse ./photos/ -o out/
# split back
python3 tools/imgfuse.py split pack.imgf -o out/
# inspect
python3 tools/imgfuse.py info pack.imgf
```

Zero dependencies — pure standard library, including self-written PNG/BMP/GIF decoders and a PNG encoder.

## Notes

- Alpha channels are dropped (RGB24 only).
- `.imgf` is a new extension, chosen to avoid colliding with existing `.mif` formats (MapInfo, FrameMaker, MRtrix).
- The app requests no permissions beyond storage access via the system file picker.

## License

MIT — see [LICENSE](LICENSE).
