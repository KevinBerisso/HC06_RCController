package com.rccontroller;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Reads phone IMU (rotation vector / accelerometer) and maps tilt to
 * a steering value in [-1, +1].
 *
 * Tilt the phone left → negative X (left turn)
 * Tilt the phone right → positive X (right turn)
 *
 * Uses the ROTATION_VECTOR sensor for stable orientation; falls back
 * to ACCELEROMETER if unavailable.
 */
public class ImuController implements SensorEventListener {

    public interface ImuListener {
        /** @param steer -1.0 (full left) .. +1.0 (full right) */
        void onSteerUpdate(float steer);
    }

    private static final float TILT_RANGE_DEG = 45f; // degrees = full deflection

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private Sensor accelSensor;
    private boolean useRotationVector;
    private ImuListener listener;
    private boolean active = false;

    private float[] rotationMatrix = new float[9];
    private float[] orientationAngles = new float[3];

    public ImuController(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        useRotationVector = (rotationSensor != null);
    }

    public void setListener(ImuListener listener) {
        this.listener = listener;
    }

    public boolean isAvailable() {
        return rotationSensor != null || accelSensor != null;
    }

    public void start() {
        if (active) return;
        active = true;
        if (useRotationVector) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        } else if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    public void stop() {
        active = false;
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!active) return;

        float steer = 0f;

        if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            SensorManager.getOrientation(rotationMatrix, orientationAngles);
            // orientationAngles[2] = roll (radians), negative = tilt right in landscape
            float rollDeg = (float) Math.toDegrees(orientationAngles[2]);
            // In landscape, roll maps nicely to left/right tilt
            steer = clamp(-rollDeg / TILT_RANGE_DEG);

        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // Simple accel: x-axis tilt in landscape
            float ax = event.values[0]; // m/s², ~±9.8 at 90°
            steer = clamp(-ax / (SensorManager.GRAVITY_EARTH * (TILT_RANGE_DEG / 90f)));
        }

        if (listener != null) listener.onSteerUpdate(steer);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
