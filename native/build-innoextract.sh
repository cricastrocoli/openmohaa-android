#!/bin/sh
# Builds innoextract for Android (arm64), for the launcher's GOG installer import, into
# android/app/src/main/jniLibs/arm64-v8a/libinnoextract.so (a program, named like a library so
# Android installs it where apps may run it).
#
# Sources, downloaded into build-tools-src/ (not kept in git):
#   innoextract 1.9  https://constexpr.org/innoextract/        zlib license
#   Boost 1.83.0     https://www.boost.org/                    Boost Software License 1.0
#   bzip2 1.0.8      https://sourceware.org/bzip2/             bzip2 license (BSD-style)
#   xz 5.8.1         https://tukaani.org/xz/ (liblzma)         0BSD
# zlib is Android's own (libz.so).
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
NDK=$SDK/ndk/27.2.12479018
CMAKE_DIR=$SDK/cmake/3.31.6/bin
case "$(uname)" in Darwin) HOST=darwin-x86_64 ;; *) HOST=linux-x86_64 ;; esac
TC=$NDK/toolchains/llvm/prebuilt/$HOST
API=26
SRC=$ROOT/build-tools-src
OUT=$SRC/out-android
mkdir -p "$SRC"
cd "$SRC"

fetch() {  # url file sha256
    [ -f "$2" ] || curl -fL -o "$2" "$1"
    echo "$3  $2" | shasum -a 256 -c -
    [ -d "${2%.tar.*}" ] || tar xf "$2"
}
fetch https://constexpr.org/innoextract/files/innoextract-1.9.tar.gz innoextract-1.9.tar.gz \
    6344a69fc1ed847d4ed3e272e0da5998948c6b828cb7af39c6321aba6cf88126
fetch https://archives.boost.io/release/1.83.0/source/boost_1_83_0.tar.bz2 boost_1_83_0.tar.bz2 \
    6478edfe2f3305127cffe8caf73ea0176c53769f4bf1585be237eb30798c3b8e
fetch https://sourceware.org/pub/bzip2/bzip2-1.0.8.tar.gz bzip2-1.0.8.tar.gz \
    ab5a03176ee106d3f0fa90e381da478ddae405918153cca248e682cd0c4a2269
fetch https://github.com/tukaani-project/xz/releases/download/v5.8.1/xz-5.8.1.tar.gz xz-5.8.1.tar.gz \
    507825b599356c10dca1cd720c9d0d0c9d5400b9de300af00e4d1ea150795543

# Boost: only the libraries innoextract uses, static.
cd boost_1_83_0
[ -x b2 ] || ./bootstrap.sh --with-libraries=iostreams,filesystem,date_time,system,program_options
cat > user-config.jam <<EOF
using clang : android : $TC/bin/aarch64-linux-android$API-clang++ : <archiver>$TC/bin/llvm-ar <ranlib>$TC/bin/llvm-ranlib <cxxflags>-fPIC <cflags>-fPIC ;
EOF
./b2 -j8 --user-config=user-config.jam toolset=clang-android target-os=android architecture=arm \
    address-model=64 link=static threading=multi variant=release \
    --with-iostreams --with-filesystem --with-date_time --with-system --with-program_options \
    -sBZIP2_SOURCE="$SRC/bzip2-1.0.8" -sZLIB_INCLUDE="$TC/sysroot/usr/include" \
    -sZLIB_LIBPATH="$TC/sysroot/usr/lib/aarch64-linux-android/$API" -sNO_LZMA=1 -sNO_ZSTD=1 \
    --prefix="$OUT" --build-dir="$SRC/boost-build" install > "$SRC/boost-b2.log"
cd ..

ANDROID="-G Ninja -DCMAKE_MAKE_PROGRAM=$CMAKE_DIR/ninja -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=$API -DCMAKE_BUILD_TYPE=Release"

# liblzma, decoder side only needed (the encoders are kept: xz's build requires them).
"$CMAKE_DIR/cmake" -S xz-5.8.1 -B xz-build $ANDROID -DBUILD_SHARED_LIBS=OFF -DXZ_TOOL_XZ=OFF \
    -DXZ_TOOL_XZDEC=OFF -DXZ_TOOL_LZMADEC=OFF -DXZ_TOOL_LZMAINFO=OFF -DXZ_TOOL_SCRIPTS=OFF \
    -DXZ_TOOL_SYMLINKS=OFF -DXZ_DOC=OFF -DXZ_NLS=OFF -DCMAKE_INSTALL_PREFIX="$OUT" > xz-cmake.log
"$CMAKE_DIR/cmake" --build xz-build --target install > xz-build.log

# innoextract, linked statically (C++ runtime included).
"$CMAKE_DIR/cmake" -S innoextract-1.9 -B ie-build $ANDROID -DANDROID_STL=c++_static \
    -DCMAKE_FIND_ROOT_PATH="$OUT" -DBoost_NO_SYSTEM_PATHS=ON -DBOOST_ROOT="$OUT" \
    -DBoost_INCLUDE_DIR="$OUT/include" -DBoost_DIR="$OUT/lib/cmake/Boost-1.83.0" \
    -DLZMA_INCLUDE_DIR="$OUT/include" -DLZMA_LIBRARY="$OUT/lib/liblzma.a" -DUSE_STATIC_LIBS=ON \
    -DZLIB_USE_STATIC_LIBS=OFF -DWITH_CONV=builtin -DUSE_LTO=OFF -DUSE_LDGOLD=OFF > ie-cmake.log
"$CMAKE_DIR/cmake" --build ie-build > ie-build.log

DEST=$ROOT/android/app/src/main/jniLibs/arm64-v8a
mkdir -p "$DEST"
"$TC/bin/llvm-strip" -o "$DEST/libinnoextract.so" ie-build/innoextract
echo "Built $DEST/libinnoextract.so"
