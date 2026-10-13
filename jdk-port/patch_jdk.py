import sys
root=sys.argv[1]
def sub(path, old, new):
    p=root+"/"+path; t=open(p).read()
    if new in t: return
    assert t.count(old)==1, (path, old)
    open(p,"w").write(t.replace(old,new))
sub("src/hotspot/os/posix/os_posix.cpp",
"""void* os::dll_lookup(void* handle, const char* name) {
  return dlsym(handle, name);""",
"""#ifdef STATIC_BUILD
extern "C" void* ps5_static_lookup(const char* name);
#endif
void* os::dll_lookup(void* handle, const char* name) {
#ifdef STATIC_BUILD
  // PS5: everything is linked into one executable; resolve from a generated table.
  return ps5_static_lookup(name);
#endif
  return dlsym(handle, name);""")
sub("src/hotspot/os/posix/os_posix.cpp",
"""void* os::get_default_process_handle() {
#ifdef __APPLE__""",
"""void* os::get_default_process_handle() {
#ifdef STATIC_BUILD
  return (void*)1; // PS5: sentinel, lookups go through ps5_static_lookup
#endif
#ifdef __APPLE__""")
sub("src/hotspot/share/runtime/os.cpp",
"""  // Check whether file exists.
  if (ret != -1) {
    struct stat statbuf;""",
"""  // Check whether file exists.
#ifdef STATIC_BUILD
  if (ret != -1) return true; // PS5: libraries are linked in, no file to find
#endif
  if (ret != -1) {
    struct stat statbuf;""")
sub("src/java.base/unix/native/libjava/jni_util_md.c",
"""void* getProcessHandle() {""",
"""void* getProcessHandle() {
#ifdef STATIC_BUILD
    return (void*)1; /* PS5: sentinel, lookups go through ps5_static_lookup */
#endif""")
print("patched")
sub("src/hotspot/os/bsd/os_bsd.cpp",
"""  char dli_fname[MAXPATHLEN];
  dli_fname[0] = '\\0';
  bool ret = dll_address_to_library_name(
                                         CAST_FROM_FN_PTR(address, os::jvm_path),""",
"""#ifdef STATIC_BUILD
  {
    // PS5: there is no libjvm file to locate; Java lives in a fixed folder.
    const char* h = ::getenv("PS5_JAVA_HOME");
    if (h == nullptr || h[0] == '\\0') h = "/data/jvm";
    jio_snprintf(buf, buflen, "%s/lib/zero/libjvm.so", h);
    strncpy(saved_jvm_path, buf, MAXPATHLEN);
    saved_jvm_path[MAXPATHLEN - 1] = '\\0';
    return;
  }
#endif
  char dli_fname[MAXPATHLEN];
  dli_fname[0] = '\\0';
  bool ret = dll_address_to_library_name(
                                         CAST_FROM_FN_PTR(address, os::jvm_path),""")
print("patched2")
sub("make/common/NativeCompilation.gmk",
"""  ifeq ($$($1_TYPE), STATIC_LIBRARY)
    $1_EXTRA_CFLAGS += -DLIBRARY_NAME=$$($1_NAME)
    $1_EXTRA_CXXFLAGS += -DLIBRARY_NAME=$$($1_NAME)
  endif""",
"""  ifeq ($$($1_TYPE), STATIC_LIBRARY)
    $1_EXTRA_CFLAGS += -DLIBRARY_NAME=$$($1_NAME)
    $1_EXTRA_CXXFLAGS += -DLIBRARY_NAME=$$($1_NAME)
  endif
  # PS5: libraries are built with -DSTATIC_BUILD and packed into archives by the stand-in linker
  ifeq ($$($1_TYPE), LIBRARY)
    ifneq ($$($1_NAME), jvm)
      $1_EXTRA_CFLAGS += -DLIBRARY_NAME=$$($1_NAME)
      $1_EXTRA_CXXFLAGS += -DLIBRARY_NAME=$$($1_NAME)
    endif
  endif""")
print("patched3")
