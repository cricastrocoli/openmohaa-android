package io.github.cricastrocoli.openmohaa;

import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

// The game itself: SDL's activity, given the launcher's settings as engine arguments. It runs
// in its own process (":game", see the manifest) so the launcher survives it: when the game
// quits, that process ends and the launcher is back.
public class GameActivity extends SDLActivity {
    public static final String EXTRA_ARGS = "args";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_FPS = "fps";  // the frame rate cap; 0 = unlimited

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestRefreshRate();
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
