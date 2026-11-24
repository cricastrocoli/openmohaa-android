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
            FIRE = 7, CROUCH = 8, GRENADE = 10, MENU = 14;

    private static native void nativeMove(float x, float y);
    private static native void nativeAim(float dxDp, float dyDp);
    private static native void nativeAction(int action, boolean down);
    private static native void nativeMenuTouch(float x, float y, boolean down);
    private static native void nativeReleaseAll();
    private static native boolean nativeInMenu();

    // A button: its action, its label, and where it sits, in dp from the screen's right and
    // bottom edges (the menu button: top centre, clear of the compass), with its radius.
    private static final class Button {
        final int action, label;
        final float fromRight, fromBottom, radius;
        final boolean aims;  // dragging from it also turns the view (fire)
        boolean pressed;
        float x, y, r;       // on screen, in pixels

        Button(int action, int label, float fromRight, float fromBottom, float radius, boolean aims) {
            this.action = action;
            this.label = label;
            this.fromRight = fromRight;
            this.fromBottom = fromBottom;
            this.radius = radius;
            this.aims = aims;
        }
    }

    private final Button[] buttons = {
            new Button(FIRE, R.string.touch_fire, 100, 125, 50, true),
            new Button(ALTFIRE, R.string.touch_altfire, 100, 245, 32, false),
            // The most used next to fire: reload and grenade; jump and crouch further out.
            new Button(RELOAD, R.string.touch_reload, 215, 62, 34, false),
            new Button(GRENADE, R.string.touch_grenade, 225, 170, 32, false),
            new Button(JUMP, R.string.touch_jump, 318, 62, 30, false),
            new Button(USE, R.string.touch_use, 215, 268, 30, false),
            new Button(CROUCH, R.string.touch_crouch, 318, 160, 28, false),
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
    private boolean inMenu = true;
    // What each finger is doing: a Button, STICK or AIM.
    private final SparseArray<Object> fingers = new SparseArray<>();
    private final SparseArray<float[]> lastPos = new SparseArray<>();
    private static final Object STICK = new Object(), AIM = new Object();
    private float stickX, stickY, knobX, knobY;  // the stick's centre and the thumb, pixels
    private boolean stickHeld;

    public TouchControls(Context c, int mode) {
        super(c);
        this.mode = mode;
        dp = c.getResources().getDisplayMetrics().density;
        inputManager = (InputManager) c.getSystemService(Context.INPUT_SERVICE);
        controller = controllerConnected();
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

        if (inMenu) {
            // A menu: the first finger is the mouse, pressed where it lands, released where it
            // lifts (so a tap clicks there).
            if (action == MotionEvent.ACTION_DOWN) {
                nativeMenuTouch(x / getWidth(), y / getHeight(), true);
            } else if (action == MotionEvent.ACTION_UP) {
                nativeMenuTouch(x / getWidth(), y / getHeight(), false);
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
                for (int i = 0; i < e.getPointerCount(); i++) fingerMove(e.getPointerId(i), e.getX(i), e.getY(i));
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
        lastPos.put(id, new float[]{x, y});
        Button hit = null;
        for (Button b : buttons) {
            float dx = x - b.x, dy = y - b.y;
            if (dx * dx + dy * dy <= (b.r * 1.15f) * (b.r * 1.15f)) { hit = b; break; }
        }
        if (hit != null) {
            fingers.put(id, hit);
            hit.pressed = true;
            nativeAction(hit.action, true);
        } else if (x < getWidth() * 0.42f && !stickHeld) {
            fingers.put(id, STICK);
            stickHeld = true;
            stickX = knobX = x;
            stickY = knobY = y;
        } else {
            fingers.put(id, AIM);
        }
    }

    private void fingerMove(int id, float x, float y) {
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
        } else if (what == AIM || (what instanceof Button && ((Button) what).aims)) {
            nativeAim((x - last[0]) / dp, (y - last[1]) / dp);
        }
        last[0] = x;
        last[1] = y;
    }

    private void fingerUp(int id) {
        Object what = fingers.get(id);
        fingers.remove(id);
        lastPos.remove(id);
        if (what == STICK) {
            stickHeld = false;
            nativeMove(0, 0);
        } else if (what instanceof Button) {
            Button b = (Button) what;
            if (fingers.indexOfValue(b) < 0) {  // no other finger still on it
                b.pressed = false;
                nativeAction(b.action, false);
            }
        }
    }

    // ---- Drawing ---------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        unit = dp * Math.min(1f, h / dp / 430f);
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
