# IMGF 查看器

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)]()
[![Language](https://img.shields.io/badge/language-Java-orange.svg)]()

[English](README.md)

一个安卓 App，把**多张图片无损融合成单个 `.imgf` 文件**，想看时再拆回来。用 CPU 时间换存储空间。


## 为什么做这个？

手机相册里总有一堆高度相似的图：连拍、重复下载、截了两次的屏。`.imgf` 把它们打成一个包做强力无损压缩，东西全留着，空间省下来。想看时 App 直接拆开看，像素级无损。

和隐写工具不同：`.imgf` 不是把图藏进另一张图里，而是一个带 JSON 清单的诚实归档格式，每张图都独立可寻址。

## 功能

- **融合** — 多选图片，选压缩编码（lzma / bz2 / zlib），存成 `.imgf`
- **查看** — 在文件管理器里点 `.imgf` 直接打开，前后翻页浏览
- **导出** — 一键把包里所有图存回相册（JPEG 按原字节还原）
- **日志** — 内置日志查看器 + 崩溃捕获，不用接 `adb`
- **离线** — 全本地运行，无账号

## `.imgf` 格式

```
magic 4B  "IMGF"          （老文件 "MIF1" 仍可读）
codec 1B  0=zlib 1=bz2 2=lzma
flags 1B  bit0 = 行经过 PNG 滤波
n     4B  图片数（大端）
mlen  4B  manifest JSON 长度（大端）
manifest  JSON: [{name, w, h, kind, offset, size}]
blob      压缩后的数据块
```

- `kind: "raw"` — PNG/BMP/GIF 解码成 RGB24，逐行 PNG 滤波（None/Sub/Up/Average/Paeth 取最优），拼起来
- `kind: "file"` — JPEG 存原字节（JPEG 本来就是有损压缩，解码再无损存只会更大）

完整规范：[docs/FORMAT.md](docs/FORMAT.md)。纯 Python 零依赖参考实现：[tools/imgfuse.py](tools/imgfuse.py)。

## 压缩效果

两张 800×600 相似图（共 38.5KB）实测：

| 编码 | 输出 | 比例 |
|------|------|------|
| zlib -9 | 28.7 KB | 74% |
| bz2 -9 | 12.7 KB | 33% |
| lzma | 13.7 KB | 35% |

连拍、截图序列这类相似图片能吃到跨图字典匹配红利。安卓端 lzma 用 preset 6（8MB 字典），因为 preset 9 要 600MB+ 内存，手机堆（通常 256MB）会 OOM——实测踩过这个坑。

## 构建

需要 Android SDK（API 34）与 JDK 17+：

```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Python 工具

```bash
# 融合
python3 tools/imgfuse.py fuse a.png b.jpg ./photos/ -o pack.imgf
# 批量：每个子目录各融成一个 .imgf
python3 tools/imgfuse.py fuse ./photos/ -o out/
# 拆回
python3 tools/imgfuse.py split pack.imgf -o out/
# 查看
python3 tools/imgfuse.py info pack.imgf
```

零依赖，纯标准库（含手写的 PNG/BMP/GIF 解码器与 PNG 编码器）。

## 说明

- Alpha 通道会丢弃（只存 RGB24）。
- `.imgf` 是新后缀，刻意避开已存在的 `.mif` 格式（MapInfo、FrameMaker、MRtrix）。
- App 除了系统文件选择器的存储访问，不申请其他权限。

## 协议

MIT — 见 [LICENSE](LICENSE)。
