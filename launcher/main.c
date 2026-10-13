/* ps5j2me-launch - one-shot payload that starts the J2ME player as an app.
 *
 * It needs no service running on the console: the home-screen tile (see
 * ../tile) sends this payload to the ELF loader on port 9021 when opened,
 * and it can also be sent by hand from a PC.
 *
 * The launch itself is done by the homebrew loader code from
 * ps5-payload-websrv (vendor/, GPLv3, Copyright John Tornblom), unchanged. */
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <ps5/kernel.h>

#include "fs.h"
#include "hbldr.h"

#define APP_ELF  "/data/homebrew/PS5J2ME/eboot.elf"
#define LOG_PATH "/data/ps5j2me-launch.log"

typedef struct notify_request {
  char useless1[45];
  char message[3075];
} notify_request_t;

int sceKernelSendNotificationRequest(int, notify_request_t *, size_t, int);
int sceUserServiceInitialize(void *);

uint8_t *
fs_readfile(const char *path, size_t *size) {
  FILE *f = fopen(path, "rb");
  uint8_t *buf = 0;
  long len;

  if(!f) return 0;
  if(!fseek(f, 0, SEEK_END) && (len = ftell(f)) > 0 && !fseek(f, 0, SEEK_SET) &&
     (buf = malloc(len)) && fread(buf, 1, len, f) != (size_t)len) {
    free(buf);
    buf = 0;
  }
  fclose(f);
  if(buf && size) *size = len;
  return buf;
}

static void
notify(const char *fmt, ...) {
  notify_request_t req;
  va_list ap;

  bzero(&req, sizeof req);
  va_start(ap, fmt);
  vsnprintf(req.message, sizeof req.message, fmt, ap);
  va_end(ap);
  sceKernelSendNotificationRequest(0, &req, sizeof req, 0);
}

static int g_log = -1;

/* Prints to whoever sent the payload and to the log file. */
static void
say(const char *fmt, ...) {
  char line[512];
  va_list ap;
  int n;

  va_start(ap, fmt);
  n = vsnprintf(line, sizeof line, fmt, ap);
  va_end(ap);
  if(n > (int)sizeof line - 1) n = sizeof line - 1;
  fputs(line, stdout);
  fflush(stdout);
  if(g_log >= 0) write(g_log, line, n);
}

int
main(void) {
  static const uint8_t caps[16] = {0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
                                   0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff};
  char *argv[] = { 0 }, *envp[] = { 0 };
  pid_t me = getpid(), pid;

  kernel_set_proc_rootdir(me, kernel_get_root_vnode());
  kernel_set_proc_jaildir(me, 0);
  kernel_set_ucred_uid(me, 0);
  kernel_set_ucred_caps(me, caps);

  g_log = open(LOG_PATH, O_CREAT | O_TRUNC | O_WRONLY, 0666);
  say("ps5j2me-launch start, fw 0x%08x\n", kernel_get_fw_version());

  if(access(APP_ELF, R_OK)) {
    say("missing %s - run the installer first\n", APP_ELF);
    notify("J2ME Player is not installed.");
    return 1;
  }

  sceUserServiceInitialize(0);
  kernel_set_ucred_authid(-1, 0x4801000000000013L);

  pid = hbldr_launch("/", APP_ELF, g_log, argv, envp);
  if(pid < 0) {
    say("launch failed\n");
    notify("J2ME Player could not start.\nSee " LOG_PATH);
    return 1;
  }
  say("launched, pid %d\n", pid);
  return 0;
}
