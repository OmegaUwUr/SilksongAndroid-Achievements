package android.app

open class ActivityManager {
    open val runningAppProcesses: List<RunningAppProcessInfo>? get() = emptyList()
    fun getMemoryInfo(info: MemoryInfo) = Unit
    fun getHistoricalProcessExitReasons(name: String, pid: Int, count: Int): List<ApplicationExitInfo> = emptyList()
    class MemoryInfo { var totalMem = 0L; var availMem = 0L; var lowMemory = false }
    class RunningAppProcessInfo(val processName: String, val pid: Int)
}
class ApplicationExitInfo {
    val processName = "fixture"; val pid = 0; val timestamp = 0L; val reason = 0; val status = 0
    companion object {
        const val REASON_LOW_MEMORY = 1; const val REASON_SIGNALED = 2; const val REASON_CRASH = 3
        const val REASON_CRASH_NATIVE = 4; const val REASON_ANR = 5
        const val REASON_EXCESSIVE_RESOURCE_USAGE = 6; const val REASON_EXIT_SELF = 7
    }
}
