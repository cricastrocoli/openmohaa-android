# gl4es (MIT), built as in its Android.mk, as libGL4ES.so.
set(GL4ES_DIR ${SRC}/gl4es)
add_library(GL4ES SHARED
    ${GL4ES_DIR}/src/gl/array.c
    ${GL4ES_DIR}/src/gl/blend.c
    ${GL4ES_DIR}/src/gl/blit.c
    ${GL4ES_DIR}/src/gl/buffers.c
    ${GL4ES_DIR}/src/gl/build_info.c
    ${GL4ES_DIR}/src/gl/debug.c
    ${GL4ES_DIR}/src/gl/decompress.c
    ${GL4ES_DIR}/src/gl/depth.c
    ${GL4ES_DIR}/src/gl/directstate.c
    ${GL4ES_DIR}/src/gl/drawing.c
    ${GL4ES_DIR}/src/gl/enable.c
    ${GL4ES_DIR}/src/gl/eval.c
    ${GL4ES_DIR}/src/gl/face.c
    ${GL4ES_DIR}/src/gl/fog.c
    ${GL4ES_DIR}/src/gl/fpe.c
    ${GL4ES_DIR}/src/gl/fpe_cache.c
    ${GL4ES_DIR}/src/gl/fpe_shader.c
    ${GL4ES_DIR}/src/gl/framebuffers.c
    ${GL4ES_DIR}/src/gl/getter.c
    ${GL4ES_DIR}/src/gl/gl4es.c
    ${GL4ES_DIR}/src/gl/glstate.c
    ${GL4ES_DIR}/src/gl/hint.c
    ${GL4ES_DIR}/src/gl/init.c
    ${GL4ES_DIR}/src/gl/light.c
    ${GL4ES_DIR}/src/gl/line.c
    ${GL4ES_DIR}/src/gl/list.c
    ${GL4ES_DIR}/src/gl/listdraw.c
    ${GL4ES_DIR}/src/gl/listrl.c
    ${GL4ES_DIR}/src/gl/loader.c
    ${GL4ES_DIR}/src/gl/matrix.c
    ${GL4ES_DIR}/src/gl/matvec.c
    ${GL4ES_DIR}/src/gl/pixel.c
    ${GL4ES_DIR}/src/gl/planes.c
    ${GL4ES_DIR}/src/gl/pointsprite.c
    ${GL4ES_DIR}/src/gl/preproc.c
    ${GL4ES_DIR}/src/gl/program.c
    ${GL4ES_DIR}/src/gl/queries.c
    ${GL4ES_DIR}/src/gl/raster.c
    ${GL4ES_DIR}/src/gl/render.c
    ${GL4ES_DIR}/src/gl/shader.c
    ${GL4ES_DIR}/src/gl/shaderconv.c
    ${GL4ES_DIR}/src/gl/stack.c
    ${GL4ES_DIR}/src/gl/stencil.c
    ${GL4ES_DIR}/src/gl/string_utils.c
    ${GL4ES_DIR}/src/gl/stubs.c
    ${GL4ES_DIR}/src/gl/texenv.c
    ${GL4ES_DIR}/src/gl/texgen.c
    ${GL4ES_DIR}/src/gl/texture.c
    ${GL4ES_DIR}/src/gl/uniform.c
    ${GL4ES_DIR}/src/gl/vertexattrib.c
    ${GL4ES_DIR}/src/gl/wrap/gl4eswraps.c
    ${GL4ES_DIR}/src/gl/wrap/gles.c
    ${GL4ES_DIR}/src/gl/wrap/glstub.c
    ${GL4ES_DIR}/src/gl/math/matheval.c
    ${GL4ES_DIR}/src/glx/hardext.c
    ${GL4ES_DIR}/src/glx/glx.c
    ${GL4ES_DIR}/src/glx/lookup.c
    ${GL4ES_DIR}/src/glx/gbm.c
    ${GL4ES_DIR}/src/glx/streaming.c
)
set_target_properties(GL4ES PROPERTIES OUTPUT_NAME libGL4ES C_STANDARD 99)
target_include_directories(GL4ES PRIVATE ${GL4ES_DIR}/include)
target_compile_options(GL4ES PRIVATE -O3 -funwind-tables -fvisibility=hidden
  -include ${GL4ES_DIR}/include/android_debug.h -Wno-everything)
target_compile_definitions(GL4ES PRIVATE BCMHOST NOX11 NO_GBM NO_INIT_CONSTRUCTOR NOEGL)
target_link_libraries(GL4ES PRIVATE dl log)
