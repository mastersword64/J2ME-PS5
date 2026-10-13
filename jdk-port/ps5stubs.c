/* PS5 libc lacks these; the JVM only needs them for optional features. */
#include <stddef.h>
int dl_iterate_phdr(void *cb, void *data) { (void)cb; (void)data; return 0; }
int getpeereid(int s, void *uid, void *gid) { (void)s; (void)uid; (void)gid; return -1; }
void *getutxent(void) { return NULL; }
void setutxent(void) {}
#include <stdio.h>
#include <stdlib.h>
long getline(char **lineptr, size_t *n, FILE *fp) {
    if (!lineptr || !n || !fp) return -1;
    if (!*lineptr || *n == 0) { *n = 128; *lineptr = (char*)malloc(*n); if (!*lineptr) return -1; }
    size_t len = 0; int c;
    while ((c = fgetc(fp)) != EOF) {
        if (len + 2 > *n) { size_t nn = *n * 2; char *p = (char*)realloc(*lineptr, nn); if (!p) return -1; *lineptr = p; *n = nn; }
        (*lineptr)[len++] = (char)c;
        if (c == '\n') break;
    }
    if (len == 0) return -1;
    (*lineptr)[len] = 0; return (long)len;
}
int waitid(int idtype, int id, void *info, int options) { (void)idtype; (void)id; (void)info; (void)options; return -1; }
/* Libraries that Java loads by name but that define no load function of their own. */
int JNI_OnLoad_jimage(void *vm, void *reserved) { (void)vm; (void)reserved; return 0x00010008; }
int JNI_OnLoad_freetype(void *vm, void *reserved) { (void)vm; (void)reserved; return 0x00010008; }
