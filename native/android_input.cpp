// Controller input for OpenMoHAA on Android handhelds (our own code, GPL-2.0 like the engine).
//
// Aiming (right stick): radial dead zone, response curve, turn ramp at full deflection, rates
// in degrees per second (independent of the frame rate), scaled down while zoomed like the
// mouse. Moving (left stick): analog. Buttons: the game's own commands; crouch (L3) and walk
// (R3) are click-on/click-off toggles. Start and Android's Back open the menu. In menus the
// sticks move the pointer, A clicks and B goes back.
//
// Tunable with rp_* cvars (saved in the config):
//   rp_aim_scale                overall aim speed, set by the launcher (1.0)
//   rp_aim_yaw / rp_aim_pitch   turn rate at full deflection, degrees/second (200 / 140)
//   rp_map                      buttons for the actions, set by the launcher ("jump=A,...")
//   rp_aim_deadzone             inner dead zone, fraction of the stick's travel (0.12)
//   rp_aim_outer                where full deflection starts (0.94)
//   rp_aim_curve                response exponent: 1 linear, higher = finer near centre (2.0)
//   rp_aim_ramp_delay           seconds at full deflection before the ramp starts (0.20)
//   rp_aim_ramp_time            seconds the ramp takes to reach full boost (0.30)
//   rp_aim_ramp_boost           extra yaw rate the ramp adds, as a fraction (0.9)
//   rp_aim_invert               1: pushing up looks down (0)
//   rp_move_deadzone            left stick dead zone (0.15)
//   rp_cursor_speed             menu pointer speed at full deflection, pixels/second (1400)
//   rp_touch_aim                touch aiming: degrees turned per dp the finger moves (0.20)
//   rp_aimassist                aim assist in single player, set by the launcher (0)
//   rp_aa_slow                  aim speed with the crosshair over an enemy, as a fraction (0.75)
//   rp_aa_fulldist / rp_aa_maxdist  full help up to this distance, fading to none at the
//                               second (605 / 1330 units, about 15 m / 34 m)
//   rp_aa_track                 how much of the enemy's movement the crosshair follows (0.65)
//   rp_aa_nudge                 seconds of the nudge toward the chest on reaching an enemy (0.12)
//
// Aim assist, like a "free aim, assisted" mode: the crosshair is the player's alone until it
// is over an enemy's body (the game names the nearest one in rp_aimtarget, see
// fgame/player.cpp UpdateAimAssist). Over it, aiming slows down (friction) and the crosshair
// follows the enemy's movement and the player's, keeping the spot aimed at (so the player can
// still go for the head); on reaching the body there's a short nudge toward the chest, never
// the head. Never in multiplayer.
//
// Touch (TouchControls.java, an Android view over the game): it sets a move vector, adds aim
// movement and presses actions through the JNI functions at the end; this file applies them
// on the game's thread, once a frame, like the controller. In menus a tap puts the pointer
// where the finger is and clicks.

#include "client.h"

#include <string.h>
#include <stdio.h>

#include <math.h>
#include "SDL.h"
#include <jni.h>

static SDL_GameController* pad;
static cvar_t* buttonMap;
static void loadButtonMap(void);

static cvar_t *aaOn, *aaSlow, *aaTrack, *aaNudge, *aaTarget, *gametype, *aaFull, *aaMax;
static cvar_t *aimScale, *aimYaw, *aimPitch, *aimDead, *aimOuter, *aimCurve, *rampDelay, *rampTime, *rampBoost,
              *aimInvert, *moveDead, *cursorSpeed, *touchAim;

static void registerCvars(void) {
    if (aimYaw) {
        if (buttonMap->modified) loadButtonMap();
        return;
    }
    buttonMap   = Cvar_Get("rp_map", "", CVAR_ARCHIVE);
    loadButtonMap();
    aimScale    = Cvar_Get("rp_aim_scale", "1.0", CVAR_ARCHIVE);
    aaOn        = Cvar_Get("rp_aimassist", "0", 0);
    aaSlow      = Cvar_Get("rp_aa_slow", "0.75", 0);
    aaFull      = Cvar_Get("rp_aa_fulldist", "605", 0);
    aaMax       = Cvar_Get("rp_aa_maxdist", "1330", 0);
    aaTrack     = Cvar_Get("rp_aa_track", "0.65", 0);
    aaNudge     = Cvar_Get("rp_aa_nudge", "0.12", 0);
    aaTarget    = Cvar_Get("rp_aimtarget", "", 0);
    gametype    = Cvar_Get("g_gametype", "0", 0);
    aimYaw      = Cvar_Get("rp_aim_yaw", "200", CVAR_ARCHIVE);
    aimPitch    = Cvar_Get("rp_aim_pitch", "140", CVAR_ARCHIVE);
    aimDead     = Cvar_Get("rp_aim_deadzone", "0.12", CVAR_ARCHIVE);
    aimOuter    = Cvar_Get("rp_aim_outer", "0.94", CVAR_ARCHIVE);
    aimCurve    = Cvar_Get("rp_aim_curve", "2.0", CVAR_ARCHIVE);
    rampDelay   = Cvar_Get("rp_aim_ramp_delay", "0.20", CVAR_ARCHIVE);
    rampTime    = Cvar_Get("rp_aim_ramp_time", "0.30", CVAR_ARCHIVE);
    rampBoost   = Cvar_Get("rp_aim_ramp_boost", "0.9", CVAR_ARCHIVE);
    aimInvert   = Cvar_Get("rp_aim_invert", "0", CVAR_ARCHIVE);
    moveDead    = Cvar_Get("rp_move_deadzone", "0.15", CVAR_ARCHIVE);
    cursorSpeed = Cvar_Get("rp_cursor_speed", "1400", CVAR_ARCHIVE);
    touchAim    = Cvar_Get("rp_touch_aim", "0.20", CVAR_ARCHIVE);
}

// The first game controller, opened when it appears and dropped when it goes away.
static void updatePad(void) {
    if (pad && !SDL_GameControllerGetAttached(pad)) {
        SDL_GameControllerClose(pad);
        pad = NULL;
    }
    if (pad) return;
    if (!SDL_WasInit(SDL_INIT_GAMECONTROLLER)) SDL_InitSubSystem(SDL_INIT_GAMECONTROLLER);
    for (int i = 0; i < SDL_NumJoysticks(); i++) {
        if (SDL_IsGameController(i) && (pad = SDL_GameControllerOpen(i)) != NULL) {
            Com_Printf("Controller: %s\n", SDL_GameControllerName(pad));
            break;
        }
    }
}

static float axis(SDL_GameControllerAxis a) {
    float v = SDL_GameControllerGetAxis(pad, a) / 32767.0f;
    return v < -1 ? -1 : v > 1 ? 1 : v;
}

// A stick as a direction and an amount 0-1 after a radial dead zone (inner to outer edge),
// so diagonals behave like straight pushes.
static float stick(float x, float y, float inner, float outer, float* dx, float* dy) {
    float r = sqrtf(x * x + y * y);
    if (r <= inner || r < 1e-4f) { *dx = *dy = 0; return 0; }
    float n = (r - inner) / (outer - inner);
    if (n > 1) n = 1;
    *dx = x / r;
    *dy = y / r;
    return n;
}

static bool inMenu(void) {
    return in_guimouse || (Key_GetCatcher() & (KEYCATCH_UI | KEYCATCH_CONSOLE)) || clc.state != CA_ACTIVE;
}

// ---- Buttons -------------------------------------------------------------------------------

enum { B_A, B_B, B_X, B_Y, B_LB, B_RB, B_LT, B_RT, B_L3, B_R3, B_UP, B_DOWN, B_LEFT, B_RIGHT,
       B_START, B_BACK, B_COUNT };
static const char* buttonNames[B_COUNT] = {"A", "B", "X", "Y", "LB", "RB", "LT", "RT", "L3", "R3",
                                           "UP", "DOWN", "LEFT", "RIGHT", "START", "BACK"};

typedef struct {
    const char* name;   // as in rp_map
    int button;         // default button
    const char* hold;   // "+cmd": sent as +cmd on press and -cmd on release
    const char* press;  // a command sent once on press
    const char* toggle; // "+cmd": one click turns it on (+cmd), the next turns it off (-cmd)
} Action;

// The game's actions and their default buttons (Xbox naming, as the Retroid Pocket 6 reports
// its buttons). Handhelds are awkward to hold buttons down on, so crouch and walk are toggles.
// The launcher can move them to other buttons with rp_map ("jump=A,use=B,...").
enum { A_JUMP = 0, A_CROUCH = 8, A_MENU = 14, A_OBJECTIVES = 15, A_PREVWEAPON = 4, A_NEXTWEAPON = 5,
       A_COUNT = 16 };
static const Action actions[A_COUNT] = {
    {"jump",       B_A,     "+moveup", NULL, NULL},
    {"use",        B_B,     "+use", NULL, NULL},           // doors, mounted guns
    {"reload",     B_X,     NULL, "reload", NULL},
    {"item",       B_Y,     NULL, "toggleitem", NULL},     // binoculars / items
    {"prevweapon", B_LB,    NULL, "weapprev", NULL},
    {"nextweapon", B_RB,    NULL, "weapnext", NULL},
    {"altfire",    B_LT,    "+attacksecondary", NULL, NULL},
    {"fire",       B_RT,    "+attackprimary", NULL, NULL},
    {"crouch",     B_L3,    NULL, NULL, "+movedown"},      // click on, click off
    {"walk",       B_R3,    NULL, NULL, "+speed"},         // click on, click off
    {"grenade",    B_UP,    NULL, "useweaponclass grenade", NULL},
    {"holster",    B_DOWN,  NULL, "holster", NULL},
    {"leanleft",   B_LEFT,  "+leanleft", NULL, NULL},
    {"leanright",  B_RIGHT, "+leanright", NULL, NULL},
    {"menu",       B_START, NULL, NULL, NULL},             // sent as Esc
    {"objectives", B_BACK,  "+scores", NULL, NULL},        // objectives / scores while held
};

static int buttonAction[B_COUNT];  // the action on each button (-1: none)
static int actionButton[A_COUNT];

// rp_map: "action=BUTTON" pairs separated by commas; actions not named keep their default.
static void loadButtonMap(void) {
    for (int i = 0; i < A_COUNT; i++) actionButton[i] = actions[i].button;
    char map[MAX_STRING_CHARS];
    Q_strncpyz(map, buttonMap ? buttonMap->string : "", sizeof(map));
    for (char* pair = strtok(map, ","); pair; pair = strtok(NULL, ",")) {
        char* eq = strchr(pair, '=');
        if (!eq) continue;
        *eq = 0;
        for (int i = 0; i < A_COUNT; i++) {
            if (Q_stricmp(pair, actions[i].name)) continue;
            for (int b = 0; b < B_COUNT; b++) if (!Q_stricmp(eq + 1, buttonNames[b])) actionButton[i] = b;
        }
    }
    for (int b = 0; b < B_COUNT; b++) buttonAction[b] = -1;
    for (int i = 0; i < A_COUNT; i++) buttonAction[actionButton[i]] = i;  // a later action wins
    if (buttonMap) buttonMap->modified = qfalse;
}

static bool toggled[A_COUNT];  // a toggle action is on
static bool down[B_COUNT];
static bool held[A_COUNT];     // an action's "+cmd" is active

static bool readButton(int b) {
    switch (b) {
        case B_A: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_A);
        case B_B: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_B);
        case B_X: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_X);
        case B_Y: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_Y);
        case B_LB: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_LEFTSHOULDER);
        case B_RB: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_RIGHTSHOULDER);
        // Triggers with hysteresis: on above 40%, off below 25%.
        case B_LT: return axis(SDL_CONTROLLER_AXIS_TRIGGERLEFT) > (down[B_LT] ? 0.25f : 0.40f);
        case B_RT: return axis(SDL_CONTROLLER_AXIS_TRIGGERRIGHT) > (down[B_RT] ? 0.25f : 0.40f);
        case B_L3: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_LEFTSTICK);
        case B_R3: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_RIGHTSTICK);
        case B_UP: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_DPAD_UP);
        case B_DOWN: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_DPAD_DOWN);
        case B_LEFT: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_DPAD_LEFT);
        case B_RIGHT: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_DPAD_RIGHT);
        case B_START: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_START);
        case B_BACK: return SDL_GameControllerGetButton(pad, SDL_CONTROLLER_BUTTON_BACK);
    }
    return false;
}

static void releaseHeld(void) {
    for (int i = 0; i < A_COUNT; i++) {
        if (!held[i]) continue;
        Cbuf_AddText(va("-%s\n", actions[i].hold + 1));
        held[i] = false;
    }
}

// Toggles (crouch, walk) stay on through menus: the game gets them back on returning.
static void sendToggles(bool on) {
    for (int i = 0; i < A_COUNT; i++) {
        if (toggled[i]) Cbuf_AddText(va("%s%s\n", on ? "+" : "-", actions[i].toggle + 1));
    }
}

static void menuKey(int key, bool isDown) {
    Com_QueueEvent(Sys_Milliseconds(), SE_KEY, key, isDown ? qtrue : qfalse, 0, NULL);
}

// Esc opens or closes the menu as it goes down, so the release would land in the other mode
// and be lost (leaving the key "held"): it is sent down and up together, on press.
static void escape(bool isDown) {
    if (!isDown) return;
    menuKey(K_ESCAPE, true);
    menuKey(K_ESCAPE, false);
}

// An action pressed or released in the game (from a controller button or a touch button).
static void actionChanged(int act, bool isDown) {
    if (act < 0) return;
    if (act == A_MENU) {
        // The game's menu opens with Esc (its key handler), not with a console command.
        escape(isDown);
        return;
    }
    // Jump while crouched (crouch is a toggle): stand up instead, as jumping can't happen from
    // a crouch here (the held crouch cancels it) and it would look as if jump crouched.
    if (act == A_JUMP && isDown && toggled[A_CROUCH]) {
        toggled[A_CROUCH] = false;
        Cbuf_AddText(va("-%s\n", actions[A_CROUCH].toggle + 1));
        return;
    }
    const Action* a = &actions[act];
    if (a->hold) {
        if (isDown && !held[act]) { Cbuf_AddText(va("%s\n", a->hold)); held[act] = true; }
        else if (!isDown && held[act]) { Cbuf_AddText(va("-%s\n", a->hold + 1)); held[act] = false; }
    } else if (a->press && isDown) {
        Cbuf_AddText(va("%s\n", a->press));
    } else if (a->toggle && isDown) {
        toggled[act] = !toggled[act];
        Cbuf_AddText(va("%s%s\n", toggled[act] ? "+" : "-", a->toggle + 1));
    }
}

static void buttonChanged(int b, bool isDown, bool menu) {
    int act = buttonAction[b];
    if (menu) {
        // Menus: A clicks, B and Start (or the menu button) go back, the D-pad is the arrows.
        if (act == A_MENU) { escape(isDown); return; }
        switch (b) {
            case B_A: menuKey(K_MOUSE1, isDown); break;
            case B_B: case B_START: escape(isDown); break;
            case B_UP: menuKey(K_UPARROW, isDown); break;
            case B_DOWN: menuKey(K_DOWNARROW, isDown); break;
            case B_LEFT: menuKey(K_LEFTARROW, isDown); break;
            case B_RIGHT: menuKey(K_RIGHTARROW, isDown); break;
            default: break;
        }
        return;
    }
    // Objectives button + previous / next weapon: quick save / quick load (single player).
    if (isDown && act >= 0 && down[actionButton[A_OBJECTIVES]] && (act == A_PREVWEAPON || act == A_NEXTWEAPON)) {
        if (held[A_OBJECTIVES]) { Cbuf_AddText("-scores\n"); held[A_OBJECTIVES] = false; }
        Cbuf_AddText(act == A_PREVWEAPON ? "savegame quick\n" : "loadgame quick\n");
        return;
    }
    actionChanged(act, isDown);
}

// ---- Touch ---------------------------------------------------------------------------------

// Written by the UI thread (JNI below), read by the game's thread once a frame.
static SDL_SpinLock touchLock;
static float touchMoveX, touchMoveY;  // the touch stick, -1..1 (y down)
static float touchAimX, touchAimY;    // finger movement for aiming not yet applied, in dp
static bool touchAction[A_COUNT];     // touch buttons held
static bool touchPressed[A_COUNT];    // pressed since the last frame (a tap may already be over)
static bool touchApplied[A_COUNT];    // as last applied to the game
static int tapCount;                  // menu touches waiting: tapX/tapY (0-1 of the screen), tapDown
static float tapX[16], tapY[16];
static bool tapDown[16];
static volatile int touchInMenu = 1;  // for the view: menus show no touch controls

// The touch buttons' changes and the menu taps, on the game's thread.
static void applyTouch(bool menu) {
    bool now[A_COUNT];
    float x = 0, y = 0;
    bool isDown = false, tap = false;
    SDL_AtomicLock(&touchLock);
    memcpy(now, touchAction, sizeof(now));
    // A tap shorter than a frame still presses its button for one frame.
    for (int i = 0; i < A_COUNT; i++) if (touchPressed[i]) now[i] = true;
    memset(touchPressed, 0, sizeof(touchPressed));
    // One menu touch a frame, so a quick tap's press and release reach the menu in different
    // frames, as a mouse click's do.
    if (tapCount > 0) {
        tap = true;
        x = tapX[0];
        y = tapY[0];
        isDown = tapDown[0];
        tapCount--;
        memmove(tapX, tapX + 1, tapCount * sizeof(float));
        memmove(tapY, tapY + 1, tapCount * sizeof(float));
        memmove(tapDown, tapDown + 1, tapCount * sizeof(bool));
    }
    SDL_AtomicUnlock(&touchLock);

    if (tap && menu) {
        // The finger goes down: the pointer moves under it and the button goes down; it lifts:
        // the button comes up (a click, as with a mouse; menus act on the release).
        // Menus in 4:3 (r_pillarbox2d): the picture is a centred 4:3 area of the screen.
        static cvar_t* pillarbox = Cvar_Get("r_pillarbox2d", "0", CVAR_TEMP);
        float W = cls.glconfig.vidWidth, H = cls.glconfig.vidHeight;
        if (pillarbox->integer && W * 3 > H * 4) {
            float f = H * 4 / 3 / W;
            x = (x - (1 - f) / 2) / f;
            x = x < 0 ? 0 : x > 1 ? 1 : x;
        }
        CL_SetMousePos((int) (x * W), (int) (y * H));
        menuKey(K_MOUSE1, isDown);
    }
    for (int act = 0; act < A_COUNT; act++) {
        if (now[act] == touchApplied[act]) continue;
        touchApplied[act] = now[act];
        if (!menu) actionChanged(act, now[act]);
    }
}

// ---- Background ----------------------------------------------------------------------------

// When Android sends the app to the background (the screen locked, another app), SDL stops the
// game's loop, but the sound goes through OpenAL, whose own thread kept playing the music. Its
// output device is paused while the app is in the background (ALC_SOFT_pause_device) and
// resumed when it returns. OpenAL is the engine's (dlopen'd), already loaded by then.
#include <dlfcn.h>

static void pauseSound(bool pause) {
    void* lib = dlopen("libopenal.so", RTLD_NOW | RTLD_NOLOAD);
    if (!lib) return;
    typedef void* (*GetContext)(void);
    typedef void* (*ContextDevice)(void*);
    typedef void* (*GetProc)(void*, const char*);
    typedef void (*DeviceFn)(void*);
    GetContext getContext = (GetContext) dlsym(lib, "alcGetCurrentContext");
    ContextDevice contextDevice = (ContextDevice) dlsym(lib, "alcGetContextsDevice");
    GetProc getProc = (GetProc) dlsym(lib, "alcGetProcAddress");
    void* context = getContext ? getContext() : NULL;
    void* device = context && contextDevice ? contextDevice(context) : NULL;
    DeviceFn fn = device && getProc ? (DeviceFn) getProc(device, pause ? "alcDevicePauseSOFT" : "alcDeviceResumeSOFT") : NULL;
    if (fn) fn(device);
    dlclose(lib);
    Com_DPrintf("Sound %s (app %s)\n", pause ? "paused" : "resumed", pause ? "in the background" : "back");
}

// Called by SDL as each event is posted (on Android's thread, while the game's may be stopped).
static int SDLCALL appEvents(void*, SDL_Event* e) {
    switch (e->type) {
        case SDL_APP_WILLENTERBACKGROUND: pauseSound(true); break;
        case SDL_APP_DIDENTERFOREGROUND: pauseSound(false); break;
        default: break;
    }
    return 0;
}

// ---- Per frame -----------------------------------------------------------------------------

static float cursorRemX, cursorRemY;
static Uint32 lastCursorTime;

// Called once a frame from Com_Frame, before events: the controller's buttons, and the menu
// pointer.
static SDL_atomic_t keyboardToggle;

extern "C" void IN_Android_Commands(void) {
    if (SDL_AtomicSet(&keyboardToggle, 0)) {
        // (SDL counts text input as active from the start, so ask whether the keyboard shows.)
        SDL_Window* w = SDL_GL_GetCurrentWindow();
        if (w && SDL_IsScreenKeyboardShown(w)) SDL_StopTextInput(); else SDL_StartTextInput();
    }
    registerCvars();
    static bool watching;
    if (!watching) {
        SDL_AddEventWatch(appEvents, NULL);
        watching = true;
    }
    updatePad();

    static bool wasMenu;
    bool menu = inMenu();
    touchInMenu = menu;
    applyTouch(menu);
    if (!pad) {
        if (menu != wasMenu) {
            releaseHeld();
            sendToggles(!menu);
            wasMenu = menu;
        }
        return;
    }
    if (menu != wasMenu) {
        // Switching between menu and game: let go of everything held in the other mode;
        // toggles come back when the game does.
        releaseHeld();
        sendToggles(!menu);
        for (int b = 0; b < B_COUNT; b++) down[b] = readButton(b);
        wasMenu = menu;
        lastCursorTime = SDL_GetTicks();
        return;
    }

    for (int b = 0; b < B_COUNT; b++) {
        bool now = readButton(b);
        if (now != down[b]) {
            down[b] = now;
            buttonChanged(b, now, menu);
        }
    }

    Uint32 t = SDL_GetTicks();
    float dt = (t - lastCursorTime) / 1000.0f;
    lastCursorTime = t;
    if (!menu || dt <= 0 || dt > 0.1f) return;
    // Either stick moves the pointer, faster the further it's pushed.
    float x = axis(SDL_CONTROLLER_AXIS_LEFTX) + axis(SDL_CONTROLLER_AXIS_RIGHTX);
    float y = axis(SDL_CONTROLLER_AXIS_LEFTY) + axis(SDL_CONTROLLER_AXIS_RIGHTY);
    float dx, dy, n = stick(x, y, 0.15f, 0.95f, &dx, &dy);
    if (n <= 0) return;
    float speed = cursorSpeed->value * n * n * dt;
    cursorRemX += dx * speed;
    cursorRemY += dy * speed;
    int mx = (int) cursorRemX, my = (int) cursorRemY;
    cursorRemX -= mx;
    cursorRemY -= my;
    if (mx || my) Com_QueueEvent(Sys_Milliseconds(), SE_MOUSE, mx, my, 0, NULL);
}

static float rampHeld;  // seconds the aim stick has been at full deflection

// Called from CL_CreateCmd after the keyboard, mouse and joystick: movement and aiming.
// Zoomed weapons scale the turn down as they do for the mouse.
static float zoomScale(void) {
    if (cge && cge->CG_SensitivityScale() >= 0.0f) return cge->CG_SensitivityScale();
    return 1.0f;
}

static void addMove(usercmd_t* cmd, float x, float y) {
    int fwd = cmd->forwardmove - (int) (y * 127);
    int side = cmd->rightmove + (int) (x * 127);
    cmd->forwardmove = (signed char) (fwd < -127 ? -127 : fwd > 127 ? 127 : fwd);
    cmd->rightmove = (signed char) (side < -127 ? -127 : side > 127 ? 127 : side);
}

// Aim assist state: the enemy under the crosshair last frame and where it was (angles from
// the eye), and how long the entry nudge still runs.
static int aaNum = -1;
static float aaYaw, aaPitch, aaNudgeLeft;

// Whether the crosshair is over the named enemy's body; err gets the angle to its chest
// {yaw, pitch}, at gets the chest's angles from the eye.
static bool aimOver(int* num, float* err, float* at, float* strength) {
    if (!aaOn || !aaOn->integer || gametype->integer != 0 || !aaTarget->string[0]) return false;
    vec3_t chest, eye, dir, ang;
    float bottom, top;
    if (sscanf(aaTarget->string, "%d %f %f %f %f %f", num, &chest[0], &chest[1], &chest[2], &bottom, &top) != 6)
        return false;
    // The eye: the player's origin plus view height (vEyePos isn't sent to the client).
    VectorCopy(cl.snap.ps.origin, eye);
    eye[2] += cl.snap.ps.viewheight;
    VectorSubtract(chest, eye, dir);
    float flat = sqrtf(dir[0] * dir[0] + dir[1] * dir[1]);
    if (flat < 8) return false;
    // Full help up close, fading out with distance.
    float dist = VectorLength(dir), full = aaFull->value, far = aaMax->value;
    *strength = dist <= full ? 1.0f : dist >= far ? 0.0f : (far - dist) / (far - full);
    if (*strength <= 0) return false;
    vectoangles(dir, ang);
    at[0] = ang[YAW];
    at[1] = ang[PITCH];
    // The view's direction: the command angles plus the game's delta_angles (set when the
    // player spawns or is turned by the game), as the engine itself combines them.
    float viewYaw = cl.viewangles[YAW] + SHORT2ANGLE(cl.snap.ps.delta_angles[YAW]);
    float viewPitch = cl.viewangles[PITCH] + SHORT2ANGLE(cl.snap.ps.delta_angles[PITCH]);
    err[0] = AngleSubtract(ang[YAW], viewYaw);
    err[1] = AngleSubtract(ang[PITCH], viewPitch);
    // The body's extent seen from the eye: about 20 units either side, feet to head, plus a
    // degree of margin. (Pitch grows downward.)
    float half = RAD2DEG(atanf(20.0f / flat)) + 1.0f;
    float pTop = -RAD2DEG(atan2f(top - eye[2], flat)) - 1.0f;
    float pBottom = -RAD2DEG(atan2f(bottom - eye[2], flat)) + 1.0f;
    float pitch = AngleNormalize180(viewPitch);
    return fabsf(err[0]) < half && pitch > pTop && pitch < pBottom;
}

// Runs once a frame before the aim input: returns the aim speed factor (friction), and moves
// the crosshair with the enemy (tracking) and toward the chest on arrival (nudge).
static float aimAssist(float dt) {
    int num;
    float err[2], at[2], strength;
    if (dt <= 0 || !aimOver(&num, err, at, &strength)) {
        aaNum = -1;
        return 1.0f;
    }
    if (num == aaNum) {
        // Tracking: follow the chest's movement since last frame (the enemy's and the
        // player's), keeping the offset the player chose. Capped, in case of a jump.
        float dy = AngleSubtract(at[0], aaYaw), dp = AngleSubtract(at[1], aaPitch);
        float k = aaTrack->value * strength;
        if (fabsf(dy) < 5 && fabsf(dp) < 5) {
            cl.viewangles[YAW] += dy * k;
            cl.viewangles[PITCH] += dp * k;
        }
    } else {
        aaNudgeLeft = strength > 0.5f ? aaNudge->value : 0;  // just reached this enemy
    }
    if (aaNudgeLeft > 0) {
        // Nudge: a short, gentle move toward the chest (a quarter of the gap per 0.1 s).
        float f = fminf(1.0f, dt * 2.5f);
        cl.viewangles[YAW] += err[0] * f;
        cl.viewangles[PITCH] += err[1] * f;
        aaNudgeLeft -= dt;
    }
    aaNum = num;
    aaYaw = at[0];
    aaPitch = at[1];
    return 1.0f - (1.0f - aaSlow->value) * strength;
}

// Gyro aiming (GyroAim.java): degrees turned since the last frame.
static float gyroYaw, gyroPitch;

extern "C" JNIEXPORT void JNICALL
Java_io_github_cricastrocoli_openmohaa_GyroAim_nativeGyro(JNIEnv*, jclass, jfloat yaw, jfloat pitch) {
    SDL_AtomicLock(&touchLock);
    gyroYaw += yaw;
    gyroPitch += pitch;
    SDL_AtomicUnlock(&touchLock);
}

// Touch: the stick's move and the finger movement for aiming since the last frame.
static void touchMove(usercmd_t* cmd, float slow) {
    SDL_AtomicLock(&touchLock);
    float mx = touchMoveX, my = touchMoveY, ax = touchAimX, ay = touchAimY;
    float gy = gyroYaw, gp = gyroPitch;
    touchAimX = touchAimY = 0;
    gyroYaw = gyroPitch = 0;
    SDL_AtomicUnlock(&touchLock);
    if (gy || gp) {
        // Slower while zoomed, like the other aiming; aim assist's friction applies too.
        float z = zoomScale() * slow;
        cl.viewangles[YAW] += gy * z;
        cl.viewangles[PITCH] += (aimInvert->integer ? -gp : gp) * z;
    }
    if (mx || my) addMove(cmd, mx, my);
    if (ax || ay) {
        float deg = touchAim->value * aimScale->value * zoomScale() * slow;
        cl.viewangles[YAW] -= ax * deg;
        cl.viewangles[PITCH] += (aimInvert->integer ? -ay : ay) * deg;
    }
}

extern "C" void CL_AndroidMove(usercmd_t* cmd, float frame_msec) {
    if (inMenu()) {
        rampHeld = 0;
        SDL_AtomicLock(&touchLock);
        touchAimX = touchAimY = 0;  // aiming done over a menu isn't kept for later
        gyroYaw = gyroPitch = 0;
        SDL_AtomicUnlock(&touchLock);
        return;
    }
    float dt = frame_msec / 1000.0f;
    if (dt > 0.1f) dt = 0.1f;

    // Aim assist (single player, if on): see aimAssist.
    float slow = aimAssist(dt);
    touchMove(cmd, slow);

    if (!pad) { rampHeld = 0; return; }
    if (dt <= 0) return;

    // Move: the left stick, analog, added to whatever the keys did.
    float dx, dy;
    float m = stick(axis(SDL_CONTROLLER_AXIS_LEFTX), axis(SDL_CONTROLLER_AXIS_LEFTY),
                    moveDead->value, 0.95f, &dx, &dy);
    if (m > 0) {
        int fwd = cmd->forwardmove - (int) (dy * m * 127);
        int side = cmd->rightmove + (int) (dx * m * 127);
        cmd->forwardmove = (signed char) (fwd < -127 ? -127 : fwd > 127 ? 127 : fwd);
        cmd->rightmove = (signed char) (side < -127 ? -127 : side > 127 ? 127 : side);
    }

    // Aim: the right stick.
    float n = stick(axis(SDL_CONTROLLER_AXIS_RIGHTX), axis(SDL_CONTROLLER_AXIS_RIGHTY),
                    aimDead->value, aimOuter->value, &dx, &dy);
    if (n <= 0) { rampHeld = 0; return; }
    float curved = powf(n, aimCurve->value > 0.1f ? aimCurve->value : 1.0f);

    // Turn ramp: held at full deflection, the turn speeds up (for quick 180s) after a delay.
    float boost = 0;
    if (n >= 0.999f) {
        rampHeld += dt;
        float t = rampHeld - rampDelay->value;
        if (t > 0) boost = rampBoost->value * (rampTime->value > 0 ? fminf(1.0f, t / rampTime->value) : 1.0f);
    } else {
        rampHeld = 0;
    }

    float zoom = zoomScale();

    float yawRate = aimYaw->value * aimScale->value * curved * (1.0f + boost) * zoom * slow;
    float pitchRate = aimPitch->value * aimScale->value * curved * zoom * slow;
    cl.viewangles[YAW] -= dx * yawRate * dt;
    cl.viewangles[PITCH] += (aimInvert->integer ? -dy : dy) * pitchRate * dt;
}

// ---- JNI: TouchControls.java ---------------------------------------------------------------


extern "C" JNIEXPORT void JNICALL
Java_io_github_cricastrocoli_openmohaa_TouchControls_nativeMove(JNIEnv*, jclass, jfloat x, jfloat y) {
    SDL_AtomicLock(&touchLock);
    touchMoveX = x;
    touchMoveY = y;
    SDL_AtomicUnlock(&touchLock);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_cricastrocoli_openmohaa_TouchControls_nativeAim(JNIEnv*, jclass, jfloat dx, jfloat dy) {
    SDL_AtomicLock(&touchLock);
    touchAimX += dx;
    touchAimY += dy;
    SDL_AtomicUnlock(&touchLock);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_cricastrocoli_openmohaa_TouchControls_nativeAction(JNIEnv*, jclass, jint action, jboolean isDown) {
    if (action < 0 || action >= A_COUNT) return;
    SDL_AtomicLock(&touchLock);
    touchAction[action] = isDown;
    if (isDown) touchPressed[action] = true;
    SDL_AtomicUnlock(&touchLock);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_cricastrocoli_openmohaa_TouchControls_nativeMenuTouch(JNIEnv*, jclass, jfloat x, jfloat y,
                                                                     jboolean isDown) {
    SDL_AtomicLock(&touchLock);
    if (tapCount < 16) {
        tapX[tapCount] = x;
        tapY[tapCount] = y;
        tapDown[tapCount] = isDown;
        tapCount++;
    }
    SDL_AtomicUnlock(&touchLock);
}

// The keyboard button: opens or closes Android's keyboard (SDL text input) on the game's
// thread; typed keys reach the game as key presses and text, like a real keyboard.

extern "C" JNIEXPORT void JNICALL
Java_io_github_cricastrocoli_openmohaa_TouchControls_nativeToggleKeyboard(JNIEnv*, jclass) {
    SDL_AtomicSet(&keyboardToggle, 1);
}

// Lets go of everything touch holds (a controller came, the controls were hidden).
extern "C" JNIEXPORT void JNICALL
Java_io_github_cricastrocoli_openmohaa_TouchControls_nativeReleaseAll(JNIEnv*, jclass) {
    SDL_AtomicLock(&touchLock);
    touchMoveX = touchMoveY = touchAimX = touchAimY = 0;
    memset(touchAction, 0, sizeof(touchAction));
    memset(touchPressed, 0, sizeof(touchPressed));
    SDL_AtomicUnlock(&touchLock);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_cricastrocoli_openmohaa_TouchControls_nativeInMenu(JNIEnv*, jclass) {
    return touchInMenu ? JNI_TRUE : JNI_FALSE;
}
