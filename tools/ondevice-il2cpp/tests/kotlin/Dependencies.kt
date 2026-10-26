package dev.silksong.launcher
import java.io.File
import kotlinx.coroutines.CancellationException
object LauncherLog { fun log(message: String, error: Throwable? = null) {} }
object Toolchain {
    data class Result(val code: Int, val output: String) {
        val ok get() = code == 0
        val outOfMemory get() = code == 137
    }
    fun rootFor(context: android.content.Context) = File("unused")
    suspend fun verify(root: File): String? = error("real compiler probe is outside host tests")
}
object PlayerImage {
    fun depotData(depot: File): File? = depot
    fun wrongBuildProblem(depot: File): String? = null
    fun stageSurgery(root: File, assets: android.content.res.AssetManager) = File(root, "surgery.dll")
    suspend fun run(dll: File, context: android.content.Context, args: List<String>, onLine: (String) -> Unit) {
        if (Fixture.cancelRewrite) throw CancellationException("cancelled while rewriting")
    }
}
object Fixture {
    var fail = false
    var emptyCpp = false
    var cancelRewrite = false
    var executions = 0
    fun write(root: File, path: String, text: String = "fixture"): File = File(root, path).also {
        it.parentFile.mkdirs(); it.writeText(text)
    }
    fun execute(dll: File, args: List<String>, cwd: File?): Toolchain.Result {
        executions++
        if (dll.name == "il2cpp.dll") {
            val cpp = File(args.single { it.startsWith("--generatedcppdir=") }.substringAfter('='))
            val data = File(args.single { it.startsWith("--data-folder=") }.substringAfter('='))
            write(data, "Metadata/global-metadata.dat")
            if (!emptyCpp) write(cpp, "Game.cpp")
        } else {
            val rsp = File(args.single().removePrefix("@")).readLines()
            val out = File(rsp.single { it.startsWith("-out:") }.removePrefix("-out:").trim('"'))
            write(out.parentFile, out.name)
        }
        return Toolchain.Result(if (fail) 1 else 0, "fixture result")
    }
}
