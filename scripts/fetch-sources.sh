#!/bin/sh
# Downloads the third-party sources the Android build uses into src/ (kept out of git):
#   OpenMoHAA (with the Android changes, branch rp6-android of the fork), SDL2, OpenAL Soft, gl4es.
set -eu

ROOT=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$ROOT/src"
cd "$ROOT/src"

tarball() {  # url file sha256 dir
    if [ -d "$4" ]; then echo "$4: already there"; return; fi
    curl -fL -o "$2" "$1"
    echo "$3  $2" | shasum -a 256 -c -
    tar xf "$2"
    rm "$2"
}

repo() {  # url branch commit dir
    if [ -d "$4" ]; then echo "$4: already there"; return; fi
    git clone --branch "$2" "$1" "$4"
    git -C "$4" checkout -q "$3"
}

# OpenMoHAA: openmoh/openmohaa, with emileb's Android port (mobile_master) and this project's
# changes on top (see that branch's history). Pinned to the commit the current release is built
# from, so a build from this tree matches the published APK.
repo https://github.com/cricastrocoli/openmohaa.git rp6-android c60436d9ed582fead4b42afcaaf6303cb1201b05 openmohaa

tarball https://github.com/libsdl-org/SDL/releases/download/release-2.32.8/SDL2-2.32.8.tar.gz \
    SDL2-2.32.8.tar.gz 0ca83e9c9b31e18288c7ec811108e58bac1f1bb5ec6577ad386830eac51c787e SDL2-2.32.8

tarball https://openal-soft.org/openal-releases/openal-soft-1.24.3.tar.bz2 \
    openal-soft-1.24.3.tar.bz2 cb5e6197a1c0da0edcf2a81024953cc8fa8545c3b9474e48c852af709d587892 \
    openal-soft-1.24.3

repo https://github.com/emileb/gl4es.git android_new fb286f8e97c8873a71d0ad4bc010fde1aca36442 gl4es

echo "Sources ready in $ROOT/src"
