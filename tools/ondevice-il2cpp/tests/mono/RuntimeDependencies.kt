package dev.silksong.launcher

object LauncherLog { fun log(message: String) = Unit }
object Toolchain { data class Result(val code: Int, val output: String, val lowMemory: Boolean) }
open class MonoService {
    companion object {
        const val MSG_RUN = 1; const val MSG_QUIT = 2; const val FAILED = 120
        const val KEY_ASSEMBLY = "assembly"; const val KEY_ARGS = "args"; const val KEY_CWD = "cwd"
        const val KEY_BCL = "bcl"; const val KEY_RUNTIME = "runtime"; const val KEY_ENV = "env"
        const val KEY_OUT = "out"; const val KEY_RESULT = "result"
    }
}
class MonoServiceAlt : MonoService()
