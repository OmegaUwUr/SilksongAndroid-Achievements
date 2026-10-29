package dev.silksong.launcher

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/** Each candidate owns its source, engine, build, preferences and saves. */
class GenericPortContext private constructor(base: Context, val appId: Int) : ContextWrapper(base) {
    companion object {
        @JvmStatic fun wrap(base: Context, appId: Int): GenericPortContext {
            require(appId > 0 && appId !in listOf(1030300, 367520))
            if (base is GenericPortContext) { require(base.appId == appId); return base }
            return GenericPortContext(base, appId)
        }
        @JvmStatic fun isGameProcess(): Boolean = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName().endsWith(":unity_port")
            else File("/proc/self/cmdline").readText().substringBefore('\u0000').endsWith(":unity_port")
        }.getOrDefault(false)
        @JvmStatic fun active(base: Context): GenericPortContext {
            val file = File(base.filesDir, "generic-active")
            require(file.length() in 1L..10L) { "No prepared generic port was selected." }
            return wrap(base, file.readText().trim().toInt())
        }
    }
    private val directory get() = "games/steam-$appId"
    val sharedFilesDir: File get() = baseContext.filesDir
    val sharedExternalFilesDir: File? get() = baseContext.getExternalFilesDir(null)
    private fun dir(parent: File, child: String = directory) = File(parent, child).apply {
        check(isDirectory || mkdirs()) { "Cannot create game storage." }
    }
    override fun getFilesDir(): File = dir(baseContext.filesDir)
    override fun getCacheDir(): File = dir(baseContext.cacheDir)
    override fun getCodeCacheDir(): File = dir(baseContext.codeCacheDir)
    override fun getNoBackupFilesDir(): File = dir(baseContext.noBackupFilesDir)
    override fun getExternalFilesDir(type: String?): File? = baseContext.getExternalFilesDir(null)?.let {
        val root = dir(it); if (type == null) root else dir(root, type)
    }
    override fun getExternalFilesDirs(type: String?): Array<File> = baseContext.getExternalFilesDirs(null)
        .filterNotNull().map { val root = dir(it); if (type == null) root else dir(root, type) }.toTypedArray()
    override fun getObbDir(): File = dir(filesDir, "pkg")
    override fun getObbDirs(): Array<File> = arrayOf(obbDir)
    override fun getDir(name: String, mode: Int): File = dir(filesDir, "app_$name")
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences("steam-$appId.$name", mode)
    override fun getApplicationContext(): Context = this
}
