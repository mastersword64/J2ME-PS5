#!/bin/bash
unset JAVA_TOOL_OPTIONS
# Compiles and links the app, then rebuilds the installer that carries it.
# usage: ./build-app.sh [output.elf]      (see BUILDING.md)
#   JDK_PORT  folder with the PS5 Java build (jdk/, jsound-ps5/, ps5root/, ps5stubs.o, symtab.o); default ../j2me
#   SDL_ROOT  SDL2 for the PS5 installed with prefix /user/homebrew; default deps/sdl-root
#   OUT       where the installer is copied; default ./dist
set -e
cd "$(dirname "$0")"
export PS5_PAYLOAD_SDK=${PS5_PAYLOAD_SDK:-/opt/ps5-payload-sdk}
JDK_PORT=${JDK_PORT:-../j2me}
SDL_ROOT=${SDL_ROOT:-deps/sdl-root}
OUT=${OUT:-dist}
mkdir -p "$OUT"
/opt/ps5-payload-sdk/bin/prospero-clang -O2 -Wall -c app/main.c -I$SDL_ROOT/user/homebrew/include -I$JDK_PORT/jdk/src/java.base/share/native/include -I$JDK_PORT/jdk/src/java.base/unix/native/include -I$JDK_PORT/jdk/build/ps5/support/modules_include/java.base -o app/main.o
/opt/ps5-payload-sdk/bin/prospero-clang -O2 -Wall -c app/relpath.c -o app/relpath.o
/opt/ps5-payload-sdk/bin/prospero-clang -O2 -Wall -c app/picture.c -o app/picture.o
/opt/ps5-payload-sdk/bin/prospero-clang -O2 -Wall -c app/superxbr.c -o app/superxbr.o
for f in hq2x hq3x hq4x xbr scaler_scalex; do /opt/ps5-payload-sdk/bin/prospero-clang -O2 -w -Iapp/pas -c app/pas/lib/$f.c -o app/pas_$f.o; done
/opt/ps5-payload-sdk/bin/prospero-clang++ -O2 -std=c++17 -w -c app/xbrz/xbrz.cpp -o app/xbrz.o
/opt/ps5-payload-sdk/bin/prospero-clang++ -O2 -std=c++17 -c app/upscale_xbrz.cpp -o app/upscale_xbrz.o
(cd java && rm -rf cls toolcls cls-classic cls-new && mkdir cls toolcls cls-classic cls-new \
 && javac -nowarn -cp freej2me-lr.jar -d toolcls PatchCanvas.java 2>/dev/null \
 && java -cp toolcls:freej2me-lr.jar PatchCanvas freej2me-lr.jar cls-classic \
 && java -cp toolcls:freej2me-new.jar PatchCanvas freej2me-new.jar cls-new \
 && javac --release 21 -nowarn -d cls PS5Boot.java PS5SysLoader.java \
 && javac --release 8 -nowarn -cp freej2me-lr.jar -d cls PS5Frontend.java PS5Touch.java PS5Probe.java PS5Media.java PS5Unload.java GameSize.java Ui.java 2>/dev/null \
 && mkdir -p cls/fonts && cp res/fonts/* cls/fonts/ && mkdir -p cls/bezels && cp res/bezels/*.jpg cls/bezels/ \
 && jar cf ps5frontend.jar -C cls . && jar cf ps5patch-classic.jar -C cls-classic . && jar cf ps5patch-new.jar -C cls-new .)
/opt/ps5-payload-sdk/bin/prospero-clang++ -Wl,--wrap=open,--wrap=fopen,--wrap=stat,--wrap=lstat,--wrap=access,--wrap=mkdir,--wrap=rmdir,--wrap=unlink,--wrap=remove,--wrap=rename,--wrap=opendir,--wrap=chmod,--wrap=utimes,--wrap=truncate,--wrap=realpath,--wrap=readlink app/main.o app/relpath.o app/picture.o app/superxbr.o app/pas_hq2x.o app/pas_hq3x.o app/pas_hq4x.o app/pas_xbr.o app/pas_scaler_scalex.o app/xbrz.o app/upscale_xbrz.o $JDK_PORT/ps5stubs.o $JDK_PORT/symtab.o -Wl,--whole-archive $JDK_PORT/jdk/build/ps5/support/modules_libs/java.base/zero/libjvm.a -Wl,--no-whole-archive -Wl,--start-group $JDK_PORT/jdk/build/ps5/support/modules_libs/java.base/libjava.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.base/libzip.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.base/libnio.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.base/libnet.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.base/libjimage.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.base/libverify.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.prefs/libprefs.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.desktop/libawt.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.desktop/libawt_headless.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.desktop/libmlib_image.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.desktop/libfontmanager.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.desktop/libfreetype.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.desktop/liblcms.a $JDK_PORT/jdk/build/ps5/support/modules_libs/java.desktop/libjavajpeg.a $JDK_PORT/jsound-ps5/libjsound.a -Wl,--end-group $JDK_PORT/ps5root/lib/libffi.a $SDL_ROOT/user/homebrew/lib/libSDL2.a -lSceSystemService -lSceUserService -lScePad -lSceVideoOut -lSceKeyboard -lSceImeDialog -lSceAudioOut -lpthread -o app/eboot.elf
make -s -C installer clean all > /dev/null
cp installer/ps5j2me-install.elf "$OUT/${1:-ps5j2me-install.elf}"
ls -la "$OUT/${1:-ps5j2me-install.elf}"
