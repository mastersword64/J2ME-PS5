/* Home-screen tile for the J2ME player.
 *
 * This is the whole program behind the tile: it hands the launcher payload
 * (../launcher, embedded below) to the ELF loader on port 9021 and waits.
 * The launcher then closes this app and starts the real file manager, so
 * nothing has to be running in the background beforehand.
 *
 * Free-standing on purpose: no libc, only system calls. */

__asm__(".section .rodata\n"
        ".global payload_start\npayload_start:\n"
        ".incbin \"../launcher/ps5j2me-launch.elf\"\n"
        ".global payload_end\npayload_end:\n"
        ".text\n");
extern const char payload_start[], payload_end[];

#define SYS_exit      1
#define SYS_write     4
#define SYS_close     6
#define SYS_socket    97
#define SYS_connect   98
#define SYS_nanosleep 240

struct sockaddr_in {
  unsigned char  len;
  unsigned char  family;
  unsigned short port;
  unsigned int   addr;
  char           zero[8];
};

struct timespec {
  long sec;
  long nsec;
};

static long
__syscall(long n, ...) {
  long a1 = 0, a2 = 0, a3 = 0, a4 = 0, a5 = 0, a6 = 0;
  __builtin_va_list ap;
  unsigned long ret;
  char iserror;

  __builtin_va_start(ap, n);
  a1 = __builtin_va_arg(ap, long);
  a2 = __builtin_va_arg(ap, long);
  a3 = __builtin_va_arg(ap, long);
  a4 = __builtin_va_arg(ap, long);
  a5 = __builtin_va_arg(ap, long);
  a6 = __builtin_va_arg(ap, long);
  __builtin_va_end(ap);

  register long r10 __asm__("r10") = a4;
  register long r8  __asm__("r8")  = a5;
  register long r9  __asm__("r9")  = a6;

  __asm__ __volatile__("syscall"
		       : "=a"(ret), "=@ccc"(iserror), "+r"(r10), "+r"(r8), "+r"(r9)
		       : "a"(n), "D"(a1), "S"(a2), "d"(a3)
		       : "rcx", "r11", "memory");

  return iserror ? -(long)ret : (long)ret;
}

static void
sleep_ms(long ms) {
  struct timespec ts;

  ts.sec = ms / 1000;
  ts.nsec = (ms % 1000) * 1000000;
  __syscall(SYS_nanosleep, &ts, 0L);
}

/* Sends the launcher to 127.0.0.1:9021. Returns 0 once all of it is sent. */
static int
send_launcher(void) {
  const char *p = payload_start;
  struct sockaddr_in sin;
  long fd, n;

  sin.len = sizeof sin;
  sin.family = 2;       /* AF_INET */
  sin.port = 0x3d23;    /* 9021, network byte order */
  sin.addr = 0x0100007f; /* 127.0.0.1 */
  for(int i = 0; i < 8; i++) sin.zero[i] = 0;

  if((fd = __syscall(SYS_socket, 2L, 1L, 0L)) < 0) return -1;
  if(__syscall(SYS_connect, fd, &sin, (long)sizeof sin) < 0) {
    __syscall(SYS_close, fd);
    return -1;
  }
  while(p < payload_end) {
    if((n = __syscall(SYS_write, fd, p, (long)(payload_end - p))) <= 0) {
      __syscall(SYS_close, fd);
      return -1;
    }
    p += n;
  }
  __syscall(SYS_close, fd);
  return 0;
}

void
_start(void) {
  int sent = -1;

  for(int attempt = 0; attempt < 10 && sent; attempt++) {
    if((sent = send_launcher())) sleep_ms(500);
  }
  /* The launcher closes this app. If that never happens, give up. */
  for(int i = 0; i < (sent ? 1 : 40); i++) sleep_ms(500);

  __syscall(SYS_exit, (long)(sent ? 1 : 0));
  __builtin_trap();
}
