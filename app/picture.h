/* See picture.c. */
#pragma once
#include <stdint.h>

#define PIC_W 1920
#define PIC_H 1080

/* The "fx" value packs the picture options chosen in the menu:
 * bit 0 ambient background, bits 1-2 colour, bits 3-4 sharpen, bits 5-6 effect,
 * bits 7-8 rotation (1 quarter turn right, 2 upside down, 3 quarter turn left). */
#define PIC_AMBIENT(fx) ((fx) & 1)
#define PIC_COLOUR(fx)  (((fx) >> 1) & 3)
#define PIC_SHARPEN(fx) (((fx) >> 3) & 3)
#define PIC_EFFECT(fx)  (((fx) >> 5) & 3)
#define PIC_ROTATE(fx)  (((fx) >> 7) & 3)
#define PIC_FRAME(fx)   (((fx) >> 9) & 1)
#define PIC_FXAA(fx)    (((fx) >> 10) & 1)
#define PIC_GHOST(fx)   (((fx) >> 11) & 3)   /* LCD ghosting: 0 off, 1 light, 2 strong */
#define PIC_BRIGHT(fx)  (((fx) >> 13) & 3)   /* 0 normal .. 3 brightest */

#define PIC_COLOUR_VIVID 1
#define PIC_COLOUR_WARM  2
#define PIC_EFFECT_SCANLINES 1
#define PIC_EFFECT_LCD       2
#define PIC_EFFECT_CRT       3

/* Upscalers ("filter" below). */
enum {
  PIC_UP_OFF = 0,
  PIC_UP_XBRZ2, PIC_UP_XBRZ3, PIC_UP_XBRZ4, PIC_UP_XBRZ5, PIC_UP_XBRZ6, /* 1..5 */
  PIC_UP_SUPERXBR,                                                      /* 6: 2x */
  PIC_UP_XBR2, PIC_UP_XBR3, PIC_UP_XBR4,                                /* 7..9 */
  PIC_UP_HQ2, PIC_UP_HQ3, PIC_UP_HQ4,                                   /* 10..12 */
  PIC_UP_SCALE2, PIC_UP_SCALE3,                                         /* 13, 14 */
  PIC_UP_SMOOTH,                                                        /* 15: bicubic resize */
  PIC_UP_COUNT
};

/* src: w*h pixels, 0xRRGGBB (changed in place by the colour option).
 * mode: 0 fit, 1 stretch, 2 whole-number enlargement. filter: one of PIC_UP_*.
 * fb: PIC_W*PIC_H pixels, 0xAARRGGBB. */
void picture_compose(uint32_t *src, int w, int h, int mode, int filter, int fx, uint32_t *fb);

/* Where the last picture was put on the TV: its rectangle, the size of the
 * (turned) game picture, and the rotation used. */
extern int pic_ox, pic_oy, pic_dw, pic_dh, pic_uw, pic_uh, pic_rot;

/* Phone frame: a 1920x1080 picture of a phone drawn by Java, a copy with every key
 * lit, the screen window the game goes in, and where each phone key is (code, x, y,
 * w, h). lit[code] is set while that key is held. main.c changes it under a lock. */
struct pic_frame {
  uint32_t *base, *litimg;
  int wx, wy, ww, wh;
  int nkeys;
  int keys[32][5];
  volatile unsigned char lit[64];
};
extern struct pic_frame pic_frame;
