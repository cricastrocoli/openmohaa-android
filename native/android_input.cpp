// Controller input for OpenMoHAA on Android handhelds (our own code, GPL-2.0 like the engine).
//
// Aiming (right stick): radial dead zone, response curve, turn ramp at full deflection, rates
// in degrees per second (independent of the frame rate), scaled down while zoomed like the
// mouse. Moving (left stick): analog. Buttons: the game's own commands; crouch (L3) and walk
// (R3) are click-on/click-off toggles. Start and Android's Back open the menu. In menus the
// sticks move the pointer, A clicks and B goes back.
//
// Tunable with rp_* cvars (saved in the config):
//   rp_aim_yaw / rp_aim_pitch   turn rate at full deflection, degrees/second (200 / 140)
//   rp_aim_deadzone             inner dead zone, fraction of the stick's travel (0.12)
//   rp_aim_outer                where full deflection starts (0.94)
//   rp_aim_curve                response exponent: 1 linear, higher = finer near centre (2.0)
//   rp_aim_ramp_delay           seconds at full deflection before the ramp starts (0.20)
//   rp_aim_ramp_time            seconds the ramp takes to reach full boost (0.30)
//   rp_aim_ramp_boost           extra yaw rate the ramp adds, as a fraction (0.9)
//   rp_aim_invert               1: pushing up looks down (0)
//   rp_move_deadzone            left stick dead zone (0.15)
//   rp_cursor_speed             menu pointer speed at full deflection, pixels/second (1400)

#include "client.h"

#include <math.h>
#include "SDL.h"

static SDL_GameController* pad;

static cvar_t *aimYaw, *aimPitch, *aimDead, *aimOuter, *aimCurve, *rampDelay, *rampTime, *rampBoost,
              *aimInvert, *moveDead, *cursorSpeed;

static void registerCvars(void) {
    if (aimYaw) return;
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

typedef struct {
    const char* hold;   // "+cmd": sent as +cmd on press and -cmd on release
    const char* press;  // a command sent once on press
    const char* toggle; // "+cmd": one click turns it on (+cmd), the next turns it off (-cmd)
} Action;

// In-game layout (Xbox naming, as the Retroid Pocket 6 reports its buttons). Handhelds are
// awkward to hold buttons down on, so crouch and walk are toggles.
static const Action gameActions[B_COUNT] = {
    /* A     */ {"+moveup", NULL, NULL},          // jump
    /* B     */ {"+use", NULL, NULL},             // use: doors, mounted guns
    /* X     */ {NULL, "reload", NULL},
    /* Y     */ {NULL, "toggleitem", NULL},       // binoculars / items
    /* LB    */ {NULL, "weapprev", NULL},
    /* RB    */ {NULL, "weapnext", NULL},
    /* LT    */ {"+attacksecondary", NULL, NULL},
    /* RT    */ {"+attackprimary", NULL, NULL},
    /* L3    */ {NULL, NULL, "+movedown"},        // crouch: click on, click off
    /* R3    */ {NULL, NULL, "+speed"},           // walk: click on, click off
    /* UP    */ {NULL, "useweaponclass grenade", NULL},
    /* DOWN  */ {NULL, "holster", NULL},
    /* LEFT  */ {"+leanleft", NULL, NULL},
    /* RIGHT */ {"+leanright", NULL, NULL},
    /* START */ {NULL, NULL, NULL},               // the menu (sent as Esc)
    /* BACK  */ {"+scores", NULL, NULL},          // objectives / scores while held
};

static bool toggled[B_COUNT];  // a toggle is on

static bool down[B_COUNT];
static bool held[B_COUNT];  // a game "+cmd" is active for this button

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
    for (int b = 0; b < B_COUNT; b++) {
        if (!held[b]) continue;
        Cbuf_AddText(va("-%s\n", gameActions[b].hold + 1));
        held[b] = false;
    }
}

// Toggles (crouch, walk) stay on through menus: the game gets them back on returning.
static void sendToggles(bool on) {
    for (int b = 0; b < B_COUNT; b++) {
        if (toggled[b]) Cbuf_AddText(va("%s%s\n", on ? "+" : "-", gameActions[b].toggle + 1));
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

static void buttonChanged(int b, bool isDown, bool menu) {
    if (menu) {
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
    if (b == B_START) {
        // The game's menu opens with Esc (its key handler), not with a console command.
        escape(isDown);
        return;
    }
    // Select + LB / RB: quick save / quick load (single player).
    if (isDown && down[B_BACK] && (b == B_LB || b == B_RB)) {
        if (held[B_BACK]) { Cbuf_AddText("-scores\n"); held[B_BACK] = false; }
        Cbuf_AddText(b == B_LB ? "savegame quick\n" : "loadgame quick\n");
        return;
    }
    const Action* a = &gameActions[b];
    if (a->hold) {
        if (isDown && !held[b]) { Cbuf_AddText(va("%s\n", a->hold)); held[b] = true; }
        else if (!isDown && held[b]) { Cbuf_AddText(va("-%s\n", a->hold + 1)); held[b] = false; }
    } else if (a->press && isDown) {
        Cbuf_AddText(va("%s\n", a->press));
    } else if (a->toggle && isDown) {
        toggled[b] = !toggled[b];
        Cbuf_AddText(va("%s%s\n", toggled[b] ? "+" : "-", a->toggle + 1));
    }
}

// ---- Per frame -----------------------------------------------------------------------------

static float cursorRemX, cursorRemY;
static Uint32 lastCursorTime;

// Called once a frame from Com_Frame, before events: the controller's buttons, and the menu
// pointer.
extern "C" void IN_Android_Commands(void) {
    registerCvars();
    updatePad();
    if (!pad) return;

    static bool wasMenu;
    bool menu = inMenu();
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
extern "C" void CL_AndroidMove(usercmd_t* cmd, float frame_msec) {
    if (!pad || inMenu()) { rampHeld = 0; return; }
    float dt = frame_msec / 1000.0f;
    if (dt <= 0) return;
    if (dt > 0.1f) dt = 0.1f;

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

    // Zoomed weapons scale the turn down as they do for the mouse.
    float zoom = 1.0f;
    if (cge && cge->CG_SensitivityScale() >= 0.0f) zoom = cge->CG_SensitivityScale();

    float yawRate = aimYaw->value * curved * (1.0f + boost) * zoom;
    float pitchRate = aimPitch->value * curved * zoom;
    cl.viewangles[YAW] -= dx * yawRate * dt;
    cl.viewangles[PITCH] += (aimInvert->integer ? -dy : dy) * pitchRate * dt;
}
