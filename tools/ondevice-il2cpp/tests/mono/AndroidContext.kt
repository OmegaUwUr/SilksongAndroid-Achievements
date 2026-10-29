package android.content

import android.os.IBinder
import java.io.File

open class Context {
    companion object { const val ACTIVITY_SERVICE = "activity"; const val BIND_AUTO_CREATE = 1; const val BIND_ABOVE_CLIENT = 8 }
    open val applicationContext: Context get() = this
    open val packageName = "fixture.launcher"
    open val filesDir: File get() = error("fixture must provide filesDir")
    open val cacheDir: File get() = File(filesDir, "cache").apply { mkdirs() }
    val applicationInfo = android.content.pm.ApplicationInfo()
    val assets = android.content.res.AssetManager()
    open fun getSystemService(name: String): Any? = null
    open fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean = false
    open fun unbindService(connection: ServiceConnection) = Unit
}
class Intent(val context: Context, val target: Class<*>)
class ComponentName
interface ServiceConnection {
    fun onServiceConnected(name: ComponentName?, service: IBinder?)
    fun onServiceDisconnected(name: ComponentName?)
    fun onNullBinding(name: ComponentName?) = Unit
}
