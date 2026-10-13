# Building J2ME Player for PS5

This is an advanced build: it cross-compiles a whole Java VM for the PS5. It
was done on Ubuntu 24.04 (x86-64).

## 1. Tools

- [ps5-payload-sdk](https://github.com/ps5-payload-dev/sdk) in `/opt/ps5-payload-sdk`
  (or set `PS5_PAYLOAD_SDK`).
- clang-18, lld-18, make, autoconf, git, zip, unzip, python3, `openjdk-21-jdk`
  (the boot JDK, and `javac`/`jar` for the front end).

## 2. Java for the PS5 (`jdk-port/`)

`jdk-port/build-jvm-ps5.sh` shows the whole sequence; the main steps are:

1. **libffi 3.4.6** cross-built as a static library.
2. **OpenJDK 21**, FreeBSD port ([battleblow/jdk21u](https://github.com/battleblow/jdk21u)),
   configured with `jdk-port/cfg.sh`:
   - Zero VM (interpreter only), headless;
   - target `x86_64-unknown-freebsd`;
   - `-DSTATIC_BUILD=1`.
3. **`jdk-port/patch_jdk.py`** adapts the sources to a single static binary with
   no `dlopen`:
   - library lookup goes through a generated symbol table;
   - the Java home is `$PS5_JAVA_HOME` or `/data/jvm`.
4. Build with `jdk-port/ps5-fakeld` as the linker. It packs objects into `.a`
   archives instead of linking shared libraries.
5. **`jdk-port/gen-symtab.py`** generates `symtab.c`, the table that replaces
   `dlsym`, from those archives. **`jdk-port/ps5stubs.c`** supplies the few C
   library functions the PS5 lacks.
6. **Sound:** `jdk-port/jsound-ps5/PLATFORM_API_PS5_PCM.c` is a Java Sound
   backend that mixes every line into one `sceAudioOut` port. Build it into
   `libjsound.a`.
7. **The runtime image:** `jlink` the modules `java.base`, `java.desktop`,
   `java.xml`, `java.datatransfer`, `java.prefs` and `java.logging` into
   `jvm/lib/modules`. Add DejaVu fonts and `jdk-port/fontconfig.properties`.
   The release zip `ps5j2me-java-runtime-1.0.zip` is exactly this folder.

`build-app.sh` expects this tree at `$JDK_PORT` (default `../j2me`), laid out
as:
- `jdk/` (the OpenJDK checkout, built as `build/ps5`)
- `jsound-ps5/libjsound.a`
- `ps5root/lib/libffi.a`
- `ps5stubs.o`
- `symtab.o`

## 3. SDL2 for the PS5

Build [ps5-payload-dev/SDL](https://github.com/ps5-payload-dev/SDL) with OpenGL
and audio off. Install it with prefix `/user/homebrew` into `deps/sdl-root`
(or set `SDL_ROOT`).

## 4. The emulator jars

Put these in `java/`:
- `freej2me-lr.jar`: FreeJ2ME-Plus **v1.52** release (libretro jar).
- `freej2me-new.jar`: FreeJ2ME-Plus built from commit `baca76a` with `ant`.

## 5. The app

```
cd tile && make && cd ..       # home-screen tile (needs launcher/ps5j2me-launch.elf: cd launcher && make)
./build-app.sh                 # -> dist/ps5j2me-install.elf
```

`build-app.sh` does the following:
- compiles the native app (`app/`): SDL display, controller, touch pointer,
  picture pipeline and upscalers;
- patches the emulator classes (`java/PatchCanvas.java`) into
  `ps5patch-classic.jar` and `ps5patch-new.jar`;
- builds the front end (`ps5frontend.jar`). The app starts in `PS5Boot`, which loads the front end and one emulator build in a class loader of its own (`PS5SysLoader` passes the game's class lookups to it), so the build can change without restarting;
- links everything with the Java VM into `eboot.elf`;
- packs the installer.

## Testing on a PC

`picture.c` has no PS5 dependencies, so it can be tested on a PC.

The Java front end runs on a PC too:
```
java -Djava.awt.headless=true -cp java/ps5frontend.jar:java/ps5patch-classic.jar:java/freej2me-lr.jar \
     PS5Frontend --selftest <games folder> <output folder>
```
It renders the menus to PNG files and plays the first game for a few seconds.
