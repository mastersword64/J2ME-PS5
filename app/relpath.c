/* In a PS5 app slot, opening a file by a relative path fails even after
 * chdir(). FreeJ2ME-Plus keeps its settings and game saves under relative
 * paths, so every file call made by Java is routed through these wrappers
 * (linker option --wrap), which turn a relative path into one under BASE. */
#include <dirent.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <unistd.h>

#define BASE "/data/jvm/fj/"

static const char *
fix(const char *path, char *buf, size_t size) {
  if(!path || path[0] == '/' || path[0] == 0) return path;
  if(path[0] == '.' && path[1] == '/') path += 2;
  else if(path[0] == '.' && path[1] == 0) path += 1;
  snprintf(buf, size, BASE "%s", path);
  return buf;
}

#define P(name) char name##_buf[1100]; name = fix(name, name##_buf, sizeof name##_buf)

int __real_open(const char *, int, ...);
int __wrap_open(const char *path, int flags, ...) {
  int mode = 0;
  if(flags & O_CREAT) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
  P(path);
  return __real_open(path, flags, mode);
}
FILE *__real_fopen(const char *, const char *);
FILE *__wrap_fopen(const char *path, const char *m) { P(path); return __real_fopen(path, m); }
int __real_stat(const char *, struct stat *);
int __wrap_stat(const char *path, struct stat *st) { P(path); return __real_stat(path, st); }
int __real_lstat(const char *, struct stat *);
int __wrap_lstat(const char *path, struct stat *st) { P(path); return __real_lstat(path, st); }
int __real_access(const char *, int);
int __wrap_access(const char *path, int m) { P(path); return __real_access(path, m); }
int __real_mkdir(const char *, mode_t);
int __wrap_mkdir(const char *path, mode_t m) { P(path); return __real_mkdir(path, m); }
int __real_rmdir(const char *);
int __wrap_rmdir(const char *path) { P(path); return __real_rmdir(path); }
int __real_unlink(const char *);
int __wrap_unlink(const char *path) { P(path); return __real_unlink(path); }
int __real_remove(const char *);
int __wrap_remove(const char *path) { P(path); return __real_remove(path); }
int __real_rename(const char *, const char *);
int __wrap_rename(const char *a, const char *b) { P(a); P(b); return __real_rename(a, b); }
DIR *__real_opendir(const char *);
DIR *__wrap_opendir(const char *path) { P(path); return __real_opendir(path); }
int __real_chmod(const char *, mode_t);
int __wrap_chmod(const char *path, mode_t m) { P(path); return __real_chmod(path, m); }
int __real_utimes(const char *, const struct timeval *);
int __wrap_utimes(const char *path, const struct timeval *t) { P(path); return __real_utimes(path, t); }
int __real_truncate(const char *, off_t);
int __wrap_truncate(const char *path, off_t n) { P(path); return __real_truncate(path, n); }
char *__real_realpath(const char *, char *);
char *__wrap_realpath(const char *path, char *out) { P(path); return __real_realpath(path, out); }
long __real_readlink(const char *, char *, size_t);
long __wrap_readlink(const char *path, char *b, size_t n) { P(path); return __real_readlink(path, b, n); }
