# `.imgf` Format Specification

Version 1.0. All multi-byte integers are big-endian.

## Overview

`.imgf` (IMaGe Fuse) is a simple lossless container that bundles N images into
one file. It is designed for trading CPU time for storage space: pixel data is
PNG-filtered per row and compressed as a whole, so similar images benefit from
cross-image dictionary matching.

The extension was chosen to avoid colliding with existing `.mif` formats
(MapInfo Interchange Format, Adobe FrameMaker MIF, MRtrix image format,
Intel Memory Initialization File).

## Layout

```
offset  size  field
0       4     magic: "IMGF" (0x49 0x4D 0x47 0x46)
              Legacy files use "MIF1" — readers MUST accept both.
4       1     codec: 0 = zlib, 1 = bzip2, 2 = lzma (XZ)
5       1     flags: bit 0 = rows are PNG-filtered (always 1 when written
              by current tools); other bits reserved, MUST be 0
6       4     n: number of images (uint32)
10      4     mlen: manifest JSON byte length (uint32)
14      mlen  manifest: UTF-8 JSON array (see below)
14+mlen ...   blob: compressed payload (codec above)
```

## Manifest

A JSON array with exactly `n` objects, in bundle order:

```json
[
  {"name": "a.png", "w": 320, "h": 240, "kind": "raw",
   "offset": 0, "size": 225200},
  {"name": "b.jpg", "w": 0, "h": 0, "kind": "file",
   "offset": 225200, "size": 5773}
]
```

| field  | type   | meaning |
|--------|--------|---------|
| name   | string | original file name (informational) |
| w, h   | int    | image dimensions; `0` when `kind` is `"file"` |
| kind   | string | `"raw"` or `"file"` |
| offset | int    | byte offset into the **decompressed** blob |
| size   | int    | byte length of this entry in the decompressed blob |

`offset`/`size` are arbitrary-precision JSON numbers; readers MUST bounds-check
them against the decompressed blob length.

## Payload entries

### `kind: "raw"`

The entry holds `h` rows. Each row is:

```
1 byte   PNG filter type: 0=None, 1=Sub, 2=Up, 3=Average, 4=Paeth
w*3 bytes  filtered RGB24 bytes (R, G, B per pixel, top-to-bottom, left-to-right)
```

To reconstruct: apply the inverse PNG filter per row (using the previous
reconstructed row, `bpp = 3`), yielding `w*h*3` bytes of RGB24. Alpha channels
are not stored.

Writers SHOULD choose the filter per row that minimizes the sum of absolute
values of the filtered bytes (treating bytes as signed). This is what makes
the data highly compressible.

Typical source: PNG, BMP, GIF decoded to RGB24.

### `kind: "file"`

The entry holds the original file bytes verbatim (e.g. a JPEG). This is used
for formats where decoding + lossless re-encoding would only grow the data —
JPEG being the prime example, since it is already lossy-compressed.

## Compression notes

- The **entire** concatenated entry data (all images) is compressed as one
  stream, so the compressor dictionary spans images.
- zlib: level 9. bzip2: level 9. lzma: XZ preset 9 on desktop (64 MB
  dictionary); **preset 6 on Android** (8 MB dictionary) because preset 9
  needs 600 MB+ of encoder memory and OOMs on typical 256 MB heaps.

## Reference implementation

`tools/imgfuse.py` — pure Python, zero dependencies (includes hand-written
PNG/BMP/GIF decoders and a PNG encoder). The Android app (`app/`) contains an
independent Java implementation (`MifParser`, `MifWriter`); the two have been
tested for byte-identical filter output and lossless round-trips.
