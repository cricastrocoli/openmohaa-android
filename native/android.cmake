# Android build of OpenMoHAA: Gradle builds src/openmohaa/CMakeLists.txt with
# -DCMAKE_PROJECT_openmohaa_INCLUDE=<this file>, which runs right after its project() call. Here SDL2,
# OpenAL Soft and gl4es are added, OpenMoHAA's options are set for Android, and once its
# targets exist (deferred to the end) our glue joins the client library.
set(SRC ${CMAKE_CURRENT_LIST_DIR}/../src)
set(NATIVE_DIR ${CMAKE_CURRENT_LIST_DIR})

# --- SDL2 (Zlib) -----------------------------------------------------------------------
set(SDL_SHARED ON CACHE BOOL "" FORCE)
set(SDL_STATIC OFF CACHE BOOL "" FORCE)
set(SDL_TEST OFF CACHE BOOL "" FORCE)
set(SDL2_DISABLE_SDL2MAIN OFF CACHE BOOL "" FORCE)
add_subdirectory(${SRC}/SDL2-2.32.8 ${CMAKE_BINARY_DIR}/sdl2 EXCLUDE_FROM_ALL)

# --- OpenAL Soft (LGPL, loaded at run time as libopenal.so) ------------------------------
set(ALSOFT_UTILS OFF CACHE BOOL "" FORCE)
set(ALSOFT_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_CONFIG OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_HRTF_DATA OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_AMBDEC_PRESETS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_UTILS OFF CACHE BOOL "" FORCE)
set(ALSOFT_BACKEND_OBOE OFF CACHE BOOL "" FORCE)  # OpenSL ES is in the NDK; Oboe would be another download
set(LIBTYPE SHARED CACHE STRING "" FORCE)
add_subdirectory(${SRC}/openal-soft-1.24.3 ${CMAKE_BINARY_DIR}/openal EXCLUDE_FROM_ALL)

# --- gl4es (MIT): desktop OpenGL on OpenGL ES, as libGL4ES.so (the name the engine loads) ----
include(${NATIVE_DIR}/gl4es.cmake)

# --- OpenMoHAA (GPL-2.0) -----------------------------------------------------------------
set(BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_CLIENT ON CACHE BOOL "" FORCE)
set(BUILD_RENDERER_GL1 ON CACHE BOOL "" FORCE)
set(BUILD_GAME_LIBRARIES ON CACHE BOOL "" FORCE)
set(BUILD_GAME_QVMS OFF CACHE BOOL "" FORCE)
set(USE_RENDERER_DLOPEN OFF CACHE BOOL "" FORCE)
set(USE_OPENAL_DLOPEN ON CACHE BOOL "" FORCE)
set(USE_HTTP OFF CACHE BOOL "" FORCE)
set(USE_CODEC_OPUS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(FLEX_EXECUTABLE /opt/homebrew/opt/flex/bin/flex CACHE FILEPATH "" FORCE)
set(BISON_EXECUTABLE /opt/homebrew/opt/bison/bin/bison CACHE FILEPATH "" FORCE)

# Our Android glue (entry point, paths, screen size, the controller aiming hook) joins the
# client library once OpenMoHAA's CMakeLists has defined it.
function(mohaa_android_finish)
  target_sources(openmohaa PRIVATE ${NATIVE_DIR}/android_glue.c ${NATIVE_DIR}/android_input.cpp)
  target_link_libraries(openmohaa PRIVATE SDL2::SDL2main GL4ES log android)
  add_dependencies(openmohaa OpenAL)
endfunction()
cmake_language(DEFER DIRECTORY ${CMAKE_SOURCE_DIR} CALL mohaa_android_finish)

# The launcher's GOG installer import: runs innoextract (a prebuilt program, see
# native/build-innoextract.sh) with the installer's files handed to it.
add_library(gogexec SHARED ${NATIVE_DIR}/gog_exec.c)
target_link_libraries(gogexec PRIVATE log)
set_target_properties(gogexec PROPERTIES PREFIX "lib")  # OpenMoHAA drops the prefix; Android needs it

