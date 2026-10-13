/* J2ME Player for PS5: runs FreeJ2ME-Plus on a built-in Java virtual machine.
 *
 * This program owns the TV picture and the controller. Java runs on its own
 * thread; it hands finished frames to present() and receives buttons through
 * PS5Frontend.button(). Started as an app by the launcher (see ../launcher). */
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>

#define SDL_MAIN_HANDLED
#include <SDL2/SDL.h>
#include <jni.h>
#include <jvmti.h>
#include <ps5/kernel.h>

#include "picture.h"

#define SCREEN_W PIC_W
#define SCREEN_H PIC_H
#define MAX_LCD  (1920 * 1080)
#define JAVA_DIR "/data/jvm"
#define LOG_PATH JAVA_DIR "/ps5j2me.log"

int sceSystemServiceLoadExec(const char *, const char **);

static void
logf_(const char *fmt, ...) {
  va_list ap;

  va_start(ap, fmt);
  vprintf(fmt, ap);
  va_end(ap);
  fflush(stdout);
}

static int
escape_sandbox(void) {
  static const uint8_t caps[16] = {0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
                                   0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff};
  pid_t pid = getpid();
  intptr_t vnode;

  if(!(vnode = kernel_get_root_vnode()))    return -1;
  if(kernel_set_proc_rootdir(pid, vnode))   return -2;
  if(kernel_set_proc_jaildir(pid, 0))       return -3;
  if(kernel_set_ucred_uid(pid, 0))          return -4;
  if(kernel_set_ucred_caps(pid, caps))      return -5;
  return 0;
}

/* ---- the picture Java last finished, shared between the two threads ---- */

static pthread_mutex_t g_frame_lock = PTHREAD_MUTEX_INITIALIZER;
static uint32_t g_frame[MAX_LCD];
static int g_frame_w, g_frame_h, g_frame_mode, g_frame_filter, g_frame_fx, g_frame_dirty;

static void JNICALL
native_present(JNIEnv *env, jclass cls, jintArray rgb, jint w, jint h, jint mode, jint filter, jint fx) {
  jsize len = (*env)->GetArrayLength(env, rgb);

  if(w <= 0 || h <= 0 || w * h > MAX_LCD || w * h > len) return;
  pthread_mutex_lock(&g_frame_lock);
  (*env)->GetIntArrayRegion(env, rgb, 0, w * h, (jint *)g_frame);
  g_frame_w = w;
  g_frame_h = h;
  g_frame_mode = mode;
  g_frame_filter = filter;
  g_frame_fx = fx;
  g_frame_dirty = 1;
  pthread_mutex_unlock(&g_frame_lock);
}

/* ---- in-game menu, drawn by Java over the game picture ---- */

static uint32_t g_ov[SCREEN_W * SCREEN_H]; /* 0xAARRGGBB */
static int g_ov_x, g_ov_y, g_ov_w, g_ov_h, g_ov_on;
static volatile int g_redraw; /* something drawn at display time changed */
/* The menu slides and fades in and out: dir 1 opening, -1 closing, 0 still. */
static volatile int g_ov_dir;
static volatile Uint32 g_ov_t0;
#define OV_MS 180
static void JNICALL native_fade(JNIEnv *env, jclass cls, jint ms);

static void JNICALL
native_overlay(JNIEnv *env, jclass cls, jintArray argb, jint x, jint y, jint w, jint h) {
  pthread_mutex_lock(&g_frame_lock);
  if(!argb || w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > SCREEN_W || y + h > SCREEN_H ||
     (*env)->GetArrayLength(env, argb) < w * h) {
    if(g_ov_on && g_ov_dir != -1) { g_ov_dir = -1; g_ov_t0 = SDL_GetTicks(); } /* slide away, then off */
  } else {
    (*env)->GetIntArrayRegion(env, argb, 0, w * h, (jint *)g_ov);
    if(!g_ov_on || g_ov_dir == -1) { g_ov_dir = 1; g_ov_t0 = SDL_GetTicks(); }
    g_ov_x = x;
    g_ov_y = y;
    g_ov_w = w;
    g_ov_h = h;
    g_ov_on = 1;
  }
  g_redraw = 1; /* show the change even while the game is paused */
  pthread_mutex_unlock(&g_frame_lock);
}

/* ---- phone frame around the game (see picture.c) ---- */

static pthread_mutex_t g_decor_lock = PTHREAD_MUTEX_INITIALIZER;

static void JNICALL
native_frame(JNIEnv *env, jclass cls, jintArray base, jintArray lit, jint wx, jint wy, jint ww, jint wh, jintArray keys) {
  uint32_t *b = 0, *l = 0, *ob, *ol;
  const jsize n = SCREEN_W * SCREEN_H;

  if(base && lit && (*env)->GetArrayLength(env, base) >= n && (*env)->GetArrayLength(env, lit) >= n &&
     (b = malloc((size_t)n * 4)) && (l = malloc((size_t)n * 4))) {
    (*env)->GetIntArrayRegion(env, base, 0, n, (jint *)b);
    (*env)->GetIntArrayRegion(env, lit, 0, n, (jint *)l);
  } else {
    free(b);
    b = l = 0;
  }
  pthread_mutex_lock(&g_decor_lock);
  ob = pic_frame.base;
  ol = pic_frame.litimg;
  pic_frame.base = b;
  pic_frame.litimg = l;
  pic_frame.wx = wx; pic_frame.wy = wy; pic_frame.ww = ww; pic_frame.wh = wh;
  pic_frame.nkeys = 0;
  if(b && keys) {
    jsize k = (*env)->GetArrayLength(env, keys) / 5;
    if(k > 32) k = 32;
    (*env)->GetIntArrayRegion(env, keys, 0, k * 5, (jint *)pic_frame.keys);
    pic_frame.nkeys = k;
  }
  pthread_mutex_unlock(&g_decor_lock);
  free(ob);
  free(ol);
  pthread_mutex_lock(&g_frame_lock);
  g_frame_dirty = 1;
  pthread_mutex_unlock(&g_frame_lock);
}

static void JNICALL
native_key_lit(JNIEnv *env, jclass cls, jint code, jint on) {
  if(code < 0 || code >= 64) return;
  pic_frame.lit[code] = on != 0;
  if(pic_frame.base) {
    pthread_mutex_lock(&g_frame_lock);
    g_frame_dirty = 1;
    pthread_mutex_unlock(&g_frame_lock);
  }
}

/* ---- ending a game without leaving the app ----
 * Java can no longer stop a thread by itself (Thread.stop is gone), but the
 * tool interface still can: each of the game's threads gets an error thrown
 * into it, which unwinds it. */

static JavaVM *g_vm;
static jvmtiEnv *g_jvmti;

static void JNICALL
native_stop_threads(JNIEnv *env, jclass cls, jobjectArray threads, jobject reason) {
  jsize n;

  if(!g_jvmti) {
    jvmtiCapabilities caps;
    if((*g_vm)->GetEnv(g_vm, (void **)&g_jvmti, JVMTI_VERSION_1_2) != JNI_OK || !g_jvmti) {
      logf_("stopThreads: no JVMTI\n");
      g_jvmti = 0;
      return;
    }
    memset(&caps, 0, sizeof caps);
    caps.can_signal_thread = 1;
    jvmtiError e = (*g_jvmti)->AddCapabilities(g_jvmti, &caps);
    if(e != JVMTI_ERROR_NONE) logf_("stopThreads: AddCapabilities %d\n", e);
  }
  n = threads ? (*env)->GetArrayLength(env, threads) : 0;
  for(jsize i = 0; i < n; i++) {
    jobject t = (*env)->GetObjectArrayElement(env, threads, i);
    if(!t) continue;
    jvmtiError e = (*g_jvmti)->StopThread(g_jvmti, t, reason);
    if(e != JVMTI_ERROR_NONE && e != JVMTI_ERROR_THREAD_NOT_ALIVE) logf_("stopThreads: StopThread %d\n", e);
    (*env)->DeleteLocalRef(env, t);
  }
}

/* ---- going back to the game library ----
 * A game cannot be unloaded from a running Java, so the app is started again:
 * the launcher payload (the same one the home-screen tile uses) is handed to
 * the ELF loader, and it closes this app and opens a fresh one. */

__asm__(".section .rodata\n"
        ".global launcher_start\nlauncher_start:\n"
        ".incbin \"launcher/ps5j2me-launch.elf\"\n"
        ".global launcher_end\nlauncher_end:\n"
        ".text\n");
extern const char launcher_start[], launcher_end[];

static jboolean JNICALL
native_restart(JNIEnv *env, jclass cls) {
  struct sockaddr_in sin;
  const char *p = launcher_start;
  int fd;

  memset(&sin, 0, sizeof sin);
  sin.sin_len = sizeof sin;
  sin.sin_family = AF_INET;
  sin.sin_port = htons(9021);
  sin.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  if((fd = socket(AF_INET, SOCK_STREAM, 0)) < 0) return JNI_FALSE;
  if(connect(fd, (struct sockaddr *)&sin, sizeof sin)) {
    logf_("restart: no ELF loader on port 9021\n");
    close(fd);
    return JNI_FALSE;
  }
  while(p < launcher_end) {
    ssize_t n = write(fd, p, launcher_end - p);
    if(n <= 0) {
      close(fd);
      return JNI_FALSE;
    }
    p += n;
  }
  close(fd);
  logf_("restart: launcher sent, waiting to be closed\n");
  return JNI_TRUE;
}

/* ---- touch pointer: the right stick moves it, R2 touches the screen ---- */

static volatile int g_touch_on;   /* set by Java: touch screen on and a game showing */
static float g_cur_x = SCREEN_W / 2, g_cur_y = SCREEN_H / 2;
static Uint32 g_cur_seen;         /* last time the pointer moved or touched */
static int g_cur_down;            /* R2 held */
static int g_cur_shown;           /* the pointer is on the TV picture right now */

static void JNICALL
native_set_touch(JNIEnv *env, jclass cls, jint on) {
  g_touch_on = on;
  if(on) g_cur_seen = SDL_GetTicks();
}

/* TV position to phone screen position, undoing the size and the rotation. */
static int
tv_to_lcd(float tx, float ty, int *lx, int *ly) {
  int ox = pic_ox, oy = pic_oy, dw = pic_dw, dh = pic_dh, uw = pic_uw, uh = pic_uh, rot = pic_rot;
  int X, Y;

  if(dw <= 0 || dh <= 0 || uw <= 0 || uh <= 0) return -1;
  X = (int)((tx - ox) * uw / dw);
  Y = (int)((ty - oy) * uh / dh);
  if(X < 0) X = 0;
  if(Y < 0) Y = 0;
  if(X >= uw) X = uw - 1;
  if(Y >= uh) Y = uh - 1;
  switch(rot) {
  case 1:  *lx = Y;          *ly = uw - 1 - X; break; /* quarter turn right */
  case 2:  *lx = uw - 1 - X; *ly = uh - 1 - Y; break; /* upside down */
  case 3:  *lx = uh - 1 - Y; *ly = X;          break; /* quarter turn left */
  default: *lx = X;          *ly = Y;
  }
  return 0;
}

/* ---- Java ---- */

static JavaVM *g_vm;
static jclass g_frontend;       /* PS5Frontend, global reference */
static jmethodID g_button;      /* static void button(int, boolean) */
static jmethodID g_touch;       /* static void touch(int action, int x, int y) */
static volatile int g_java_state; /* 0 starting, 1 running, -1 failed */

/* Calls into the front end come from the SDL thread; PS5Boot swaps the front-end
 * class when it changes emulator build, so the two take turns. */
static pthread_mutex_t g_call_lock = PTHREAD_MUTEX_INITIALIZER;

static JNINativeMethod g_natives[] = {
  {"present", "([IIIIII)V", (void *)native_present},
  {"overlay", "([IIIII)V", (void *)native_overlay},
  {"restart", "()Z", (void *)native_restart},
  {"setTouch", "(I)V", (void *)native_set_touch},
  {"frame", "([I[IIIII[I)V", (void *)native_frame},
  {"keyLit", "(II)V", (void *)native_key_lit},
  {"fade", "(I)V", (void *)native_fade},
  {"stopThreads", "([Ljava/lang/Thread;Ljava/lang/Throwable;)V", (void *)native_stop_threads},
};

/* PS5Boot.bind(Class): the front-end class to send controller input to, or null while switching. */
static void JNICALL
native_bind(JNIEnv *env, jclass boot, jclass cls) {
  jmethodID b = 0, t = 0;

  if(cls) {
    if((*env)->RegisterNatives(env, cls, g_natives, sizeof g_natives / sizeof g_natives[0]) ||
       !(b = (*env)->GetStaticMethodID(env, cls, "button", "(IZ)V")) ||
       !(t = (*env)->GetStaticMethodID(env, cls, "touch", "(III)V"))) {
      logf_("PS5Frontend class is missing or incomplete\n");
      return; /* the pending exception reaches PS5Boot */
    }
  }
  pthread_mutex_lock(&g_call_lock);
  if(g_frontend) (*env)->DeleteGlobalRef(env, g_frontend);
  g_frontend = cls ? (*env)->NewGlobalRef(env, cls) : 0;
  g_button = b;
  g_touch = t;
  if(cls) g_java_state = 1;
  pthread_mutex_unlock(&g_call_lock);
}

static void *
java_thread(void *arg) {
  /* PS5Boot picks the emulator build (fj/engine) and loads it in a class loader
   * of its own, so it can change build without the app restarting. */
  static JavaVMOption opt[] = {
    {"-Xmx192m", 0}, {"-Xms32m", 0}, {"-XX:+UseSerialGC", 0}, {"-Xshare:off", 0},
    /* An app slot cannot set aside the 1 GB of address space Java wants by default. */
    {"-XX:-UseCompressedClassPointers", 0}, {"-XX:-UseCompressedOops", 0},
    {"-XX:MaxMetaspaceSize=128m", 0},
    {"-Djava.class.path=" JAVA_DIR "/fj/ps5frontend.jar", 0},
    {"-Djava.system.class.loader=PS5SysLoader", 0},
    {"-Dps5.fj=" JAVA_DIR "/fj", 0},
    {"-Djava.awt.headless=true", 0},
    {"-Dsun.awt.fontconfig=" JAVA_DIR "/lib/fontconfig.properties", 0},
    {"-Duser.home=" JAVA_DIR "/home", 0}, {"-Duser.dir=" JAVA_DIR "/fj", 0},
    {"-Djava.io.tmpdir=" JAVA_DIR "/tmp", 0},
  };
  static JNINativeMethod boot_natives[] = {
    {"bind", "(Ljava/lang/Class;)V", (void *)native_bind},
    {"stopThreads", "([Ljava/lang/Thread;Ljava/lang/Throwable;)V", (void *)native_stop_threads},
  };
  JavaVMInitArgs args;
  JNIEnv *env;
  jclass cls, str;
  jmethodID main_id;
  int rc;

  args.version = JNI_VERSION_10;
  args.nOptions = sizeof opt / sizeof opt[0];
  args.options = opt;
  args.ignoreUnrecognized = JNI_TRUE;
  if((rc = JNI_CreateJavaVM(&g_vm, (void **)&env, &args))) {
    logf_("Java failed to start: %d\n", rc);
    g_java_state = -1;
    return 0;
  }
  logf_("Java started\n");
  if(!(cls = (*env)->FindClass(env, "PS5Boot")) ||
     (*env)->RegisterNatives(env, cls, boot_natives, sizeof boot_natives / sizeof boot_natives[0]) ||
     !(main_id = (*env)->GetStaticMethodID(env, cls, "main", "([Ljava/lang/String;)V"))) {
    logf_("PS5Boot class is missing or incomplete\n");
    (*env)->ExceptionDescribe(env);
    g_java_state = -1;
    return 0;
  }
  str = (*env)->FindClass(env, "java/lang/String");
  (*env)->CallStaticVoidMethod(env, cls, main_id, (*env)->NewObjectArray(env, 0, str, 0));
  if(g_java_state == 0) g_java_state = -1; /* the front end never came up */
  if((*env)->ExceptionCheck(env)) {
    logf_("the Java front end stopped with an exception\n");
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
  }
  fflush(stdout);
  for(;;) sleep(3600); /* the game runs on Java's own threads from here */
  return 0;
}

static JNIEnv *g_ui_env; /* the SDL thread's handle on Java */

static void
send_button(int code, int down) {
  if(code < 0 || g_java_state != 1) return;
  if(!g_ui_env && (*g_vm)->AttachCurrentThreadAsDaemon(g_vm, (void **)&g_ui_env, 0)) {
    g_ui_env = 0;
    return;
  }
  pthread_mutex_lock(&g_call_lock);
  if(g_frontend) (*g_ui_env)->CallStaticVoidMethod(g_ui_env, g_frontend, g_button, (jint)code, (jboolean)(down != 0));
  pthread_mutex_unlock(&g_call_lock);
  if((*g_ui_env)->ExceptionCheck(g_ui_env)) {
    (*g_ui_env)->ExceptionDescribe(g_ui_env);
    (*g_ui_env)->ExceptionClear(g_ui_env);
  }
}

/* action 0 press, 1 drag, 2 release */
static void
send_touch(int action) {
  int x, y;

  if(g_java_state != 1 || tv_to_lcd(g_cur_x, g_cur_y, &x, &y)) return;
  if(!g_ui_env && (*g_vm)->AttachCurrentThreadAsDaemon(g_vm, (void **)&g_ui_env, 0)) {
    g_ui_env = 0;
    return;
  }
  pthread_mutex_lock(&g_call_lock);
  if(g_frontend) (*g_ui_env)->CallStaticVoidMethod(g_ui_env, g_frontend, g_touch, (jint)action, (jint)x, (jint)y);
  pthread_mutex_unlock(&g_call_lock);
  if((*g_ui_env)->ExceptionCheck(g_ui_env)) {
    (*g_ui_env)->ExceptionDescribe(g_ui_env);
    (*g_ui_env)->ExceptionClear(g_ui_env);
  }
}

/* Moves the pointer with the right stick. Returns 1 when it should be redrawn. */
static int
pointer_poll(SDL_Joystick *pad, Uint32 now) {
  static Uint64 last;
  static Uint32 last_drag;
  Uint64 pc = SDL_GetPerformanceCounter();
  float dt = last ? (float)((double)(pc - last) / SDL_GetPerformanceFrequency()) : 0;
  int moved = 0, visible;

  last = pc;
  if(dt > 0.1f) dt = 0.1f;
  if(!g_touch_on) {
    if(g_cur_down) { g_cur_down = 0; send_touch(2); }
    visible = 0;
  } else {
    if(pad) {
      /* Round dead zone, then a curve: fine control near the centre, quick at
       * full tilt. The speed itself eases towards where the stick asks, so the
       * pointer glides instead of jumping when the stick moves. */
      static float vx, vy;
      float ax = SDL_JoystickGetAxis(pad, 2) / 32767.0f, ay = SDL_JoystickGetAxis(pad, 3) / 32767.0f;
      float mag = sqrtf(ax * ax + ay * ay), tx = 0, ty = 0;
      const float dead = 0.16f, top = 1500.0f;
      if(mag > dead) {
        float m = (mag - dead) / (1 - dead);
        if(m > 1) m = 1;
        float speed = top * (0.2f * m + 0.8f * m * m * m);
        tx = ax / mag * speed;
        ty = ay / mag * speed;
      }
      float k = 1 - expf(-dt * 16.0f);
      vx += (tx - vx) * k;
      vy += (ty - vy) * k;
      if(tx == 0 && ty == 0 && vx * vx + vy * vy < 4) vx = vy = 0;
      if(vx != 0 || vy != 0) {
        g_cur_x += vx * dt;
        g_cur_y += vy * dt;
        moved = 1;
      }
    }
    /* keep it on the game picture */
    if(pic_dw > 0) {
      if(g_cur_x < pic_ox) g_cur_x = pic_ox;
      if(g_cur_y < pic_oy) g_cur_y = pic_oy;
      if(g_cur_x > pic_ox + pic_dw - 1) g_cur_x = pic_ox + pic_dw - 1;
      if(g_cur_y > pic_oy + pic_dh - 1) g_cur_y = pic_oy + pic_dh - 1;
    }
    if(moved) g_cur_seen = now;
    if(moved && g_cur_down && now - last_drag >= 16) { last_drag = now; send_touch(1); }
    visible = g_cur_down || now - g_cur_seen < 4000; /* hides itself when not used */
  }
  if(visible != g_cur_shown) { g_cur_shown = visible; return 1; }
  return moved && visible;
}

/* Draws the pointer straight onto the window, over the finished picture. */
static void
draw_pointer(SDL_Surface *s) {
  /* soft-edged rings drawn with sub-pixel position, so movement looks continuous */
  float cx = g_cur_x, cy = g_cur_y, r = g_cur_down ? 15.0f : 18.0f;
  int x0 = (int)(cx - r - 6), x1 = (int)(cx + r + 6), y0 = (int)(cy - r - 6), y1 = (int)(cy + r + 6);

  if(s->format->BytesPerPixel != 4) return;
  for(int py = y0; py <= y1; py++) {
    if(py < 0 || py >= s->h) continue;
    Uint32 *row = (Uint32 *)((Uint8 *)s->pixels + py * s->pitch);
    for(int px = x0; px <= x1; px++) {
      if(px < 0 || px >= s->w) continue;
      float dx = px + 0.5f - cx, dy = py + 0.5f - cy, d = sqrtf(dx * dx + dy * dy);
      /* coverage of: dark outline (r..r+3), white ring (r-4..r), filled centre when pressed, centre dot */
      float ring = fminf(fmaxf(0, fminf(d - (r - 4) + 0.5f, r - d + 0.5f)), 1);
      float edge = fminf(fmaxf(0, fminf(d - r + 0.5f, r + 3 - d + 0.5f)), 1);
      float fill = g_cur_down ? fminf(fmaxf(0, r - 4 - d + 0.5f), 1) : 0;
      float dot = fminf(fmaxf(0, 2.5f - d + 0.5f), 1), dotedge = fminf(fmaxf(0, fminf(d - 2.5f + 0.5f, 4.0f - d + 0.5f)), 1);
      float cr, cg, cb, a;
      if(dot > 0 || dotedge > 0) { a = fmaxf(dot, dotedge); cr = cg = cb = dot >= dotedge ? 255 : 20; }
      else if(ring > 0) { a = ring; cr = cg = cb = 255; }
      else if(edge > 0) { a = edge * 0.85f; cr = 20; cg = 22; cb = 40; }
      else if(fill > 0) { a = fill * 0.9f; cr = 124; cg = 92; cb = 255; }
      else continue;
      Uint8 r0, g0, b0;
      SDL_GetRGB(row[px], s->format, &r0, &g0, &b0);
      row[px] = SDL_MapRGB(s->format, (Uint8)(r0 + (cr - r0) * a), (Uint8)(g0 + (cg - g0) * a), (Uint8)(b0 + (cb - b0) * a));
    }
  }
}

/* ---- controller ----
 * Buttons go to Java by their PS5 SDL number; which phone key each one
 * presses is decided there (and can be changed on the settings screen). */

#define PAD_OPTIONS 6
#define PAD_R2      16

static int
map_button(int b) {
  return b >= 0 && b <= 16 ? b : -1;
}

/* Left stick, reported as four extra buttons (20 to 23). */
static void
stick_poll(SDL_Joystick *pad) {
  static int held[4];
  static const int code[4] = {20, 21, 22, 23}; /* up, down, left, right */
  int x = SDL_JoystickGetAxis(pad, 0), y = SDL_JoystickGetAxis(pad, 1);
  int now[4] = { y < -16000, y > 16000, x < -16000, x > 16000 };

  for(int i = 0; i < 4; i++) {
    if(now[i] != held[i]) {
      held[i] = now[i];
      send_button(code[i], now[i]);
    }
  }
}

/* ---- picture: picture.c turns the game frame into the TV picture ---- */

static uint32_t *g_fb;   /* 1920x1080, 0xAARRGGBB: the finished picture on the TV */
static uint32_t *g_back; /* the next one, being made by the compose thread */
static pthread_mutex_t g_swap_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_new_picture;

/* ---- smooth changes between screens ----
 * Java asks for a fade before it shows a new screen. The picture on the TV
 * at that moment is kept, and when the new one arrives the two are blended
 * for a moment instead of cutting. */
static uint32_t *g_out, *g_fade_from;
static volatile int g_fade_request; /* ms, set by Java */
static int g_fade_ms, g_fade_armed, g_fade_active;
static Uint32 g_fade_t0, g_fade_armed_at;

static void JNICALL
native_fade(JNIEnv *env, jclass cls, jint ms) {
  g_fade_request = ms > 0 ? ms : 0;
}

static inline uint32_t
blend_px(uint32_t a, uint32_t b, int k) { /* k = share of b, 0..256 */
  return 0xff000000u | ((((a & 0xff00ff) * (256 - k) + (b & 0xff00ff) * k) >> 8) & 0xff00ff) |
         ((((a & 0x00ff00) * (256 - k) + (b & 0x00ff00) * k) >> 8) & 0x00ff00);
}

static float
ease(float t) { /* smooth start and end */
  if(t <= 0) return 0;
  if(t >= 1) return 1;
  return t * t * (3 - 2 * t);
}

/* What the TV shows right now, without the menu: the picture, or the fade in progress. */
static const uint32_t *
current_picture(Uint32 now) {
  if(!g_fade_active) return g_fb;
  float t = (float)(now - g_fade_t0) / (g_fade_ms ? g_fade_ms : 1);
  if(t >= 1) { g_fade_active = 0; return g_fb; }
  int k = (int)(ease(t) * 256);
  for(int i = 0; i < SCREEN_W * SCREEN_H; i++) g_out[i] = blend_px(g_fade_from[i], g_fb[i], k);
  return g_out;
}

/* Called before compose(): keeps the current picture if Java asked for a fade. */
static void
fade_poll(Uint32 now) {
  int ms = g_fade_request;
  if(ms) {
    g_fade_request = 0;
    if(!g_fade_from) g_fade_from = malloc((size_t)SCREEN_W * SCREEN_H * 4);
    if(g_fade_from && g_out) {
      pthread_mutex_lock(&g_swap_lock);
      const uint32_t *cur = current_picture(now);
      memcpy(g_fade_from, cur, (size_t)SCREEN_W * SCREEN_H * 4);
      pthread_mutex_unlock(&g_swap_lock);
      g_fade_active = 0;
      g_fade_armed = 1;
      g_fade_armed_at = now;
      g_fade_ms = ms;
    }
  }
  if(g_fade_armed && now - g_fade_armed_at > 8000) g_fade_armed = 0; /* the new picture never came */
}

static int
animating(void) {
  return g_fade_active || g_ov_dir != 0;
}

static void
compose(void) {
  static uint32_t copy[MAX_LCD];
  int w, h, mode, filter, fx;

  pthread_mutex_lock(&g_frame_lock);
  w = g_frame_w;
  h = g_frame_h;
  mode = g_frame_mode;
  filter = g_frame_filter;
  fx = g_frame_fx;
  if(w <= 0 || h <= 0) { g_frame_dirty = 0; pthread_mutex_unlock(&g_frame_lock); return; } /* nothing to show yet */
  memcpy(copy, g_frame, (size_t)w * h * 4);
  g_frame_dirty = 0;
  pthread_mutex_unlock(&g_frame_lock);
  pthread_mutex_lock(&g_decor_lock);
  picture_compose(copy, w, h, mode, filter, fx, g_back);
  pthread_mutex_unlock(&g_decor_lock);

  pthread_mutex_lock(&g_swap_lock); /* hand it to the display side */
  uint32_t *t = g_fb;
  g_fb = g_back;
  g_back = t;
  g_new_picture = 1;
  pthread_mutex_unlock(&g_swap_lock);

}

/* Makes the TV pictures on its own thread, so the display (pointer, fades, the
 * menu sliding) keeps moving at full speed while a frame is being upscaled. */
static void *
compose_thread(void *arg) {
  for(;;) {
    if(g_frame_dirty) compose();
    else SDL_Delay(1);
  }
  return 0;
}

static void
show(SDL_Window *win) {
  SDL_Surface *s = SDL_GetWindowSurface(win);
  Uint32 now = SDL_GetTicks();
  const uint32_t *pic;

  if(!s) return;
  pthread_mutex_lock(&g_swap_lock);
  if(g_new_picture) {
    g_new_picture = 0;
    if(g_fade_armed) { g_fade_armed = 0; g_fade_active = 1; g_fade_t0 = now; } /* new picture: start the cross-fade */
  }
  pic = current_picture(now);

  /* the menu, sliding in from the left and fading as it opens and closes */
  pthread_mutex_lock(&g_frame_lock);
  int ov = g_ov_on;
  float e = 1;
  if(ov && g_ov_dir) {
    float t = (float)(now - g_ov_t0) / OV_MS;
    if(t >= 1) {
      if(g_ov_dir == -1) { g_ov_on = ov = 0; }
      g_ov_dir = 0;
    } else {
      e = ease(g_ov_dir == 1 ? t : 1 - t);
    }
  }
  if(ov && g_out) {
    if(pic != g_out) { memcpy(g_out, pic, (size_t)SCREEN_W * SCREEN_H * 4); pic = g_out; }
    int shift = (int)((1 - e) * (g_ov_x == 0 ? -140 : 0)), drop = (int)((1 - e) * (g_ov_x == 0 ? 0 : -40));
    int am = (int)(e * 256);
    for(int y = 0; y < g_ov_h; y++) {
      int ty = g_ov_y + y + drop;
      if(ty < 0 || ty >= SCREEN_H) continue;
      const uint32_t *o = g_ov + (size_t)y * g_ov_w;
      uint32_t *d = g_out + (size_t)ty * SCREEN_W;
      for(int x = 0; x < g_ov_w; x++) {
        int tx = g_ov_x + x + shift;
        uint32_t a = (o[x] >> 24) * am >> 8;
        if(!a || tx < 0 || tx >= SCREEN_W) continue;
        d[tx] = blend_px(d[tx], o[x], a == 255 ? 256 : (int)a);
      }
    }
  }
  pthread_mutex_unlock(&g_frame_lock);

  if(SDL_MUSTLOCK(s)) SDL_LockSurface(s);
  SDL_ConvertPixels(SCREEN_W, SCREEN_H, SDL_PIXELFORMAT_ARGB8888, pic,
                    SCREEN_W * 4, s->format->format, s->pixels, s->pitch);
  pthread_mutex_unlock(&g_swap_lock);
  if(g_cur_shown) draw_pointer(s);
  if(SDL_MUSTLOCK(s)) SDL_UnlockSurface(s);
  SDL_UpdateWindowSurface(win);
}

int
main(int argc, char *argv[]) {
  SDL_Window *win;
  SDL_Joystick *pad = 0;
  pthread_attr_t attr;
  pthread_t java;
  Uint32 options_since = 0;
  int rc, fd, frames = 0, pointer_dirty = 0;

  rc = escape_sandbox();
  mkdir(JAVA_DIR "/tmp", 0777);
  mkdir(JAVA_DIR "/home", 0777);
  mkdir(JAVA_DIR "/games", 0777);
  /* Everything printed, by this program and by Java, goes to the log file. */
  if((fd = open(LOG_PATH, O_CREAT | O_TRUNC | O_WRONLY, 0666)) >= 0) {
    dup2(fd, 1);
    dup2(fd, 2);
    close(fd);
  }
  logf_("ps5j2me start, fw 0x%08x, sandbox escape %d, chdir %d\n",
        kernel_get_fw_version(), rc, chdir(JAVA_DIR "/fj"));

  if(!(g_fb = malloc((size_t)SCREEN_W * SCREEN_H * 4))) return 1;
  if(!(g_back = malloc((size_t)SCREEN_W * SCREEN_H * 4))) return 1;
  g_out = malloc((size_t)SCREEN_W * SCREEN_H * 4);
  if(SDL_Init(SDL_INIT_VIDEO | SDL_INIT_JOYSTICK)) {
    logf_("SDL_Init failed: %s\n", SDL_GetError());
    return 1;
  }
  win = SDL_CreateWindow("J2ME Player", SDL_WINDOWPOS_CENTERED,
                         SDL_WINDOWPOS_CENTERED, SCREEN_W, SCREEN_H, 0);
  if(!win || !SDL_GetWindowSurface(win)) {
    logf_("window failed: %s\n", SDL_GetError());
    return 1;
  }
  logf_("window ok, %d joysticks\n", SDL_NumJoysticks());
  if(SDL_NumJoysticks() > 0) pad = SDL_JoystickOpen(0);
  for(int i = 0; i < SCREEN_W * SCREEN_H; i++) g_fb[i] = 0xff101830u;
  show(win);

  pthread_attr_init(&attr);
  pthread_attr_setstacksize(&attr, 16 * 1024 * 1024);
  {
    pthread_t comp;
    pthread_attr_t cattr;
    pthread_attr_init(&cattr);
    pthread_attr_setstacksize(&cattr, 1024 * 1024);
    if(pthread_create(&comp, &cattr, compose_thread, 0)) logf_("could not start the compose thread\n");
  }
  if(pthread_create(&java, &attr, java_thread, 0)) {
    logf_("could not start the Java thread\n");
    return 1;
  }

  for(;;) {
    Uint32 now = SDL_GetTicks();
    SDL_Event ev;

    while(SDL_PollEvent(&ev)) {
      if(ev.type == SDL_QUIT) goto out;
      if(ev.type == SDL_JOYDEVICEADDED && !pad) pad = SDL_JoystickOpen(ev.jdevice.which);
      if((ev.type == SDL_JOYBUTTONDOWN || ev.type == SDL_JOYBUTTONUP) && ev.jbutton.button == PAD_R2 && g_touch_on) {
        int down = ev.type == SDL_JOYBUTTONDOWN;
        if(down != g_cur_down) {
          g_cur_down = down;
          g_cur_seen = now;
          send_touch(down ? 0 : 2);
          pointer_dirty = 1;
        }
        continue; /* R2 is the finger while touch is on */
      }
      if(ev.type == SDL_JOYBUTTONDOWN) {
        if(ev.jbutton.button == PAD_OPTIONS) options_since = now ? now : 1;
        send_button(map_button(ev.jbutton.button), 1);
      }
      if(ev.type == SDL_JOYBUTTONUP) {
        if(ev.jbutton.button == PAD_OPTIONS) options_since = 0;
        send_button(map_button(ev.jbutton.button), 0);
      }
    }
    if(pad) stick_poll(pad);
    if(pointer_poll(pad, now)) pointer_dirty = 1;
    if(options_since && now - options_since > 1500) goto out; /* hold OPTIONS to quit */
    if(g_java_state < 0) { SDL_Delay(3000); goto out; }

    fade_poll(now);
    if(g_new_picture) {
      show(win);
      pointer_dirty = 0;
      if(frames++ == 0) logf_("first frame shown\n");
    } else if(pointer_dirty || animating() || g_redraw) {
      g_redraw = 0;
      show(win); /* the picture is unchanged; the pointer, a fade or the menu moved */
      pointer_dirty = 0;
      SDL_Delay(4);
    } else {
      SDL_Delay(2);
    }
  }

out:
  logf_("exit\n");
  fflush(stdout);
  SDL_Quit();
  sceSystemServiceLoadExec("exit", 0); /* hand the screen back to the home menu */
  _exit(0);
  return 0;
}
