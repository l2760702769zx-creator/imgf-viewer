package com.mtr.mifviewer;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;

/** .imgf 查看器：打开、浏览、导出。 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1001;
    private static final int REQ_PICK_IMAGES = 1002;
    private static final int REQ_CREATE = 1003;

    private ZoomImageView imageView;
    private TextView titleView;
    private TextView infoView;
    private Button prevBtn, nextBtn, exportBtn;

    private MifParser.Bundle bundle;
    private String bundleName = "";
    private int index = 0;
    private Bitmap current;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.installCrashHandler();
        Logger.init(new java.io.File(getFilesDir(), "imgfviewer.log"));
        buildUi();

        Intent it = getIntent();
        if (Intent.ACTION_VIEW.equals(it.getAction()) && it.getData() != null) {
            Logger.d("APP", "VIEW intent: " + it.getData());
            openUri(it.getData());
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        titleView = new TextView(this);
        titleView.setText("IMGF 查看器");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(16);
        titleView.setPadding(24, 24, 24, 8);
        root.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        imageView = new ZoomImageView(this);
        imageView.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(imageView, imgLp);

        infoView = new TextView(this);
        infoView.setTextColor(Color.LTGRAY);
        infoView.setTextSize(14);
        infoView.setGravity(Gravity.CENTER);
        infoView.setPadding(16, 8, 16, 8);
        root.addView(infoView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(16, 8, 16, 24);

        Button pickBtn = new Button(this);
        pickBtn.setText("选择文件");
        pickBtn.setOnClickListener(v -> pickFile());

        prevBtn = new Button(this);
        prevBtn.setText("上一张");
        prevBtn.setOnClickListener(v -> move(-1));

        nextBtn = new Button(this);
        nextBtn.setText("下一张");
        nextBtn.setOnClickListener(v -> move(1));

        exportBtn = new Button(this);
        exportBtn.setText("导出全部");
        exportBtn.setOnClickListener(v -> exportAll());

        Button fuseBtn = new Button(this);
        fuseBtn.setText("＋ 融合");
        fuseBtn.setOnClickListener(v -> pickImagesForFuse());

        Button logBtn = new Button(this);
        logBtn.setText("日志");
        logBtn.setOnClickListener(v -> showLogs());

        for (Button b : new Button[]{pickBtn, prevBtn, nextBtn}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(6, 0, 6, 0);
            row.addView(b, lp);
        }
        root.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        row2.setPadding(16, 0, 16, 24);
        for (Button b : new Button[]{exportBtn, fuseBtn, logBtn}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(6, 0, 6, 0);
            row2.addView(b, lp);
        }
        root.addView(row2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        updateNav();
    }

    private void pickFile() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        startActivityForResult(it, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data.getData() != null) {
            Uri uri = data.getData();
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
            openUri(uri);
        } else if (requestCode == REQ_PICK_IMAGES && resultCode == RESULT_OK && data != null) {
            java.util.ArrayList<Uri> uris = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n; i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) showCodecDialog(uris);
            else toast("未选择图片");
        } else if (requestCode == REQ_CREATE && resultCode == RESULT_OK
                && data.getData() != null && pendingUris != null) {
            doFuse(pendingUris, pendingCodec, data.getData());
            pendingUris = null;
        }
    }

    // ---------- 融合 ----------

    private java.util.ArrayList<Uri> pendingUris;
    private int pendingCodec = MifWriter.CODEC_LZMA;

    private void pickImagesForFuse() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("image/*");
        it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(it, REQ_PICK_IMAGES);
    }

    private void showCodecDialog(java.util.ArrayList<Uri> uris) {
        final String[] names = {"lzma（最小，推荐）", "bz2", "zlib（最快）"};
        final int[] codecs = {MifWriter.CODEC_LZMA, MifWriter.CODEC_BZ2, MifWriter.CODEC_ZLIB};
        final int[] sel = {0};
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择 " + uris.size() + " 张图的压缩编码")
                .setSingleChoiceItems(names, 0, (d, which) -> sel[0] = which)
                .setPositiveButton("开始融合", (d, which) -> {
                    pendingUris = uris;
                    pendingCodec = codecs[sel[0]];
                    Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    it.setType("application/octet-stream");
                    it.putExtra(Intent.EXTRA_TITLE, "pack.imgf");
                    startActivityForResult(it, REQ_CREATE);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doFuse(java.util.ArrayList<Uri> uris, int codec, Uri saveUri) {
        infoView.setText("正在读取图片…");
        Logger.d("FUSE", "开始融合: " + uris.size() + " 个输入, 编码="
                + MifWriter.CODEC_NAMES[codec] + ", 输出=" + saveUri);
        new Thread(() -> {
            try {
                java.util.ArrayList<MifWriter.ImageInput> inputs = new java.util.ArrayList<>();
                for (Uri u : uris) {
                    byte[] bytes;
                    try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                        if (in == null) continue;
                        bytes = readAll(in);
                    } catch (Exception e) {
                        Logger.d("FUSE", "读取失败跳过: " + u + " (" + e.getMessage() + ")");
                        continue;
                    }
                    if (bytes.length < 2) continue;
                    MifWriter.ImageInput ii = new MifWriter.ImageInput();
                    ii.name = queryName(u);
                    ii.originalSize = bytes.length;
                    if (MifWriter.isPassthrough(bytes)) {
                        ii.fileBytes = bytes; // JPEG/WebP/HEIC/AVIF 存原文
                        Logger.d("FUSE", ii.name + ": 有损格式存原文 " + human(bytes.length));
                    } else {
                        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (bmp == null) {
                            Logger.d("FUSE", ii.name + ": 解码失败，跳过");
                            continue;
                        }
                        Logger.d("FUSE", ii.name + ": 解码 " + bmp.getWidth()
                                + "x" + bmp.getHeight());
                        ii.bitmap = bmp;
                    }
                    inputs.add(ii);
                }
                if (inputs.isEmpty()) throw new java.io.IOException("没有可融合的图片");
                MifWriter.Progress prog = (idx, total, name) -> {
                    Logger.d("FUSE", "融合 " + (idx + 1) + "/" + total + " " + name);
                    runOnUiThread(() -> infoView.setText(
                            "正在融合 " + (idx + 1) + "/" + total + "  " + name));
                };
                Logger.d("FUSE", "开始压缩…");
                try (java.io.OutputStream out = getContentResolver().openOutputStream(saveUri)) {
                    if (out == null) throw new java.io.IOException("无法写入目标文件");
                    MifWriter.Result r = MifWriter.fuse(inputs, codec, out, prog);
                    Logger.d("FUSE", "完成: " + r.fused + " 张, "
                            + human(r.inBytes) + " -> " + human(r.outBytes));
                    final String msg = "融合完成：" + r.fused + " 张，"
                            + human(r.inBytes) + " → " + human(r.outBytes)
                            + "（" + String.format("%.1f", r.outBytes * 100.0 / r.inBytes) + "%）"
                            + (r.skipped > 0 ? "，跳过 " + r.skipped + " 张" : "");
                    runOnUiThread(() -> {
                        infoView.setText(msg);
                        toast(msg);
                    });
                }
            } catch (Exception e) {
                Logger.e("FUSE", "融合失败", e);
                runOnUiThread(() -> {
                    infoView.setText("融合失败: " + e.getMessage());
                    toast("融合失败: " + e.getMessage());
                });
            }
        }).start();
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) >= 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    private static String human(long n) {
        if (n < 1024) return n + "B";
        String[] units = {"KB", "MB", "GB"};
        double v = n;
        String u = "B";
        for (String x : units) { v /= 1024; u = x; if (v < 1024) break; }
        return String.format("%.1f%s", v, u);
    }

    private void openUri(Uri uri) {
        Logger.d("OPEN", "打开: " + uri);
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("无法打开文件");
                MifParser.Bundle b = MifParser.parse(in);
                String name = queryName(uri);
                Logger.d("OPEN", "解析成功: " + b.entries.size() + " 张");
                runOnUiThread(() -> {
                    bundle = b;
                    bundleName = name;
                    index = 0;
                    titleView.setText(name);
                    showCurrent();
                    toast("载入 " + b.entries.size() + " 张图片");
                });
            } catch (Exception e) {
                Logger.e("OPEN", "打开失败", e);
                runOnUiThread(() -> toast("打开失败: " + e.getMessage()));
            }
        }).start();
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        String p = uri.getPath();
        return p != null ? p.substring(p.lastIndexOf('/') + 1) : "unknown.imgf";
    }

    private void move(int d) {
        if (bundle == null) return;
        int n = bundle.entries.size();
        index = (index + d + n) % n;
        showCurrent();
    }

    private void showCurrent() {
        if (bundle == null) return;
        new Thread(() -> {
            try {
                MifParser.Decoded dec = bundle.decode(index);
                Bitmap bmp;
                if (dec.fileBytes != null) {
                    bmp = BitmapFactory.decodeByteArray(dec.fileBytes, 0, dec.fileBytes.length);
                    if (bmp == null) throw new java.io.IOException("图片解码失败");
                } else {
                    bmp = Bitmap.createBitmap(dec.w, dec.h, Bitmap.Config.ARGB_8888);
                    bmp.setPixels(dec.argb, 0, dec.w, 0, 0, dec.w, dec.h);
                }
                Bitmap old = current;
                current = bmp;
                int idx = index;
                runOnUiThread(() -> {
                    if (old != null) old.recycle();
                    imageView.setImageBitmap(bmp);
                    MifParser.Entry e = bundle.entries.get(idx);
                    infoView.setText((idx + 1) + "/" + bundle.entries.size()
                            + "  " + e.name
                            + (e.w > 0 ? "  " + e.w + "x" + e.h : ""));
                    updateNav();
                });
            } catch (Exception e) {
                Logger.e("VIEW", "解码失败", e);
                runOnUiThread(() -> toast("解码失败: " + e.getMessage()));
            }
        }).start();
    }

    private void updateNav() {
        boolean has = bundle != null && !bundle.entries.isEmpty();
        prevBtn.setEnabled(has);
        nextBtn.setEnabled(has);
        exportBtn.setEnabled(has);
        if (!has) infoView.setText("点「选择文件」打开 .imgf，或在文件管理器中点 .imgf 文件");
    }

    private void exportAll() {
        if (bundle == null) return;
        exportBtn.setEnabled(false);
        new Thread(() -> {
            int ok = 0;
            try {
                for (int i = 0; i < bundle.entries.size(); i++) {
                    MifParser.Decoded dec = bundle.decode(i);
                    String fname = dec.name;
                    byte[] data;
                    if (dec.fileBytes != null) {
                        data = dec.fileBytes; // JPEG 原文
                    } else {
                        Bitmap bmp = Bitmap.createBitmap(dec.w, dec.h, Bitmap.Config.ARGB_8888);
                        bmp.setPixels(dec.argb, 0, dec.w, 0, 0, dec.w, dec.h);
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                        bmp.recycle();
                        data = bos.toByteArray();
                        int dot = fname.lastIndexOf('.');
                        fname = (dot > 0 ? fname.substring(0, dot) : fname) + ".png";
                    }
                    if (saveToPictures(fname, data)) ok++;
                }
                final int f = ok, t = bundle.entries.size();
                runOnUiThread(() -> {
                    toast("导出 " + f + "/" + t + " 到相册 IMGFViewer");
                    exportBtn.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    toast("导出失败: " + e.getMessage());
                    exportBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private boolean saveToPictures(String fname, byte[] data) {
        try {
            ContentResolver cr = getContentResolver();
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, fname);
            cv.put(MediaStore.Images.Media.MIME_TYPE,
                    fname.endsWith(".png") ? "image/png" : "image/jpeg");
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/IMGFViewer");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return false;
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) return false;
                os.write(data);
            }
            if (Build.VERSION.SDK_INT >= 29) {
                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                cr.update(uri, cv, null, null);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------- 日志查看 ----------

    private void showLogs() {
        TextView tv = new TextView(this);
        tv.setText(Logger.getAll());
        tv.setTextIsSelectable(true);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setPadding(16, 16, 16, 16);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        // 固定高度，自动滚到底
        int h = (int) (420 * getResources().getDisplayMetrics().density);
        sv.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        sv.post(() -> sv.fullScroll(android.view.View.FOCUS_DOWN));
        new android.app.AlertDialog.Builder(this)
                .setTitle("运行日志")
                .setView(sv)
                .setPositiveButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "imgfviewer-log", Logger.getAll()));
                    toast("日志已复制");
                })
                .setNeutralButton("清空", (d, w) -> Logger.clear())
                .setNegativeButton("关闭", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (current != null) current.recycle();
        super.onDestroy();
    }
}
