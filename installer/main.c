/* Installer payload: writes the J2ME player into /data/homebrew/PS5J2ME so the
 * ps5-payload-dev homebrew launcher can start it. Send to port 9021. */
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <unistd.h>

#include <ps5/kernel.h>

__asm__(".section .rodata\n"
        ".global eboot_start\neboot_start:\n.incbin \"../app/eboot.elf\"\n"
        ".global eboot_end\neboot_end:\n"
        ".global icon_start\nicon_start:\n.incbin \"icon0.png\"\n"
        ".global icon_end\nicon_end:\n"
        ".global tile_start\ntile_start:\n.incbin \"../tile/eboot.bin\"\n"
        ".global tile_end\ntile_end:\n"
        ".global front_start\nfront_start:\n.incbin \"../java/ps5frontend.jar\"\n"
        ".global front_end\nfront_end:\n"
        ".global fj_start\nfj_start:\n.incbin \"../java/freej2me-lr.jar\"\n"
        ".global fj_end\nfj_end:\n"
        ".global fjnew_start\nfjnew_start:\n.incbin \"../java/freej2me-new.jar\"\n"
        ".global fjnew_end\nfjnew_end:\n"
        ".global pclassic_start\npclassic_start:\n.incbin \"../java/ps5patch-classic.jar\"\n"
        ".global pclassic_end\npclassic_end:\n"
        ".global pnew_start\npnew_start:\n.incbin \"../java/ps5patch-new.jar\"\n"
        ".global pnew_end\npnew_end:\n"
        ".text\n");
extern const uint8_t eboot_start[], eboot_end[], icon_start[], icon_end[],
                     tile_start[], tile_end[], front_start[], front_end[],
                     fj_start[], fj_end[], fjnew_start[], fjnew_end[],
                     pclassic_start[], pclassic_end[], pnew_start[], pnew_end[];

int sceUserServiceInitialize(void *);
int sceAppInstUtilInitialize(void);
int sceAppInstUtilAppInstallAll(void *);

/* Registers the title folder with the home screen. The per-title call does
 * not exist on every firmware, so it is looked up at run time and the
 * install-everything call is used when it is missing. */
static int
register_title(const char *title_id) {
  int (*install_dir)(const char *, const char *, void *) = 0;
  uint32_t handle;
  int err;

  sceUserServiceInitialize(0);
  kernel_set_ucred_authid(-1, 0x4801000000000013L);
  if((err = sceAppInstUtilInitialize())) {
    printf("sceAppInstUtilInitialize: 0x%08x\n", err);
    return err;
  }
  if(!kernel_dynlib_handle(-1, "libSceAppInstUtil.sprx", &handle)) {
    install_dir = (void *)kernel_dynlib_resolve(-1, handle, "Wudg3Xe3heE");
  }
  if(install_dir) {
    printf("registering %s (per-title call)\n", title_id);
    err = install_dir(title_id, "/user/app/", 0);
  } else {
    printf("registering %s (install-all call)\n", title_id);
    err = sceAppInstUtilAppInstallAll(0);
  }
  if(err) printf("register failed: 0x%08x\n", err);
  return err;
}

/* Home-screen tile: a small native app (../tile) whose program lives in
 * /system_ex/app and whose name and icon are registered from /user/app,
 * the same layout the SDK's install_app sample uses. */
#define TITLE_ID  "PSJM00001"
#define DIR_TITLE "/user/app/" TITLE_ID
#define DIR_SYS   "/system_ex/app/" TITLE_ID

static const char param_json[] =
  "{\n"
  "  \"applicationCategoryType\": 0,\n"
  "  \"localizedParameters\": {\n"
  "    \"defaultLanguage\": \"en-US\",\n"
  "    \"en-US\": { \"titleName\": \"J2ME Player\" }\n"
  "  },\n"
  "  \"titleId\": \"" TITLE_ID "\"\n"
  "}\n";

/* The copy next to the program itself; the system reads this one at launch. */
static const char param_json_sys[] =
  "{\n"
  "  \"applicationCategoryType\": 33554432,\n"
  "  \"localizedParameters\": {\n"
  "    \"defaultLanguage\": \"en-US\",\n"
  "    \"en-US\": { \"titleName\": \"J2ME Player\" }\n"
  "  },\n"
  "  \"titleId\": \"" TITLE_ID "\"\n"
  "}\n";

#define IOV(x) { (void *)(x), (x) ? strlen(x) + 1 : 0 }

/* /system_ex is read-only by default; this makes it writable until reboot. */
static int
remount_system_ex(void) {
  struct iovec iov[] = {
    IOV("from"),      IOV("/dev/ssd0.system_ex"),
    IOV("fspath"),    IOV("/system_ex"),
    IOV("fstype"),    IOV("exfatfs"),
    IOV("large"),     IOV("yes"),
    IOV("timezone"),  IOV("static"),
    IOV("async"),     IOV((char *)0),
    IOV("ignoreacl"), IOV((char *)0),
  };
  return nmount(iov, sizeof iov / sizeof iov[0], MNT_UPDATE);
}

#define DIR_APP "/data/homebrew/PS5J2ME"

static int
put_file(const char *path, const uint8_t *data, size_t size) {
  int fd = open(path, O_CREAT | O_TRUNC | O_WRONLY, 0755);

  if(fd < 0) {
    printf("FAIL %s: %s\n", path, strerror(errno));
    return -1;
  }
  for(size_t off = 0; off < size;) {
    ssize_t n = write(fd, data + off, size - off);
    if(n <= 0) {
      printf("FAIL writing %s: %s\n", path, strerror(errno));
      close(fd);
      return -1;
    }
    off += n;
  }
  close(fd);
  printf("ok   %s (%zu bytes)\n", path, size);
  return 0;
}

int
main(void) {
  static const uint8_t caps[16] = {0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
                                   0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff};
  pid_t pid = getpid();
  int bad = 0;

  printf("ps5j2me installer running\n");
  fflush(stdout);

  kernel_set_proc_rootdir(pid, kernel_get_root_vnode());
  kernel_set_proc_jaildir(pid, 0);
  kernel_set_ucred_uid(pid, 0);
  kernel_set_ucred_caps(pid, caps);

  mkdir("/data/homebrew", 0777);
  mkdir(DIR_APP, 0777);
  mkdir(DIR_APP "/sce_sys", 0777);

  bad |= put_file(DIR_APP "/eboot.elf", eboot_start, eboot_end - eboot_start);
  bad |= put_file(DIR_APP "/sce_sys/icon0.png", icon_start, icon_end - icon_start);

  /* The emulator itself (Java code) lives with the Java runtime in /data/jvm. */
  mkdir("/data/jvm", 0777);
  mkdir("/data/jvm/fj", 0777);
  mkdir("/data/jvm/games", 0777);
  bad |= put_file("/data/jvm/fj/ps5frontend.jar", front_start, front_end - front_start);
  bad |= put_file("/data/jvm/fj/freej2me-lr.jar", fj_start, fj_end - fj_start);
  /* the newer emulator build, used for games that need it (chosen per game) */
  bad |= put_file("/data/jvm/fj/freej2me-new.jar", fjnew_start, fjnew_end - fjnew_start);
  bad |= put_file("/data/jvm/fj/ps5patch-classic.jar", pclassic_start, pclassic_end - pclassic_start);
  bad |= put_file("/data/jvm/fj/ps5patch-new.jar", pnew_start, pnew_end - pnew_start);
  if(access("/data/jvm/lib/modules", R_OK)) {
    printf("NOTE: the Java runtime is not in /data/jvm yet - upload it before starting the app\n");
  }

  if(bad) {
    printf("=== install FAILED ===\n");
    return 1;
  }
  printf("=== app installed to " DIR_APP " ===\n");

  if(mkdir(DIR_SYS, 0755) && errno != EEXIST) {
    if(remount_system_ex()) printf("remount /system_ex: %s\n", strerror(errno));
    mkdir(DIR_SYS, 0755);
  }
  if(put_file(DIR_SYS "/eboot.bin", tile_start, tile_end - tile_start)) {
    /* folder existed but the volume is read-only again after a reboot */
    if(remount_system_ex()) printf("remount /system_ex: %s\n", strerror(errno));
    bad |= put_file(DIR_SYS "/eboot.bin", tile_start, tile_end - tile_start);
  }
  mkdir(DIR_TITLE, 0755);
  mkdir(DIR_TITLE "/sce_sys", 0755);
  bad |= put_file(DIR_TITLE "/sce_sys/param.json", (const uint8_t *)param_json,
                  sizeof param_json - 1);
  bad |= put_file(DIR_TITLE "/sce_sys/icon0.png", icon_start, icon_end - icon_start);
  if(!bad && register_title(TITLE_ID)) bad = 1;

  /* Written after registering, in the same order as the SDK sample. */
  mkdir(DIR_SYS "/sce_sys", 0755);
  bad |= put_file(DIR_SYS "/sce_sys/param.json", (const uint8_t *)param_json_sys,
                  sizeof param_json_sys - 1);
  printf(bad ? "=== home-screen tile FAILED (the app itself is installed) ===\n"
             : "=== home-screen tile \"J2ME Player\" installed ===\n");
  return bad ? 1 : 0;
}
