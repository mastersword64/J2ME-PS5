/* Super-xBR 2x for the CPU.
 *
 * Follows Hyllian's three-pass Super-xBR (the "fast" variant used in his C++
 * reference): pass 1 fills the diagonal new pixels from a 4x4 neighbourhood,
 * pass 2 fills the remaining ones on the rotated grid, pass 3 refines every
 * pixel. Each pass picks between two edge directions with diagonal_edge()
 * and clamps against the nearby colours so edges do not ring.
 * Super-xBR is by Hyllian (MIT licence). This C version is ours.
 *
 * All three passes are split over threads by bands of rows. Pass 3 reads
 * pixels it has just written, as the reference does; at the band edges it
 * may see the neighbour's pixels before or after their refinement, which
 * makes no visible difference. */
#include <math.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>

#define WGT1 0.129633f
#define WGT2 0.175068f
#define W1 (-WGT1)
#define W2 (WGT1 + 0.5f)
#define W3 (-WGT2)
#define W4 (WGT2 + 0.5f)

static inline float df(float a, float b) { return fabsf(a - b); }
static inline int clampi(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }
static inline float clampf(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
static inline float min4(float a, float b, float c, float d) { float m = a < b ? a : b; m = m < c ? m : c; return m < d ? m : d; }
static inline float max4(float a, float b, float c, float d) { float m = a > b ? a : b; m = m > c ? m : c; return m > d ? m : d; }

static float
diagonal_edge(float m[4][4], const float *wp) {
  float dw1 = wp[0] * (df(m[0][2], m[1][1]) + df(m[1][1], m[2][0]) + df(m[1][3], m[2][2]) + df(m[2][2], m[3][1])) +
              wp[1] * (df(m[0][3], m[1][2]) + df(m[2][1], m[3][0])) +
              wp[2] * (df(m[0][3], m[2][1]) + df(m[1][2], m[3][0])) +
              wp[3] * df(m[1][2], m[2][1]) +
              wp[4] * (df(m[0][2], m[2][0]) + df(m[1][3], m[3][1])) +
              wp[5] * (df(m[0][1], m[1][0]) + df(m[2][3], m[3][2]));
  float dw2 = wp[0] * (df(m[0][1], m[1][2]) + df(m[1][2], m[2][3]) + df(m[1][0], m[2][1]) + df(m[2][1], m[3][2])) +
              wp[1] * (df(m[0][0], m[1][1]) + df(m[2][2], m[3][3])) +
              wp[2] * (df(m[0][0], m[2][2]) + df(m[1][1], m[3][3])) +
              wp[3] * df(m[1][1], m[2][2]) +
              wp[4] * (df(m[1][0], m[3][2]) + df(m[0][1], m[2][3])) +
              wp[5] * (df(m[0][2], m[1][3]) + df(m[2][0], m[3][1]));
  return dw1 - dw2;
}

/* One new pixel from a 4x4 neighbourhood (r, g, b and luma), with weights a/b
 * for the far and near pairs along the chosen diagonal. */
static uint32_t
blend(float r[4][4], float g[4][4], float b[4][4], float y[4][4], const float *wp, float wa, float wb) {
  float d = diagonal_edge(y, wp), out[3];
  float (*ch[3])[4] = { r, g, b };

  for(int c = 0; c < 3; c++) {
    float (*m)[4] = ch[c];
    float lo = min4(m[1][1], m[2][1], m[1][2], m[2][2]), hi = max4(m[1][1], m[2][1], m[1][2], m[2][2]);
    float v = d <= 0.0f ? wa * (m[0][3] + m[3][0]) + wb * (m[1][2] + m[2][1])
                        : wa * (m[0][0] + m[3][3]) + wb * (m[1][1] + m[2][2]);
    out[c] = clampf(v, lo, hi);
  }
  return (uint32_t)clampi((int)ceilf(out[0]), 0, 255) << 16 | (uint32_t)clampi((int)ceilf(out[1]), 0, 255) << 8 |
         (uint32_t)clampi((int)ceilf(out[2]), 0, 255);
}

static inline void
sample(uint32_t p, float *r, float *g, float *b, float *y) {
  *r = (float)((p >> 16) & 255);
  *g = (float)((p >> 8) & 255);
  *b = (float)(p & 255);
  *y = 0.2126f * *r + 0.7152f * *g + 0.0722f * *b;
}

struct job {
  const uint32_t *src;
  uint32_t *out;
  int w, h, y0, y1, pass;
};

static void *
run(void *arg) {
  struct job *j = arg;
  const int w = j->w, h = j->h, ow = 2 * w, oh = 2 * h;
  float r[4][4], g[4][4], b[4][4], y[4][4];

  if(j->pass == 1) {
    static const float wp[6] = { 2.0f, 1.0f, -1.0f, 4.0f, -1.0f, 1.0f };
    for(int cy = j->y0; cy < j->y1; cy++) {
      for(int cx = 0; cx < w; cx++) {
        for(int sx = -1; sx <= 2; sx++)
          for(int sy = -1; sy <= 2; sy++)
            sample(j->src[clampi(cy + sy, 0, h - 1) * w + clampi(cx + sx, 0, w - 1)],
                   &r[sx + 1][sy + 1], &g[sx + 1][sy + 1], &b[sx + 1][sy + 1], &y[sx + 1][sy + 1]);
        uint32_t c = j->src[cy * w + cx] & 0xffffff;
        uint32_t *o = j->out + (size_t)(2 * cy) * ow + 2 * cx;
        o[0] = o[1] = o[ow] = c;
        o[ow + 1] = blend(r, g, b, y, wp, W1, W2);
      }
    }
  } else if(j->pass == 2) {
    static const float wp[6] = { 2.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f };
    for(int yy = 2 * j->y0; yy < 2 * j->y1; yy += 2) {
      for(int xx = 0; xx < ow; xx += 2) {
        for(int sx = -1; sx <= 2; sx++)
          for(int sy = -1; sy <= 2; sy++)
            sample(j->out[clampi(sx - sy + yy, 0, oh - 1) * ow + clampi(sx + sy + xx, 0, ow - 1)],
                   &r[sx + 1][sy + 1], &g[sx + 1][sy + 1], &b[sx + 1][sy + 1], &y[sx + 1][sy + 1]);
        uint32_t right = blend(r, g, b, y, wp, W3, W4);
        for(int sx = -1; sx <= 2; sx++)
          for(int sy = -1; sy <= 2; sy++)
            sample(j->out[clampi(sx - sy + 1 + yy, 0, oh - 1) * ow + clampi(sx + sy - 1 + xx, 0, ow - 1)],
                   &r[sx + 1][sy + 1], &g[sx + 1][sy + 1], &b[sx + 1][sy + 1], &y[sx + 1][sy + 1]);
        uint32_t below = blend(r, g, b, y, wp, W3, W4);
        j->out[(size_t)yy * ow + xx + 1] = right;
        j->out[(size_t)(yy + 1) * ow + xx] = below;
      }
    }
  } else {
    static const float wp[6] = { 2.0f, 1.0f, -1.0f, 4.0f, -1.0f, 1.0f };
    for(int yy = 2 * j->y1 - 1; yy >= 2 * j->y0; yy--) {
      for(int xx = ow - 1; xx >= 0; xx--) {
        for(int sx = -2; sx <= 1; sx++)
          for(int sy = -2; sy <= 1; sy++)
            sample(j->out[clampi(sy + yy, 0, oh - 1) * ow + clampi(sx + xx, 0, ow - 1)],
                   &r[sx + 2][sy + 2], &g[sx + 2][sy + 2], &b[sx + 2][sy + 2], &y[sx + 2][sy + 2]);
        j->out[(size_t)yy * ow + xx] = blend(r, g, b, y, wp, W1, W2);
      }
    }
  }
  return 0;
}

static void
parallel(const uint32_t *src, uint32_t *out, int w, int h, int pass) {
  enum { THREADS = 4 };
  pthread_t th[THREADS];
  struct job job[THREADS];
  int started = 0;

  for(int i = 0; i < THREADS; i++) {
    job[i] = (struct job){ src, out, w, h, h * i / THREADS, h * (i + 1) / THREADS, pass };
    if(i == THREADS - 1 || pthread_create(&th[i], 0, run, &job[i])) {
      job[i].y1 = h;
      run(&job[i]);
      break;
    }
    started++;
  }
  for(int i = 0; i < started; i++) pthread_join(th[i], 0);
}

/* dst must hold (2w)x(2h) pixels. Pixels are 0xRRGGBB. */
void
superxbr_2x(const uint32_t *src, uint32_t *dst, int w, int h) {
  parallel(src, dst, w, h, 1);
  parallel(src, dst, w, h, 2);
  parallel(src, dst, w, h, 3);
}
