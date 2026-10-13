# Third-party software

J2ME Player for PS5 is licensed under the GNU GPL v3 (see `LICENSE`). It
includes or builds on the following, each under its own licence.

| Component | Where | Licence | Source |
|---|---|---|---|
| FreeJ2ME-Plus v1.52 and commit `baca76a` | installed as `freej2me-lr.jar` / `freej2me-new.jar` | GPL v3 | https://github.com/TASEmulators/freej2me-plus |
| FreeJ2ME (original) | basis of FreeJ2ME-Plus | GPL v3 | https://github.com/hex007/freej2me |
| ObjectWeb ASM | inside the FreeJ2ME-Plus jars; used by `java/PatchCanvas.java` at build time | BSD 3-clause | https://asm.ow2.io/ |
| OpenJDK 21 (FreeBSD port) | the Java VM and class library linked into the app | GPL v2 with Classpath Exception | https://github.com/battleblow/jdk21u |
| libffi 3.4.6 | linked into the app | MIT | https://sourceware.org/libffi/ |
| SDL2 (PS5 port) | linked into the app | zlib | https://github.com/ps5-payload-dev/SDL |
| ps5-payload-sdk | build toolchain | GPL v3 / various | https://github.com/ps5-payload-dev/sdk |
| Homebrew loader code (`launcher/vendor`) | launcher | GPL v3, © John Törnblom | https://github.com/ps5-payload-dev/websrv |
| xBRZ (`app/xbrz`) | upscaler | GPL v3, © Zenju | https://sourceforge.net/projects/xbrz/ |
| HQ2x/3x/4x (`app/pas/lib/hq*`) | upscaler | LGPL 2.1, © Maxim Stepin, Cameron Zemek, Francois Gannaz | via https://github.com/ImageProcessing-ElectronicPublications/gimp-plugin-pixel-art-scalers |
| xBR (`app/pas/lib/xbr.c`) | upscaler | from the same collection (GPL v3); algorithm by Hyllian | as above |
| ScaleNx (`app/pas/lib/scaler_scalex.c`) | upscaler | from the same collection (GPL v3) | as above |
| Super-xBR (`app/superxbr.c`) | upscaler | written for this project; algorithm by Hyllian | — |
| Poppins font (`java/res/fonts`) | menus | SIL Open Font License 1.1 | https://fonts.google.com/specimen/Poppins |
| DejaVu fonts (Java runtime package) | fallback text in games | DejaVu Fonts License | https://dejavu-fonts.github.io/ |

Changes made to third-party code:
- **HQx and xBR:** their 64 MB colour lookup tables were replaced with a direct calculation.
- **xBRZ:** its header includes were adjusted for the PS5 toolchain.
- **FreeJ2ME-Plus:** not modified on disk. `java/PatchCanvas.java` rewrites a few classes when the app is built:
  - touch support follows the player's setting;
  - a crash in `Graphics3D.setDepthRange` is fixed;
  - screen-size detection hooks are added;
  - sound is set up only once.
