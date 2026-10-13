#!/bin/bash
# Builds the OpenJDK 21 "Zero" (interpreter-only) virtual machine for the PS5 and a small test program.
# Tested on Ubuntu 24.04 with clang-18, openjdk-21-jdk and ps5-payload-sdk in /opt/ps5-payload-sdk.
# Needs: git curl make autoconf zip unzip clang-18 lld-18 openjdk-21-jdk libx11-dev libfontconfig1-dev
set -e
S=${PS5_PAYLOAD_SDK:-/opt/ps5-payload-sdk}
R=${1:-$HOME/ps5-workspace/j2me-port}
mkdir -p "$R" && cd "$R"
P=$R/ps5root; H=$R/hdr/inc
unset JAVA_TOOL_OPTIONS

# 1. libffi (the interpreter needs it); the PS5 target has no Windows calling convention, so strip that attribute
if [ ! -f $P/lib/libffi.a ]; then
  curl -sSL -o libffi.tgz https://github.com/libffi/libffi/releases/download/v3.4.6/libffi-3.4.6.tar.gz
  tar xf libffi.tgz && cd libffi-3.4.6
  sed -i 's/__attribute__((ms_abi))//g' src/x86/ffiw64.c
  CC=$S/bin/prospero-clang CXX=$S/bin/prospero-clang++ AR=$S/bin/prospero-ar RANLIB=$S/bin/prospero-ranlib \
    ./configure --host=x86_64-unknown-freebsd --prefix=$P --disable-shared --enable-static --disable-docs --disable-multi-os-directory
  make -j"$(nproc)" && make install && cd ..
fi

# 2. Headers that OpenJDK's configure insists on, even for a build with no display or sound
mkdir -p $H/alsa $H/cups $R/hdr/lib
[ -d hdr/cups ] || git clone --depth 1 -q https://github.com/OpenPrinting/cups.git hdr/cups
cp hdr/cups/cups/*.h $H/cups/
printf '#ifndef __ASOUNDLIB_H\n#define __ASOUNDLIB_H\n#define SND_LIB_VERSION 0x10209\n#endif\n' > $H/alsa/asoundlib.h
cp -r /usr/include/X11 /usr/include/fontconfig $H/
for f in X11/extensions/XTest.h X11/extensions/Xrandr.h X11/Intrinsic.h; do
  [ -f $H/$f ] || echo "/* stub for headless PS5 build */" > $H/$f
done

# 3. OpenJDK 21, FreeBSD port
[ -d jdk ] || git clone --depth 1 https://github.com/battleblow/jdk21u.git jdk
cd jdk
bash configure --openjdk-target=x86_64-unknown-freebsd --with-sysroot=$S --with-toolchain-type=clang \
 --with-jvm-variants=zero --enable-headless-only --with-debug-level=release --with-native-debug-symbols=none \
 --disable-warnings-as-errors --disable-precompiled-headers --with-boot-jdk=/usr/lib/jvm/java-21-openjdk-amd64 \
 --with-libffi=$P --with-freetype=bundled --with-libjpeg=bundled --with-libpng=bundled --with-zlib=bundled \
 --with-giflib=bundled --with-lcms=bundled --with-harfbuzz=bundled --disable-jvm-feature-dtrace --with-conf-name=ps5 \
 --with-alsa-include=$H --with-alsa-lib=$R/hdr/lib --with-cups-include=$H --with-fontconfig-include=$H \
 --x-includes=$H --x-libraries=$R/hdr/lib \
 CC=$S/bin/prospero-clang CXX=$S/bin/prospero-clang++ AR=$S/bin/prospero-ar NM=$S/bin/prospero-nm \
 STRIP=$S/bin/prospero-strip OBJCOPY=$S/bin/prospero-objcopy BUILD_CC=/usr/bin/clang BUILD_CXX=/usr/bin/clang++

# 4. Compile the virtual machine. The final link step fails (no -lm on PS5); that is expected, we only need the objects.
make CONF=ps5 hotspot MAKEFLAGS=-k || true
cd ..
rm -f $P/lib/libjvm.a
$S/bin/prospero-ar rcs $P/lib/libjvm.a jdk/build/ps5/hotspot/variant-zero/libjvm/objs/*.o

# 5. Stand-ins for the four functions the PS5 C library lacks, and a test program
cat > ps5stubs.c <<'EOS'
#include <stddef.h>
int dl_iterate_phdr(void *cb, void *data) { (void)cb; (void)data; return 0; }
int getpeereid(int s, void *uid, void *gid) { (void)s; (void)uid; (void)gid; return -1; }
void *getutxent(void) { return NULL; }
void setutxent(void) {}
EOS
cat > jvmtest.cpp <<'EOS'
#include <jni.h>
#include <stdio.h>
int main() {
    JavaVM *vm; JNIEnv *env; JavaVMInitArgs a; JavaVMOption o[4];
    o[0].optionString = (char*)"-Xmx128m";
    o[1].optionString = (char*)"-XX:+UseSerialGC";
    o[2].optionString = (char*)"-Djava.home=/data/jvm";
    o[3].optionString = (char*)"-Xshare:off";
    a.version = JNI_VERSION_10; a.nOptions = 4; a.options = o; a.ignoreUnrecognized = JNI_TRUE;
    printf("jvmtest: calling JNI_CreateJavaVM\n"); fflush(stdout);
    int r = JNI_CreateJavaVM(&vm, (void**)&env, &a);
    printf("jvmtest: JNI_CreateJavaVM returned %d\n", r); fflush(stdout);
    return 0;
}
EOS
$S/bin/prospero-clang -c ps5stubs.c -o ps5stubs.o
$S/bin/prospero-clang++ -Ijdk/src/java.base/share/native/include -Ijdk/src/java.base/unix/native/include \
  jvmtest.cpp ps5stubs.o -Wl,--whole-archive $P/lib/libjvm.a -Wl,--no-whole-archive $P/lib/libffi.a -o jvmtest.elf
ls -la jvmtest.elf
