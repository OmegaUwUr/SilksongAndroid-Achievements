package android.os

object Build { object VERSION { const val SDK_INT = 35 }; object VERSION_CODES { const val R = 30 } }
open class RemoteException : Exception()
interface IBinder { fun send(message: Message) }
class Messenger(private val binder: IBinder) { fun send(message: Message) = binder.send(message) }
class Message(val what: Int) {
    var data = Bundle()
    companion object { fun obtain(unused: Any?, what: Int) = Message(what) }
}
class Bundle {
    private val values = mutableMapOf<String, Any?>()
    fun putString(name: String, value: String?) { values[name] = value }
    fun putStringArray(name: String, value: Array<String>) { values[name] = value }
    fun getString(name: String): String? = values[name] as? String
}
