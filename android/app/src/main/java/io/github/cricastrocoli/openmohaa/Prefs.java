package io.github.cricastrocoli.openmohaa;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

// The launcher's settings, kept in SharedPreferences, and the engine arguments they become.
public final class Prefs {
    // Render sizes: 0 = the screen's own (native). Others are 16:9.
    public static final int[][] RESOLUTIONS = {{0, 0}, {1600, 900}, {1280, 720}, {960, 540}};
    // Frame rate caps: 0 = unlimited.
    public static final int[] FRAME_RATES = {30, 60, 90, 120, 0};
    public static final int[] AIM_SPEEDS = {50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150};

    // The game's actions, in the order the Controls page lists them, with their default buttons
    // (the names android_input.cpp understands in rp_map).
    public static final String[] ACTIONS = {"fire", "altfire", "jump", "use", "reload", "item",
            "prevweapon", "nextweapon", "crouch", "walk", "grenade", "holster", "leanleft",
            "leanright", "menu", "objectives"};
    public static final String[] DEFAULT_BUTTONS = {"RT", "LT", "A", "B", "X", "Y",
            "LB", "RB", "L3", "R3", "UP", "DOWN", "LEFT", "RIGHT", "START", "BACK"};

    private final SharedPreferences p;

    public Prefs(Context c) {
        p = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    public int resolution() { return clamp(p.getInt("resolution", 0), RESOLUTIONS.length); }
    public void setResolution(int i) { p.edit().putInt("resolution", i).apply(); }

    public int frameRate() { return clamp(p.getInt("frameRate", 1), FRAME_RATES.length); }  // 60
    public void setFrameRate(int i) { p.edit().putInt("frameRate", i).apply(); }

    public int aimSpeed() { return clamp(p.getInt("aimSpeed", 5), AIM_SPEEDS.length); }     // 100%
    public void setAimSpeed(int i) { p.edit().putInt("aimSpeed", i).apply(); }

    // The M1 Garand can be reloaded before its clip is empty (single player).
    public boolean aimAssist() { return p.getBoolean("aimAssist", false); }
    public void setAimAssist(boolean b) { p.edit().putBoolean("aimAssist", b).apply(); }
    public boolean garandReload() { return p.getBoolean("garandReload", false); }
    public void setGarandReload(boolean b) { p.edit().putBoolean("garandReload", b).apply(); }

    // Touch controls: TouchControls.AUTO (only without a controller), ALWAYS or NEVER.
    public int scanlines() { return clamp(p.getInt("scanlines", 0), ScanlineView.LEVELS.length); }
    public void setScanlines(int v) { p.edit().putInt("scanlines", v).apply(); }
    public int touchMode() { return clamp(p.getInt("touchMode", TouchControls.AUTO), 3); }
    public void setTouchMode(int m) { p.edit().putInt("touchMode", m).apply(); }

    public boolean invertAim() { return p.getBoolean("invertAim", false); }
    public void setInvertAim(boolean b) { p.edit().putBoolean("invertAim", b).apply(); }

    // 0 Allied Assault, 1 Spearhead, 2 Breakthrough.
    public int game() { return clamp(p.getInt("game", 0), 3); }
    public void setGame(int g) { p.edit().putInt("game", g).apply(); }

    public String button(int action) {
        return p.getString("btn_" + ACTIONS[action], DEFAULT_BUTTONS[action]);
    }

    // Puts a button on an action; the action that had it takes this one's old button (a swap),
    // so every action keeps a button.
    public void setButton(int action, String button) {
        String old = button(action);
        SharedPreferences.Editor e = p.edit();
        for (int i = 0; i < ACTIONS.length; i++) {
            if (i != action && button(i).equals(button)) e.putString("btn_" + ACTIONS[i], old);
        }
        e.putString("btn_" + ACTIONS[action], button).apply();
    }

    public void resetButtons() {
        SharedPreferences.Editor e = p.edit();
        for (String a : ACTIONS) e.remove("btn_" + a);
        e.apply();
    }

    // The render size for a resolution choice: the screen's own for "native".
    public static int[] size(int choice, int screenW, int screenH) {
        int[] r = RESOLUTIONS[choice];
        return r[0] == 0 ? new int[]{screenW, screenH} : r;
    }

    // Graphics presets: 0 custom (the game's own settings, as set in its menus), then low,
    // medium, high (the game's defaults) and very high. Each sets the same cvars, so moving
    // between presets leaves nothing behind. The resolution is a setting of its own.
    public static final String[] GRAPHICS_CVARS = {
            "r_picmip", "r_textureMode", "r_ext_texture_filter_anisotropic", "r_ext_max_anisotropy",
            "r_ext_multisample", "r_lodscale", "r_subdivisions", "cg_shadows", "r_flares", "r_drawSun",
            "cg_effectdetail", "cg_marks_max", "vss_maxcount"};
    public static final String[][] GRAPHICS = {
            null,
            {"1", "GL_LINEAR_MIPMAP_NEAREST", "0", "2", "0", "0.6", "8", "0", "0", "0", "0.4", "64", "4"},
            {"0", "GL_LINEAR_MIPMAP_LINEAR", "0", "2", "0", "0.9", "4", "1", "0", "0", "0.7", "128", "7"},
            {"0", "GL_LINEAR_MIPMAP_LINEAR", "0", "2", "0", "1.1", "3", "1", "0", "0", "1.0", "256", "10"},
            // (No multisample antialiasing: requested through gl4es on Android it showed no
            // difference on a Retroid Pocket Nova, only a cost.)
            {"0", "GL_LINEAR_MIPMAP_LINEAR", "1", "8", "0", "1.6", "2", "1", "1", "1", "1.0", "512", "16"},
    };

    public static final int[] HUD_SIZES = {100, 125, 150};
    public int hudSize() { return clamp(p.getInt("hudSize", 1), HUD_SIZES.length); }  // 125%
    public void setHudSize(int v) { p.edit().putInt("hudSize", v).apply(); }

    // 0 widescreen, 1 menus and videos in 4:3 (play in widescreen), 2 everything in 4:3.
    public int aspect() { return clamp(p.getInt("aspect", 0), 3); }
    public void setAspect(int v) { p.edit().putInt("aspect", v).apply(); }

    public int graphics() { return clamp(p.getInt("graphics", 0), GRAPHICS.length); }
    public void setGraphics(int g) { p.edit().putInt("graphics", g).apply(); }

    // Engine arguments ("+set name value" ...).
    // The game's render size: the resolution setting (the screen's own for "native"), made
    // 4:3 by the aspect option on a wider screen ({0, 0} = the screen's own, nothing to set).
    public int[] renderSize(int screenW, int screenH) {
        int[] r = RESOLUTIONS[resolution()];
        int w = r[0] != 0 ? r[0] : screenW, h = r[0] != 0 ? r[1] : screenH;
        boolean four3 = aspect() == 2 && w * 3 > h * 4;
        if (four3) w = (h * 4 / 3) & ~1;
        return r[0] == 0 && !four3 ? new int[]{0, 0} : new int[]{w, h};
    }

    public String[] gameArguments(int screenW, int screenH) {
        List<String> a = new ArrayList<>();
        int[] r = renderSize(screenW, screenH);
        if (r[0] != 0) {
            // The engine must be told the size too, or it renders the whole screen's width
            // into the (narrower) 4:3 picture, cut off at the right.
            set(a, "r_mode", "-1");
            set(a, "r_customwidth", Integer.toString(r[0]));
            set(a, "r_customheight", Integer.toString(r[1]));
        }
        String[] g = GRAPHICS[graphics()];
        if (g != null) {
            for (int i = 0; i < g.length; i++) set(a, GRAPHICS_CVARS[i], g[i]);
        }
        set(a, "rp_menu43", aspect() == 1 ? "1" : "0");
        set(a, "rp_hudscale", Float.toString(HUD_SIZES[hudSize()] / 100f));
        set(a, "com_maxfps", Integer.toString(FRAME_RATES[frameRate()]));
        set(a, "com_target_game", Integer.toString(game()));
        set(a, "rp_aim_scale", Float.toString(AIM_SPEEDS[aimSpeed()] / 100f));
        set(a, "rp_aim_invert", invertAim() ? "1" : "0");
        set(a, "g_garandreload", garandReload() ? "1" : "0");
        set(a, "rp_aimassist", aimAssist() ? "1" : "0");
        StringBuilder map = new StringBuilder();
        for (int i = 0; i < ACTIONS.length; i++) {
            if (i > 0) map.append(',');
            map.append(ACTIONS[i]).append('=').append(button(i));
        }
        set(a, "rp_map", map.toString());
        return a.toArray(new String[0]);
    }

    private static void set(List<String> a, String name, String value) {
        a.add("+set");
        a.add(name);
        a.add(value);
    }

    private static int clamp(int v, int n) {
        return v < 0 || v >= n ? 0 : v;
    }
}
