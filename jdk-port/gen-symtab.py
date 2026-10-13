#!/usr/bin/env python3
# Generates ps5_static_lookup(): a name -> address table of every exported function in the
# given static libraries, replacing dlsym() which the PS5 cannot do for a single executable.
import subprocess, sys
NM = "/opt/ps5-payload-sdk/bin/prospero-nm"
out, libs = sys.argv[1], sys.argv[2:]
syms = set()
for lib in libs:
    for line in subprocess.check_output([NM, "-g", "--defined-only", lib], text=True).splitlines():
        p = line.split()
        if len(p) == 3 and p[1] == "T" and not p[2].startswith("_Z") and p[2].replace("_", "a").isalnum():
            syms.add(p[2])
syms = sorted(syms)
with open(out, "w") as f:
    f.write("#include <string.h>\n#include <stdlib.h>\n")
    for s in syms: f.write("extern void %s(void);\n" % s)
    f.write("static const struct { const char *n; void (*p)(void); } T[] = {\n")
    for s in syms: f.write('  {"%s", %s},\n' % (s, s))
    f.write("};\nvoid *ps5_static_lookup(const char *name) {\n"
            "  int lo = 0, hi = (int)(sizeof(T)/sizeof(T[0])) - 1;\n"
            "  while (lo <= hi) { int m = (lo + hi) / 2; int c = strcmp(name, T[m].n);\n"
            "    if (c == 0) return (void*)T[m].p; if (c < 0) hi = m - 1; else lo = m + 1; }\n"
            "  return 0;\n}\n")
print(len(syms), "symbols")
