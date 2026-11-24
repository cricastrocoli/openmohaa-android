package io.github.cricastrocoli.openmohaa;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Toast;

import org.libsdl.app.R;
import org.libsdl.app.SDLActivity;

// The game itself: SDL's activity, given the launcher's settings as engine arguments. It runs
// in its own process (":game", see the manifest) so the launcher survives it: when the game
// quits, that process ends and the launcher is back.
public class GameActivity extends SDLActivity {
    public static final String EXTRA_ARGS = "args";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_FPS = "fps";  // the frame rate cap; 0 = unlimited
    public static final String EXTRA_TOUCH = "touch";  // TouchControls.AUTO / ALWAYS / NEVER

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestRefreshRate();
        // Touch controls and menu taps, over the game (see TouchControls).
        if (mLayout != null) {
            mLayout.addView(new TouchControls(this, getIntent().getIntExtra(EXTRA_TOUCH, TouchControls.AUTO)),
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // A lower render resolution: the GPU draws fewer pixels and the display scales them up.
        int w = getIntent().getIntExtra(EXTRA_WIDTH, 0), h = getIntent().getIntExtra(EXTRA_HEIGHT, 0);
        if (w > 0 && h > 0 && mSurface != null) {
            mSurface.getHolder().setFixedSize(w, h);
            // The view must still fill the screen (SDL adds it wrapping its content, which with
            // a fixed size would shrink it to w x h in a corner); the buffer is scaled up to it.
            ViewGroup.LayoutParams lp = mSurface.getLayoutParams();
            if (lp != null) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                mSurface.setLayoutParams(lp);
            }
        }
    }

    // Above 60 FPS, ask Android for the screen's fastest refresh rate at its current resolution:
    // many phones keep games at 60 Hz unless the app asks, and the extra frames would never
    // show. Up to 60 FPS, the system's own choice stands (it saves battery).
    private void requestRefreshRate() {
        int fps = getIntent().getIntExtra(EXTRA_FPS, 60);
        if (fps > 0 && fps <= 60) return;
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode(), best = current;
        for (Display.Mode m : display.getSupportedModes()) {
            Log.v("MOHAA", "display supports " + m.getModeId() + ": " + m.getPhysicalWidth() + "x"
                    + m.getPhysicalHeight() + " @ " + m.getRefreshRate() + " Hz"
                    + (Build.VERSION.SDK_INT >= 31 ? " alt " + java.util.Arrays.toString(m.getAlternativeRefreshRates()) : ""));
            if (m.getPhysicalWidth() == current.getPhysicalWidth()
                    && m.getPhysicalHeight() == current.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate()) {
                best = m;
            }
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(lp);
        Log.v("MOHAA", "display mode: " + best.getPhysicalWidth() + "x" + best.getPhysicalHeight()
                + " @ " + best.getRefreshRate() + " Hz (frame rate cap " + fps + ")");

        // The request loses to a limit the user set (a 60 Hz mode in the system settings or quick
        // settings, power saving): then say so, or the higher frame rate seems not to work.
        // Such a limit either hides the faster modes, leaving them only among the 60 Hz mode's
        // alternative rates (Samsung), or keeps them listed but unused (Retroid): both count.
        boolean faster = best.getRefreshRate() > 61f;
        if (!faster && Build.VERSION.SDK_INT >= 31) {
            for (float r : best.getAlternativeRefreshRates()) if (r > 61f) faster = true;
        }
        if (faster) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (isFinishing()) return;
                // The display mode in use, not the momentary rate: adaptive screens drop below
                // 60 Hz while nothing moves (a loading screen) even when 120 Hz is allowed.
                float now = getWindowManager().getDefaultDisplay().getMode().getRefreshRate();
                Log.v("MOHAA", "display mode now " + now + " Hz");
                if (now < 61f) Toast.makeText(this, R.string.refresh_capped, Toast.LENGTH_LONG).show();
            }, 4000);
        }
    }

    @Override
    protected String[] getArguments() {
        String[] a = getIntent().getStringArrayExtra(EXTRA_ARGS);
        if (a == null) a = new String[0];
        Log.v("MOHAA", "arguments: " + String.join(" ", a));
        return a;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // SDL can't start the game a second time in the same process: end it.
        System.exit(0);
    }
}
