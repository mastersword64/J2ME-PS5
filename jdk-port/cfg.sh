#!/bin/bash
# usage: cfg.sh  (run from jdk dir)
S=/opt/ps5-payload-sdk; R=$(cd .. && pwd); P=$R/ps5root; H=$R/hdr/inc
unset JAVA_TOOL_OPTIONS
bash configure --openjdk-target=x86_64-unknown-freebsd --with-sysroot=$S --with-toolchain-type=clang \
 --with-jvm-variants=zero --enable-headless-only --with-debug-level=release --with-native-debug-symbols=none \
 --disable-warnings-as-errors --with-boot-jdk=/usr/lib/jvm/java-21-openjdk-amd64 --with-libffi=$P \
 --with-freetype=bundled --with-libjpeg=bundled --with-libpng=bundled --with-zlib=bundled --with-giflib=bundled \
 --with-lcms=bundled --with-harfbuzz=bundled --disable-jvm-feature-dtrace --with-conf-name=ps5 \
 --with-alsa-include=$H --with-alsa-lib=$R/hdr/lib --with-cups-include=$H --with-fontconfig-include=$H --x-includes=$H --x-libraries=$R/hdr/lib --disable-precompiled-headers --with-extra-cflags=-DSTATIC_BUILD=1 --with-extra-cxxflags=-DSTATIC_BUILD=1 "$@" \
 CC=$S/bin/prospero-clang CXX=$S/bin/prospero-clang++ AR=$S/bin/prospero-ar NM=$S/bin/prospero-nm \
 STRIP=$S/bin/prospero-strip OBJCOPY=$S/bin/prospero-objcopy BUILD_CC=/usr/bin/clang BUILD_CXX=/usr/bin/clang++
