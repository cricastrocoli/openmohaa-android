package io.github.cricastrocoli.openmohaa;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.libsdl.app.R;

// The opening screen: credits for the port and the free software it is built on, and the
// trademark notice, shown when the launcher's Play starts the game. Any button or a tap
// continues; it also moves on by itself. The launcher's settings go on to the game.
public class CreditsActivity extends Activity {
    private static final long AUTO_CONTINUE_MS = 15000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean done;
    private float uiScale = 1f;  // as in the launcher: smaller on screens under 450 x 820 dp

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        uiScale = Math.max(0.7f, Math.min(1f, Math.min(Math.min(m.widthPixels, m.heightPixels) / m.density / 450f,
                Math.max(m.widthPixels, m.heightPixels) / m.density / 820f)));
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setBackgroundColor(Color.rgb(12, 13, 11));
        int pad = dp(48);
        column.setPadding(pad, dp(28), pad, dp(28));

        column.addView(text(R.string.credits_title, 30, Color.rgb(232, 226, 205), true, 0));
        column.addView(text(R.string.credits_port, 16, Color.rgb(214, 208, 188), false, dp(14)));
        column.addView(text(R.string.credits_built_on_title, 14, Color.rgb(176, 150, 92), true, dp(26)));
        column.addView(text(R.string.credits_built_on, 13, Color.rgb(170, 166, 150), false, dp(6)));
        column.addView(text(R.string.credits_notice, 12, Color.rgb(128, 125, 114), false, dp(22)));
        TextView cont = text(R.string.credits_continue, 13, Color.rgb(232, 226, 205), true, dp(26));
        column.addView(cont);
        cont.animate().alpha(0.35f).setDuration(900).withEndAction(new Runnable() {
            @Override public void run() {
                if (done) return;
                float next = cont.getAlpha() < 0.5f ? 1f : 0.35f;
                cont.animate().alpha(next).setDuration(900).withEndAction(this);
            }
        });

        setContentView(column);
        hideSystemBars();
        column.setAlpha(0f);
        column.animate().alpha(1f).setDuration(500);
        handler.postDelayed(this::startGame, AUTO_CONTINUE_MS);
    }

    private TextView text(int id, int sp, int color, boolean bold, int topMargin) {
        TextView t = new TextView(this);
        t.setText(id);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * uiScale);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.25f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        t.setLayoutParams(lp);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density * uiScale);
    }

    private void hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void startGame() {
        if (done) return;
        done = true;
        handler.removeCallbacksAndMessages(null);
        Intent game = new Intent(this, GameActivity.class);
        if (getIntent().getExtras() != null) game.putExtras(getIntent().getExtras());
        startActivity(game);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    // Any controller or keyboard button continues (on release, so the press doesn't reach
    // the game), as does a tap.
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        startGame();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) startGame();
        return true;
    }
}
