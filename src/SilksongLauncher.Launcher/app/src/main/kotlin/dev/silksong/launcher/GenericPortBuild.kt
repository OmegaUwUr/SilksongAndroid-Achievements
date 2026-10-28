package dev.silksong.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import kotlin.coroutines.coroutineContext

/** Shared experimental classic-Mono pipeline; dedicated adapters retain their own source guards. */
object GenericPortBuild {
    private const val GB = 1024L * 1024 * 1024
    private fun signature(context: GenericPortContext): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION") val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "generic-classic-1|${context.appId}|$code|${info.lastUpdateTime}"
    }
    @JvmStatic fun isReady(context: Context): Boolean = runCatching {
        val port = context as? GenericPortContext ?: context.applicationContext as? GenericPortContext ?: GenericPortContext.active(context)
        val pkg = File(port.filesDir, "pkg")
        File(pkg, ".built").readText() == signature(port) &&
            listOf("data.apk", ClassicPlayerImage.mainObbName(port), "lib/arm64/libunity.so", "lib/arm64/libmain.so",
                "lib/arm64/libil2cpp.so").all { File(pkg, it).length() > 0 } && File(UnityDex.outputDir(port), "classes.jar").length() > 0
    }.getOrDefault(false)

    suspend fun prepare(context: GenericPortContext, source: File,
        progress: suspend (PlayerImage.Progress) -> Unit): GenericPortAssessment = withContext(Dispatchers.IO) {
        withLock(context) { assess(context, source, progress) }
    }

    private suspend fun <T> withLock(context: GenericPortContext, action: suspend () -> T): T =
        RandomAccessFile(File(context.filesDir, "port.lock"), "rw").use { handle ->
            val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: error("Close this game before preparing or rebuilding its port.")
            lock.use { action() }
        }

    private suspend fun assess(context: GenericPortContext, source: File,
        progress: suspend (PlayerImage.Progress) -> Unit): GenericPortAssessment {
        progress(PlayerImage.Progress("Inspecting the downloaded game", -1f))
        val initial = GenericPortAssessment.source(source)
        if (initial.data == null || initial.version == null ||
            !GameCompatibility.hasManagedHeader(File(initial.data, "Managed/Assembly-CSharp.dll"))) return initial
        require("arm64-v8a" in Build.SUPPORTED_ABIS && context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)) {
            "The common builder needs an ARM64 Android device with Vulkan."
        }
        val root = Il2cppConverter.rootFor(context).apply { mkdirs() }
        val unity = UnityFetcher.rootFor(context)
        val external = context.getExternalFilesDir(null) ?: error("Game storage is unavailable.")
        check(external.usableSpace > 3 * GB && context.filesDir.usableSpace > GB) { "Free at least 3 GB external and 1 GB internal storage for component preparation; compilation needs more." }
        MonoRuntime.stage(context).collect { progress(PlayerImage.Progress(it.step, it.fraction, it.detail)) }
        val surgery = PlayerImage.stageSurgery(root, context.assets)
        // Check native declarations before fetching hundreds of megabytes of engine components.
        val managedPath = File(root, "managed-port-audit.json")
        PlayerImage.run(surgery, context, listOf("inspect-managed-port", initial.data!!.absolutePath, "-", managedPath.absolutePath))
        fun json(file: File): JSONObject { require(file.length() in 1L..(32 * 1024 * 1024L)); return JSONObject(file.readText()) }
        val nativeCheck = GenericPortAssessment.finish(initial, context, json(managedPath), null)
        if (nativeCheck.blockers.isNotEmpty()) return nativeCheck
        val target = PortTarget.classic(context.appId, initial.version)
        progress(PlayerImage.Progress("Inspecting scenes and Vulkan shaders", -1f))
        val assetPath = File(root, "classic-inventory.json")
        PlayerImage.run(surgery, context, listOf("manifest-classic-tree", initial.data.absolutePath, assetPath.absolutePath))
        val preliminary = GenericPortAssessment.finish(initial, context, json(managedPath), json(assetPath))
        if (preliminary.blockers.isNotEmpty()) return preliminary
        if (!UnityFetcher.isPresent(unity, target)) UnityFetcher.fetch(unity, target = target).collect {
            progress(PlayerImage.Progress(it.step, it.fraction, it.detail))
        }
        // Resolve references again with the actual Android BCL/engine, not the desktop copies.
        PlayerImage.run(surgery, context, listOf("inspect-managed-port", initial.data.absolutePath, unity.absolutePath, managedPath.absolutePath))
        return GenericPortAssessment.finish(initial, context, json(managedPath), json(assetPath))
    }

    fun build(context: GenericPortContext, source: File): Flow<PlayerImage.Progress> = channelFlow {
        withLock(context) {
            val pkg = File(context.filesDir, "pkg")
            val previous = File(context.filesDir, "pkg.previous")
            val pending = File(context.filesDir, "pkg.next")
            if (!pkg.exists() && previous.exists()) check(previous.renameTo(pkg)) { "Could not restore the previous build." }
            if (pending.exists()) check(pending.deleteRecursively())
            if (previous.exists()) check(previous.deleteRecursively())
            val assessment = assess(context, source) { send(it) }
            check(assessment.canBuild) { assessment.asText() }
            val data = assessment.data!!
            val target = PortTarget.classic(context.appId, assessment.version!!)
            target.requireData(data)
            val bytes = data.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val external = context.getExternalFilesDir(null) ?: error("Game storage unavailable.")
            // Converted assets and archives coexist with the original. Include a compiler/engine margin.
            val shared = android.os.Environment.isExternalStorageEmulated(external)
            val externalNeed = 2 * bytes + (if (shared) 10 else 5) * GB
            val internalNeed = if (shared) externalNeed else 5 * GB
            check(external.usableSpace >= externalNeed && context.filesDir.usableSpace >= internalNeed) {
                "Insufficient build space. Allow about ${externalNeed / GB + 1} GB external and ${internalNeed / GB + 1} GB internal free; original files are preserved."
            }
            val unity = UnityFetcher.rootFor(context)
            val tools = ToolchainFetcher.rootFor(context)
            val root = Il2cppConverter.rootFor(context)
            UnityDex.build(context, unity)
            if (!PackageCompiler.isCurrent(unity, data, root)) PackageCompiler.compile(unity, data, context, root).collect {
                send(PlayerImage.Progress(it.step, it.fraction, it.detail))
            }
            PackageCompiler.compileIo(unity, data, context, root, context.assets).collect { send(PlayerImage.Progress(it.step, it.fraction, it.detail)) }
            PackageCompiler.compilePortServices(unity, data, context, root, context.assets).collect { send(PlayerImage.Progress(it.step, it.fraction, it.detail)) }
            PackageCompiler.compilePatches(unity, data, context, root, context.assets, target).collect { send(PlayerImage.Progress(it.step, it.fraction, it.detail)) }
            // Check the exact rebuilt Input System and Android API surface before a long native compile.
            val surgery = PlayerImage.stageSurgery(root, context.assets)
            val managedPath = File(root, "managed-build-audit.json")
            PlayerImage.run(surgery, context, listOf("inspect-managed-port", data.absolutePath, unity.absolutePath,
                managedPath.absolutePath, PackageCompiler.outputDir(root).absolutePath))
            val managed = JSONObject(managedPath.readText())
            val checked = GenericPortAssessment.finish(assessment, context, managed, null)
            check(checked.blockers.isEmpty()) { checked.asText() }
            if (!Il2cppConverter.isCurrent(unity, data, root)) Il2cppConverter.convert(unity, data, context, root, target).collect {
                send(PlayerImage.Progress(it.step, it.fraction, it.detail))
            }
            ClassicPlayerImage.build(unity, data, context, root, target).collect { send(it) }
            check(JSONObject(File(root, "classic-tree-report.json").readText()).getString("sourceTreeSha256") == assessment.sourceTreeSha256) {
                "Original game files changed during preparation. No new build was published. Prepare the source again."
            }
            send(PlayerImage.Progress("Packing the experimental port", -1f))
            ClassicPlayerImage.install(context, root, pending)
            // Resolve asset/archive limits before spending minutes on native compilation.
            if (!ToolchainFetcher.isPresent(tools)) ToolchainFetcher.fetch(tools).collect { send(PlayerImage.Progress(it.step, it.fraction, it.detail)) }
            check(Toolchain.canExecute(tools)) { "Android cannot run the downloaded compiler on this device." }
            val engine = File(pending, "lib/arm64").apply { mkdirs() }
            UnityFetcher.ensureEngineStaged(unity, engine)
            NativeBuild.build(unity, tools, root, context.assets, install = engine).collect { send(PlayerImage.Progress(it.step, it.fraction, it.detail)) }
            AndroidPluginPack.verified(context, target.unityVersion, data).values.forEach { it.first.copyTo(File(engine, it.first.name), overwrite = true) }
            coroutineContext.ensureActive()
            File(pending, ".built").writeText(signature(context))
            if (pkg.exists()) check(pkg.renameTo(previous)) { "Could not preserve the previous working build." }
            if (!pending.renameTo(pkg)) { previous.renameTo(pkg); error("Could not publish the new build. The previous package was kept.") }
            previous.deleteRecursively()
            send(PlayerImage.Progress("Experimental port compiled", 1f, "Ready for device testing"))
        }
    }.flowOn(Dispatchers.IO)
}
