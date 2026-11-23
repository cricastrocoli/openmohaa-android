// Android glue for OpenMoHAA (our own code, GPL-2.0 like the engine).
//
// What the engine's Android changes (from emileb/openmohaa mobile_master) expect from the
// app: the entry point (main_android), where the native libraries are (nativeLibsPath) and
// the screen size. The controller hooks are in android_input.cpp.

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
  findNativeLibsPath();
  // Android's Back button reaches the game (as Esc, the menu) instead of closing the app.
  SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "1");

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

  // Our defaults first; the launcher's arguments (resolution, frame rate, game, buttons...)
  // come after, so they win.
  static char* args[96];
  int count = 0;
  char* defaults[] = {
    "openmohaa",
    "+set", "fs_basepath", basePath,
    "+set", "fs_homepath", homePath,
    "+set", "r_fullscreen", "1",
    "+set", "r_mode", "-2",
    "+set", "in_joystick", "0",  // the controller is handled in android_input.cpp
  };
  for(size_t i = 0; i < sizeof(defaults) / sizeof(defaults[0]); i++) args[count++] = defaults[i];
  // SDL's argv[0] is a placeholder; the rest are from GameActivity.getArguments().
  for(int i = 1; i < argc && count < (int) (sizeof(args) / sizeof(args[0])) - 1; i++) {
    args[count++] = argv[i];
    // A chosen render size: the screen size the renderer clips to follows it.
    if(i >= 2 && !strcmp(argv[i - 2], "+set") && !strcmp(argv[i - 1], "r_customwidth")) mobile_screen_width = atoi(argv[i]);
    if(i >= 2 && !strcmp(argv[i - 2], "+set") && !strcmp(argv[i - 1], "r_customheight")) mobile_screen_height = atoi(argv[i]);
    LOG("arg %s", argv[i]);
  }
  args[count] = NULL;
  return main_android(count, args);
}
