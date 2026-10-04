package com.mtr.mifviewer;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * 支持双指缩放 + 拖动 + 双击重置的图片视图。
 * Matrix 模式实现，无外部依赖。
 */
public class ZoomImageView extends ImageView {

    private final Matrix matrix = new Matrix();
    private final float[] mvals = new float[9];

    private float minScale = 1f;   // 适应屏幕的基准
    private static final float MAX_SCALE = 8f;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private final PointF last = new PointF();
    private int lastPointerCount = 0;

    public ZoomImageView(Context ctx) {
        this(ctx, null);
    }

    public ZoomImageView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(ctx, new ScaleListener());
        gestureDetector = new GestureDetector(ctx, new GestureListener());
        setOnTouchListener((v, e) -> {
            scaleDetector.onTouchEvent(e);
            gestureDetector.onTouchEvent(e);
            int pc = e.getPointerCount();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    last.set(e.getX(), e.getY());
                    lastPointerCount = pc;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress() && pc == lastPointerCount) {
                        float dx = e.getX() - last.x;
                        float dy = e.getY() - last.y;
                        if (pc == 1 || getScale() > minScale) {
                            matrix.postTranslate(dx, dy);
                            fixBounds();
                            setImageMatrix(matrix);
                        }
                        last.set(e.getX(), e.getY());
                    } else {
                        last.set(e.getX(), e.getY());
                        lastPointerCount = pc;
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                    lastPointerCount = pc - 1;
                    if (lastPointerCount > 0 && e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
                        int idx = e.getActionIndex() == 0 ? 1 : 0;
                        last.set(e.getX(idx), e.getY(idx));
                    }
                    break;
            }
            return true;
        });
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        resetZoom();
    }

    /** 按 FIT_CENTER 计算基准矩阵。 */
    public void resetZoom() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        float vw = getWidth(), vh = getHeight();
        float s = Math.min(vw / dw, vh / dh);
        minScale = s;
        matrix.reset();
        matrix.postScale(s, s);
        matrix.postTranslate((vw - dw * s) / 2f, (vh - dh * s) / 2f);
        setImageMatrix(matrix);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) resetZoom();
    }

    private float getScale() {
        matrix.getValues(mvals);
        return mvals[Matrix.MSCALE_X];
    }

    /** 缩放后把图片拉回视图范围内（或居中）。 */
    private void fixBounds() {
        Drawable d = getDrawable();
        if (d == null) return;
        matrix.getValues(mvals);
        float s = mvals[Matrix.MSCALE_X];
        float tx = mvals[Matrix.MTRANS_X], ty = mvals[Matrix.MTRANS_Y];
        float dw = d.getIntrinsicWidth() * s, dh = d.getIntrinsicHeight() * s;
        float vw = getWidth(), vh = getHeight();
        float dx = 0, dy = 0;
        if (dw <= vw) dx = (vw - dw) / 2f - tx;
        else if (tx > 0) dx = -tx;
        else if (tx + dw < vw) dx = vw - (tx + dw);
        if (dh <= vh) dy = (vh - dh) / 2f - ty;
        else if (ty > 0) dy = -ty;
        else if (ty + dh < vh) dy = vh - (ty + dh);
        matrix.postTranslate(dx, dy);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float s = getScale();
            float factor = detector.getScaleFactor();
            float ns = s * factor;
            if (ns < minScale) factor = minScale / s;
            if (ns > MAX_SCALE * minScale) factor = MAX_SCALE * minScale / s;
            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            fixBounds();
            setImageMatrix(matrix);
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (getScale() > minScale * 1.05f) {
                resetZoom();
            } else {
                float s = Math.min(3f, MAX_SCALE) * minScale / getScale();
                matrix.postScale(s, s, e.getX(), e.getY());
                fixBounds();
                setImageMatrix(matrix);
            }
            return true;
        }
    }
}
