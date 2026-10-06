package com.xyether.upscaler;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * A fit-centred image view whose viewport is expressed in image-normalized
 * coordinates. Two instances can be linked even when their bitmap dimensions
 * differ (for example 1440×1956 before vs 2880×3912 after).
 */
public final class SyncZoomImageView extends AppCompatImageView {
    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 8f;

    private final Matrix imageMatrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private @Nullable SyncZoomImageView partner;
    private float zoom = MIN_ZOOM;
    private float centerX = 0.5f;
    private float centerY = 0.5f;
    private float lastX;
    private float lastY;
    private boolean dragged;

    public SyncZoomImageView(Context context) {
        this(context, null);
    }

    public SyncZoomImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        scaleAround(detector.getFocusX(), detector.getFocusY(), detector.getScaleFactor());
                        return true;
                    }
                });
    }

    public void setPartner(@Nullable SyncZoomImageView other) {
        partner = other;
    }

    public void resetViewport() {
        zoom = MIN_ZOOM;
        centerX = 0.5f;
        centerY = 0.5f;
        applyViewport();
        notifyPartner();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        post(this::applyViewport);
    }

    @Override public void setImageDrawable(@Nullable Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::applyViewport);
    }

    private float baseScale() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) return 0f;
        return Math.min(getWidth() / (float) drawable.getIntrinsicWidth(),
                getHeight() / (float) drawable.getIntrinsicHeight());
    }

    private void clampCenter() {
        Drawable drawable = getDrawable();
        float fit = baseScale();
        if (drawable == null || fit <= 0f) return;
        float scaledW = drawable.getIntrinsicWidth() * fit * zoom;
        float scaledH = drawable.getIntrinsicHeight() * fit * zoom;
        if (scaledW <= getWidth()) centerX = 0.5f;
        else {
            float edge = getWidth() / (2f * scaledW);
            centerX = Math.max(edge, Math.min(1f - edge, centerX));
        }
        if (scaledH <= getHeight()) centerY = 0.5f;
        else {
            float edge = getHeight() / (2f * scaledH);
            centerY = Math.max(edge, Math.min(1f - edge, centerY));
        }
    }

    private void applyViewport() {
        Drawable drawable = getDrawable();
        float fit = baseScale();
        if (drawable == null || fit <= 0f) return;
        clampCenter();
        float scale = fit * zoom;
        float tx = getWidth() * 0.5f - drawable.getIntrinsicWidth() * centerX * scale;
        float ty = getHeight() * 0.5f - drawable.getIntrinsicHeight() * centerY * scale;
        imageMatrix.reset();
        imageMatrix.setScale(scale, scale);
        imageMatrix.postTranslate(tx, ty);
        setImageMatrix(imageMatrix);
    }

    private void scaleAround(float focusX, float focusY, float factor) {
        Drawable drawable = getDrawable();
        float fit = baseScale();
        if (drawable == null || fit <= 0f) return;
        float oldScale = fit * zoom;
        float sourceX = (focusX - (getWidth() * 0.5f - drawable.getIntrinsicWidth() * centerX * oldScale))
                / (drawable.getIntrinsicWidth() * oldScale);
        float sourceY = (focusY - (getHeight() * 0.5f - drawable.getIntrinsicHeight() * centerY * oldScale))
                / (drawable.getIntrinsicHeight() * oldScale);
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
        float newScale = fit * zoom;
        centerX = (getWidth() * 0.5f - focusX) / (drawable.getIntrinsicWidth() * newScale) + sourceX;
        centerY = (getHeight() * 0.5f - focusY) / (drawable.getIntrinsicHeight() * newScale) + sourceY;
        applyViewport();
        notifyPartner();
    }

    private void panBy(float dx, float dy) {
        Drawable drawable = getDrawable();
        float fit = baseScale();
        if (drawable == null || fit <= 0f) return;
        float scale = fit * zoom;
        centerX -= dx / (drawable.getIntrinsicWidth() * scale);
        centerY -= dy / (drawable.getIntrinsicHeight() * scale);
        applyViewport();
        notifyPartner();
    }

    private void setViewport(float nextZoom, float nextCenterX, float nextCenterY) {
        zoom = nextZoom;
        centerX = nextCenterX;
        centerY = nextCenterY;
        applyViewport();
    }

    private void notifyPartner() {
        if (partner != null) partner.setViewport(zoom, centerX, centerY);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                lastX = event.getX();
                lastY = event.getY();
                dragged = false;
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                if (Math.abs(dx) > 2f || Math.abs(dy) > 2f) dragged = true;
                panBy(dx, dy);
                lastX = event.getX();
                lastY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP && !dragged) {
                performClick();
            }
        }
        return true;
    }
}
