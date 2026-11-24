package io.github.cricastrocoli.openmohaa;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.libsdl.app.R;

// The launcher: game files (import), settings and controls before playing. Pages: home,
// settings, controls, files. Works with a controller (D-pad to move, A to select, B to go
// back, left/right to change a setting) and by touch.
public class LauncherActivity extends Activity {
    private static final int REQ_IMPORT = 1;
    private static final int REQ_MODS = 2;
    private static final int REQ_IMPORT_FILE = 3;

    // Colours.
    private static final int INK = Color.rgb(236, 231, 214);         // text on dark
    private static final int INK_DIM = Color.rgb(170, 165, 148);
    private static final int GOLD = Color.rgb(201, 164, 92);
    private static final int CARD = Color.argb(214, 24, 27, 22);
    private static final int CARD_FOCUS = Color.rgb(242, 238, 226);
    private static final int TEXT_ON_FOCUS = Color.rgb(28, 30, 24);
    private static final int OK = Color.rgb(120, 200, 120);
    private static final int WARN = Color.rgb(230, 170, 80);

    // The project's links, shown in the footer.
    private static final String GITHUB_URL = "https://github.com/cricastrocoli/openmohaa-android";
    private static final String YOUTUBE_URL = "https://www.youtube.com/@cricas";

    private enum Page { HOME, SETTINGS, CONTROLS, FILES, MODS }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private FrameLayout pageHolder;
    private TextView statusText, statusDot;
    private ImageView background;
    private LinearLayout hints;
    private Page page = Page.HOME;
    private int screenW, screenH;
    private View defaultFocus;  // the page's first tile, for the first key press

    // Controls page: the action waiting for a button (-1: none) and its row's value view.
    private int capturing = -1;
    private TextView captureView;
    // Files page, while importing.
    private boolean importing;
    private TextView importText;
    private ProgressBar importBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        screenW = Math.max(m.widthPixels, m.heightPixels);
        screenH = Math.min(m.widthPixels, m.heightPixels);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(14, 16, 12));
        background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        background.setAlpha(0f);
        root.addView(background, match());
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.argb(235, 10, 12, 9), Color.argb(150, 10, 12, 9), Color.argb(200, 10, 12, 9)}));
        root.addView(shade, match());

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(32), dp(22), dp(32), dp(16));
        root.addView(column, match());

        // Header: the medal and the name; the game files' status on the right.
        LinearLayout header = row();
        ImageView medal = new ImageView(this);
        medal.setImageResource(R.drawable.medal);
        header.addView(medal, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView name = text("OpenMoHAA", 24, INK, true);
        name.setPadding(dp(12), 0, 0, 0);
        header.addView(name);
        TextView nameSub = text("ANDROID", 13, GOLD, true);
        nameSub.setPadding(dp(10), dp(6), 0, 0);
        nameSub.setLetterSpacing(0.2f);
        header.addView(nameSub);
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        statusDot = text("●", 12, OK, false);
        header.addView(statusDot);
        statusText = text("", 14, INK_DIM, false);
        statusText.setPadding(dp(8), 0, 0, 0);
        header.addView(statusText);
        column.addView(header);

        pageHolder = new FrameLayout(this);
        // Pages stay inside their area (lists scroll under nothing); the padding gives a focused
        // tile's slight enlargement room to show.
        pageHolder.setPadding(dp(8), dp(6), dp(8), dp(6));
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.addView(pageHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Footer: controller hints and version.
        LinearLayout footer = row();
        hints = row();
        footer.addView(hints);
        footer.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView madeBy = text("", 13, INK_DIM, false);
        madeBy.setText(getText(R.string.made_by));
        madeBy.setPadding(0, 0, dp(14), 0);
        footer.addView(madeBy);
        footer.addView(link(R.drawable.ic_github, true, getString(R.string.link_github), GITHUB_URL));
        footer.addView(link(R.drawable.ic_youtube, false, getString(R.string.link_youtube), YOUTUBE_URL));
        TextView ver = text("v" + version(), 12, INK_DIM, false);
        ver.setPadding(dp(6), 0, 0, 0);
        footer.addView(ver);
        column.addView(footer);

        setContentView(root);
        hideSystemBars();
        showPage(Page.HOME);
        loadBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        if (!importing) refreshStatus();
    }

    // ---- Pages -----------------------------------------------------------------------------

    private void showPage(Page p) {
        page = p;
        capturing = -1;
        defaultFocus = null;
        pageHolder.removeAllViews();
        View v;
        switch (p) {
            case SETTINGS: v = settingsPage(); break;
            case CONTROLS: v = controlsPage(); break;
            case FILES: v = filesPage(); break;
            case MODS: v = modsPage(); break;
            default: v = homePage(); break;
        }
        pageHolder.addView(v, match());
        // Focus the page's first tile once it is laid out (asking earlier can be ignored).
        View first = defaultFocus;
        if (first != null) pageHolder.post(() -> {
            View f = getCurrentFocus();
            if (f == null || !isDescendant(pageHolder, f)) first.requestFocus();
        });
        refreshStatus();
        setHints(p);
    }

    private void refreshStatus() {
        boolean ready = GameData.installed(this)[0];
        statusDot.setTextColor(ready ? OK : WARN);
        statusText.setText(ready ? R.string.status_ready : R.string.status_missing);
    }

    private View homePage() {
        boolean[] have = GameData.installed(this);
        boolean ready = have[0];
        if (ready && !have[prefs.game()]) prefs.setGame(0);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_VERTICAL);

        page.addView(text(getString(ready ? R.string.home_ready : R.string.home_missing), 34, INK, true));
        TextView sub = text(ready ? summary() : getString(R.string.home_missing_sub), 15, INK_DIM, false);
        sub.setPadding(0, dp(4), 0, ready ? dp(10) : dp(22));
        page.addView(sub);
        if (ready) {
            View campaigns = campaignRow(have);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = dp(18);
            page.addView(campaigns, cp);
        }

        LinearLayout grid = row();
        grid.setGravity(Gravity.TOP);
        grid.setClipChildren(false);
        page.setClipChildren(false);
        // The big card: play, or import when the files are missing.
        View main = card(getString(ready ? R.string.play : R.string.import_files),
                ready ? getString(R.string.play_sub, gameName(prefs.game())) : getString(R.string.import_sub),
                ready ? "▶" : "⤓", true, v -> {
                    if (GameData.installed(this)[0]) play(); else showPage(Page.FILES);
                });
        grid.addView(main, weighted(dp(210), 1.05f, 0, dp(14)));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);
        LinearLayout top = row(), bottom = row();
        top.setClipChildren(false);
        bottom.setClipChildren(false);
        top.addView(card(getString(R.string.settings), getString(R.string.settings_sub), "⚙", false,
                v -> showPage(Page.SETTINGS)), weighted(dp(98), 1, 0, dp(14)));
        top.addView(card(getString(R.string.controls), getString(R.string.controls_sub), "✥", false,
                v -> showPage(Page.CONTROLS)), weighted(dp(98), 1, 0, 0));
        bottom.addView(card(getString(R.string.files), getString(R.string.files_sub), "▤", false,
                v -> showPage(Page.FILES)), weighted(dp(98), 1, 0, dp(14)));
        bottom.addView(card(getString(R.string.mods), getString(R.string.mods_sub), "✚", false,
                v -> showPage(Page.MODS)), weighted(dp(98), 1, 0, 0));
        right.addView(top);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(14);
        right.addView(bottom, bp);
        grid.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        page.addView(grid);
        defaultFocus = main;
        main.requestFocus();
        return page;
    }

    private String summary() {
        int[] r = Prefs.RESOLUTIONS[prefs.resolution()];
        String res = r[0] == 0 ? getString(R.string.res_native, screenW, screenH)
                : getString(R.string.res_size, r[0], r[1]);
        int f = Prefs.FRAME_RATES[prefs.frameRate()];
        String fps = f == 0 ? getString(R.string.fps_unlimited) : getString(R.string.fps_value, f);
        return getString(R.string.summary_short, res, fps);
    }

    // The campaign Play starts, chosen on the home page: a chip for each game, the chosen one
    // lit; a game that isn't installed is dimmed and can't be chosen. LB / RB change it too.
    private View campaignRow(boolean[] have) {
        LinearLayout r = row();
        r.setClipChildren(false);
        TextView label = text(getString(R.string.campaign), 14, INK_DIM, false);
        label.setPadding(0, 0, dp(12), 0);
        r.addView(label);
        for (int g = 0; g < 3; g++) {
            boolean on = g == prefs.game();
            String name = gameName(g);
            TextView chip = text(have[g] ? name : getString(R.string.campaign_missing, name), 14,
                    !have[g] ? Color.rgb(108, 106, 96) : on ? TEXT_ON_FOCUS : INK, on);
            chip.setPadding(dp(14), dp(5), dp(14), dp(5));
            chip.setContentDescription("campaign" + g);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(40));
            bg.setColor(on ? CARD_FOCUS : CARD);
            if (on) bg.setStroke(dp(2), GOLD);
            chip.setBackground(bg);
            if (have[g]) {
                int game = g;
                chip.setFocusable(true);
                chip.setClickable(true);
                if (Build.VERSION.SDK_INT >= 26) chip.setDefaultFocusHighlightEnabled(false);
                chip.setOnClickListener(v -> selectGame(game, "campaign" + game));
                chip.setOnFocusChangeListener((v, has) -> {
                    bg.setStroke(has ? dp(3) : on ? dp(2) : 0, has && on ? Color.rgb(140, 100, 30) : GOLD);
                    v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120);
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            r.addView(chip, lp);
        }
        return r;
    }

    // Picks the campaign and redraws the home page, keeping the focus where it was.
    private void selectGame(int g, String focusDesc) {
        prefs.setGame(g);
        showPage(Page.HOME);
        if (focusDesc != null) {
            View v = findCard(pageHolder, focusDesc);
            if (v != null) v.requestFocus();
        }
    }

    // LB / RB on the home page: the previous / next installed campaign.
    private void stepGame(int step) {
        boolean[] h = GameData.installed(this);
        if (!h[0]) return;
        int g = prefs.game();
        for (int i = 1; i <= 3; i++) {
            int n = ((g + step * i) % 3 + 3) % 3;
            if (h[n]) { g = n; break; }
        }
        if (g == prefs.game()) return;
        View f = getCurrentFocus();
        CharSequence d = f != null ? f.getContentDescription() : null;
        if (d != null && d.toString().startsWith("campaign")) d = "campaign" + g;
        selectGame(g, d != null ? d.toString() : null);
    }

    private String gameName(int g) {
        return getString(g == 1 ? R.string.game_sh : g == 2 ? R.string.game_bt : R.string.game_aa);
    }

    // ---- Settings --------------------------------------------------------------------------

    private interface Choice {
        String label();
        void change(int step);
    }

    private View settingsPage() {
        LinearLayout list = pageColumn(R.string.settings);
        // Which game Play starts: Allied Assault, or an installed expansion.
        View first = option(list, R.string.game, R.string.setting_game_desc, new Choice() {
            public String label() { return gameName(prefs.game()); }
            public void change(int step) {
                boolean[] h = GameData.installed(LauncherActivity.this);
                int g = prefs.game();
                for (int i = 1; i <= 3; i++) {
                    int n = ((g + step * i) % 3 + 3) % 3;
                    if (h[n]) { g = n; break; }
                }
                prefs.setGame(g);
            }
        });
        option(list, R.string.setting_resolution, R.string.setting_resolution_desc, new Choice() {
            public String label() {
                int[] r = Prefs.RESOLUTIONS[prefs.resolution()];
                return r[0] == 0 ? getString(R.string.res_native, screenW, screenH) : getString(R.string.res_size, r[0], r[1]);
            }
            public void change(int s) { prefs.setResolution(wrap(prefs.resolution() + s, Prefs.RESOLUTIONS.length)); }
        });
        option(list, R.string.setting_fps, R.string.setting_fps_desc, new Choice() {
            public String label() {
                int f = Prefs.FRAME_RATES[prefs.frameRate()];
                return f == 0 ? getString(R.string.fps_unlimited) : getString(R.string.fps_value, f);
            }
            public void change(int s) { prefs.setFrameRate(wrap(prefs.frameRate() + s, Prefs.FRAME_RATES.length)); }
        });
        option(list, R.string.setting_aim, R.string.setting_aim_desc, new Choice() {
            public String label() { return Prefs.AIM_SPEEDS[prefs.aimSpeed()] + "%"; }
            public void change(int s) { prefs.setAimSpeed(Math.max(0, Math.min(Prefs.AIM_SPEEDS.length - 1, prefs.aimSpeed() + s))); }
        });
        option(list, R.string.setting_invert, 0, new Choice() {
            public String label() { return getString(prefs.invertAim() ? R.string.on : R.string.off); }
            public void change(int s) { prefs.setInvertAim(!prefs.invertAim()); }
        });
        option(list, R.string.setting_garand, R.string.setting_garand_desc, new Choice() {
            public String label() { return getString(prefs.garandReload() ? R.string.on : R.string.off); }
            public void change(int s) { prefs.setGarandReload(!prefs.garandReload()); }
        });
        defaultFocus = first;
        first.requestFocus();
        return scroll(list);
    }

    private View option(LinearLayout list, int label, int desc, Choice c) {
        LinearLayout r = row();
        r.setPadding(dp(18), dp(12), dp(18), dp(12));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(getString(label), 17, INK, true);
        texts.addView(l);
        TextView d = desc != 0 ? text(getString(desc), 13, INK_DIM, false) : null;
        if (d != null) texts.addView(d);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView value = text("◀  " + c.label() + "  ▶", 17, GOLD, true);
        r.addView(value);
        focusable(r, new TextView[]{l, d, value}, v -> { c.change(1); value.setText("◀  " + c.label() + "  ▶"); });
        r.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            int step = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            if (step == 0) return false;
            c.change(step);
            value.setText("◀  " + c.label() + "  ▶");
            return true;
        });
        list.addView(r, listItem());
        return r;
    }

    // ---- Controls --------------------------------------------------------------------------

    private static final int[] ACTION_LABELS = {R.string.act_fire, R.string.act_altfire, R.string.act_jump,
            R.string.act_use, R.string.act_reload, R.string.act_item, R.string.act_prevweapon,
            R.string.act_nextweapon, R.string.act_crouch, R.string.act_walk, R.string.act_grenade,
            R.string.act_holster, R.string.act_leanleft, R.string.act_leanright, R.string.act_menu,
            R.string.act_objectives};

    private View controlsPage() {
        LinearLayout list = pageColumn(R.string.controls);
        TextView desc = text(getString(R.string.controls_desc), 14, INK_DIM, false);
        desc.setPadding(dp(4), 0, 0, dp(10));
        list.addView(desc);
        View first = null;
        for (int i = 0; i < Prefs.ACTIONS.length; i++) {
            final int action = i;
            LinearLayout r = row();
            r.setPadding(dp(18), dp(9), dp(18), dp(9));
            TextView l = text(getString(ACTION_LABELS[i]), 16, INK, false);
            r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView value = text(buttonLabel(prefs.button(i)), 16, GOLD, true);
            r.addView(value);
            focusable(r, new TextView[]{l, value}, v -> {
                capturing = action;
                captureView = value;
                value.setText(getString(R.string.controls_press, getString(ACTION_LABELS[action])));
            });
            list.addView(r, listItem());
            if (first == null) first = r;
        }
        LinearLayout reset = row();
        reset.setPadding(dp(18), dp(12), dp(18), dp(12));
        TextView rl = text(getString(R.string.controls_reset), 16, GOLD, true);
        reset.addView(rl);
        focusable(reset, new TextView[]{rl}, v -> { prefs.resetButtons(); showPage(Page.CONTROLS); });
        list.addView(reset, listItem());
        TextView combo = text(getString(R.string.controls_combo), 13, INK_DIM, false);
        combo.setPadding(dp(4), dp(10), 0, 0);
        list.addView(combo);
        defaultFocus = first;
        if (first != null) first.requestFocus();
        return scroll(list);
    }

    private static String buttonLabel(String b) {
        switch (b) {
            case "UP": return "D-pad ↑";
            case "DOWN": return "D-pad ↓";
            case "LEFT": return "D-pad ←";
            case "RIGHT": return "D-pad →";
            case "BACK": return "Select";
            case "START": return "Start";
            default: return b;
        }
    }

    // The button an Android key event is, by the names android_input.cpp uses (null: none).
    private static String buttonFor(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "LB";
            case KeyEvent.KEYCODE_BUTTON_R1: return "RB";
            case KeyEvent.KEYCODE_BUTTON_L2: return "LT";
            case KeyEvent.KEYCODE_BUTTON_R2: return "RT";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            case KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "BACK";
            default: return null;
        }
    }

    private void finishCapture(String button) {
        int action = capturing;
        capturing = -1;
        if (button != null) prefs.setButton(action, button);
        // Rebuild so a swapped action shows its new button too; keep the focus on the row.
        showPage(Page.CONTROLS);
        ViewGroup list = (ViewGroup) ((ScrollView) pageHolder.getChildAt(0)).getChildAt(0);
        View row = list.getChildAt(2 + action);  // title, description, then the actions
        if (row != null) row.requestFocus();
    }

    // ---- Files -----------------------------------------------------------------------------

    private View filesPage() {
        LinearLayout list = pageColumn(R.string.files);
        TextView desc = text(getString(R.string.files_desc), 14, INK_DIM, false);
        desc.setPadding(dp(4), 0, 0, dp(12));
        list.addView(desc);
        boolean[] have = GameData.installed(this);
        int[] names = {R.string.game_aa, R.string.game_sh, R.string.game_bt};
        for (int i = 0; i < 3; i++) {
            LinearLayout r = row();
            r.setPadding(dp(18), dp(8), dp(18), dp(8));
            r.addView(text(getString(names[i]), 16, INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            r.addView(text((have[i] ? "✓  " : "✗  ") + getString(have[i] ? R.string.files_installed : R.string.files_not_installed),
                    15, have[i] ? OK : INK_DIM, false));
            list.addView(r);
        }
        TextView storage = text(getString(R.string.files_storage,
                Formatter.formatFileSize(this, GameData.sizeOnDisk(GameData.gameDir(this)))), 14, INK_DIM, false);
        storage.setPadding(dp(18), dp(4), 0, dp(12));
        list.addView(storage);

        // From files first: the GOG installer, the simplest way.
        LinearLayout fileRow = row();
        fileRow.setPadding(dp(18), dp(14), dp(18), dp(14));
        TextView fl = text("⤓  " + getString(R.string.files_import_zip), 17, GOLD, true);
        fileRow.addView(fl);
        focusable(fileRow, new TextView[]{fl}, v -> { if (!importing) pickImportFile(); });
        list.addView(fileRow, listItem());

        LinearLayout importRow = row();
        importRow.setPadding(dp(18), dp(14), dp(18), dp(14));
        TextView il = text("⤓  " + getString(R.string.files_import), 17, GOLD, true);
        importRow.addView(il);
        focusable(importRow, new TextView[]{il}, v -> { if (!importing) pickImportFolder(); });
        list.addView(importRow, listItem());

        importBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        importBar.setMax(1000);
        importBar.setVisibility(importing ? View.VISIBLE : View.GONE);
        list.addView(importBar);
        importText = text("", 14, INK_DIM, false);
        importText.setPadding(dp(4), dp(6), 0, 0);
        list.addView(importText);
        defaultFocus = fileRow;
        fileRow.requestFocus();
        return scroll(list);
    }

    private void pickImportFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(i, REQ_IMPORT);
    }

    private void pickImportFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);  // an installer's .exe and .bin parts
        startActivityForResult(i, REQ_IMPORT_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MODS && resultCode == RESULT_OK && data != null) {
            importModsResult(data);
            return;
        }
        if ((requestCode != REQ_IMPORT && requestCode != REQ_IMPORT_FILE) || resultCode != RESULT_OK
                || data == null) return;
        java.util.List<Uri> picked = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) picked.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (picked.isEmpty()) return;
        boolean fromFile = requestCode == REQ_IMPORT_FILE;
        importing = true;
        // An import can take a few minutes: the screen stays on meanwhile.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showPage(Page.FILES);
        importBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            GameData.Progress progress = (done, total, file) -> ui.post(() -> {
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (importBar != null) importBar.setProgress(total > 0 ? (int) (done * 1000 / total) : 0);
                if (importText != null) importText.setText(getString(R.string.files_importing, pct) + "\n" + file);
            });
            GameData.ImportResult r = fromFile ? GameData.importFiles(this, picked, progress)
                    : GameData.importFrom(this, picked.get(0), progress);
            ui.post(() -> {
                importing = false;
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                showPage(Page.FILES);
                importText.setText(r.error == null ? getString(R.string.files_done)
                        : r.error.equals("nofiles") ? getString(fromFile ? R.string.files_none_file : R.string.files_none)
                        : r.error.equals("unsupported") ? getString(R.string.files_unsupported)
                        : r.error.equals("noexe") ? getString(R.string.files_gog_noexe)
                        : r.error.startsWith("missing:") ? getString(R.string.files_gog_missing)
                        : r.error.startsWith("nospace:") ? getString(R.string.files_nospace,
                                Formatter.formatFileSize(this, Long.parseLong(r.error.substring(8))))
                        : getString(R.string.files_error, r.error));
                loadBackground();
            });
        }, "import").start();
    }

    // ---- Mods ------------------------------------------------------------------------------

    private int modsGame = -1;  // the game whose mods are shown (and imported to)

    private View modsPage() {
        boolean[] have = GameData.installed(this);
        if (modsGame < 0 || !have[modsGame]) modsGame = have[prefs.game()] ? prefs.game() : 0;
        LinearLayout list = pageColumn(R.string.mods);
        TextView desc = text(getString(R.string.mods_desc), 14, INK_DIM, false);
        desc.setPadding(dp(4), 0, 0, dp(12));
        list.addView(desc);

        // Which game's mods: left/right to change.
        LinearLayout gameRow = row();
        gameRow.setPadding(dp(18), dp(12), dp(18), dp(12));
        TextView gl = text(getString(R.string.game), 17, INK, true);
        gameRow.addView(gl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView gv = text("◀  " + gameName(modsGame) + "  ▶", 17, GOLD, true);
        gameRow.addView(gv);
        Runnable nextGame = () -> {
            for (int i = 1; i <= 3; i++) if (have[(modsGame + i) % 3]) { modsGame = (modsGame + i) % 3; break; }
            showPage(Page.MODS);
        };
        focusable(gameRow, new TextView[]{gl, gv}, v -> nextGame.run());
        gameRow.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code != KeyEvent.KEYCODE_DPAD_LEFT && code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            nextGame.run();
            return true;
        });
        list.addView(gameRow, listItem());

        LinearLayout importRow = row();
        importRow.setPadding(dp(18), dp(12), dp(18), dp(12));
        TextView il = text("✚  " + getString(R.string.mods_import), 17, GOLD, true);
        importRow.addView(il);
        focusable(importRow, new TextView[]{il}, v -> pickMods());
        list.addView(importRow, listItem());

        java.util.List<java.io.File> mods = GameData.mods(this, modsGame);
        if (mods.isEmpty()) {
            TextView none = text(getString(R.string.mods_none), 15, INK_DIM, false);
            none.setPadding(dp(22), dp(6), 0, dp(6));
            list.addView(none);
        }
        for (java.io.File f : mods) {
            LinearLayout r = row();
            r.setPadding(dp(18), dp(10), dp(18), dp(10));
            r.setTag(f);
            TextView n = text(GameData.modName(f), 16, INK, false);
            r.addView(n, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            boolean on = GameData.modOn(f);
            TextView state = text(getString(on ? R.string.on : R.string.off), 16, on ? OK : INK_DIM, true);
            r.addView(state);
            focusable(r, new TextView[]{n, state}, v -> {
                GameData.setModOn(f, !GameData.modOn(f));
                focusModAfterRebuild(GameData.modName(f));
            });
            r.setOnLongClickListener(v -> { confirmRemoveMod(f); return true; });
            list.addView(r, listItem());
        }
        TextView note = text(getString(R.string.mods_note), 13, INK_DIM, false);
        note.setPadding(dp(4), dp(10), 0, 0);
        list.addView(note);
        if (modsStatus != null) {
            TextView st = text(modsStatus, 14, GOLD, false);
            st.setPadding(dp(4), dp(8), 0, 0);
            list.addView(st);
            modsStatus = null;
        }
        defaultFocus = gameRow;
        return scroll(list);
    }

    private String modsStatus;  // shown once on the next Mods page

    private void focusModAfterRebuild(String name) {
        showPage(Page.MODS);
        View row = findModRow((ViewGroup) pageHolder.getChildAt(0), name);
        if (row != null) row.requestFocus();
    }

    private View findModRow(ViewGroup g, String name) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getTag() instanceof java.io.File && GameData.modName((java.io.File) c.getTag()).equals(name)) return c;
            if (c instanceof ViewGroup) {
                View r = findModRow((ViewGroup) c, name);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void confirmRemoveMod(java.io.File f) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.mods_remove_title)
                .setMessage(getString(R.string.mods_remove_message, GameData.modName(f)))
                .setPositiveButton(R.string.mods_remove, (d, w) -> {
                    if (!f.delete()) modsStatus = getString(R.string.mods_error, f.getName());
                    showPage(Page.MODS);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickMods() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_MODS);
    }

    private void importModsResult(Intent data) {
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        int game = modsGame;
        new Thread(() -> {
            String msg;
            try {
                int n = GameData.importMods(this, uris, game);
                msg = n < 0 ? getString(R.string.mods_none_imported) : getString(R.string.mods_imported, n);
            } catch (Exception e) {
                msg = getString(R.string.mods_error, e.getMessage() != null ? e.getMessage() : e.toString());
            }
            String m = msg;
            ui.post(() -> { modsStatus = m; showPage(Page.MODS); });
        }, "mods").start();
    }

    // ---- Play ------------------------------------------------------------------------------

    private void play() {
        Intent i = new Intent(this, CreditsActivity.class);
        i.putExtra(GameActivity.EXTRA_ARGS, prefs.gameArguments());
        int[] r = Prefs.RESOLUTIONS[prefs.resolution()];
        i.putExtra(GameActivity.EXTRA_WIDTH, r[0]);
        i.putExtra(GameActivity.EXTRA_HEIGHT, r[1]);
        i.putExtra(GameActivity.EXTRA_FPS, Prefs.FRAME_RATES[prefs.frameRate()]);
        startActivity(i);
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ---- Input -----------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (capturing >= 0) {
            // Waiting for a button for an action: Android's Back cancels, anything else is it.
            if (e.getAction() == KeyEvent.ACTION_UP) {
                if (code == KeyEvent.KEYCODE_BACK) finishCapture(null);
                else { String b = buttonFor(code); if (b != null) finishCapture(b); }
            }
            return true;
        }
        // Opened by touch, nothing is focused yet: the first D-pad or A press lights up the
        // page's first tile instead of doing anything else.
        if (getCurrentFocus() == null && defaultFocus != null && e.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_BUTTON_A || (code >= KeyEvent.KEYCODE_DPAD_UP && code <= KeyEvent.KEYCODE_DPAD_RIGHT))) {
            defaultFocus.setFocusableInTouchMode(true);
            defaultFocus.requestFocus();
            defaultFocus.setFocusableInTouchMode(false);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_A) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                View f = getCurrentFocus();
                if (f != null) f.performClick();
            }
            return true;
        }
        if ((code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) && page == Page.HOME) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0)
                stepGame(code == KeyEvent.KEYCODE_BUTTON_L1 ? -1 : 1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_Y && page == Page.MODS) {
            if (e.getAction() == KeyEvent.ACTION_UP) {
                View f = getCurrentFocus();
                if (f != null && f.getTag() instanceof java.io.File) confirmRemoveMod((java.io.File) f.getTag());
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) goBack();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // Triggers are axes on most controllers: while waiting for a button they count as LT / RT.
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (capturing >= 0 && (e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0) {
            float lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
            float rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
            if (lt > 0.6f) { finishCapture("LT"); return true; }
            if (rt > 0.6f) { finishCapture("RT"); return true; }
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void goBack() {
        if (importing) return;
        if (page != Page.HOME) {
            Page from = page;
            showPage(Page.HOME);
            // Back on the card that led there.
            ViewGroup home = (ViewGroup) pageHolder.getChildAt(0);
            View card = findCard(home, from == Page.SETTINGS ? getString(R.string.settings)
                    : from == Page.CONTROLS ? getString(R.string.controls)
                    : from == Page.MODS ? getString(R.string.mods) : getString(R.string.files));
            if (card != null) card.requestFocus();
        } else {
            finish();
        }
    }

    private static boolean isDescendant(ViewGroup g, View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == g) return true;
        }
        return false;
    }

    private View findCard(ViewGroup g, String title) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (title.equals(c.getContentDescription())) return c;
            if (c instanceof ViewGroup) {
                View r = findCard((ViewGroup) c, title);
                if (r != null) return r;
            }
        }
        return null;
    }

    private void setHints(Page p) {
        hints.removeAllViews();
        hint("✥", R.string.hint_move);
        hint("A", p == Page.MODS ? R.string.hint_toggle : R.string.hint_select);
        if (p == Page.SETTINGS) hint("◀▶", R.string.hint_change);
        if (p == Page.MODS) hint("Y", R.string.hint_remove);
        if (p == Page.HOME && GameData.installed(this)[0]) hint("LB RB", R.string.hint_campaign);
        hint("B", R.string.hint_back);
    }

    private void hint(String glyph, int label) {
        TextView g = text(glyph, 13, GOLD, true);
        g.setPadding(0, 0, dp(6), 0);
        hints.addView(g);
        TextView l = text(getString(label), 13, INK_DIM, false);
        l.setPadding(0, 0, dp(22), 0);
        hints.addView(l);
    }

    // ---- Background ------------------------------------------------------------------------

    private void loadBackground() {
        new Thread(() -> {
            Bitmap b = GameData.briefingPicture(this);
            if (b == null) return;
            ui.post(() -> {
                background.setImageBitmap(b);
                if (Build.VERSION.SDK_INT >= 31)
                    background.setRenderEffect(RenderEffect.createBlurEffect(dp(3), dp(3), Shader.TileMode.CLAMP));
                background.animate().alpha(0.85f).setDuration(700);
            });
        }, "background").start();
    }

    // ---- Building blocks -------------------------------------------------------------------

    // A card on the home page: the big one has its icon above, small ones beside the text.
    private View card(String title, String sub, String icon, boolean big, View.OnClickListener click) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(big ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        c.setGravity(big ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL);
        c.setPadding(dp(22), dp(14), dp(18), dp(16));
        c.setContentDescription(title);
        TextView i = text(icon, big ? 34 : 24, GOLD, false);
        TextView t = text(title, big ? (title.length() > 12 ? 26 : 32) : 19, INK, true);
        TextView s = text(sub, big ? 15 : 13, INK_DIM, false);
        if (big) t.setMaxLines(2); else t.setSingleLine(true);
        s.setMaxLines(2);
        if (big) {
            c.addView(i);
            c.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
            c.addView(t);
            c.addView(s);
        } else {
            i.setMinWidth(dp(34));
            c.addView(i);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(14), 0, 0, 0);
            texts.addView(t);
            texts.addView(s);
            c.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        focusable(c, new TextView[]{t, s, i}, click);
        return c;
    }

    // A small footer button with an icon that opens a web page. A white icon (tint) takes the
    // text colour, so it stays visible when the button lights up.
    private View link(int icon, boolean tint, String label, String url) {
        LinearLayout b = row();
        b.setPadding(dp(10), dp(5), dp(12), dp(5));
        b.setContentDescription(label);
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        if (tint) i.setColorFilter(INK);
        b.addView(i, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(16)));
        i.setAdjustViewBounds(true);
        TextView t = text(label, 13, INK, true);
        t.setPadding(dp(7), 0, 0, 0);
        b.addView(t);
        focusable(b, new TextView[]{t}, v -> openLink(url));
        View.OnFocusChangeListener inner = b.getOnFocusChangeListener();
        b.setOnFocusChangeListener((v, has) -> {
            inner.onFocusChange(v, has);
            if (tint) i.setColorFilter(has ? TEXT_ON_FOCUS : INK);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, getString(R.string.link_failed, url), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // Makes a view a focusable, clickable tile that lights up when focused.
    private void focusable(View v, TextView[] texts, View.OnClickListener click) {
        v.setFocusable(true);
        v.setClickable(true);
        if (Build.VERSION.SDK_INT >= 26) v.setDefaultFocusHighlightEnabled(false);  // ours instead
        v.setOnClickListener(click);
        int[] colours = new int[texts.length];
        for (int i = 0; i < texts.length; i++) if (texts[i] != null) colours[i] = texts[i].getCurrentTextColor();
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(CARD);
        v.setBackground(bg);
        v.setOnFocusChangeListener((view, has) -> {
            bg.setColor(has ? CARD_FOCUS : CARD);
            bg.setStroke(has ? dp(3) : 0, GOLD);
            for (int i = 0; i < texts.length; i++) {
                if (texts[i] == null) continue;
                texts[i].setTextColor(has ? (colours[i] == GOLD ? Color.rgb(140, 100, 30) : TEXT_ON_FOCUS) : colours[i]);
            }
            view.animate().scaleX(has ? 1.02f : 1f).scaleY(has ? 1.02f : 1f).setDuration(120);
        });
    }

    private LinearLayout pageColumn(int title) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(14), dp(6), dp(10));
        TextView t = text(getString(title), 28, INK, true);
        t.setPadding(dp(4), 0, 0, dp(8));
        list.addView(t);
        return list;
    }

    private ScrollView scroll(View content) {
        ScrollView s = new ScrollView(this);
        s.setVerticalScrollBarEnabled(false);
        // Lists scroll inside their page (not over the footer); a little room keeps a focused
        // row's highlight clear of the edges.
        s.setClipChildren(true);
        s.addView(content);
        return s;
    }

    private LinearLayout.LayoutParams listItem() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        lp.leftMargin = dp(20);
        lp.rightMargin = dp(24);
        return lp;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private TextView text(String s, int sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        return t;
    }

    private LinearLayout.LayoutParams weighted(int height, float weight, int top, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, weight);
        lp.topMargin = top;
        lp.rightMargin = right;
        return lp;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int wrap(int v, int n) {
        return ((v % n) + n) % n;
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }
}
