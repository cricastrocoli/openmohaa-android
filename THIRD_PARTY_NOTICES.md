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
| innoextract 1.9 (`libinnoextract.so`, the GOG installer import; built by `native/build-innoextract.sh`) | Daniel Scharrer | zlib | https://constexpr.org/innoextract/ |
| Boost 1.83.0 (iostreams, filesystem, date_time, system, program_options; in innoextract) | Boost contributors | BSL-1.0 | https://www.boost.org/ |
| bzip2 1.0.8 (in innoextract) | Julian R Seward | bzip2 license (below) | https://sourceware.org/bzip2/ |
| liblzma from xz 5.8.1 (in innoextract) | Lasse Collin and contributors | 0BSD | https://tukaani.org/xz/ |

This software is based in part on the work of the Independent JPEG Group.

Medal of Honor is a trademark of Electronic Arts Inc. This project is not affiliated with or
endorsed by Electronic Arts. No game data is included: you need your own copy of the original
game files.

The Android port (`native/`, the Android project in `android/`) was developed by cricastrocoli
with the help of generative AI: Claude (Anthropic) for the development, DeepSeek for reviewing
the plan.

## bzip2 license

```
--------------------------------------------------------------------------

This program, "bzip2", the associated library "libbzip2", and all
documentation, are copyright (C) 1996-2019 Julian R Seward.  All
rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.

2. The origin of this software must not be misrepresented; you must 
   not claim that you wrote the original software.  If you use this 
   software in a product, an acknowledgment in the product 
   documentation would be appreciated but is not required.

3. Altered source versions must be plainly marked as such, and must
   not be misrepresented as being the original software.

4. The name of the author may not be used to endorse or promote 
   products derived from this software without specific prior written 
   permission.

THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS
OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Julian Seward, jseward@acm.org
bzip2/libbzip2 version 1.0.8 of 13 July 2019

--------------------------------------------------------------------------
```
