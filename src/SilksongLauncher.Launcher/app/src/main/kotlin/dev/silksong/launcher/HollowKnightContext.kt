package dev.silksong.launcher

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/** Fixed HK namespace. Neither a catalog value nor an Intent can choose storage paths. */
class HollowKnightContext private constructor(base: Context) : ContextWrapper(base) {
    companion object {
        private const val DIRECTORY = "games/hollow-knight"

        @JvmStatic fun wrap(base: Context): Context =
            if (base is HollowKnightContext) base else HollowKnightContext(base)

        fun isGameProcess(): Boolean = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28)
                return@runCatching android.app.Application.getProcessName().endsWith(":hollow_knight")
            File("/proc/self/cmdline").inputStream().use { input ->
                val bytes = ByteArray(256)
                val count = input.read(bytes)
                count > 0 && String(bytes, 0, count, Charsets.UTF_8).substringBefore('\u0000')
                    .endsWith(":hollow_knight")
            }
        }.getOrDefault(false)
    }

    private fun directory(parent: File, name: String) = File(parent, name).apply {
        check(isDirectory || mkdirs()) { "Cannot create Hollow Knight storage: $this" }
    }

    override fun getFilesDir(): File = directory(baseContext.filesDir, DIRECTORY)
    override fun getCacheDir(): File = directory(baseContext.cacheDir, DIRECTORY)
    override fun getCodeCacheDir(): File = directory(baseContext.codeCacheDir, DIRECTORY)
    override fun getNoBackupFilesDir(): File = directory(baseContext.noBackupFilesDir, DIRECTORY)
    override fun getExternalFilesDir(type: String?): File? = baseContext.getExternalFilesDir(null)?.let {
        val root = directory(it, DIRECTORY)
        if (type == null) root else directory(root, type)
    }
    override fun getExternalFilesDirs(type: String?): Array<File> =
        baseContext.getExternalFilesDirs(null).filterNotNull().map {
            val root = directory(it, DIRECTORY)
            if (type == null) root else directory(root, type)
        }.toTypedArray()
    override fun getObbDir(): File = directory(filesDir, "pkg")
    override fun getObbDirs(): Array<File> = arrayOf(obbDir)
    override fun getDir(name: String, mode: Int): File = directory(filesDir, "app_$name")
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences("hollow-knight.$name", mode)
    override fun getApplicationContext(): Context = this
}
