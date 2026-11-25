package io.github.cricastrocoli.openmohaa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

// Scanlines over the game: a darker screen row every few rows, drawn by Android on top of the
// game's image (so the engine and its frame rate are untouched). It doesn't take touches.
final class ScanlineView extends View {
    // Off, light, medium, strong: the darkness (alpha of black) of the rows at the bottom of
    // each line, last row first. On a small, dense screen a thin faint row barely shows, so
    // the stronger levels are both darker and thicker.
    static final int[][] LEVELS = {{}, {130}, {170, 45}, {210, 90}};

    private final Paint paint = new Paint();

    ScanlineView(Context c, int level) {
        super(c);
        setClickable(false);
        setFocusable(false);
        int[] rows = LEVELS[Math.max(0, Math.min(LEVELS.length - 1, level))];
        // One dark row in every `period`, about 360 lines down the screen (3 rows on 1080p and
        // 960p screens, 2 on 720p), on whole screen pixels so they stay sharp.
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        ((android.app.Activity) c).getWindowManager().getDefaultDisplay().getRealMetrics(m);
        int h = Math.min(m.widthPixels, m.heightPixels);
        int period = Math.max(2, Math.round(h / 360f));
        Bitmap b = Bitmap.createBitmap(1, period, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < period; y++) {
            int i = period - 1 - y;  // rows from the bottom of the line
            b.setPixel(0, y, i < rows.length && i < period - 1 ? Color.argb(rows[i], 0, 0, 0) : 0);
        }
        paint.setShader(new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        setWillNotDraw(rows.length == 0);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }
}
