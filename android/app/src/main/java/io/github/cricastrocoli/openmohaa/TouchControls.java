package io.github.cricastrocoli.openmohaa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.R;

// On-screen controls over the game, for playing without a controller, and taps on the game's
// menus. A view on top of SDL's: it takes every touch, so the game never sees raw touches.
//
// Modes (Settings): AUTO shows the controls only while no controller is connected (checked at
// start and whenever one comes or goes), ALWAYS shows them anyway, NEVER ignores the screen
// (no stray touches). In AUTO and ALWAYS, a tap on a game menu clicks there.
//
// In the game: the left part of the screen is a stick that appears where the thumb lands;
// dragging on the right part turns the view (and so does dragging from the fire button); the
// buttons press the game's actions (android_input.cpp applies all of it on the game's thread).
public class TouchControls extends View implements InputManager.InputDeviceListener {
    public static final int AUTO = 0, ALWAYS = 1, NEVER = 2;

    // Actions, as numbered in android_input.cpp.
    private static final int JUMP = 0, USE = 1, RELOAD = 2, ITEM = 3, NEXTWEAPON = 5, ALTFIRE = 6,
            FIRE = 7, CROUCH = 8, MENU = 14;

    private static native void nativeMove(float x, float y);
    // The game's picture across the view (pixels): all of it unless the 4:3 option centres it.
    private float gameLeft, gameWidth;

    void setGameArea(float left, float width) {
        gameLeft = left;
        gameWidth = width;
    }

    private static native void nativeAim(float dxDp, float dyDp);
    private static native void nativeAction(int action, boolean down);
    private static native void nativeMenuTouch(float x, float y, boolean down);
    private static native void nativeReleaseAll();
    private static native boolean nativeInMenu();
    private static native void nativeToggleKeyboard();

    // The keyboard button: at the left edge, halfway down (clear of menus' fields and of the
    // HUD); in menus too (to type a server address, a name, or press keys when binding the
    // game's own controls).
    private float kbX, kbY, kbR;
    private boolean kbPressed;
    private int kbFinger = -1;

    private static int fingersExcept(MotionEvent e, int id) {
        int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) if (e.getPointerId(i) != id) n++;
        return n;
    }

    // With the touch controls (no controller, or touch set to Always), in menus and in play;
    // with a controller, Select + Start opens the keyboard instead (android_input.cpp).
    private boolean keyboardShown() {
        return mode == ALWAYS || (mode == AUTO && !controller);
    }

    // A button: its action, its label, and where it sits, in dp from the screen's right and
    // bottom edges (the menu button: top centre, clear of the compass), with its radius.
    private static final class Button {
        final int action, label;
        final float fromRight, fromBottom, radius;
        // Held buttons (fire, aim, jump: they must react at once) act while the finger is on
        // them, and the finger can turn the view at the same time. The others act on a tap (on lifting the finger): a finger
        // that drags from them turns the view instead, so the buttons don't take space from
        // aiming and a swipe doesn't reload by accident.
        final boolean holds;
        boolean pressed;
        long pressedAt;      // when a held button went down (uptime ms)
        float x, y, r;       // on screen, in pixels

        Button(int action, int label, float fromRight, float fromBottom, float radius, boolean holds) {
            this.action = action;
            this.label = label;
            this.fromRight = fromRight;
            this.fromBottom = fromBottom;
            this.radius = radius;
            this.holds = holds;
        }
    }

    private final Button[] buttons = {
            new Button(FIRE, R.string.touch_fire, 100, 125, 44, true),
            new Button(ALTFIRE, R.string.touch_altfire, 124, 240, 32, true),  // between use and the right edge
            // Next to fire: reload and jump. Jump acts at once, so only its own circle counts
            // (no margin around it). No grenade button: it only picks the grenade, as the
            // weapon button does. Crouch on the right edge above fire's height, clear of the
            // ammo count in the bottom right corner.
            new Button(RELOAD, R.string.touch_reload, 215, 62, 34, false),
            new Button(JUMP, R.string.touch_jump, 225, 170, 32, true),
            new Button(CROUCH, R.string.touch_crouch, 36, 212, 27, false),
            new Button(USE, R.string.touch_use, 215, 268, 30, false),
            new Button(NEXTWEAPON, R.string.touch_weapon, 72, 345, 30, false),
            new Button(ITEM, R.string.touch_item, 160, 345, 26, false),
            new Button(MENU, R.string.touch_menu, -1, -1, 26, false),  // top centre
    };

    private final int mode;
    private final float dp;
    private float unit;  // dp, scaled down on screens shorter than 430 dp (phones held sideways)
    private final InputManager inputManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            label = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean controller;      // a controller is connected
    static volatile boolean controllerPresent;  // the same, for GyroAim's automatic mode
    private boolean inMenu = true;
    // What each finger is doing: a Button, STICK or AIM.
    private final SparseArray<Object> fingers = new SparseArray<>();
    private final SparseArray<float[]> lastPos = new SparseArray<>();
    private final SparseArray<float[]> startPos = new SparseArray<>();
    private final SparseArray<long[]> lastTime = new SparseArray<>();
    private static final float TAP_SLOP_DP = 12;
    private static final long TAP_HOLD_MS = 120;  // how long a tap holds its button down  // further than this, a tap on a button becomes aiming
    private static final Object STICK = new Object(), AIM = new Object();
    private float stickX, stickY, knobX, knobY;  // the stick's centre and the thumb, pixels
    private boolean stickHeld;

    public TouchControls(Context c, int mode) {
        super(c);
        this.mode = mode;
        dp = c.getResources().getDisplayMetrics().density;
        inputManager = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
        controller = controllerConnected();
        controllerPresent = controller;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * dp);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        label.setColor(Color.argb(210, 240, 234, 214));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        inputManager.registerInputDeviceListener(this, handler);
        handler.post(poll);
    }

    @Override
    protected void onDetachedFromWindow() {
        inputManager.unregisterInputDeviceListener(this);
        handler.removeCallbacks(poll);
        super.onDetachedFromWindow();
    }

    // Follows the game in and out of its menus (no controls drawn over a menu).
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean m;
            try {
                m = nativeInMenu();
            } catch (UnsatisfiedLinkError e) {
                m = true;  // the game's library isn't loaded yet
            }
            if (m != inMenu) {
                inMenu = m;
                if (m) releaseAll();
                invalidate();
            }
            handler.postDelayed(this, 100);
        }
    };

    private boolean controlsShown() {
        return !inMenu && (mode == ALWAYS || (mode == AUTO && !controller));
    }

    // ---- Controllers -----------------------------------------------------------------------

    private boolean controllerConnected() {
        for (int id : inputManager.getInputDeviceIds()) {
            InputDevice d = inputManager.getInputDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }

    private void devicesChanged() {
        boolean c = controllerConnected();
        if (c == controller) return;
        controller = c;
        controllerPresent = c;
        if (!controlsShown()) releaseAll();
        invalidate();
    }

    @Override public void onInputDeviceAdded(int id) { devicesChanged(); }
    @Override public void onInputDeviceRemoved(int id) { devicesChanged(); }
    @Override public void onInputDeviceChanged(int id) { devicesChanged(); }

    // ---- Touch -----------------------------------------------------------------------------

    private void releaseAll() {
        fingers.clear();
        lastPos.clear();
        startPos.clear();
        lastTime.clear();
        for (Button b : buttons) b.pressed = false;
        stickHeld = false;
        try {
            nativeReleaseAll();
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mode == NEVER) return true;
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        int id = e.getPointerId(index);
        float x = e.getX(index), y = e.getY(index);

        // The keyboard button (any finger): opens or closes Android's keyboard on release.
        if (keyboardShown()) {
            float kx = x - kbX, ky = y - kbY;
            boolean on = kx * kx + ky * ky <= kbR * kbR * 1.3f;
            if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && on) {
                kbPressed = true;
                kbFinger = id;
                invalidate();
                return true;
            }
            if (kbPressed && id == kbFinger
                    && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP)) {
                kbPressed = false;
                invalidate();
                if (on) nativeToggleKeyboard();
                return true;
            }
            if (kbPressed && action == MotionEvent.ACTION_MOVE && fingersExcept(e, kbFinger) == 0) return true;
        }

        if (inMenu) {
            // A menu: the first finger is the mouse, pressed where it lands, released where it
            // lifts (so a tap clicks there).
            // Where on the game's picture (which may be a 4:3 area in the middle, see
            // setGameArea), as fractions of it.
            float gl = gameLeft, gw = gameWidth > 0 ? gameWidth : getWidth();
            float fx = Math.max(0, Math.min(1, (x - gl) / gw)), fy = y / getHeight();
            if (action == MotionEvent.ACTION_DOWN) {
                nativeMenuTouch(fx, fy, true);
            } else if (action == MotionEvent.ACTION_UP) {
                nativeMenuTouch(fx, fy, false);
            }
            return true;
        }
        if (!controlsShown()) return true;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                fingerDown(id, x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) fingerMove(e.getPointerId(i), e.getX(i), e.getY(i), e.getEventTime());
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                fingerUp(id);
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        invalidate();
        return true;
    }

    private void fingerDown(int id, float x, float y) {
        lastPos.put(id, new float[]{x, y, 0});  // x, y, smoothed speed (dp/s)
        lastTime.put(id, new long[]{android.os.SystemClock.uptimeMillis()});
        startPos.put(id, new float[]{x, y});
        Button hit = null;
        for (Button b : buttons) {
            float dx = x - b.x, dy = y - b.y;
            // Buttons that act on touch (fire, aim, jump) count only inside their circle, so a
            // swipe to aim that starts near them doesn't set them off; tap buttons get a margin.
            float reach = b.r * (b.holds ? 1f : 1.15f);
            if (dx * dx + dy * dy <= reach * reach) { hit = b; break; }
        }
        if (hit != null) {
            fingers.put(id, hit);
            hit.pressed = true;
            if (hit.holds) {
                hit.pressedAt = android.os.SystemClock.uptimeMillis();
                nativeAction(hit.action, true);
            }
        } else if (x < getWidth() * 0.42f && !stickHeld) {
            fingers.put(id, STICK);
            stickHeld = true;
            stickX = knobX = x;
            stickY = knobY = y;
        } else {
            fingers.put(id, AIM);
        }
    }

    private void fingerMove(int id, float x, float y, long time) {
        Object what = fingers.get(id);
        float[] last = lastPos.get(id);
        if (what == null || last == null) return;
        if (what == STICK) {
            float r = 60 * unit, dx = x - stickX, dy = y - stickY;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > r) {  // the base follows a thumb that goes past the edge
                stickX = x - dx / len * r;
                stickY = y - dy / len * r;
                dx = x - stickX;
                dy = y - stickY;
            }
            knobX = x;
            knobY = y;
            float n = Math.min(1f, len / r);
            float dead = 0.12f;
            float m = n <= dead ? 0 : (n - dead) / (1 - dead);
            float d = len > 0 ? len : 1;
            nativeMove(dx / d * m, dy / d * m);
        } else if (what == AIM || (what instanceof Button && ((Button) what).holds)) {
            aim(id, x, y, last, time);
        } else if (what instanceof Button) {
            // A tap button: once the finger drags away, it aims instead (except the menu's).
            Button b = (Button) what;
            float[] start = startPos.get(id);
            float sx = (x - start[0]) / dp, sy = (y - start[1]) / dp;
            if (b.action != MENU && sx * sx + sy * sy > TAP_SLOP_DP * TAP_SLOP_DP) {
                fingers.put(id, AIM);
                if (fingers.indexOfValue(b) < 0) b.pressed = false;
                aim(id, x, y, last, time);
            }
        }
        last[0] = x;
        last[1] = y;
    }

    // Aiming with acceleration, like a mouse's: slow drags keep their precision (the base rate,
    // rp_touch_aim degrees per dp), fast swipes turn more per dp, up to ACCEL_MAX times, so a
    // quick flick turns the view around in one go.
    private static final float ACCEL_FROM = 250;   // dp/s: below this, no acceleration
    private static final float ACCEL_SPAN = 500;   // dp/s more for each extra 1x
    private static final float ACCEL_MAX = 3.5f;

    private void aim(int id, float x, float y, float[] last, long time) {
        float dx = (x - last[0]) / dp, dy = (y - last[1]) / dp;
        long[] t = lastTime.get(id);
        long dt = t != null ? time - t[0] : 0;
        if (t != null) t[0] = time;
        if (dt > 0) {
            float speed = (float) Math.sqrt(dx * dx + dy * dy) * 1000f / dt;
            last[2] = last[2] * 0.6f + speed * 0.4f;  // smoothed: touch samples are uneven
        }
        float gain = 1f + Math.max(0f, Math.min(ACCEL_MAX - 1f, (last[2] - ACCEL_FROM) / ACCEL_SPAN));
        nativeAim(dx * gain, dy * gain);
    }

    private void fingerUp(int id) {
        Object what = fingers.get(id);
        fingers.remove(id);
        lastPos.remove(id);
        startPos.remove(id);
        lastTime.remove(id);
        if (what == STICK) {
            stickHeld = false;
            nativeMove(0, 0);
        } else if (what instanceof Button) {
            Button b = (Button) what;
            if (fingers.indexOfValue(b) < 0) {  // no other finger still on it
                b.pressed = false;
                if (b.holds) {
                    // Held at least TAP_HOLD_MS: the game reads buttons at its own pace, and a
                    // quick tap could come and go between two reads (a missed shot or jump).
                    long left = b.pressedAt + TAP_HOLD_MS - android.os.SystemClock.uptimeMillis();
                    if (left > 0) {
                        handler.postDelayed(() -> {
                            if (fingers.indexOfValue(b) < 0) nativeAction(b.action, false);
                        }, left);
                    } else {
                        nativeAction(b.action, false);
                    }
                } else {
                    // A tap: pressed for a moment, long enough for the game to see it (a single
                    // frame isn't always: a jump or a door could be missed).
                    nativeAction(b.action, true);
                    handler.postDelayed(() -> {
                        if (fingers.indexOfValue(b) < 0) nativeAction(b.action, false);
                    }, TAP_HOLD_MS);
                }
            }
        }
    }

    // ---- Drawing ---------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        unit = dp * Math.min(1f, h / dp / 430f);
        kbR = 22 * unit;
        kbX = (22 + 12) * unit;
        kbY = h / 2f;
        for (Button b : buttons) {
            b.r = b.radius * unit;
            if (b.fromRight < 0) {  // the menu button: top centre
                b.x = w / 2f;
                b.y = (b.radius + 12) * unit;
            } else {
                b.x = w - b.fromRight * unit;
                b.y = h - b.fromBottom * unit;
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (keyboardShown()) {
            // The keyboard button: a small key-cap with three rows of dots.
            fill.setColor(kbPressed ? Color.argb(120, 200, 160, 70) : Color.argb(55, 20, 22, 16));
            c.drawCircle(kbX, kbY, kbR, fill);
            ring.setColor(kbPressed ? Color.argb(220, 230, 190, 90) : Color.argb(110, 235, 228, 205));
            c.drawCircle(kbX, kbY, kbR, ring);
            fill.setColor(Color.argb(170, 235, 228, 205));
            float d = kbR * 0.26f, s = kbR * 0.09f;
            for (int row = -1; row <= 1; row++) {
                int n = row == 1 ? 1 : 3;
                for (int k = 0; k < n; k++) {
                    float px = kbX + (n == 1 ? 0 : (k - 1) * d), py = kbY + row * d * 0.85f;
                    if (row == 1) c.drawRect(kbX - d, py - s, kbX + d, py + s, fill);
                    else c.drawCircle(px, py, s * 1.1f, fill);
                }
            }
        }
        if (!controlsShown()) return;
        for (Button b : buttons) {
            fill.setColor(b.pressed ? Color.argb(120, 200, 160, 70) : Color.argb(55, 20, 22, 16));
            c.drawCircle(b.x, b.y, b.r, fill);
            ring.setColor(b.pressed ? Color.argb(220, 230, 190, 90) : Color.argb(110, 235, 228, 205));
            c.drawCircle(b.x, b.y, b.r, ring);
            String text = getContext().getString(b.label);
            label.setTextSize(Math.min(b.r * 0.42f, 13 * unit));
            c.drawText(text, b.x, b.y + label.getTextSize() * 0.35f, label);
        }
        // The stick: where the thumb holds it, or a hint of it at rest.
        float r = 60 * unit;
        float cx = stickHeld ? stickX : 120 * unit, cy = stickHeld ? stickY : getHeight() - 110 * unit;
        fill.setColor(Color.argb(stickHeld ? 50 : 30, 20, 22, 16));
        c.drawCircle(cx, cy, r, fill);
        ring.setColor(Color.argb(stickHeld ? 120 : 60, 235, 228, 205));
        c.drawCircle(cx, cy, r, ring);
        fill.setColor(Color.argb(stickHeld ? 150 : 60, 235, 228, 205));
        c.drawCircle(stickHeld ? knobX : cx, stickHeld ? knobY : cy, 24 * unit, fill);
    }
}
