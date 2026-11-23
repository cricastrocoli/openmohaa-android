// Android glue for OpenMoHAA (our own code, GPL-2.0 like the engine).
//
// What the engine's Android changes (from emileb/openmohaa mobile_master) expect from the
// app: the entry point (main_android), where the native libraries are (nativeLibsPath),
// the screen size, a per-frame command hook and the controller movement/aiming hook.

#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#include <android/log.h>
#include "SDL.h"
#include "SDL_main.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "MOHAA", __VA_ARGS__)

int main_android(int argc, char** argv);

// Read by Sys_LoadDll: the game modules (libmohaa_game_dev.so, libmohaa_cgame_dev.so) are
// next to libmain.so, in the app's native library folder.
const char* nativeLibsPath = "";

// Read by RB_SwapBuffers: the whole screen, in pixels.
int mobile_screen_width = 1920;
int mobile_screen_height = 1080;

// Called once a frame from Com_Frame, before events: nothing yet.
void IN_Android_Commands(void) {}

// Called from CL_CreateCmd after the joystick movement: the place for the controller aiming
// (response curve, radial dead zone, turn ramp, gyro). Empty for now: the engine's own
// joystick handling is used.
typedef struct usercmd_s usercmd_t;
void CL_AndroidMove(usercmd_t* cmd, float frame_msec) {
  (void) cmd;
  (void) frame_msec;
}

static void findNativeLibsPath(void) {
  static char dir[PATH_MAX];
  Dl_info info;
  if(dladdr((void*) findNativeLibsPath, &info) && info.dli_fname) {
    snprintf(dir, sizeof(dir), "%s", info.dli_fname);
    char* slash = strrchr(dir, '/');
    if(slash) *slash = 0;
    nativeLibsPath = dir;
  }
}

int SDL_main(int argc, char** argv) {
  (void) argc;
  (void) argv;
  findNativeLibsPath();

  SDL_DisplayMode mode;
  if(SDL_GetDesktopDisplayMode(0, &mode) == 0) {
    // Always landscape.
    mobile_screen_width = mode.w > mode.h ? mode.w : mode.h;
    mobile_screen_height = mode.w > mode.h ? mode.h : mode.w;
  }

  // The game files and the player's settings live in the app's folder on the shared storage
  // (Android/data/<app>/files), which adb and file managers can reach without permissions:
  // game/main, game/mainta, game/maintt hold the .pk3 files; home/ the configs and saves.
  const char* ext = SDL_AndroidGetExternalStoragePath();
  if(ext == NULL) ext = SDL_AndroidGetInternalStoragePath();
  static char basePath[PATH_MAX], homePath[PATH_MAX];
  snprintf(basePath, sizeof(basePath), "%s/game", ext);
  snprintf(homePath, sizeof(homePath), "%s/home", ext);
  mkdir(basePath, 0755);
  mkdir(homePath, 0755);
  setenv("HOME", homePath, 1);
  // gl4es: translate to OpenGL ES 2 (the engine asks SDL for an ES 2 context); its default
  // here would be ES 1.1, which draws nothing in an ES 2 context.
  setenv("LIBGL_ES", "2", 1);
  LOG("libs %s, game %s, home %s, screen %dx%d", nativeLibsPath, basePath, homePath,
      mobile_screen_width, mobile_screen_height);

  char* args[] = {
    "openmohaa",
    "+set", "fs_basepath", basePath,
    "+set", "fs_homepath", homePath,
    "+set", "r_fullscreen", "1",
    "+set", "r_mode", "-2",
    "+set", "in_joystick", "1",
    NULL,
  };
  int count = (int) (sizeof(args) / sizeof(args[0])) - 1;
  return main_android(count, args);
}
