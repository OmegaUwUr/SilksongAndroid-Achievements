// UnityFetcher — downloads Unity's redistributables, on the device, from Unity.
//
// None of this ships in the APK. Unity's engine and toolchain are Unity's to
// distribute, so each device fetches them from Unity directly, and what
// arrives is checked against a known digest before anything uses it.
//
// This is the Kotlin counterpart of tools/ondevice-il2cpp/fetch-unity.sh: same
// URLs, same digests, same extraction. The shell script is what runs when a PC
// or a terminal is doing the work; this is what runs when the app is.
//
// Everything is streamed and nothing is stored whole. The editor archive alone
// is 4.29 GB, of which about 155 MB is wanted, and a phone should not have to
// find room for the rest -- see the range request below for how most of it is
// never even transferred.

package dev.silksong.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

object UnityFetcher {

    const val UNITY_VERSION = "6000.0.50f1"

    /**
     * Roughly how much of the editor archive gets read before everything
     * wanted has gone past. Used only to show a progress bar, so being a
     * little wrong costs nothing: the transfer stops when the archive says to,
     * not when this number is reached.
     */
    private const val EDITOR_APPROX_BYTES = 640L * 1024 * 1024

    /** Everything lands under here, and is skipped if already present. */
    fun rootFor(context: android.content.Context): File =
        File(context.getExternalFilesDir(null), "unity")

    data class Progress(
        val step: String,
        /** 0f..1f within the current step, or -1f when the size is unknown. */
        val fraction: Float,
        val detail: String = "",
    )

    /** True once both pieces are on disk. */
    fun isPresent(root: File, target: PortTarget = PortTarget.SILKSONG): Boolean =
        versionMatches(root, target) && inputSystemReady(root) &&
            if (UnityDistribution.isPinned(target.unityVersion))
                editorReady(File(root, "editor"), target) && engineLibsIn(File(root, "android")) != null
            else UnityRuntimeInstaller.ready(root, target.unityVersion) && runCatching {
                UnityRuntimeInstaller.requireDevice(root, android.os.Build.VERSION.SDK_INT,
                    android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)); true
            }.getOrDefault(false)

    /**
     * Where the Android module's arm64 libraries ended up.
     *
     * The .pkg's payload is rooted wherever Unity's installer wants to place
     * it, which is not something to guess at: the directory is found by the
     * one file that has to be in it. Cheap, and it cannot be wrong.
     */
    private fun engineLibsIn(android: File): File? {
        if (!android.isDirectory) return null
        val suffix = File("Variations/il2cpp/Release/Libs/arm64-v8a")
        android.walkTopDown().maxDepth(8).forEach { d ->
            if (d.isDirectory && d.name == "arm64-v8a" &&
                d.path.replace('\\', '/').endsWith(suffix.path.replace('\\', '/')) &&
                CompletedArtifact.hasFiles(d, listOf("libunity.so", "libmain.so"))
            ) return d
        }
        return null
    }

    /**
     * Fetches whatever is missing. Safe to re-run: each piece is skipped when
     * already extracted, so an interrupted first run is resumed by running it
     * again rather than starting over.
     */
    private fun versionMatches(root: File, target: PortTarget): Boolean {
        val marker = File(root, "unity-version.txt")
        return if (marker.isFile) marker.readText().trim() == target.unityVersion
        else target == PortTarget.SILKSONG // adopt the original, single-version download
    }

    fun fetch(root: File, downloads: Semaphore = Semaphore(1), target: PortTarget = PortTarget.SILKSONG): Flow<Progress> = channelFlow {
        if (target == PortTarget.HOLLOW_KNIGHT) require(root.path.replace('\\', '/').endsWith("/games/hollow-knight/unity")) {
            "Hollow Knight needs its own Unity download directory"
        }
        if (!UnityDistribution.isPinned(target.unityVersion)) {
            require(target.generic) { "Dedicated game adapters require their pinned Unity components." }
            downloads.withPermit {
                UnityRuntimeInstaller.install(root, target.unityVersion, android.os.Build.VERSION.SDK_INT,
                    android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)) { step, done, total ->
                    trySend(Progress(step, done.toFloat() / total, mb(done, total)))
                }
            }
            if (!inputSystemReady(root)) downloads.withPermit {
                fetchInputSystem(root) { done, total -> trySend(Progress("Unity Input System", done.toFloat() / total, mb(done, total))) }
            }
            coroutineContext.ensureActive()
            stageEngine(root, File(root.parentFile, "staging"))
            File(root, "unity-version.txt").writeText(target.unityVersion)
            send(Progress("Unity ${target.unityVersion}", 1f, "exact runtime installed; game validation still required"))
            return@channelFlow
        }
        val distribution = UnityDistribution.forTarget(target)
        val versionOk = versionMatches(root, target)
        root.mkdirs()

        // Progress arrives on whatever thread is reading the socket, which is
        // not the one collecting this flow. trySend is the thread-safe way to
        // publish from there; it drops an update rather than blocking a
        // download, and the next one is along in a few megabytes.
        val report: (String, Long, Long) -> Unit = { step, done, total ->
            trySend(Progress(step, done.toFloat() / total, mb(done, total)))
        }

        // These archives write to separate directories. Share the caller's
        // permits with the compiler fetch, rather than opening an unbounded
        // set of downloads/decompressors on a phone. The scope waits for all
        // pieces before stageEngine and cancels siblings if one fails.
        coroutineScope {
            launch {
                val editor = File(root, "editor")
                if (!versionOk || !editorReady(editor, target)) {
                    downloads.withPermit {
                        send(Progress("Unity IL2CPP toolchain", 0f, "connecting"))
                        fetchEditor(editor, target, distribution) { done, total -> report("Unity IL2CPP toolchain", done, total) }
                    }
                }
            }
            launch {
                if (!inputSystemReady(root)) {
                    downloads.withPermit {
                        send(Progress("Unity Input System", 0f, "connecting"))
                        fetchInputSystem(root) { done, total -> report("Unity Input System", done, total) }
                    }
                }
            }
            launch {
                val android = File(root, "android")
                if (!versionOk || engineLibsIn(android) == null) {
                    downloads.withPermit {
                        send(Progress("Unity Android engine", 0f, "connecting"))
                        fetchAndroidModule(android, target, distribution) { done, total -> report("Unity Android engine", done, total) }
                    }
                }
            }
        }

        // The engine has to end up somewhere the game can load it from, and
        // that is not here: Android will not map code out of external storage.
        // Putting it where GameActivity looks for arriving libraries means
        // this and a hand-staged copy take exactly the same route in.
        send(Progress("Unity", -1f, "staging the engine"))
        stageEngine(root, File(root.parentFile, "staging"))

        File(root, "unity-version.txt").writeText(target.unityVersion)
        send(Progress("Unity", 1f, "ready"))
    }.flowOn(Dispatchers.IO)

    /**
     * Puts Unity's two engine libraries back in the queue if they are missing.
     *
     * Called on every setup run, not only when the module is downloaded, and
     * that distinction is the whole point. fetch() is skipped once isPresent()
     * is true -- the module is 640 MB and there is no reason to fetch it twice
     * -- but staging used to happen only INSIDE fetch. So anything that
     * removed the installed copies (libmain.so, libunity.so under pkg/lib)
     * without also removing the downloaded module left them gone for good, and
     * the game died at startup with
     *
     *     dlopen failed: library "libmain.so" not found
     *     Your hardware does not support this application.
     *
     * which names neither the file that is missing nor the reason. The reset in
     * BuildReset does exactly that removal, by design: it keeps the expensive
     * download and throws away everything built from it.
     *
     * Cheap to call: it copies only what is absent or the wrong size, and the
     * install step downstream skips files that already match.
     */
    fun ensureEngineStaged(root: File, staging: File) {
        stageEngine(root, staging)
    }

    /**
     * libil2cpp.so is not here: it is the game's own code and is compiled on
     * the device from the depot's assemblies. Only Unity's own two go with it.
     */
    private fun stageEngine(root: File, staging: File) {
        val libs = engineLibsIn(File(root, "android")) ?: return
        staging.mkdirs()
        for (name in listOf("libunity.so", "libmain.so")) {
            val src = File(libs, name)
            val dst = File(staging, name)
            if (!src.isFile || dst.length() == src.length()) continue
            src.copyTo(dst, overwrite = true)
            LauncherLog.log("staged $name for install (${src.length()} bytes)")
        }
    }

    private fun mb(done: Long, total: Long) =
        "${done / 1024 / 1024} of ${total / 1024 / 1024} MB"

    // ── the editor archive ─────────────────────────────────────────────────

    private suspend fun fetchEditor(dest: File, target: PortTarget, distribution: UnityDistribution, onProgress: (Long, Long) -> Unit) {
        val tmp = File(dest.parentFile, "editor.part")
        tmp.deleteRecursively()

        open("${distribution.downloadRoot}/LinuxEditorInstaller/Unity-${target.unityVersion}.tar.xz").use { raw ->
            val counted = CountingInputStream(raw, EDITOR_APPROX_BYTES, onProgress)
            // Stopping early means closing the stream part-way through, so the
            // decoders are cut off mid-archive and say so. That is the
            // intended end of this operation, not a failure.
            runCatching {
                TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(counted, 1 shl 16)))
                    .use { tar -> extractTar(tar, tmp, EDITOR_WANTED) }
            }.exceptionOrNull()?.let { if (it is Enough) Unit else throw it }
        }

        for ((path, want) in distribution.editorRequired) {
            val f = File(tmp, path)
            if (!f.isFile) {
                tmp.deleteRecursively()
                throw java.io.IOException("$path is missing from the editor archive")
            }
            val got = sha256(f)
            if (got != want) {
                tmp.deleteRecursively()
                throw java.io.IOException("$path: expected sha256 $want but got $got")
            }
        }

        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw java.io.IOException("could not move $tmp to $dest")
    }

    /** Raised once every wanted tree has been passed. Not an error. */
    private class Enough : java.io.IOException("done reading")

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Nothing here says how big the download is, or where in the archive the
     * wanted files sit. tar is sequential and Unity packs it by walking a
     * directory tree, so each directory's entries are contiguous: once a tree
     * has been entered and then left, it is complete. When every wanted tree
     * has been left, there is nothing further to read and the transfer stops.
     *
     * The alternative -- a byte offset measured once and pinned -- is the kind
     * of constant that keeps working until Unity repacks the archive and then
     * fails in a way that looks like corruption. This asks the archive instead
     * of assuming.
     *
     * In practice it stops after roughly 600 MB of 4.29 GB.
     */
    private val EDITOR_WANTED = listOf(
        "Editor/Data/il2cpp/",
        "Editor/Data/MonoBleedingEdge/lib/mono/unityaot-linux/",
    )

    /**
     * What must exist afterwards, and what it must be.
     *
     * Pinning the files that get used is both stronger and more durable than
     * pinning the bytes they arrived in: it survives Unity repacking the
     * archive, and it checks the thing that actually runs. Unity publishes a
     * digest only for the archive as a whole, which is of no use when most of
     * it is never fetched.
     */
    private fun editorReady(editor: File, target: PortTarget): Boolean =
        CompletedArtifact.hasFiles(editor, UnityDistribution.forTarget(target).editorRequired.keys.toList())

    // ── Unity packages ─────────────────────────────────────────────────────

    /**
     * com.unity.inputsystem, from Unity's package registry.
     *
     * Packages ship as SOURCE and are compiled per target by whoever builds
     * the player, which is why there is nothing to download prebuilt: the
     * tarball carries no assemblies at all, and neither does the Editor
     * install. The depot's copy is the desktop build, with AndroidGamepad,
     * XboxOneGamepadAndroid and AndroidSupport compiled out -- so the game
     * cannot see a gamepad however well Android describes one.
     *
     * The licence is the Unity Companion License, which states that use is
     * acceptance, so there is nothing to click.
     *
     * The version is the one the DEPOT was built against, not the one an
     * Editor install happens to resolve. Those differ, and the difference is
     * easy to miss: this game ships 1.14.2 while the Editor here resolves
     * 1.11.2. Substituting the Editor's version means the game's own
     * assemblies were compiled against an API surface that is not the one
     * they get at runtime. That worked, because nothing the game calls
     * changed in between -- but it worked by luck, and luck is not a property
     * to build on.
     *
     * To re-derive it for another depot or another game, read the
     * AssemblyVersion of the depot's own copy:
     *
     *     [System.Reflection.AssemblyName]::GetAssemblyName(
     *         "<depot>/<game>_Data/Managed/Unity.InputSystem.dll").Version
     *
     * and take the first three parts. Then replace the digest below with the
     * SHA-256 of the matching tarball.
     */
    private const val INPUTSYSTEM_VERSION = "1.14.2"
    private const val INPUTSYSTEM_SHA256 =
        "875008478396009708fdcd333d8b3108097a575652e36e9f3ecde66e0af21f26"
    private const val INPUTSYSTEM_BYTES = 16_856_398L

    fun packageDir(root: File): File = File(root, "packages/com.unity.inputsystem")

    private val INPUTSYSTEM_REQUIRED = listOf("InputSystem/Unity.InputSystem.asmdef", "package.json")

    private fun inputSystemReady(root: File): Boolean =
        CompletedArtifact.isReady(packageDir(root), INPUTSYSTEM_SHA256, INPUTSYSTEM_REQUIRED)

    private suspend fun fetchInputSystem(root: File, onProgress: (Long, Long) -> Unit) {
        val dest = packageDir(root)
        val url = "https://packages.unity.com/com.unity.inputsystem/-/" +
            "com.unity.inputsystem-$INPUTSYSTEM_VERSION.tgz"
        val tmp = File(root, "inputsystem.tgz")
        open(url).use { raw ->
            val counted = CountingInputStream(raw, INPUTSYSTEM_BYTES, onProgress)
            tmp.outputStream().use { out -> counted.copyTo(out, 1 shl 16) }
        }
        val got = sha256(tmp)
        if (got != INPUTSYSTEM_SHA256) {
            tmp.delete()
            throw java.io.IOException("com.unity.inputsystem: expected sha256 $INPUTSYSTEM_SHA256 but got $got")
        }
        val extracted = File(dest.parentFile, "com.unity.inputsystem.part")
        if (extracted.exists() && !extracted.deleteRecursively()) throw java.io.IOException("could not clear $extracted")
        extracted.mkdirs()
        tmp.inputStream().use { raw ->
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(raw, 1 shl 16))).use { tar ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = tar.nextEntry ?: break
                    // npm-style tarballs wrap everything in package/.
                    val name = entry.name.removePrefix("./").removePrefix("package/")
                    if (entry.isDirectory || name.isEmpty()) continue
                    write(File(extracted, name), tar)
                }
            }
        }
        coroutineContext.ensureActive()
        CompletedArtifact.publish(extracted, dest, INPUTSYSTEM_SHA256, INPUTSYSTEM_REQUIRED)
        tmp.delete()
    }

    // ── the Android module ─────────────────────────────────────────────────
    //
    // Published for this version only as a macOS .pkg, which is a xar archive
    // wrapping a gzipped cpio payload. Neither layer needs a native tool: the
    // xar header is fixed-size and its table of contents is deflate-compressed
    // XML, and commons-compress reads the rest.

    private suspend fun fetchAndroidModule(dest: File, target: PortTarget, distribution: UnityDistribution, onProgress: (Long, Long) -> Unit) {
        val pkg = File(dest.parentFile, "android-support.pkg")
        if (pkg.length() != distribution.androidBytes || sha256(pkg) != distribution.androidSha256) {
            pkg.parentFile?.mkdirs()
            val part = File(dest.parentFile, "android-support.part")
            val digest = MessageDigest.getInstance("SHA-256")
            open("${distribution.downloadRoot}/MacEditorTargetInstaller/UnitySetup-Android-Support-for-Editor-${target.unityVersion}.pkg")
                .use { raw ->
                    val counted = CountingInputStream(raw, distribution.androidBytes, onProgress)
                    DigestInputStream(counted, digest).use { input ->
                        part.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
                    }
                }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (got != distribution.androidSha256) {
                part.delete()
                throw java.io.IOException(
                    "Android module: expected sha256 ${distribution.androidSha256} but got $got")
            }
            if (!part.renameTo(pkg)) throw java.io.IOException("could not move $part to $pkg")
        }

        val tmp = File(dest.parentFile, "android.part")
        tmp.deleteRecursively()
        payloadOf(pkg).use { payload ->
            CpioArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(payload, 1 shl 16)))
                .use { cpio -> extractCpio(cpio, tmp) }
        }
        if (engineLibsIn(tmp) == null) {
            tmp.deleteRecursively()
            throw java.io.IOException("the Android module unpacked without an arm64 engine in it")
        }
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw java.io.IOException("could not move $tmp to $dest")
        // 642 MB, and nothing needs it once it is unpacked. Kept until the
        // unpack has been checked, so a bug here does not cost the download.
        pkg.delete()
    }

    /**
     * Opens the .pkg's Payload as a stream.
     *
     * xar's header is 28 bytes: magic, header size, version, then the
     * compressed and uncompressed lengths of the table of contents. The TOC is
     * deflate-compressed XML listing every file with its offset and length
     * within the heap that follows. Only one entry is wanted.
     */
    private fun payloadOf(pkg: File): InputStream = UnityAndroidPackage.payloadOf(pkg)

    // ── extraction ─────────────────────────────────────────────────────────

    private suspend fun extractTar(tar: TarArchiveInputStream, dest: File, wanted: List<String>) {
        // Per wanted tree: has it been entered, and has it been left again.
        // Entered-then-left means complete, because tar written from a
        // directory walk keeps each directory's entries together.
        val entered = BooleanArray(wanted.size)
        val left = BooleanArray(wanted.size)

        while (true) {
            coroutineContext.ensureActive()
            val entry = tar.nextEntry ?: break
            val name = entry.name.removePrefix("./")

            var inside = -1
            for (i in wanted.indices) {
                if (name.startsWith(wanted[i])) { inside = i; break }
            }
            for (i in wanted.indices) {
                if (entered[i] && i != inside) left[i] = true
            }
            if (inside >= 0) {
                entered[inside] = true
                if (!entry.isDirectory) write(File(dest, name), tar)
            }

            // Everything wanted has been seen and passed; the rest of the
            // archive is of no interest, so stop rather than stream gigabytes
            // to nowhere.
            if (wanted.indices.all { entered[it] && left[it] }) throw Enough()
        }
    }

    private suspend fun extractCpio(cpio: CpioArchiveInputStream, dest: File) {
        while (true) {
            coroutineContext.ensureActive()
            val entry = cpio.nextEntry ?: break
            if (!entry.isRegularFile) continue
            write(File(dest, entry.name.removePrefix("./")), cpio)
        }
    }

    private fun write(out: File, from: InputStream) {
        out.parentFile?.mkdirs()
        out.outputStream().use { o -> from.copyTo(o, 1 shl 16) }
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private fun open(url: String, rangeEnd: Long? = null): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        if (rangeEnd != null) c.setRequestProperty("Range", "bytes=0-$rangeEnd")
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw java.io.IOException("HTTP $code for $url")
        }
        return c.inputStream
    }

    /** Reports progress as bytes go past, without buffering any of them. */
    private class CountingInputStream(
        input: InputStream,
        private val total: Long,
        private val onProgress: (Long, Long) -> Unit,
    ) : FilterInputStream(input) {
        private var seen = 0L
        private var lastReport = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) advance(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) advance(n.toLong())
            return n
        }

        private fun advance(n: Long) {
            seen += n
            // A repaint per 4 MB rather than per read: the UI cannot show more
            // than that and the callback is not free.
            if (seen - lastReport < 4L * 1024 * 1024) return
            lastReport = seen
            onProgress(seen, total)
        }
    }

    private object NullOutputStream : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
