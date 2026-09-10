// NativeBuild — compiling libil2cpp.so on the phone.
//
// The last step of the chain: 1500-odd translation units of generated C++ and
// IL2CPP runtime source, compiled and linked into the library the engine
// dlopens. About seventeen minutes cold on eight cores.
//
// The compile itself is tools/ondevice-il2cpp/build-il2cpp.sh, run rather than
// reimplemented. Its flags were recovered from Unity's own Bee build graph and
// are not guessable -- BASELIB_INLINE_NAMESPACE, the amalgamated bdwgc,
// Z_PREFIX on zlib, brotli needing libil2cpp on the include path -- and every
// one of them fails in a way that points somewhere else. Having two copies of
// that knowledge, one in shell for a terminal and one in Kotlin for the app,
// is how they drift; the script is staged into assets from the same file the
// terminal runs, and this class supplies it with a directory.
//
// That directory is the only real work here. The pieces are already on the
// device in three places, for reasons that are not negotiable: the toolchain
// on internal storage, because that is the only kind Android will exec from;
// the generated C++ and Unity's sources on external, because a build needs
// several gigabytes and internal storage is the scarce kind. Rather than copy
// any of it, they are linked into one root -- which is why the script uses
// find -L.

package dev.silksong.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

object NativeBuild {

    data class Progress(val step: String, val fraction: Float, val detail: String = "")

    private const val SCRIPT_ASSET = "ondevice/build-il2cpp.sh"

    /** The built library, which is what the engine loads. */
    fun output(root: File): File = File(root, "libil2cpp.so")

    fun isPresent(root: File): Boolean = output(root).length() > 0

    /**
     * Roughly how many objects the build produces, for the progress bar.
     *
     * Only used to turn a count into a fraction; being wrong makes the bar
     * inaccurate, not the build. The phases report their real totals as they
     * start, and this is replaced by those.
     */
    private const val EXPECTED_OBJECTS = 1550

    /**
     * Compiles and links libil2cpp.so.
     *
     * [root] is where the conversion left cpp/ and where obj/ and the output
     * will go -- several gigabytes, so external storage.
     */
    fun build(
        unity: File,
        toolchain: File,
        root: File,
        assets: android.content.res.AssetManager,
        install: File? = null,
    ): Flow<Progress> =
        channelFlow {
            if (!Il2cppConverter.cppDir(root).isDirectory) {
                throw IOException("nothing to compile: run the conversion first")
            }

            send(Progress("Preparing the build", -1f, "locating the sources"))
            val (script, pieces) = stage(unity, toolchain, root, assets)

            // Audit the *translated* Steamworks.NET P/Invoke surface before
            // spending minutes linking an engine that could only fail later at
            // dlsym. This makes the Android shim a strict Valve-compatible ABI
            // boundary rather than a best-effort collection of guessed exports.
            SteamAbiAudit.requireCompatible(Il2cppConverter.cppDir(root))

            val objDir = File(root, "obj")
            val phase = java.util.concurrent.atomic.AtomicReference("Compiling")
            val total = java.util.concurrent.atomic.AtomicInteger(EXPECTED_OBJECTS)
            val prefix = java.util.concurrent.atomic.AtomicReference<String?>(null)

            val ticker = launch(Dispatchers.IO) {
                var last = -1
                while (isActive) {
                    val p = prefix.get()
                    val n = if (p == null) 0
                    else objDir.list()?.count { it.startsWith(p) && it.endsWith(".o") } ?: 0
                    if (n != last) {
                        last = n
                        val t = total.get()
                        val f = if (t > 0) (n.toFloat() / t).coerceIn(0f, 1f) else -1f
                        trySend(Progress(phase.get(), f, "$n of $t"))
                    }
                    delay(1000)
                }
            }

            val started = System.currentTimeMillis()
            val log = File(root, "compile.log")
            val sink = log.bufferedWriter()
            val result = try {
                Toolchain.exec(
                    listOf("/system/bin/sh", script.absolutePath),
                    cwd = root,
                    env = Toolchain.environment(toolchain) +
                        mapOf("ROOT" to root.absolutePath) + pieces,
                ) { line ->
                    sink.write(line); sink.write("\n"); sink.flush()
                    if (line.startsWith("###")) {
                        val text = line.removePrefix("###").trim()
                        val named = when {
                            text.startsWith("PHASE A") -> "Compiling the game" to "g"
                            text.startsWith("PHASE B") -> "Compiling the game" to "c"
                            text.startsWith("PHASE C:") -> "Compiling the engine runtime" to "r"
                            text.startsWith("PHASE C") -> "Compiling support libraries" to null
                            text.startsWith("PHASE D") -> "Linking the engine" to null
                            else -> null
                        } ?: return@exec
                        Regex("\\((\\d+) TUs\\)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                            ?.let { total.set(it) }
                        phase.set(named.first)
                        prefix.set(named.second)
                        if (named.second == null) trySend(Progress(named.first, -1f, ""))
                    }
                }
            } finally {
                ticker.cancel()
                sink.flush(); sink.close()
            }
            val seconds = (System.currentTimeMillis() - started) / 1000

            val out = output(root)
            if (!result.ok || out.length() <= 0) {
                val why = result.output.lineSequence()
                    .firstOrNull { it.contains("error:") }
                    ?: result.output.trim().lines().lastOrNull()
                    ?: "exit ${result.code}"
                throw IOException("the compile failed after ${seconds}s: ${why.trim().take(300)}")
            }
            LauncherLog.log("libil2cpp.so: ${out.length()} bytes in ${seconds}s")

            if (install != null) {
                send(Progress("Installing the engine", -1f, "${out.length() / 1024 / 1024} MB"))
                installTo(out, File(install, out.name))
            }
            send(Progress("Compiled", 1f, "${out.length() / 1024 / 1024} MB in ${seconds}s"))
        }.flowOn(Dispatchers.IO)

    /**
     * Moves the built library somewhere it can actually be loaded.
     */
    private fun installTo(from: File, to: File) {
        to.parentFile?.mkdirs()
        val stamp = File(to.parentFile, "${to.name}.stamp")
        val want = "${from.length()}:${from.lastModified()}"
        if (to.length() == from.length() &&
            runCatching { stamp.readText().trim() }.getOrNull() == want
        ) {
            return
        }
        val tmp = File(to.parentFile, "${to.name}.part")
        from.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
        if (!tmp.renameTo(to)) {
            tmp.delete()
            throw IOException("could not install the engine to $to")
        }
        to.setExecutable(true, true)
        runCatching { stamp.writeText(want) }
        LauncherLog.log("installed ${to.name} to ${to.parent}")
    }

    /** Points the build script at pieces that live in different storage areas. */
    private fun stage(
        unity: File,
        toolchain: File,
        root: File,
        assets: android.content.res.AssetManager,
    ): Pair<File, Map<String, String>> {
        root.mkdirs()
        val il2cppSrc = File(unity, "editor/Editor/Data/il2cpp")
        val pieces = mapOf(
            "USR" to File(toolchain, "usr"),
            "SYSROOT" to File(toolchain, "sysroot"),
            "LIBIL2CPP" to File(il2cppSrc, "libil2cpp"),
            "EXTERNAL" to File(il2cppSrc, "external"),
            "BASELIB" to File(
                unity,
                "android/Variations/il2cpp/Release/StaticLibs/arm64-v8a/baselib.a",
            ),
            "CPPDIR" to Il2cppConverter.cppDir(root),
        )
        for ((name, target) in pieces) {
            if (!target.exists()) throw IOException("the build needs $name, which is not at $target")
        }

        val script = File(root, "build-il2cpp.sh")
        assets.open(SCRIPT_ASSET).use { input ->
            script.outputStream().use { out -> input.copyTo(out) }
        }
        return script to pieces.mapValues { it.value.absolutePath }
    }
}
