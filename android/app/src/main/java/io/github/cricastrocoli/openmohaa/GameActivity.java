package io.github.cricastrocoli.openmohaa;

import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

// The game itself: SDL's activity, given the launcher's settings as engine arguments. It runs
// in its own process (":game", see the manifest) so the launcher survives it: when the game
// quits, that process ends and the launcher is back.
public class GameActivity extends SDLActivity {
    public static final String EXTRA_ARGS = "args";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
