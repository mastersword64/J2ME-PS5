/* Bridge to the xBRZ pixel-art upscaler (app/xbrz/, GPLv3, by Zenju).
 * The picture is cut into horizontal bands and each band is scaled on its
 * own thread, since the PS5 has cores to spare and Java only uses a few. */
#include <pthread.h>
#include <stdint.h>

#include "xbrz/xbrz.h"

namespace {
struct Job {
  int factor, w, h, y0, y1;
  const uint32_t *src;
  uint32_t *dst;
};

void *run(void *arg) {
  Job *j = static_cast<Job *>(arg);
  xbrz::scale(j->factor, j->src, j->dst, j->w, j->h, xbrz::ColorFormat::RGB, xbrz::ScalerCfg(), j->y0, j->y1);
  return 0;
}
}

extern "C" void
xbrz_upscale(int factor, const uint32_t *src, uint32_t *dst, int w, int h) {
  enum { THREADS = 4 };
  pthread_t th[THREADS];
  Job job[THREADS];
  int started = 0;

  if(factor < 2) factor = 2;
  if(factor > xbrz::SCALE_FACTOR_MAX) factor = xbrz::SCALE_FACTOR_MAX;
  for(int i = 0; i < THREADS; i++) {
    job[i] = Job{factor, w, h, h * i / THREADS, h * (i + 1) / THREADS, src, dst};
    if(i == THREADS - 1 || pthread_create(&th[i], 0, run, &job[i])) {
      job[i].y1 = h;   /* the last band, or everything left if a thread could not start */
      run(&job[i]);
      break;
    }
    started++;
  }
  for(int i = 0; i < started; i++) pthread_join(th[i], 0);
}
