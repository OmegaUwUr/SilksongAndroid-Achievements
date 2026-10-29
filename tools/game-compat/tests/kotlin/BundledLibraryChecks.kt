package dev.silksong.launcher

internal fun bundledLibraryChecks(test: (String, () -> Unit) -> Unit) {
    val bundle = BundledNativeLibraryCatalog.dependencies.keys.associateWith { "a".repeat(64) }
    test("included C libraries match exact Android names without impersonating platform SDKs") {
        check(BundledNativeLibraryCatalog.candidates(listOf("sqlite3", "liblz4", "libzstd.so")) == setOf("libsqlite3.so", "liblz4.so", "libzstd.so"))
        check(BundledNativeLibraryCatalog.candidates(listOf("SQLite3", "sqlite3.dll", "libogg.so.0", "../sqlite3", "steam_api", "rail_api", "FMOD", "SDL2", "__Internal", "kernel32")).isEmpty())
    }
    test("Vorbis providers include all required library dependencies") {
        check(BundledNativeLibraryCatalog.closure(setOf("libvorbisfile.so")) == setOf("libogg.so", "libvorbis.so", "libvorbisfile.so"))
        check(runCatching { BundledNativeLibraryCatalog.closure(setOf("libsteam_api.so")) }.isFailure)
    }
    test("adding included libraries preserves independent user plugins") {
        val added = BundledNativeLibraryCatalog.additions(listOf("sqlite3"), mapOf("libcustom.so" to "b".repeat(64)), bundle)
        check(added == setOf("libsqlite3.so"))
    }
    test("a user's existing root library takes priority over the included implementation") {
        check(BundledNativeLibraryCatalog.additions(listOf("sqlite3"), mapOf("libsqlite3.so" to "b".repeat(64)), bundle).isEmpty())
    }
    test("mixed dependent native builds are refused instead of silently overwritten") {
        check(runCatching { BundledNativeLibraryCatalog.additions(listOf("vorbisfile"), mapOf("libogg.so" to "b".repeat(64)), bundle) }.isFailure)
        check(BundledNativeLibraryCatalog.additions(listOf("vorbisfile"), mapOf("libogg.so" to "a".repeat(64)), bundle) == setOf("libvorbis.so", "libvorbisfile.so"))
    }
    test("an incomplete signed-APK provider catalog cannot authorize additions") {
        check(runCatching { BundledNativeLibraryCatalog.additions(listOf("sqlite3"), emptyMap(), bundle - "libpng16.so") }.isFailure)
    }
    test("native providers load dependencies before dependents without duplicate loads") {
        val names = BundledNativeLibraryCatalog.closure(setOf("libvorbisfile.so", "libvorbisenc.so"))
        val order = BundledNativeLibraryCatalog.loadOrder(names)
        check(order.toSet() == names && order.size == names.size)
        check(order.indexOf("libogg.so") < order.indexOf("libvorbis.so") && order.indexOf("libvorbis.so") < order.indexOf("libvorbisfile.so"))
        check(runCatching { BundledNativeLibraryCatalog.loadOrder(setOf("libvorbisfile.so")) }.isFailure)
    }
    test("included native providers cannot replace Android OS or dedicated engine identities") {
        check(BundledNativeLibraryCatalog.dependencies.keys.intersect(NativePortRequirements.androidSystemLibraries).isEmpty())
        check(BundledNativeLibraryCatalog.dependencies.keys.none { it in setOf("libunity.so", "libmain.so", "libil2cpp.so", "libsteam_api.so") })
    }
}
