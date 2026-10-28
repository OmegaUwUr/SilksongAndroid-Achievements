package dev.silksong.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import kotlin.coroutines.coroutineContext

/** Experimental adapter for verified local or Steam-downloaded files; game Steam sync is still separate work. */
object HollowKnightBuild {
    private val target = PortTarget.HOLLOW_KNIGHT
    private const val GB = 1024L * 1024L * 1024L

    private fun signature(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return "hk-adapter-1|${HollowKnightSource.VERSION}|${target.unityVersion}|$version|${info.lastUpdateTime}"
    }

    @JvmStatic fun isReady(context: Context): Boolean = runCatching {
        val pkg = File(context.filesDir, "pkg")
        File(pkg, ".built").readText() == signature(context) &&
            listOf("data.apk", ClassicPlayerImage.mainObbName(context), "lib/arm64/libunity.so",
                "lib/arm64/libmain.so", "lib/arm64/libil2cpp.so").all { File(pkg, it).length() > 0 } &&
            File(UnityDex.outputDir(context), "classes.jar").length() > 0
    }.getOrDefault(false)

    fun build(context: Context, source: File): Flow<PlayerImage.Progress> = channelFlow {
        check(context.filesDir.path.replace('\\', '/').endsWith("/games/hollow-knight"))
        val external = context.getExternalFilesDir(null) ?: throw IOException("External app storage is unavailable.")
        val root = Il2cppConverter.rootFor(context).apply { mkdirs() }
        val pkg = File(context.filesDir, "pkg")
        val pending = File(context.filesDir, "pkg.next")
        val previous = File(context.filesDir, "pkg.previous")
        RandomAccessFile(File(context.filesDir, "port.lock"), "rw").use { handle ->
            val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw IOException("Close Hollow Knight before starting or rebuilding its port.")
            lock.use {
                // A kill between the two directory renames restores the prior complete package.
                if (!pkg.exists() && previous.exists() && !previous.renameTo(pkg))
                    throw IOException("Could not restore the previous Hollow Knight build.")
                if (pending.exists() && !pending.deleteRecursively()) throw IOException("Could not clear the interrupted build.")
                if (previous.exists() && !previous.deleteRecursively()) throw IOException("Could not clear the old build backup.")
                if ("arm64-v8a" !in Build.SUPPORTED_ABIS ||
                    !context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL))
                    throw IOException("Hollow Knight needs an ARM64 Android device with Vulkan graphics.")
                val data = HollowKnightSource.find(source)
                send(PlayerImage.Progress("Checking Hollow Knight ${HollowKnightSource.VERSION}", -1f))
                HollowKnightSource.verify(data, context.assets) { done, total ->
                    trySend(PlayerImage.Progress("Checking original game files", done.toFloat() / total, "$done of $total"))
                }
                // Includes a separate converted tree and temporary downloads, without touching the source.
                val sharedStorage = android.os.Environment.isExternalStorageEmulated(external)
                if (external.usableSpace < (if (sharedStorage) 17 else 10) * GB ||
                    context.filesDir.usableSpace < (if (sharedStorage) 17 else 7) * GB)
                    throw IOException("Hollow Knight needs about 17 GB free while porting (10 GB external app storage and 7 GB internal). Free space and retry.")
                val unity = UnityFetcher.rootFor(context)
                val tools = ToolchainFetcher.rootFor(context)
                if (!UnityFetcher.isPresent(unity, target)) UnityFetcher.fetch(unity, target = target).collect {
                    send(PlayerImage.Progress(it.step, it.fraction, it.detail))
                }
                if (!ToolchainFetcher.isPresent(tools)) ToolchainFetcher.fetch(tools).collect {
                    send(PlayerImage.Progress(it.step, it.fraction, it.detail))
                }
                if (!Toolchain.canExecute(tools)) throw IOException("Android cannot run the downloaded compiler on this device.")
                MonoRuntime.stage(context).collect { send(PlayerImage.Progress(it.step, it.fraction, it.detail)) }
                UnityDex.build(context, unity)

                if (!PackageCompiler.isCurrent(unity, data, root)) PackageCompiler.compile(unity, data, context, root).collect {
                    send(PlayerImage.Progress(it.step, it.fraction, it.detail))
                }
                PackageCompiler.compilePatches(unity, data, context, root, context.assets, target).collect {
                    send(PlayerImage.Progress(it.step, it.fraction, it.detail))
                }
                if (!Il2cppConverter.isCurrent(unity, data, root)) Il2cppConverter.convert(unity, data, context, root, target).collect {
                    send(PlayerImage.Progress(it.step, it.fraction, it.detail))
                }
                pending.mkdirs()
                val engine = File(pending, "lib/arm64").apply { mkdirs() }
                UnityFetcher.ensureEngineStaged(unity, engine)
                NativeBuild.build(unity, tools, root, context.assets, install = engine).collect {
                    send(PlayerImage.Progress(it.step, it.fraction, it.detail))
                }
                ClassicPlayerImage.build(unity, data, context, root).collect { send(it) }
                send(PlayerImage.Progress("Packing Hollow Knight", -1f, "Creating APK and expansion archives"))
                ClassicPlayerImage.install(context, root, pending)
                coroutineContext.ensureActive()
                File(pending, ".built").writeText(signature(context))
                if (pkg.exists() && !pkg.renameTo(previous)) throw IOException("Could not preserve the previous build.")
                if (!pending.renameTo(pkg)) {
                    previous.renameTo(pkg)
                    throw IOException("Could not publish Hollow Knight. The previous build was kept.")
                }
                previous.deleteRecursively()
                send(PlayerImage.Progress("Hollow Knight port completed", 1f, "Ready for device testing"))
            }
        }
    }.flowOn(Dispatchers.IO)
}
