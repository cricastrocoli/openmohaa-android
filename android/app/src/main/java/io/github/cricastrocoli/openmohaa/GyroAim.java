package io.github.cricastrocoli.openmohaa;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;

// Gyro aiming: turning the device turns the view, on top of the sticks or touch (fine aim with
// the hands, big turns with the thumb). Turning it left or right (about the screen's vertical
// axis) turns the view; tilting the top toward or away from you looks down or up. Only in play
// (the game ignores it in menus); the sensor runs only while the game is in front.
final class GyroAim implements SensorEventListener {
    // Off, low, medium, high: the view turns this many times the device's own turn.
    static final float[] SENSITIVITY = {0f, 0.75f, 1.5f, 2.5f};
    private static final float DEAD = 0.015f;  // rad/s: below this, sensor noise

    private final Activity activity;
    private final SensorManager sensors;
    private final Sensor gyro;
    private final float scale;
    private long last;

    GyroAim(Activity a, int level) {
        activity = a;
        sensors = (SensorManager) a.getSystemService(Activity.SENSOR_SERVICE);
        gyro = sensors != null ? sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
        scale = SENSITIVITY[Math.max(0, Math.min(SENSITIVITY.length - 1, level))];
    }

    static boolean available(Activity a) {
        SensorManager s = (SensorManager) a.getSystemService(Activity.SENSOR_SERVICE);
        return s != null && s.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
    }

    void start() {
        if (gyro == null || scale <= 0) return;
        last = 0;
        sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
    }

    void stop() {
        if (gyro != null) sensors.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long t = e.timestamp;
        float dt = last == 0 ? 0 : (t - last) / 1e9f;
        last = t;
        if (dt <= 0 || dt > 0.1f) return;
        // Angular speed in the device's natural axes, turned into the screen's axes.
        float x = e.values[0], y = e.values[1], sx, sy;
        switch (activity.getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: sx = -y; sy = x; break;
            case Surface.ROTATION_180: sx = -x; sy = -y; break;
            case Surface.ROTATION_270: sx = y; sy = -x; break;
            default: sx = x; sy = y;
        }
        if (Math.abs(sx) < DEAD) sx = 0;
        if (Math.abs(sy) < DEAD) sy = 0;
        if (sx == 0 && sy == 0) return;
        float k = (float) Math.toDegrees(dt) * scale;
        try {
            nativeGyro(sy * k, -sx * k);  // yaw left +, pitch down +
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {
    }

    private static native void nativeGyro(float yawDeg, float pitchDeg);
}
