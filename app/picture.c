/* Turns one game frame into the 1920x1080 TV picture.
 *
 * Steps, in order: colour adjustment (at the game's own size, where it is
 * cheap), xBRZ upscaling, placement and smooth resizing, sharpening, a
 * scanline or LCD-grid effect, and the background around the picture.
 * Nothing here knows about SDL or Java, so it can be tested on a PC. */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "picture.h"

#define UP_MAX (2880 * 2880)

void xbrz_upscale(int factor, const uint32_t *src, uint32_t *dst, int w, int h);
void superxbr_2x(const uint32_t *src, uint32_t *dst, int w, int h);
void hq2x_32(uint32_t *src, uint32_t *dest, int width, int height);
void hq3x_32(uint32_t *src, uint32_t *dest, int width, int height);
void hq4x_32(uint32_t *src, uint32_t *dest, int width, int height);
void xbr_filter_xbr2x(uint32_t *, uint32_t *, int, int);
void xbr_filter_xbr3x(uint32_t *, uint32_t *, int, int);
void xbr_filter_xbr4x(uint32_t *, uint32_t *, int, int);
void scaler_scalex_2x(uint32_t *, uint32_t *, int, int);
void scaler_scalex_3x(uint32_t *, uint32_t *, int, int);

static inline int
clamp255(int v) {
  return v < 0 ? 0 : v > 255 ? 255 : v;
}

/* ---- colour: more saturation and contrast ("vivid"), or a warm tint ---- */

static void
adjust_colour(uint32_t *px, int n, int mode) {
  static uint8_t curve[256];
  static int curve_ready;

  if(!curve_ready) { /* gentle S-curve: deeper darks, brighter lights */
    for(int i = 0; i < 256; i++) {
      double x = i / 255.0, y = x * x * (3 - 2 * x);
      curve[i] = (uint8_t)clamp255((int)((x * 0.45 + y * 0.55) * 255 + 0.5));
    }
    curve_ready = 1;
  }
  for(int i = 0; i < n; i++) {
    int r = (px[i] >> 16) & 255, g = (px[i] >> 8) & 255, b = px[i] & 255;
    int grey = (r * 77 + g * 150 + b * 29) >> 8;

    if(mode == PIC_COLOUR_VIVID) {
      r = curve[clamp255(grey + (r - grey) * 21 / 16)];
      g = curve[clamp255(grey + (g - grey) * 21 / 16)];
      b = curve[clamp255(grey + (b - grey) * 21 / 16)];
    } else { /* warm */
      r = clamp255(r * 17 / 16 + 4);
      b = b * 14 / 16;
    }
    px[i] = (uint32_t)(r << 16 | g << 8 | b);
  }
}

/* ---- brightness: lifts the darks and mids (gamma), keeps white white ---- */

static void
brighten(uint32_t *px, int n, int level) {
  static uint8_t lut[4][256];
  static int ready;

  if(!ready) {
    static const double gam[4] = { 1.0, 0.85, 0.72, 0.6 };
    for(int l = 0; l < 4; l++)
      for(int i = 0; i < 256; i++) lut[l][i] = (uint8_t)clamp255((int)(pow(i / 255.0, gam[l]) * 255 + 0.5));
    ready = 1;
  }
  const uint8_t *t = lut[level & 3];
  for(int i = 0; i < n; i++) {
    uint32_t p = px[i];
    px[i] = (uint32_t)t[(p >> 16) & 255] << 16 | (uint32_t)t[(p >> 8) & 255] << 8 | t[p & 255];
  }
}

/* ---- anti-aliasing: a small FXAA at the game's own size ----
 * Where the brightness changes sharply, the pixel is blended with its
 * neighbours across the edge, which smooths stair-steps on 3D models. */

static inline int luma(uint32_t p) { return (((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 150 + (p & 255) * 29) >> 8; }

static void
fxaa(uint32_t *px, int w, int h) {
  static uint32_t *copy;
  static size_t room;
  size_t n = (size_t)w * h;

  if(n > room) { free(copy); copy = malloc(n * 4); room = copy ? n : 0; }
  if(!copy) return;
  memcpy(copy, px, n * 4);
  for(int y = 1; y < h - 1; y++) {
    const uint32_t *c = copy + (size_t)y * w;
    for(int x = 1; x < w - 1; x++) {
      int lc = luma(c[x]), ln = luma(c[x - w]), ls = luma(c[x + w]), lw = luma(c[x - 1]), le = luma(c[x + 1]);
      int lo = lc, hi = lc;
      lo = lo < ln ? lo : ln;
      lo = lo < ls ? lo : ls;
      lo = lo < lw ? lo : lw;
      lo = lo < le ? lo : le;
      hi = hi > ln ? hi : ln;
      hi = hi > ls ? hi : ls;
      hi = hi > lw ? hi : lw;
      hi = hi > le ? hi : le;
      int range = hi - lo;
      if(range < 24 || range < hi / 8) continue; /* flat area */
      int vert = abs(ln + ls - 2 * lc), horz = abs(lw + le - 2 * lc);
      uint32_t a = vert >= horz ? c[x - w] : c[x - 1], b = vert >= horz ? c[x + w] : c[x + 1];
      /* blend amount grows with how much the pixel stands out from its neighbours */
      int avg = (ln + ls + lw + le) / 4, sub = abs(avg - lc) * 256 / (range + 1);
      int k = sub > 192 ? 96 : sub / 2; /* share of the neighbours, 0..96 of 256 */
      uint32_t ab = (((a & 0xfefefe) >> 1) + ((b & 0xfefefe) >> 1));
      uint32_t p = c[x];
      px[(size_t)y * w + x] = ((((p & 0xff00ff) * (256 - k) + (ab & 0xff00ff) * k) >> 8) & 0xff00ff) |
                              ((((p & 0x00ff00) * (256 - k) + (ab & 0x00ff00) * k) >> 8) & 0x00ff00);
    }
  }
}

/* ---- LCD ghosting: each frame keeps a little of the one before, like the slow
 * liquid crystals of old phone screens ---- */

static void
ghost(uint32_t *px, int w, int h, int level) {
  static uint32_t *prev;
  static int pw, ph;
  size_t n = (size_t)w * h;
  int k = level == 1 ? 90 : 150; /* share of the previous frame, of 256 */

  if(!level) { pw = ph = 0; return; }
  if(w != pw || h != ph) {
    free(prev);
    prev = malloc(n * 4);
    if(!prev) { pw = ph = 0; return; }
    memcpy(prev, px, n * 4);
    pw = w;
    ph = h;
    return;
  }
  for(size_t i = 0; i < n; i++) {
    uint32_t a = px[i], b = prev[i];
    uint32_t m = ((((a & 0xff00ff) * (256 - k) + (b & 0xff00ff) * k) >> 8) & 0xff00ff) |
                 ((((a & 0x00ff00) * (256 - k) + (b & 0x00ff00) * k) >> 8) & 0x00ff00);
    px[i] = prev[i] = m;
  }
}

/* ---- resizing ---- */

/* For each output position along one axis: source index and blend weight (0..256). */
static void
axis_table(int src, int dst, int *idx, int *wgt) {
  double scale = (double)dst / src;

  for(int i = 0; i < dst; i++) {
    double pos = (i + 0.5) / scale - 0.5, f;
    int i0 = (int)(pos < 0 ? pos - 1 : pos);

    f = pos - i0;
    if(scale > 1) f = (f - 0.5) * scale + 0.5; /* enlarging: blend only within one output pixel of an edge */
    if(f < 0) f = 0;
    if(f > 1) f = 1;
    if(i0 < 0) { i0 = 0; f = 0; }
    if(i0 >= src - 1) { i0 = src - 1; f = 0; }
    idx[i] = i0;
    wgt[i] = (int)(f * 256);
  }
}

static inline uint32_t
mix(uint32_t a, uint32_t b, int w) { /* w = share of b, 0..256 */
  uint32_t rb = ((a & 0xff00ff) * (256 - w) + (b & 0xff00ff) * w) >> 8;
  uint32_t g  = ((a & 0x00ff00) * (256 - w) + (b & 0x00ff00) * w) >> 8;
  return (rb & 0xff00ff) | (g & 0x00ff00);
}

/* ---- background: a blurred, darkened copy of the game behind the picture ---- */

#define BG_W 32
#define BG_H 18

static void
ambient_background(const uint32_t *src, int w, int h, uint32_t *fb, int ox, int oy, int dw, int dh) {
  static int xi[PIC_W], xw[PIC_W], yi[PIC_H], yw[PIC_H], ready;
  uint32_t small[BG_W * BG_H], tmp[BG_W * BG_H];

  for(int y = 0; y < BG_H; y++) { /* average the game picture down to a few big blocks */
    int y0 = y * h / BG_H, y1 = (y + 1) * h / BG_H;
    if(y1 <= y0) y1 = y0 + 1;
    for(int x = 0; x < BG_W; x++) {
      int x0 = x * w / BG_W, x1 = (x + 1) * w / BG_W, n = 0;
      int sy = (y1 - y0) / 6 + 1, sx;
      unsigned r = 0, g = 0, b = 0;
      if(x1 <= x0) x1 = x0 + 1;
      sx = (x1 - x0) / 6 + 1;
      for(int yy = y0; yy < y1 && yy < h; yy += sy) {
        for(int xx = x0; xx < x1 && xx < w; xx += sx) {
          uint32_t p = src[yy * w + xx];
          r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++;
        }
      }
      if(!n) n = 1;
      small[y * BG_W + x] = (r / n) << 16 | (g / n) << 8 | (b / n);
    }
  }
  for(int pass = 0; pass < 3; pass++) { /* blur */
    for(int y = 0; y < BG_H; y++) {
      for(int x = 0; x < BG_W; x++) {
        unsigned r = 0, g = 0, b = 0, n = 0;
        for(int dy = -1; dy <= 1; dy++) {
          for(int dx = -1; dx <= 1; dx++) {
            int yy = y + dy, xx = x + dx;
            uint32_t p;
            if(yy < 0 || yy >= BG_H || xx < 0 || xx >= BG_W) continue;
            p = small[yy * BG_W + xx];
            r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; n++;
          }
        }
        tmp[y * BG_W + x] = (r / n) << 16 | (g / n) << 8 | (b / n);
      }
    }
    memcpy(small, tmp, sizeof small);
  }
  for(int i = 0; i < BG_W * BG_H; i++) { /* darken so the game stays the focus */
    uint32_t p = small[i];
    small[i] = ((((p >> 16) & 255) * 2 / 5) << 16) | ((((p >> 8) & 255) * 2 / 5) << 8) | ((p & 255) * 2 / 5);
  }
  if(!ready) { /* plain smooth enlargement to the whole screen; the tables never change */
    for(int x = 0; x < PIC_W; x++) {
      double pos = (x + 0.5) * BG_W / PIC_W - 0.5;
      int i0 = pos < 0 ? 0 : (int)pos;
      if(i0 > BG_W - 2) i0 = BG_W - 2;
      xi[x] = i0;
      xw[x] = pos < 0 ? 0 : pos > BG_W - 1 ? 256 : (int)((pos - i0) * 256);
    }
    for(int y = 0; y < PIC_H; y++) {
      double pos = (y + 0.5) * BG_H / PIC_H - 0.5;
      int i0 = pos < 0 ? 0 : (int)pos;
      if(i0 > BG_H - 2) i0 = BG_H - 2;
      yi[y] = i0;
      yw[y] = pos < 0 ? 0 : pos > BG_H - 1 ? 256 : (int)((pos - i0) * 256);
    }
    ready = 1;
  }
  for(int y = 0; y < PIC_H; y++) {
    const uint32_t *r0 = small + yi[y] * BG_W, *r1 = r0 + BG_W;
    uint32_t *dst = fb + y * PIC_W;
    int inside_rows = y >= oy && y < oy + dh;

    for(int x = 0; x < PIC_W; x++) {
      uint32_t top, bot;
      if(inside_rows && x == ox) { x = ox + dw - 1; continue; } /* the game picture covers this part */
      top = mix(r0[xi[x]], r0[xi[x] + 1], xw[x]);
      bot = mix(r1[xi[x]], r1[xi[x] + 1], xw[x]);
      dst[x] = 0xff000000u | mix(top, bot, yw[y]);
    }
  }
}

/* ---- smooth resizing: Catmull-Rom bicubic, for 3D games ---- */

static void
bicubic(const uint32_t *src, int w, int h, uint32_t *dst, int dw, int dh, int stride) {
  static short *wx, *wy;
  static int *ix, *iy, cdw, cdh, cw, ch;
  static int32_t *tmp; /* h rows of dw pixels, 3 channels, fixed point */
  static size_t troom;

  if(dw != cdw || w != cw || !wx) {
    free(wx); free(ix);
    wx = malloc((size_t)dw * 4 * sizeof(short)); ix = malloc((size_t)dw * sizeof(int));
    if(!wx || !ix) { cdw = 0; return; }
    for(int x = 0; x < dw; x++) {
      double pos = (x + 0.5) * w / dw - 0.5;
      int i0 = (int)floor(pos);
      double t = pos - i0, t2 = t * t, t3 = t2 * t;
      double k[4] = { -0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1, -1.5 * t3 + 2 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2 };
      ix[x] = i0 - 1;
      for(int j = 0; j < 4; j++) wx[x * 4 + j] = (short)lrint(k[j] * 256);
    }
    cdw = dw; cw = w;
  }
  if(dh != cdh || h != ch || !wy) {
    free(wy); free(iy);
    wy = malloc((size_t)dh * 4 * sizeof(short)); iy = malloc((size_t)dh * sizeof(int));
    if(!wy || !iy) { cdh = 0; return; }
    for(int y = 0; y < dh; y++) {
      double pos = (y + 0.5) * h / dh - 0.5;
      int i0 = (int)floor(pos);
      double t = pos - i0, t2 = t * t, t3 = t2 * t;
      double k[4] = { -0.5 * t3 + t2 - 0.5 * t, 1.5 * t3 - 2.5 * t2 + 1, -1.5 * t3 + 2 * t2 + 0.5 * t, 0.5 * t3 - 0.5 * t2 };
      iy[y] = i0 - 1;
      for(int j = 0; j < 4; j++) wy[y * 4 + j] = (short)lrint(k[j] * 256);
    }
    cdh = dh; ch = h;
  }
  size_t need = (size_t)h * dw * 3;
  if(need > troom) { free(tmp); tmp = malloc(need * sizeof(int32_t)); troom = tmp ? need : 0; }
  if(!tmp) return;
  for(int y = 0; y < h; y++) { /* across */
    const uint32_t *row = src + (size_t)y * w;
    int32_t *t = tmp + (size_t)y * dw * 3;
    for(int x = 0; x < dw; x++) {
      int r = 0, g = 0, b = 0;
      for(int j = 0; j < 4; j++) {
        int sx = ix[x] + j;
        uint32_t p = row[sx < 0 ? 0 : sx >= w ? w - 1 : sx];
        int k = wx[x * 4 + j];
        r += (int)((p >> 16) & 255) * k; g += (int)((p >> 8) & 255) * k; b += (int)(p & 255) * k;
      }
      t[x * 3] = r; t[x * 3 + 1] = g; t[x * 3 + 2] = b;
    }
  }
  for(int y = 0; y < dh; y++) { /* down */
    uint32_t *d = dst + (size_t)y * stride;
    const int32_t *rows[4];
    for(int j = 0; j < 4; j++) { int sy = iy[y] + j; rows[j] = tmp + (size_t)(sy < 0 ? 0 : sy >= h ? h - 1 : sy) * dw * 3; }
    const short *k = wy + y * 4;
    for(int x = 0; x < dw; x++) {
      int64_t r = 0, g = 0, b = 0;
      for(int j = 0; j < 4; j++) { const int32_t *t = rows[j] + x * 3; r += (int64_t)t[0] * k[j]; g += (int64_t)t[1] * k[j]; b += (int64_t)t[2] * k[j]; }
      d[x] = 0xff000000u | (uint32_t)clamp255((int)(r >> 16)) << 16 | (uint32_t)clamp255((int)(g >> 16)) << 8 | (uint32_t)clamp255((int)(b >> 16));
    }
  }
}

/* ---- CRT look: scanlines, a shadow mask, a gently curved glass and darker corners ---- */

static void
crt(uint32_t *fb, int ox, int oy, int dw, int dh, int w0, int h0) {
  static int *map, mw, mh;
  static uint16_t *vig;
  static uint32_t *copy;

  if(dw != mw || dh != mh) {
    free(map); free(vig); free(copy);
    map = malloc((size_t)dw * dh * sizeof(int));
    vig = malloc((size_t)dw * dh * sizeof(uint16_t));
    copy = malloc((size_t)dw * dh * 4);
    if(!map || !vig || !copy) { mw = mh = 0; return; }
    for(int y = 0; y < dh; y++) {
      for(int x = 0; x < dw; x++) {
        double u = (x + 0.5) / dw * 2 - 1, v = (y + 0.5) / dh * 2 - 1;
        double su = u * (1 + 0.035 * v * v), sv = v * (1 + 0.05 * u * u); /* barrel */
        int sx = (int)((su + 1) / 2 * dw), sy = (int)((sv + 1) / 2 * dh);
        map[(size_t)y * dw + x] = sx < 0 || sy < 0 || sx >= dw || sy >= dh ? -1 : sy * dw + sx;
        double e = (1 - 0.18 * u * u) * (1 - 0.22 * v * v); /* corners darker */
        vig[(size_t)y * dw + x] = (uint16_t)(e * 330); /* 256 = 1.0; lifted to make up for the mask and scanlines */
      }
    }
    mw = dw;
    mh = dh;
  }
  /* scanlines on the game's rows and an aperture-grille mask on TV columns */
  for(int y = 0; y < dh; y++) {
    int cell0 = (int)((long)y * h0 / dh), size = dh / h0 > 0 ? dh / h0 : 1;
    int into = y - (int)((long)cell0 * dh / h0);
    int sl = size >= 3 ? (into * 3 >= size * 2 ? 150 : 256) : (y & 1 ? 200 : 256);
    uint32_t *src = fb + (size_t)(oy + y) * PIC_W + ox, *dst = copy + (size_t)y * dw;
    for(int x = 0; x < dw; x++) {
      uint32_t p = src[x];
      int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255, m = x % 3;
      r = r * (m == 0 ? 256 : 190) >> 8; g = g * (m == 1 ? 256 : 190) >> 8; b = b * (m == 2 ? 256 : 190) >> 8;
      dst[x] = (uint32_t)(r * sl >> 8) << 16 | (uint32_t)(g * sl >> 8) << 8 | (uint32_t)(b * sl >> 8);
    }
  }
  for(int y = 0; y < dh; y++) {
    uint32_t *dst = fb + (size_t)(oy + y) * PIC_W + ox;
    const int *mp = map + (size_t)y * dw;
    const uint16_t *vg = vig + (size_t)y * dw;
    for(int x = 0; x < dw; x++) {
      if(mp[x] < 0) { dst[x] = 0xff000000u; continue; }
      uint32_t p = copy[mp[x]];
      int k = vg[x];
      dst[x] = 0xff000000u | (uint32_t)clamp255((int)((p >> 16) & 255) * k >> 8) << 16 |
               (uint32_t)clamp255((int)((p >> 8) & 255) * k >> 8) << 8 | (uint32_t)clamp255((int)(p & 255) * k >> 8);
    }
  }
}

/* ---- the whole picture ---- */

/* Where the last picture went on the TV, for the touch pointer (see picture.h). */
int pic_ox, pic_oy, pic_dw, pic_dh, pic_uw, pic_uh, pic_rot;
struct pic_frame pic_frame;

static void
compose_upright(uint32_t *src, int w, int h, int mode, int filter, int fx, uint32_t *fb) {
  static int xi[PIC_W], xw[PIC_W], yi[PIC_H], yw[PIC_H], rowmul[PIC_H], colmul[PIC_W];
  static uint32_t *up, *work;
  const uint32_t *pic = src;
  int w0 = w, h0 = h, dw, dh, ox, oy;
  int colour = PIC_COLOUR(fx), sharpen = PIC_SHARPEN(fx), effect = PIC_EFFECT(fx);
  uint32_t *out;

  if(w <= 0 || h <= 0) return;
  if(colour) adjust_colour(src, w * h, colour);
  if(PIC_BRIGHT(fx)) brighten(src, w * h, PIC_BRIGHT(fx));
  if(PIC_FXAA(fx) && w > 2 && h > 2) fxaa(src, w, h);
  ghost(src, w, h, PIC_GHOST(fx));

  /* The area the picture goes in: the whole TV, or the phone frame's screen. */
  int framed = PIC_FRAME(fx) && pic_frame.base;
  int AX = framed ? pic_frame.wx : 0, AY = framed ? pic_frame.wy : 0;
  int AW = framed ? pic_frame.ww : PIC_W, AH = framed ? pic_frame.wh : PIC_H;

  /* Where the picture goes on the TV. */
  if(mode == 1) {
    dw = AW;
    dh = AH;
  } else if(mode == 2) {
    int s = AW / w < AH / h ? AW / w : AH / h;
    if(s < 1) s = 1;
    dw = w * s;
    dh = h * s;
  } else if((long)AW * h <= (long)AH * w) { /* wider than the area */
    dw = AW;
    dh = (int)((long)AW * h / w);
  } else {
    dh = AH;
    dw = (int)((long)AH * w / h);
  }
  if(dw > AW) dw = AW;
  if(dh > AH) dh = AH;
  if(dw < 1 || dh < 1) return;
  ox = AX + (AW - dw) / 2;
  oy = AY + (AH - dh) / 2;
  pic_ox = ox; pic_oy = oy; pic_dw = dw; pic_dh = dh; pic_uw = w; pic_uh = h;

  /* Background first; the picture is drawn over its middle. */
  if(framed) {
    /* the phone, then the keys being pressed, lit */
    memcpy(fb, pic_frame.base, (size_t)PIC_W * PIC_H * 4);
    for(int k = 0; k < pic_frame.nkeys; k++) {
      const int *r = pic_frame.keys[k];
      if(r[0] < 0 || r[0] >= 64 || !pic_frame.lit[r[0]] || !pic_frame.litimg) continue;
      for(int y = r[2]; y < r[2] + r[4]; y++) {
        if(y < 0 || y >= PIC_H) continue;
        int x0 = r[1] < 0 ? 0 : r[1], x1 = r[1] + r[3] > PIC_W ? PIC_W : r[1] + r[3];
        if(x1 > x0) memcpy(fb + (size_t)y * PIC_W + x0, pic_frame.litimg + (size_t)y * PIC_W + x0, (size_t)(x1 - x0) * 4);
      }
    }
  } else if(PIC_AMBIENT(fx) && (dw < PIC_W || dh < PIC_H)) {
    ambient_background(src, w, h, fb, ox, oy, dw, dh);
  } else {
    for(int i = 0; i < PIC_W * PIC_H; i++) fb[i] = 0xff080808u;
  }

  /* Upscale, but no further than the picture's place on the TV needs: past
   * that there are no TV pixels left to show the rest. */
  if(filter > PIC_UP_OFF && filter < PIC_UP_SMOOTH && w < dw && h < dh) {
    int need = (dw + w - 1) / w > (dh + h - 1) / h ? (dw + w - 1) / w : (dh + h - 1) / h;
    int k;
    if(filter <= PIC_UP_XBRZ6) {
      k = filter + 1 < need ? filter + 1 : need;
    } else {
      static const int fixed[PIC_UP_COUNT] = { [PIC_UP_SUPERXBR] = 2, [PIC_UP_XBR2] = 2, [PIC_UP_XBR3] = 3, [PIC_UP_XBR4] = 4,
                                              [PIC_UP_HQ2] = 2, [PIC_UP_HQ3] = 3, [PIC_UP_HQ4] = 4, [PIC_UP_SCALE2] = 2, [PIC_UP_SCALE3] = 3 };
      k = fixed[filter];
      if(need < 2) k = 1;
    }
    if(k > 6) k = 6;
    if((long)w * k * h * k > UP_MAX) k = 1;
    if(k > 1 && !up) up = malloc((size_t)UP_MAX * 4);
    if(k > 1 && up) {
      switch(filter) {
      case PIC_UP_SUPERXBR: superxbr_2x(src, up, w, h); break;
      case PIC_UP_XBR2:     xbr_filter_xbr2x(src, up, w, h); break;
      case PIC_UP_XBR3:     xbr_filter_xbr3x(src, up, w, h); break;
      case PIC_UP_XBR4:     xbr_filter_xbr4x(src, up, w, h); break;
      case PIC_UP_HQ2:      hq2x_32(src, up, w, h); break;
      case PIC_UP_HQ3:      hq3x_32(src, up, w, h); break;
      case PIC_UP_HQ4:      hq4x_32(src, up, w, h); break;
      case PIC_UP_SCALE2:   scaler_scalex_2x(src, up, w, h); break;
      case PIC_UP_SCALE3:   scaler_scalex_3x(src, up, w, h); break;
      default:              xbrz_upscale(k, src, up, w, h);
      }
      pic = up;
      w *= k;
      h *= k;
    }
  }

  /* Resize into place. With sharpening on, into a work picture first. */
  if(sharpen && !work) work = malloc((size_t)PIC_W * PIC_H * 4);
  if(!work) sharpen = 0;
  out = sharpen ? work : fb;
  if(filter == PIC_UP_SMOOTH) {
    bicubic(pic, w, h, out + (size_t)oy * PIC_W + ox, dw, dh, PIC_W);
  } else {
  axis_table(w, dw, xi, xw);
  axis_table(h, dh, yi, yw);
  for(int y = 0; y < dh; y++) {
    const uint32_t *r0 = pic + yi[y] * w;
    const uint32_t *r1 = yi[y] + 1 < h ? r0 + w : r0;
    uint32_t *dst = out + (oy + y) * PIC_W + ox;
    int wy = yw[y];

    for(int x = 0; x < dw; x++) {
      int i0 = xi[x], i1 = i0 + 1 < w ? i0 + 1 : i0, wx = xw[x];
      uint32_t top = wx ? mix(r0[i0], r0[i1], wx) : (r0[i0] & 0xffffff);
      if(wy) {
        uint32_t bot = wx ? mix(r1[i0], r1[i1], wx) : (r1[i0] & 0xffffff);
        top = mix(top, bot, wy);
      }
      dst[x] = 0xff000000u | top;
    }
  }
  }

  /* Sharpen: push each pixel away from the average of its four neighbours. */
  if(sharpen) {
    int k = sharpen == 1 ? 3 : 6; /* strength in eighths */
    for(int y = 0; y < dh; y++) {
      const uint32_t *c = work + (oy + y) * PIC_W + ox;
      const uint32_t *u = y > 0 ? c - PIC_W : c, *d = y < dh - 1 ? c + PIC_W : c;
      uint32_t *dst = fb + (oy + y) * PIC_W + ox;

      for(int x = 0; x < dw; x++) {
        int xl = x > 0 ? x - 1 : x, xr = x < dw - 1 ? x + 1 : x;
        uint32_t p = c[x], res = 0xff000000u;
        for(int sh = 0; sh <= 16; sh += 8) {
          int v = (p >> sh) & 255;
          int around = ((c[xl] >> sh) & 255) + ((c[xr] >> sh) & 255) + ((u[x] >> sh) & 255) + ((d[x] >> sh) & 255);
          res |= (uint32_t)clamp255(v + (4 * v - around) * k / 32) << sh;
        }
        dst[x] = res;
      }
    }
  }

  /* Scanlines or an LCD grid, lined up with the game's own pixels. They need
   * each game pixel to cover at least three TV pixels to be visible at all. */
  if(effect == PIC_EFFECT_CRT) {
    crt(fb, ox, oy, dw, dh, w0, h0);
  } else if(effect && dh / h0 >= 3 && dw / w0 >= 3) {
    for(int y = 0; y < dh; y++) {
      int cell0 = (int)((long)y * h0 / dh), next = (int)((long)(y + 1) * h0 / dh);
      int size = dh / h0, into = y - (int)((long)cell0 * dh / h0);
      if(effect == PIC_EFFECT_SCANLINES) rowmul[y] = into * 5 >= size * 3 ? 176 : 256; /* lower 40% darker */
      else rowmul[y] = next != cell0 ? 150 : 256;                                     /* last line of the cell */
    }
    for(int x = 0; x < dw; x++) {
      int cell0 = (int)((long)x * w0 / dw), next = (int)((long)(x + 1) * w0 / dw);
      colmul[x] = effect == PIC_EFFECT_LCD && next != cell0 ? 150 : 256;
    }
    for(int y = 0; y < dh; y++) {
      uint32_t *dst = fb + (oy + y) * PIC_W + ox;
      for(int x = 0; x < dw; x++) {
        int m = rowmul[y] * colmul[x] >> 8;
        if(m < 256) {
          uint32_t p = dst[x];
          dst[x] = 0xff000000u | ((((p & 0xff00ff) * m) >> 8) & 0xff00ff) | ((((p & 0x00ff00) * m) >> 8) & 0x00ff00);
        }
      }
    }
  }
}

/* Turns the game picture first when asked to (for games made to be played
 * with the phone held sideways), then does the rest. */
void
picture_compose(uint32_t *src, int w, int h, int mode, int filter, int fx, uint32_t *fb) {
  static uint32_t *turned;
  static size_t room;
  int rot = PIC_ROTATE(fx);
  pic_rot = rot;

  if(w <= 0 || h <= 0) return;
  if(rot) {
    size_t n = (size_t)w * h;
    if(n > room) {
      free(turned);
      if(!(turned = malloc(n * 4))) { room = 0; return; }
      room = n;
    }
    for(int y = 0; y < h; y++) {
      const uint32_t *row = src + (size_t)y * w;
      for(int x = 0; x < w; x++) {
        if(rot == 1)      turned[(size_t)x * h + (h - 1 - y)] = row[x];            /* quarter turn right */
        else if(rot == 2) turned[(size_t)(h - 1 - y) * w + (w - 1 - x)] = row[x];  /* upside down */
        else              turned[(size_t)(w - 1 - x) * h + y] = row[x];            /* quarter turn left */
      }
    }
    src = turned;
    if(rot != 2) { int t = w; w = h; h = t; }
  }
  compose_upright(src, w, h, mode, filter, fx, fb);
}
