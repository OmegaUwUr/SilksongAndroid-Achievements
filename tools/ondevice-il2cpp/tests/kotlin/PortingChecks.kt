package dev.silksong.launcher
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

fun main() = runBlocking {
    val root = kotlin.io.path.createTempDirectory("porting-checks").toFile()
    var passed = 0
    fun pass(name: String) { passed++; println("PASS: $name") }
    try {
        val dest = File(root, "installed")
        val staged = File(root, "staged")
        val required = listOf("header", "library")
        Fixture.write(staged, "header")
        check(!CompletedArtifact.isReady(staged, "v1", required))
        check(runCatching { CompletedArtifact.publish(staged, dest, "v1", required) }.isFailure)
        check(!dest.exists())
        pass("partial extraction is never published")
        Fixture.write(staged, "library")
        CompletedArtifact.publish(staged, dest, "v1", required)
        check(CompletedArtifact.isReady(dest, "v1", required))
        check(!CompletedArtifact.isReady(dest, "v2", required))
        File(dest, "library").delete()
        check(!CompletedArtifact.isReady(dest, "v1", required))
        pass("completion checks version and required files")

        val pinned = File(root, "pinned-unity")
        for (path in UnityDistribution.forTarget(PortTarget.SILKSONG).editorRequired.keys)
            Fixture.write(pinned, "editor/$path")
        for (name in listOf("libunity.so", "libmain.so"))
            Fixture.write(pinned, "android/Variations/il2cpp/Release/Libs/arm64-v8a/$name")
        val inputPackage = UnityFetcher.packageDir(pinned)
        Fixture.write(inputPackage, "InputSystem/Unity.InputSystem.asmdef")
        Fixture.write(inputPackage, "package.json")
        Fixture.write(inputPackage, ".complete", "875008478396009708fdcd333d8b3108097a575652e36e9f3ecde66e0af21f26")
        check(UnityFetcher.isPresent(pinned, PortTarget.SILKSONG))
        check(!UnityFetcher.isPresent(pinned, PortTarget.HOLLOW_KNIGHT))
        Fixture.write(pinned, "unity-version.txt", "6000.0.61f1")
        check(!UnityFetcher.isPresent(pinned, PortTarget.SILKSONG))
        pass("pinned runtime readiness and exact dedicated stamps remain separate from automatic resolution")

        val ctx = android.content.Context()
        val unity = File(root, "unity")
        val depot = File(root, "depot")
        val build = File(root, "build")
        val game = Fixture.write(depot, "Managed/Assembly-CSharp.dll", "gameA")
        Fixture.write(depot, "app.info", "Team Cherry\nHollow Knight Silksong\n")
        Fixture.write(unity, "editor/Editor/Data/MonoBleedingEdge/lib/mono/unityaot-linux/mscorlib.dll")
        Fixture.write(unity, "editor/Editor/Data/il2cpp/build/deploy/il2cpp.dll")
        Fixture.write(unity, "android/Variations/il2cpp/Managed/UnityEngine.CoreModule.dll")
        Fixture.write(build, "packages/SilksongIo.dll")
        Fixture.write(build, "packages/SilksongPatches.dll")
        Fixture.write(build, "cpp/Game.cpp")
        Fixture.write(build, "data/Metadata/global-metadata.dat")
        check(!Il2cppConverter.isCurrent(unity, depot, build))
        pass("legacy or interrupted conversion cannot look complete")
        Il2cppConverter.convert(unity, depot, ctx, build).collect()
        check(Il2cppConverter.isCurrent(unity, depot, build))
        game.writeText("gameB")
        check(!Il2cppConverter.isCurrent(unity, depot, build))
        pass("successful conversion is reused; same-size game change invalidates")
        Il2cppConverter.convert(unity, depot, ctx, build).collect()
        File(build, "cpp/Game.cpp").delete()
        check(!Il2cppConverter.isCurrent(unity, depot, build))
        pass("missing generated output invalidates conversion")
        Fixture.fail = true
        check(runCatching { Il2cppConverter.convert(unity, depot, ctx, build).collect() }.isFailure)
        check(!Il2cppConverter.isCurrent(unity, depot, build))
        Fixture.fail = false
        Fixture.emptyCpp = true
        check(runCatching { Il2cppConverter.convert(unity, depot, ctx, build).collect() }.isFailure)
        check(!File(build, "conversion.complete").exists())
        Fixture.emptyCpp = false
        pass("failed and output-incomplete conversions do not commit")
        Il2cppConverter.convert(unity, depot, ctx, build).collect()
        Fixture.cancelRewrite = true
        val before = Fixture.executions
        check(runCatching { Il2cppConverter.convert(unity, depot, ctx, build).collect() }.exceptionOrNull() is CancellationException)
        check(Fixture.executions == before)
        check(!Il2cppConverter.isCurrent(unity, depot, build))
        Fixture.cancelRewrite = false
        pass("cancelling save rewrite stops conversion and invalidates old completion")

        val modular = File(root, "modular/Game_Data").apply { mkdirs() }
        val modularBuild = File(root, "modular-build")
        val target = PortTarget.classic(987, "6000.0.61f1")
        val header = ByteArray(256)
        val buffer = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.BIG_ENDIAN)
        buffer.putInt(8, 22); buffer.putInt(20, 120); buffer.putLong(24, 256); buffer.putLong(32, 192)
        val stamp = "6000.0.61f1\u0000".toByteArray(); stamp.copyInto(header, 48)
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(48 + stamp.size, 24)
        File(modular, "globalgamemanagers").writeBytes(header)
        for (name in listOf("Example.Gameplay.dll", "Example.Saves.dll", "UnityEngine.CoreModule.dll", "Unity.InputSystem.dll")) {
            val file = File(modular, "Managed/$name").apply { parentFile.mkdirs() }; file.writeBytes(Fixture.cli())
        }
        Fixture.write(modularBuild, "packages/Unity.InputSystem.dll", "Android input replacement")
        Fixture.write(modularBuild, "packages/${PackageCompiler.IO_ASSEMBLY}")
        Fixture.write(modularBuild, "packages/GenericPortPatches.dll")
        val originals = File(modular, "Managed").listFiles()!!.associate { it.name to it.readBytes().toList() }
        Fixture.rewrites.clear()
        Il2cppConverter.convert(unity, modular, ctx, modularBuild, target).collect()
        val batch = Fixture.rewrites.single()
        check(batch[0] == "redirect-port-file-replace")
        val selection = org.json.JSONArray(File(batch[3]).readText())
        check((0 until selection.length()).map { selection.getString(it) } == listOf("Example.Gameplay.dll", "Example.Saves.dll"))
        check(!File(Il2cppConverter.asmDir(modularBuild), "Assembly-CSharp.dll").exists())
        check(File(Il2cppConverter.asmDir(modularBuild), "Unity.InputSystem.dll").readText() == "Android input replacement")
        check(Il2cppConverter.isCurrent(unity, modular, modularBuild, target))
        check(!Il2cppConverter.isCurrent(unity, modular, modularBuild))
        check(File(modular, "Managed").listFiles()!!.associate { it.name to it.readBytes().toList() } == originals)
        pass("custom script assemblies reach the converter and one save batch; SDK overrides and original bytes stay separate")

        val pkg = UnityFetcher.packageDir(unity)
        Fixture.write(pkg, ".complete", "verified-package-v1")
        Fixture.write(pkg, "InputSystem/Unity.InputSystem.asmdef")
        Fixture.write(pkg, "InputSystem/Input.cs")
        val roslynFiles = listOf("csc.dll", "csc.deps.json", "csc.runtimeconfig.json", "Microsoft.CodeAnalysis.dll", "Microsoft.CodeAnalysis.CSharp.dll")
        val roslyn = File(build, "roslyn.part")
        roslynFiles.forEach { Fixture.write(roslyn, it) }
        CompletedArtifact.publish(roslyn, File(build, "roslyn"), "4.12.0", roslynFiles)
        Fixture.write(build, "packages/Unity.InputSystem.dll", "old incomplete DLL")
        check(!PackageCompiler.isCurrent(unity, depot, build))
        Fixture.fail = true
        check(runCatching { PackageCompiler.compile(unity, depot, ctx, build).collect() }.isFailure)
        check(!PackageCompiler.isCurrent(unity, depot, build))
        check(File(build, "packages/Unity.InputSystem.dll").readText() == "old incomplete DLL")
        Fixture.fail = false
        PackageCompiler.compile(unity, depot, ctx, build).collect()
        check(PackageCompiler.isCurrent(unity, depot, build))
        pass("failed Input System compile preserves old DLL; retry commits new DLL")
        File(build, "packages/Unity.InputSystem.dll").writeText("fixturE")
        check(!PackageCompiler.isCurrent(unity, depot, build))
        PackageCompiler.compile(unity, depot, ctx, build).collect()
        Fixture.write(unity, "android/Variations/il2cpp/Managed/UnityEngine.CoreModule.dll", "changed")
        check(!PackageCompiler.isCurrent(unity, depot, build))
        pass("changed Input System output or reference invalidates reuse")

        val gib = 1024L * 1024 * 1024
        fun budget(total: Long, free: Long, pressure: Boolean = false) = MonoRuntime.budgetForMemory(8, total, free, pressure)
        check(budget(8*gib, 4*gib) == MonoRuntime.Budget(8, 0))
        check(budget(8*gib, gib/2) == MonoRuntime.Budget(1, 384))
        check(budget(8*gib, 3*gib/2) == MonoRuntime.Budget(2, 640))
        check(budget(8*gib, 5*gib/2) == MonoRuntime.Budget(4, 1024))
        check(budget(8*gib, 4*gib, true) == MonoRuntime.Budget(1, 384))
        check(budget(4*gib, 4*gib) == MonoRuntime.Budget(2, 640))
        check(budget(0, 0) == MonoRuntime.Budget(4, 1024))
        check(MonoRuntime.budgetForMemory(0, 0, 0, false).cores == 1)
        pass("live RAM and memory pressure clamp conversion budget without exceeding device tier")
        var next: MonoRuntime.Budget? = budget(8*gib, 4*gib)
        var count = 0
        while (next != null) { check(++count < 10); next = next.tighter() }
        pass("memory retry reaches a bounded stopping point")

        val bounded = ToolchainFetcher.BoundedInputStream(byteArrayOf(1,2,3,4).inputStream(), 3)
        check(bounded.readBytes().contentEquals(byteArrayOf(1,2,3)))
        check(bounded.read() == -1)
        check(bounded.read(ByteArray(0), 0, 0) == 0)
        check(runCatching { ToolchainFetcher.BoundedInputStream(byteArrayOf(1).inputStream(), 3).readBytes() }.isFailure)
        check(runCatching { ToolchainFetcher.BoundedInputStream(byteArrayOf(1).inputStream(), 3).drain() }.isFailure)
        pass("truncated archive members fail instead of being accepted as complete")
        println("$passed porting regression groups passed")
    } finally { root.deleteRecursively() }
}
