# Third-party software

OpenMoHAA Android is free software under the GNU General Public License, version 2 (see
[LICENSE](LICENSE)). It is built from, and its APK contains, the following:

| Component | Authors | License | Source |
|---|---|---|---|
| OpenMoHAA | The OpenMoHAA team | GPL-2.0-or-later | https://github.com/openmoh/openmohaa |
| Android engine changes (`mobile_master`) | emileb | GPL-2.0-or-later | https://github.com/emileb/openmohaa |
| ioquake3 / Quake III Arena engine (in OpenMoHAA) | id Software, ioquake3 contributors | GPL-2.0-or-later | https://github.com/ioquake/ioq3 |
| SDL 2.32.8 (native library and Android Java project) | Sam Lantinga and contributors | zlib | https://github.com/libsdl-org/SDL |
| OpenAL Soft 1.24.3 (`libopenal.so`, unmodified) | Chris Robinson and contributors | LGPL-2.0-or-later | https://github.com/kcat/openal-soft |
| gl4es (`android_new` branch) | ptitSeb and contributors | MIT | https://github.com/emileb/gl4es |
| zlib 1.3.1 | Jean-loup Gailly, Mark Adler | zlib | bundled in OpenMoHAA |
| libjpeg 9f | Independent JPEG Group | IJG | bundled in OpenMoHAA |
| libogg 1.3.6, libvorbis 1.3.7 | Xiph.Org Foundation | BSD-3-Clause | bundled in OpenMoHAA |
| libmad | Underbit Technologies | GPL-2.0-or-later | bundled in OpenMoHAA |
| Recast Navigation | Mikko Mononen and contributors | zlib | bundled in OpenMoHAA |
| GameSpy SDK (in OpenMoHAA, disabled on Android) | GameSpy Industries | no explicit license | bundled in OpenMoHAA |
| LLVM libc++ (`libc++_shared.so`, Android NDK) | LLVM project | Apache-2.0 WITH LLVM-exception | https://github.com/llvm/llvm-project |

This software is based in part on the work of the Independent JPEG Group.

Medal of Honor is a trademark of Electronic Arts Inc. This project is not affiliated with or
endorsed by Electronic Arts. No game data is included: you need your own copy of the original
game files.

The Android port (`native/`, the Android project in `android/`) was developed by cricastrocoli
with the help of generative AI: Claude (Anthropic) for the development, DeepSeek for reviewing
the plan.
