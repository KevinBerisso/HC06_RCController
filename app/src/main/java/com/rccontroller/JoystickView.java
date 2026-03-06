package com.rccontroller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * A single virtual joystick view.
 * Outputs x in [-1, +1] and y in [-1, +1].
 * Y axis: up = +1, down = -1.
 * X axis: left = -1, right = +1.
 */
public class JoystickView extends View {

    public interface JoystickListener {
        void onJoystickMoved(float x, float y);
    }

    private Paint basePaint;
    private Paint baseRimPaint;
    private Paint thumbPaint;
    private Paint thumbRimPaint;
    private Paint labelPaint;
    private Paint axisPaint;

    private float centerX, centerY;
    private float baseRadius, thumbRadius;
    private float thumbX, thumbY;

    public enum AxisMode { FREE, VERTICAL_ONLY, HORIZONTAL_ONLY }

    private int activePointerId = -1;
    private JoystickListener listener;
    private String label = "";
    private AxisMode axisMode = AxisMode.FREE;

    // Current normalized output values
    private float normX = 0f;
    private float normY = 0f;

    public JoystickView(Context context) {
        super(context);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public JoystickView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(Color.argb(80, 60, 60, 80));
        basePaint.setStyle(Paint.Style.FILL);

        baseRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseRimPaint.setColor(Color.argb(180, 100, 120, 200));
        baseRimPaint.setStyle(Paint.Style.STROKE);
        baseRimPaint.setStrokeWidth(3f);

        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setStyle(Paint.Style.FILL);

        thumbRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbRimPaint.setColor(Color.argb(220, 180, 200, 255));
        thumbRimPaint.setStyle(Paint.Style.STROKE);
        thumbRimPaint.setStrokeWidth(2.5f);

        axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        axisPaint.setColor(Color.argb(60, 180, 180, 255));
        axisPaint.setStyle(Paint.Style.STROKE);
        axisPaint.setStrokeWidth(1.5f);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.argb(180, 220, 220, 255));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setLabel(String label) {
        this.label = label;
        invalidate();
    }

    public void setListener(JoystickListener listener) {
        this.listener = listener;
    }

    public void setAxisMode(AxisMode mode) {
        this.axisMode = mode;
        invalidate();
    }

    public float getNormX() { return normX; }
    public float getNormY() { return normY; }

    /** Allow external override (e.g. from IMU) */
    public void setValues(float x, float y) {
        normX = clamp(x);
        normY = clamp(y);
        thumbX = centerX + normX * baseRadius;
        thumbY = centerY - normY * baseRadius;
        invalidate();
        if (listener != null) listener.onJoystickMoved(normX, normY);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        centerX = w / 2f;
        centerY = h / 2f;
        baseRadius = Math.min(w, h) / 2f - 8f;
        thumbRadius = baseRadius * 0.35f;
        thumbX = centerX;
        thumbY = centerY;

        // Rebuild gradient for thumb
        updateThumbGradient();

        labelPaint.setTextSize(baseRadius * 0.22f);
    }

    private void updateThumbGradient() {
        if (baseRadius <= 0) return;
        RadialGradient gradient = new RadialGradient(
                thumbX, thumbY - thumbRadius * 0.3f,
                thumbRadius,
                new int[]{Color.argb(255, 140, 170, 255), Color.argb(255, 60, 80, 180)},
                null,
                Shader.TileMode.CLAMP
        );
        thumbPaint.setShader(gradient);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Base circle
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius, baseRimPaint);

        // Axis lines — only draw the active axis
        if (axisMode != AxisMode.VERTICAL_ONLY) {
            canvas.drawLine(centerX - baseRadius, centerY, centerX + baseRadius, centerY, axisPaint);
        }
        if (axisMode != AxisMode.HORIZONTAL_ONLY) {
            canvas.drawLine(centerX, centerY - baseRadius, centerX, centerY + baseRadius, axisPaint);
        }

        // Draw a highlighted slot for constrained axes
        Paint slotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        slotPaint.setStyle(Paint.Style.STROKE);
        slotPaint.setStrokeWidth(thumbRadius * 2.1f);
        slotPaint.setStrokeCap(Paint.Cap.ROUND);
        slotPaint.setColor(Color.argb(30, 140, 170, 255));
        if (axisMode == AxisMode.VERTICAL_ONLY) {
            canvas.drawLine(centerX, centerY - baseRadius, centerX, centerY + baseRadius, slotPaint);
        } else if (axisMode == AxisMode.HORIZONTAL_ONLY) {
            canvas.drawLine(centerX - baseRadius, centerY, centerX + baseRadius, centerY, slotPaint);
        }

        // Line from center to thumb
        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.argb(100, 160, 180, 255));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2f);
        canvas.drawLine(centerX, centerY, thumbX, thumbY, linePaint);

        // Thumb
        updateThumbGradient();
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint);
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbRimPaint);

        // Label
        if (!label.isEmpty()) {
            canvas.drawText(label, centerX, centerY + baseRadius + labelPaint.getTextSize() * 1.1f, labelPaint);
        }

        // Value text
        Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        valuePaint.setColor(Color.argb(160, 200, 220, 255));
        valuePaint.setTextSize(baseRadius * 0.15f);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        String valStr = String.format("X:%.2f Y:%.2f", normX, normY);
        canvas.drawText(valStr, centerX, centerY + baseRadius - 10, valuePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = event.getActionIndex();
                activePointerId = event.getPointerId(idx);
                updateThumb(event, idx);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = event.findPointerIndex(activePointerId);
                if (idx >= 0) updateThumb(event, idx);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                int idx = event.getActionIndex();
                if (event.getPointerId(idx) == activePointerId) {
                    activePointerId = -1;
                    recenter();
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void updateThumb(MotionEvent event, int pointerIndex) {
        float dx = event.getX(pointerIndex) - centerX;
        float dy = event.getY(pointerIndex) - centerY;

        // Apply axis constraint before clamping to radius
        if (axisMode == AxisMode.VERTICAL_ONLY)   dx = 0f;
        if (axisMode == AxisMode.HORIZONTAL_ONLY) dy = 0f;

        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist > baseRadius) {
            dx = dx / dist * baseRadius;
            dy = dy / dist * baseRadius;
        }
        thumbX = centerX + dx;
        thumbY = centerY + dy;
        normX = clamp(dx / baseRadius);
        normY = clamp(-dy / baseRadius); // invert Y: up is positive
        invalidate();
        if (listener != null) listener.onJoystickMoved(normX, normY);
    }

    private void recenter() {
        thumbX = centerX;
        thumbY = centerY;
        normX = 0f;
        normY = 0f;
        invalidate();
        if (listener != null) listener.onJoystickMoved(0f, 0f);
    }

    private float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}