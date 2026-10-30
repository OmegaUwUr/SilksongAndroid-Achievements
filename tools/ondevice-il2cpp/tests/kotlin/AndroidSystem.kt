package android.system
class ErrnoException : Exception()
object Os {
    fun symlink(target: String, path: String) { java.nio.file.Files.createSymbolicLink(java.nio.file.Path.of(path), java.nio.file.Path.of(target)) }
    fun lstat(path: String) { if (!java.io.File(path).exists()) throw ErrnoException() }
    fun chmod(path: String, mode: Int) {}
    fun sysconf(name: Int): Long = 4096
}
object OsConstants { const val _SC_PAGESIZE = 30 }
