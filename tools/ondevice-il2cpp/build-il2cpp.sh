#!/system/bin/sh
#
# Build libil2cpp.so ON AN ANDROID DEVICE from IL2CPP-generated C++.
#
# Proves the final link in the on-device build chain: a phone compiling and
# linking the game's native code itself, with no PC involved. Uses Termux's
# aarch64-hosted clang (Android has no system compiler) plus the NDK sysroot
# for the bionic headers and the static libc++ that Unity itself links against.
#
# Layout expected under $ROOT:
#   usr/       Termux toolchain (clang, lld, its own libs)
#   sysroot/   NDK sysroot (bionic headers + libc++_static.a)
#   cpp/       IL2CPP-generated .cpp/.c translation units
#   libil2cpp/ + external/   IL2CPP runtime sources and headers
#   baselib.a  Unity's prebuilt arm64 static library
#
# Any of those may be a symlink, and each can instead be pointed somewhere else
# entirely with the matching variable below. When the app drives this build the
# pieces are already on the device in three different places -- the toolchain on
# internal storage because that is the only kind Android will exec from, the
# generated C++ and the Unity sources on external storage because that is where
# there is room -- and naming them is cheaper than copying several gigabytes to
# satisfy a layout.
#
# It used to LINK them into one directory instead, which is nicer to poke at by
# hand and is not portable: $ROOT is on external storage, that is FUSE-backed,
# and FUSE returns EPERM for symlink() on most Android builds. It happens to be
# permitted on some vendor ROMs, which is why this survived until a Retroid
# Pocket Flip 2 met it. Paths cost nothing and work everywhere.
#
# Hence find -L throughout: with a symlinked layout, a find rooted at a linked
# directory returns the link and nothing under it, and the build silently
# compiles nothing.
set -eu

ROOT="${ROOT:-/data/local/tmp/tx}"
cd "$ROOT" || exit 1

# Every piece, defaulting to the linked layout so a hand-made $ROOT still works.
USR="${USR:-$ROOT/usr}"
SYSROOT="${SYSROOT:-$ROOT/sysroot}"
LIBIL2CPP="${LIBIL2CPP:-$ROOT/libil2cpp}"
EXTERNAL="${EXTERNAL:-$ROOT/external}"
BASELIB="${BASELIB:-$ROOT/baselib.a}"
CPPDIR="${CPPDIR:-$ROOT/cpp}"

for _p in "$USR" "$SYSROOT" "$LIBIL2CPP" "$EXTERNAL" "$BASELIB" "$CPPDIR"; do
    [ -e "$_p" ] || { echo "missing: $_p" >&2; exit 1; }
done

export LD_LIBRARY_PATH="$USR/lib"
CLANG="$USR/bin/clang"

# How many compiles run at once.
#
# Cores, capped by memory. Every clang here is a real process holding a real
# working set -- the generated translation units are large, several are over
# 2 MB of C++ with a precompiled header attached, and one can hold several
# hundred megabytes at its peak. On a phone with room that does not matter and
# the count is simply the core count. On one without it, eight of them at once
# is how a build gets its process killed rather than merely being slow: Android
# does not refuse the memory, it takes the process, and the build ends with
# nothing to show for the hour.
#
# MemAvailable rather than MemTotal, because unlike the conversion this runs
# fifteen minutes after the user pressed the button and what else is resident
# by then is a real constraint rather than an accident. 400 MB budgeted per
# compile, and half a gigabyte left for everything that is not this.
#
# sed rather than awk: awk is not on every Android build, sed is.
JOBS=$(nproc)
_avail=$(sed -n 's/^MemAvailable: *\([0-9]*\) kB$/\1/p' /proc/meminfo 2>/dev/null)
if [ -n "$_avail" ]; then
    _fit=$(( (_avail - 524288) / 409600 ))
    [ "$_fit" -lt 1 ] && _fit=1
    [ "$_fit" -lt "$JOBS" ] && JOBS=$_fit
fi
# The caller's word beats both. The app knows things this does not -- which
# tier the conversion had to fall back to, for one -- and a terminal run is
# often deliberately throttled to keep the phone usable.
JOBS="${BUILD_JOBS:-$JOBS}"
case "$JOBS" in ''|*[!0-9]*) echo "invalid BUILD_JOBS: $JOBS" >&2; exit 1 ;; esac
[ "$JOBS" -ge 1 ] || { echo "BUILD_JOBS must be positive" >&2; exit 1; }

# --sysroot points at the NDK (not Termux's) so the produced .so depends only
# on bionic: liblog/libm/libdl/libc, exactly like Unity's own libil2cpp.so.
# NOTE: do NOT force -resource-dir at the NDK's clang here. The resource dir
# carries compiler-specific headers (arm_neon.h among them), and Termux's
# clang-21 against the NDK's clang-18 resource dir fails on NEON intrinsics
# ("incompatible constant for this __builtin_neon function") in brotli's
# dec/decode.c -- which is where BrotliDecoderCreateInstance lives, so the .so
# then dies at dlopen. Let the compiler use its own resource dir; only the
# sysroot needs to come from the NDK.
TGT="--target=aarch64-linux-android33 --sysroot=$SYSROOT -stdlib=libc++ --unwindlib=none"
# Flags below are Unity's own, recovered verbatim from the Bee build graph of a
# desktop Android build (Library/Bee/Player*.dag.json). Guessing them does not
# work: without -DBASELIB_INLINE_NAMESPACE=il2cpp_baselib the baselib classes
# land directly in namespace baselib and collide with il2cpp's own forward
# declarations, and every TU that pulls in il2cpp-object-internals.h fails with
# "reference to 'ReentrantLock' is ambiguous".
INC="-I$CPPDIR -I$LIBIL2CPP -I$LIBIL2CPP/pch \
     -I$EXTERNAL/baselib/Include \
     -I$EXTERNAL/baselib/Platforms/Android/Include \
     -I$EXTERNAL/bdwgc/include \
     -I$LIBIL2CPP/os/ClassLibraryPAL/brotli/include"
DEF="-DANDROID -DHAVE_INTTYPES_H -DNDEBUG -DTARGET_ARM64 -DGC_NOT_DLL \
     -DBASELIB_INLINE_NAMESPACE=il2cpp_baselib \
     -DIL2CPP_MONO_DEBUGGER_DISABLED -DRUNTIME_IL2CPP \
     -DIL2CPP_ENABLE_WRITE_BARRIERS=1 -DIL2CPP_INCREMENTAL_TIME_SLICE=3 \
     -DIL2CPP_DEFAULT_DATA_DIR_PATH=Data \
     -D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__"
# Unity builds il2cpp with exceptions on and RTTI off; mismatching either
# produces link-time surprises rather than compile errors.
#
# OPT is the one flag here that is a choice rather than a requirement. Unity's
# own graph says -Os, which is what its "Faster (smaller) builds" setting
# means; -O2 is the other side of that switch and is what a shipped game
# normally uses. On a phone the trade is worth measuring rather than assuming:
# -O2 costs compile time and library size, and buys frame time.
#
# Changing it invalidates every object, which is handled -- the signature
# below covers CXXFLAGS, so obj/ is wiped and the whole thing rebuilds.
OPT="${OPT:--O2}"
CXXFLAGS="-march=armv8-a $OPT -fPIC -fexceptions -fno-rtti -funwind-tables \
     -fvisibility=hidden -fomit-frame-pointer -fno-strict-overflow \
     -ffunction-sections -fdata-sections -fstack-protector -no-canonical-prefixes \
     -Wno-invalid-offsetof -Wno-missing-declarations -Wno-unused-value \
     -Wno-unknown-warning-option -Wno-pragma-once-outside-header \
     -Wno-tautological-compare -Wno-null-conversion -Wno-undef-prefix"

rm -f err.log; : > err.log
mkdir -p obj

# One queue for generated code, runtime and support libraries. Keeping these
# in separate pools leaves cores idle at the tail of every phase. Their compile
# flags remain distinct; only scheduling is shared. Largest sources go first.
# Cache records are committed per object, so a failed/interrupted build keeps
# work that actually finished. A stamp never authorizes a partially written .o.
T0=$(date +%s)
echo "### device: $(getprop ro.product.model), $(nproc) cores, $JOBS parallel compiles"
echo "### clang: $($CLANG --version | head -1)"
echo "### Checking compiler cache inputs"

# Sources alone are not a sufficient cache key: shared headers, compiler,
# sysroot and runtime changes affect the generated objects and PCH as well.
# Hash common inputs in one batch rather than spawning sha256sum per header.
resource=$($CLANG -print-resource-dir)
[ -d "$resource" ] || { echo "missing clang resource directory: $resource" >&2; exit 1; }
find -L "$LIBIL2CPP" "$EXTERNAL" "$SYSROOT" "$resource" -type f -print0 > obj/.inputs0
find -L "$CPPDIR" -type f ! -name '*.cpp' ! -name '*.c' -print0 >> obj/.inputs0
printf '%s\0' "$CLANG" >> obj/.inputs0
xargs -0 sha256sum < obj/.inputs0 > obj/.input-hashes
LC_ALL=C sort obj/.input-hashes > obj/.sorted-inputs
common=$(sha256sum obj/.sorted-inputs | cut -d' ' -f1)
# Bump the cache schema when any of the special support-library flags change.
sig=$(printf '%s|%s|%s|%s|%s|%s' 'native-cache-v2' "$TGT" "$DEF" "$INC" "$CXXFLAGS" "$common" | sha256sum | cut -d' ' -f1)
FLAGSIG=obj/.flagsig
if [ "${FULL:-0}" = 1 ] || [ ! -f "$FLAGSIG" ] || [ "$(cat "$FLAGSIG")" != "$sig" ]; then
    echo "### Compiler inputs changed — refreshing the native cache"
    rm -rf obj
    mkdir -p obj
    printf '%s' "$sig" > "$FLAGSIG"
fi
rm -f obj/.inputs0 obj/.input-hashes obj/.sorted-inputs

obj_name() { printf 'obj/%s%s.o' "$1" "$(printf '%s' "${2#$3/}" | tr -c 'A-Za-z0-9._-' '_')"; }

# PCH is optional, but never reuse a partial file after an interrupted compile.
build_pch() {
    hdr="$1"; lang="$2"; std="$3"; out="$4"
    [ -f "$hdr" ] || return 1
    [ -s "$out" ] && return 0
    rm -f "$out.part"
    if $CLANG -x "$lang-header" -std="$std" $CXXFLAGS $DEF $INC $TGT \
        -o "$out.part" "$hdr" 2>>err.log && [ -s "$out.part" ]; then
        mv "$out.part" "$out"
    else
        rm -f "$out.part"
        return 1
    fi
}
pch_for() {
    if build_pch "$1" "$2" "$3" "$4"; then
        printf '%s %s' -include-pch "$4"
    else
        echo "### PCH unavailable for $2, compiling without one" >&2
        printf ''
    fi
}
echo "### Preparing precompiled headers"
PCH_CPP=$(pch_for "$LIBIL2CPP/pch/pch-cpp.hpp" c++ c++11 obj/pch-cpp.pch)
PCH_C=$(pch_for "$LIBIL2CPP/pch/pch-c.h" c c11 obj/pch-c.pch)
BR="$LIBIL2CPP/os/ClassLibraryPAL/brotli"
GCDEF="-DANDROID -DHAVE_INTTYPES_H -DNDEBUG -DGC_NOT_DLL -DHAVE_BOEHM_GC \
     -DALL_INTERIOR_POINTERS=1 -DGC_GCJ_SUPPORT=1 -DJAVA_FINALIZATION=1 \
     -DNO_EXECUTE_PERMISSION=1 -DGC_NO_THREADS_DISCOVERY=1 \
     -DIGNORE_DYNAMIC_LOADING=1 -DGC_DONT_REGISTER_MAIN_STATIC_DATA=1 \
     -DGC_VERSION_MAJOR=7 -DGC_VERSION_MINOR=7 -DGC_VERSION_MICRO=0 \
     -DGC_THREADS=1 -DUSE_MMAP=1 -DUSE_MUNMAP=1 \
     -DIL2CPP_ENABLE_WRITE_BARRIERS=1 -DIL2CPP_INCREMENTAL_TIME_SLICE=3 \
     -D__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__"
export CLANG CXXFLAGS DEF INC TGT OPT CPPDIR LIBIL2CPP EXTERNAL BR GCDEF PCH_CPP PCH_C

cat > obj/.cc <<'WORKER'
#!/system/bin/sh
set -eu
prefix="$1"; hash="$2"; f="$3"
case "$prefix" in
    g|c) tree="$CPPDIR" ;;
    r) tree="$LIBIL2CPP" ;;
    b) tree="$EXTERNAL/bdwgc/extra" ;;
    z) tree="$EXTERNAL/zlib" ;;
    t) tree="$BR" ;;
    *) echo "unknown compile group: $prefix" >&2; exit 1 ;;
esac
rel="${f#$tree/}"
out=$(printf 'obj/%s%s.o' "$prefix" "$(printf '%s' "$rel" | tr -c 'A-Za-z0-9._-' '_')")
# An old object must not make a failing compiler look successful.
rm -f "$out" "$out.sha256" "$out.part" "$out.sha256.part"
trap 'rm -f "$out.part" "$out.sha256.part"' EXIT
case "$prefix" in
    g) set -- -x c++ -std=c++11 $PCH_CPP $CXXFLAGS $DEF $INC ;;
    c) set -- -x c -std=c11 $PCH_C $CXXFLAGS $DEF $INC ;;
    r) set -- -x c++ -std=c++11 $CXXFLAGS $DEF $INC ;;
    b) set -- -x c -std=gnu99 -march=armv8-a $OPT -fPIC -fvisibility=hidden \
        -Wno-implicit-function-declaration $GCDEF \
        -I"$EXTERNAL/bdwgc/include" -I"$EXTERNAL/bdwgc/libatomic_ops/src" ;;
    z) set -- -x c -std=gnu99 -march=armv8-a $OPT -fPIC -fvisibility=hidden \
        -DZ_PREFIX -DHAVE_HIDDEN -DNDEBUG -DANDROID -I"$EXTERNAL/zlib" ;;
    t) set -- -x c -std=gnu99 -march=armv8-a $OPT -fPIC -fvisibility=hidden \
        -DNDEBUG -DANDROID -I"$BR/include" -I"$LIBIL2CPP" -I"$BR" ;;
esac
if ! "$CLANG" "$@" $TGT -c "$f" -o "$out.part" 2>>err.log; then
    echo "### COMPILE FAILED: $f" >&2
    exit 1
fi
[ -s "$out.part" ] || { echo "### EMPTY OBJECT: $f" >&2; exit 1; }
mv "$out.part" "$out"
printf '%s' "$hash" > "$out.sha256.part"
mv "$out.sha256.part" "$out.sha256"
WORKER

: > obj/.objs
: > obj/.sources
: > obj/.tasks
queue_tree() {
    tree="$1"; prefix="$2"; pattern="$3"
    # NUL-delimited inputs keep filenames with spaces as one compiler input.
    find -L "$tree" -type f -name "$pattern" -print0 > obj/.files0
    [ -s obj/.files0 ] || { echo "missing sources for $prefix under $tree" >&2; exit 1; }
    xargs -0 sha256sum < obj/.files0 > obj/.now
    cat obj/.now >> obj/.sources
    while read -r hash f; do
        out=$(obj_name "$prefix" "$f" "$tree")
        printf '%s\n' "$out" >> obj/.objs
        if [ -s "$out" ] && [ -f "$out.sha256" ] && [ "$(cat "$out.sha256")" = "$hash" ]; then
            continue
        fi
        size=$(stat -c '%s' "$f")
        printf '%s %s %s %s\n' "$size" "$prefix" "$hash" "$f" >> obj/.tasks
    done < obj/.now
}
queue_tree "$CPPDIR" g '*.cpp'
queue_tree "$CPPDIR" c '*.c'
queue_tree "$LIBIL2CPP" r '*.cpp'
# Boehm must be compiled as its single amalgamated TU, never individual .c files.
queue_tree "$EXTERNAL/bdwgc/extra" b 'gc.c'
queue_tree "$EXTERNAL/zlib" z '*.c'
queue_tree "$BR" t '*.c'

# Drop objects removed from the source set, including support-library objects.
find obj -maxdepth 1 -type f -name '*.o' > obj/.existing
if grep -Fvx -f obj/.objs obj/.existing > obj/.stale; then
    while IFS= read -r out; do rm -f "$out" "$out.sha256"; done < obj/.stale
fi

total=$(wc -l < obj/.objs)
rebuilt=$(wc -l < obj/.tasks)
echo "### PHASE N: compiling game and engine ($total TUs)"
echo "### $rebuilt to compile, $((total - rebuilt)) cached, $JOBS workers"
if [ "$rebuilt" -gt 0 ]; then
    LC_ALL=C sort -rn obj/.tasks > obj/.tasks.sorted
    while read -r size prefix hash f; do
        printf '%s\0%s\0%s\0' "$prefix" "$hash" "$f"
    done < obj/.tasks.sorted > obj/.todo0
    if ! xargs -0 -P "$JOBS" -n 3 sh obj/.cc < obj/.todo0; then
        echo "### Compilation failed; completed objects are saved for retry" >&2
        tail -20 err.log >&2
        exit 1
    fi
fi
while IFS= read -r out; do
    [ -s "$out" ] && [ -s "$out.sha256" ] || { echo "### Missing compiled object: $out" >&2; exit 1; }
done < obj/.objs

echo "### Compiled: $rebuilt rebuilt, $((total - rebuilt)) cached in $(( $(date +%s)-T0 ))s"

# The successful library can be reused only when every input to its link is
# unchanged. This avoids re-linking and reinstalling it after a no-op retry.
LINKFLAGS="-shared -fPIC -fuse-ld=lld -nostdlib++ -Wl,-soname,libil2cpp.so -Wl,-z,max-page-size=16384"
LINKLIBS="-lc++_static -lc++abi -llog -lm -ldl -lc -Wl,--allow-shlib-undefined"
{
    printf '%s\n' "$sig" "$TGT" "$LINKFLAGS" "$LINKLIBS"
    LC_ALL=C sort obj/.sources
    LC_ALL=C sort obj/.objs
    sha256sum "$BASELIB"
    sha256sum "$USR/bin/clang++"
    # lld can change independently of the compiler driver in a toolchain update.
    if [ -f "$USR/bin/ld.lld" ]; then sha256sum "$USR/bin/ld.lld"; fi
} > obj/.link-inputs
link_sig=$(sha256sum obj/.link-inputs | cut -d' ' -f1)
if [ "$rebuilt" -eq 0 ] && [ -s "$ROOT/libil2cpp.so" ] &&
    [ -f obj/.linksig ] && [ "$(cat obj/.linksig)" = "$link_sig" ]; then
    echo "### Engine library is current; skipping link"
    echo "total build time: $(( $(date +%s)-T0 ))s"
    exit 0
fi

T3=$(date +%s)
echo "### PHASE D: linking libil2cpp.so"
# baselib.a is Unity's PREBUILT arm64-android static library (shipped in the
# Editor's AndroidPlayer StaticLibs). It is not compilable from the sources in
# external/baselib, and without it the .so fails at dlopen on
# Baselib_Timer_TickToNanosecondsConversionFactor.
#
# -nostdlib++ with the NDK's static libc++: Unity's libil2cpp.so has no
# libc++_shared dependency, and the app process will not have Termux's libs.
#
# -soname matters once the library is loaded from app storage rather than out
# of the APK. The engine asks for it by name -- dlopen("libil2cpp.so") -- and
# bionic satisfies that from an already-loaded library by matching its soname.
# Preloading an absolute path only works if the library actually carries the
# name the engine will ask for; without DT_SONAME the match depends on how the
# loader derives a name from the path, which is not worth relying on.
# The link's own output goes to err.log and its STATUS is checked.
#
# This used to end in `2>&1 | head -10`, which is two problems in one. A pipe
# makes $? the status of the LAST command, so the shell saw head's success and
# never the linker's failure; and head closing the pipe after ten lines sends
# the linker SIGPIPE, so a link that had more than ten warnings to give was
# killed by the thing reading its warnings.
#
# Neither was visible, because the check below asked whether the output FILE
# existed -- and lld creates and sizes that file early, so it exists no matter
# how the link ends. The result was a build that reported success and left
# behind a libil2cpp.so that was 151 KB short of the real one and crashed in
# the dynamic linker, running an .init_array entry, before a line of game code:
#
#   #00 pc 00000000030f3740  <unknown>
#   #01 linker64 (__dl__ZN6soinfo17call_constructorsEv+752)
#
# So: no pipe, the status is kept, and a failed link discards its temporary output instead of replacing the
# previous complete library.
#
# max-page-size is 16 KB because Android 15 allows devices whose pages are,
# and a library aligned for 4 KB cannot be mapped on one. lld defaults to 4 KB
# for Android, so it has to be asked. Nothing is lost on a 4 KB device -- the
# alignment is padding between segments, which such a kernel maps happily, and
# every other .so in the APK is already built this way.
# Publish only a completed link, keeping any previous valid library intact.
rm -f "$ROOT/libil2cpp.so.part"
link_status=0
"$USR/bin/clang++" $TGT $LINKFLAGS \
    -o "$ROOT/libil2cpp.so.part" obj/*.o "$BASELIB" \
    $LINKLIBS >>err.log 2>&1 || link_status=$?
echo "  $(( $(date +%s)-T3 ))s"

echo "### RESULT"
if [ "$link_status" -ne 0 ] || [ ! -s "$ROOT/libil2cpp.so.part" ]; then
    echo "### LINK FAILED (exit $link_status)" >&2
    tail -20 err.log >&2
    rm -f "$ROOT/libil2cpp.so.part"
    exit 1
fi
mv "$ROOT/libil2cpp.so.part" "$ROOT/libil2cpp.so"
printf '%s' "$link_sig" > obj/.linksig.part
mv obj/.linksig.part obj/.linksig
ls -la "$ROOT/libil2cpp.so"
echo "total build time: $(( $(date +%s)-T0 ))s"
