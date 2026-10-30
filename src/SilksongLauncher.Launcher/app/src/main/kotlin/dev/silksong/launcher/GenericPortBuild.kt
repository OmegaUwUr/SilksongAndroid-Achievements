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
    private fun identity(context: GenericPortContext) = PortInstallation.Identity("steam-${context.appId}")
    @JvmStatic fun isReady(context: Context): Boolean = runCatching {
        val port = context as? GenericPortContext ?: context.applicationContext as? GenericPortContext ?: GenericPortContext.active(context)
        PortInstallation.inspect(port.filesDir, port.packageName, identity(port)) != null
    }.getOrDefault(false)

    @JvmStatic fun prepareLaunch(context: GenericPortContext) = PortInstallation.prepareLaunch(context.filesDir, context.packageName, identity(context), ClassicPlayerImage.mainObbName(context))

    suspend fun prepare(context: GenericPortContext, source: File,
        progress: suspend (PlayerImage.Progress) -> Unit): GenericPortAssessment = withContext(Dispatchers.IO) {
        withLock(context) { assess(context, source, progress) }
    }

    private fun profileCache(context: GenericPortContext): File =
        File(context.sharedExternalFilesDir ?: error("Shared engine storage is unavailable."), "unity-managed-profiles")

    suspend fun prepareEngineProfile(context: GenericPortContext, source: File,
        progress: suspend (PlayerImage.Progress) -> Unit): GenericPortAssessment = withContext(Dispatchers.IO) {
        withLock(context) {
            val initial = GenericPortAssessment.source(source)
            val version = initial.version ?: error("Choose readable original game files first.")
            require(initial.data != null && GameCompatibility.hasMonoAssemblies(initial.data)) { "Choose complete original Mono files first." }
            require(UnityManagedProfile.supports(version)) { "This engine version has no separate managed-profile component provider." }
            val cache = profileCache(context)
            check((context.sharedExternalFilesDir?.usableSpace ?: 0) > 3 * GB) { "Free at least 3 GB before preparing the exact engine profile." }
            channelFlow<PlayerImage.Progress> {
                UnityProfileInstaller.install(cache, version) { step, done, total ->
                    trySend(PlayerImage.Progress(step, if (total > 0) done.toFloat() / total else -1f))
                }
            }.collect { progress(it) }
            assess(context, source, progress)
        }
    }

    suspend fun exportEvidence(context: GenericPortContext, source: File, output: java.io.OutputStream,
        progress: suspend (PlayerImage.Progress) -> Unit) = withContext(Dispatchers.IO) {
        withLock(context) {
            val report = assess(context, source, progress)
            val data = report.data ?: error("Choose complete original game files first.")
            val version = report.version ?: error("The source Unity version is unreadable.")
            progress(PlayerImage.Progress("Exporting game code and compatibility reports", -1f))
            PortEvidenceExport.write(context.appId, data, version, Il2cppConverter.rootFor(context), report.asText(), output)
        }
    }

    suspend fun addIncludedLibraries(context: GenericPortContext, source: File,
        progress: suspend (PlayerImage.Progress) -> Unit): GenericPortAssessment = withContext(Dispatchers.IO) {
        withLock(context) {
            val report = assess(context, source, progress)
            val data = report.data ?: error("Choose complete game files first.")
            val version = report.version ?: error("The source engine version is unreadable.")
            GameCompatibility.monoAssemblies(data)
            val audit = File(Il2cppConverter.rootFor(context), "managed-port-audit.json")
            require(audit.length() in 1L..(32 * 1024 * 1024L))
            val json = JSONObject(audit.readText()); require(json.getInt("schema") == 2)
            val imports = json.getJSONArray("nativeImports"); require(imports.length() <= 100000)
            val names = (0 until imports.length()).map { imports.getJSONObject(it).getString("library") }
            progress(PlayerImage.Progress("Adding included Android libraries", -1f))
            BundledNativeLibraries.installMissing(context, data, version, names)
            assess(context, source, progress)
        }
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
            !GameCompatibility.hasMonoAssemblies(initial.data)) return initial
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
        val profileEvidence = File(root, "engine-profile-evidence.json").also { it.delete() }
        val registrationPath = File(root, "legacy-android-registration.json").also { it.delete() }
        val sourceBindingsPath = File(root, "legacy-source-bindings.json").also { it.delete() }
        val scriptUsagePath = File(root, "legacy-script-usage.json").also { it.delete() }
        var registrationFailure: String? = null
        val profile = if (UnityManagedProfile.supports(initial.version)) UnityProfileInstaller.root(profileCache(context), initial.version)
            .takeIf { UnityProfileInstaller.ready(it, initial.version) } else null
        if (profile == null) {
            PlayerImage.run(surgery, context, listOf("inspect-managed-port", initial.data.absolutePath, "-", managedPath.absolutePath))
        } else {
            progress(PlayerImage.Progress("Auditing the exact Android managed profile", -1f))
            val binding = PortSourceFingerprint.bindings(initial.data)
            val receipt = UnityProfileInstaller.receiptHash(profile)
            PlayerImage.run(surgery, context, listOf("inspect-managed-profile", initial.data.absolutePath,
                UnityProfileInstaller.bcl(profile).absolutePath, UnityProfileInstaller.engine(profile).absolutePath, managedPath.absolutePath))
            progress(PlayerImage.Progress("Binding legacy scripts against Android assemblies", -1f))
            try {
                PlayerImage.run(surgery, context, listOf("inspect-legacy-android-registration", initial.data.absolutePath,
                    UnityProfileInstaller.bcl(profile).absolutePath, UnityProfileInstaller.engine(profile).absolutePath, registrationPath.absolutePath))
                check(registrationPath.length() in 1L..(32 * 1024 * 1024L)) { "Registration binding report is missing or exceeds its limit." }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                registrationPath.delete()
                registrationFailure = "Legacy Android registration planning could not complete. This source needs a registration adapter or corrected inputs; the engine remains blocked. ${error.message.orEmpty().take(400)}"
                LauncherLog.log("Legacy Android registration planning failed", error)
            }
            check(binding == PortSourceFingerprint.bindings(initial.data) && receipt == UnityProfileInstaller.receiptHash(profile) && UnityProfileInstaller.ready(profile, initial.version)) {
                "Source or exact engine profile changed during inspection. Prepare again."
            }
            profileEvidence.writeText(JSONObject().put("schema", 1).put("appId", context.appId).put("unityVersion", initial.version)
                .put("recipe", UnityManagedProfile.RECIPE).put("bindingsSha256", binding)
                .put("componentReceiptSha256", receipt).put("auditSha256", UnityReleaseCatalog.hash(managedPath.readBytes()))
                .put("registrationAuditStatus", if (registrationFailure == null) "completed" else "unavailable")
                .apply { if (registrationFailure == null) put("registrationAuditSha256", UnityReleaseCatalog.hash(registrationPath.readBytes())) }
                .put("scope", "Exact compiler/Android managed-profile audit only. Native build, Android player, registration, backend and gameplay compatibility are not approved.").toString())
        }
        val callSites = File(root, "native-call-sites.json").also { it.delete() }
        progress(PlayerImage.Progress("Inspecting native backend call sites", -1f))
        PlayerImage.run(surgery, context, listOf("inspect-native-call-sites", initial.data.absolutePath, callSites.absolutePath))
        fun json(file: File): JSONObject { require(file.length() in 1L..(32 * 1024 * 1024L)); return JSONObject(file.readText()) }
        val legacyPath = File(root, "legacy-runtime-registration.json").also { it.delete() }
        val reviewPath = File(root, "slime-source-review.json").also { it.delete() }
        suspend fun additional(command: String, output: File): JSONObject? = try {
            PlayerImage.run(surgery, context, listOf(command, initial.data.absolutePath, output.absolutePath))
            json(output)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { LauncherLog.log("Additional source review did not complete: $command", error); null }
        val legacy = if (initial.version.startsWith("2019.")) {
            progress(PlayerImage.Progress("Reading legacy runtime registration", -1f))
            additional("inspect-legacy-registration", legacyPath)
        } else null
        val review = if (context.appId == 433340 && initial.version == "2019.4.29f1")
            additional("review-slime-rancher-source", reviewPath) else null
        // Content inspection is read-only and independent of engine/native eligibility. Report
        // real shader/bundle failures even when another requirement currently blocks compilation.
        val assetPath = File(root, "classic-inventory.json").also { it.delete() }
        val content = if (initial.sourcePlatform in PortTarget.desktopPlatforms) {
            progress(PlayerImage.Progress("Inspecting scenes, local bundles and Vulkan shaders", -1f))
            PlayerImage.run(surgery, context, listOf("inspect-portable-content", initial.data.absolutePath,
                initial.sourcePlatform.toString(), assetPath.absolutePath))
            json(assetPath)
        } else null
        suspend fun supplemental(command: String, output: File): JSONObject? = try {
            PlayerImage.run(surgery, context, listOf(command, initial.data.absolutePath, registrationPath.absolutePath, output.absolutePath)); json(output)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { output.delete(); LauncherLog.log("Supplemental script inspection failed: $command", error); null }
        val binding = registrationPath.takeIf { profile != null && registrationFailure == null }?.let { json(it) }
        val bindingHash = binding?.let { UnityReleaseCatalog.hash(registrationPath.readBytes()) }
        val sourceBindings = if (binding != null) {
            progress(PlayerImage.Progress("Comparing script identities against the original DLLs", -1f))
            supplemental("inspect-legacy-source-bindings", sourceBindingsPath)
        } else null
        val usage = if (binding != null && content != null) {
            progress(PlayerImage.Progress("Inspecting MonoBehaviour script references in content", -1f))
            supplemental("inspect-legacy-script-usage", scriptUsagePath)
        } else null
        if (profileEvidence.isFile) {
            val evidence = json(profileEvidence)
            if (sourceBindings != null) evidence.put("sourceBindingAuditSha256", UnityReleaseCatalog.hash(sourceBindingsPath.readBytes()))
            if (usage != null) evidence.put("scriptUsageAuditSha256", UnityReleaseCatalog.hash(scriptUsagePath.readBytes()))
            profileEvidence.writeText(evidence.toString())
        }
        val unavailable = if (binding != null && (sourceBindings == null || content != null && usage == null))
            listOf("Supplemental legacy source/use inspection did not complete; review the launcher log. No script inventory exclusions are approved.") else emptyList()
        val withProfile = if (profile != null) initial.copy(blockers = initial.blockers + listOfNotNull(registrationFailure) + unavailable,
            facts = initial.facts.filterNot { it.startsWith("Exact Unity ${initial.version} compiler and Android managed-profile components can be downloaded separately") } +
            "Exact Unity ${initial.version} compiler and Android managed-profile components are installed and fully fingerprinted in the shared cache. Original game assemblies were audited against that Android profile; native/player integration remains blocked.") else initial
        val preliminary = GenericPortAssessment.finish(withProfile, context, json(managedPath), content, legacy, review,
            binding, sourceBindings, usage, bindingHash)
        preliminary.facts.filter { it.startsWith("Legacy script failures:") || it.startsWith("Legacy script example:") ||
            it.startsWith("Original-source binding check:") || it.startsWith("Serialized script-use census:") }
            .forEach { LauncherLog.log(it) }
        if (preliminary.blockers.isNotEmpty()) return preliminary
        val target = PortTarget.classic(context.appId, initial.version, initial.sourcePlatform!!)
        if (!UnityFetcher.isPresent(unity, target)) UnityFetcher.fetch(unity, target = target).collect {
            progress(PlayerImage.Progress(it.step, it.fraction, it.detail))
        }
        check(UnityFetcher.isPresent(unity, target)) { "The exact Unity runtime did not pass installation checks. Run preparation again." }
        // Resolve references again with the actual Android BCL/engine, not the desktop copies.
        PlayerImage.run(surgery, context, listOf("inspect-managed-port", initial.data.absolutePath, unity.absolutePath, managedPath.absolutePath))
        return GenericPortAssessment.finish(initial, context, json(managedPath), json(assetPath), legacy, review)
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
            val target = PortTarget.classic(context.appId, assessment.version!!, assessment.sourcePlatform!!)
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
            pending.mkdirs()
            UnityDex.build(context, unity, File(pending, "unity-dex"))
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
            if (!Il2cppConverter.isCurrent(unity, data, root, target)) Il2cppConverter.convert(unity, data, context, root, target).collect {
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
            BundledNativeLibraries.copyNotices(context, File(pending, "third-party/included-native-libraries"))
            coroutineContext.ensureActive()
            PortInstallation.complete(pending, context.filesDir, context.packageName, identity(context), target.unityVersion)
            if (pkg.exists()) check(pkg.renameTo(previous)) { "Could not preserve the previous working build." }
            if (!pending.renameTo(pkg)) { previous.renameTo(pkg); error("Could not publish the new build. The previous package was kept.") }
            previous.deleteRecursively()
            send(PlayerImage.Progress("Experimental port compiled", 1f, "Ready for device testing"))
        }
    }.flowOn(Dispatchers.IO)
}
