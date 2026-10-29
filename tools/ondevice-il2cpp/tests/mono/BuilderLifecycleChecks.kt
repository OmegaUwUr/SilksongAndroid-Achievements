package dev.silksong.launcher

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Message
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Messenger/process discovery boundary only. The actual MonoRuntime owns all run/cleanup decisions. */
private class HostContext(override val filesDir: File) : Context() {
    val builders = ConcurrentHashMap<Class<*>, IBinder>()
    val connections = ConcurrentHashMap<ServiceConnection, Int>()
    val quit = CopyOnWriteArrayList<Class<*>>()
    val created = CopyOnWriteArrayList<Class<*>>()
    val cleanupFlags = CopyOnWriteArrayList<Int>()
    val unavailable = mutableSetOf<Class<*>>()
    var reply = true
    var runAction: (Message) -> Unit = { message ->
        File(requireNotNull(message.data.getString(MonoService.KEY_OUT))).appendText("fixture output\n")
        File(requireNotNull(message.data.getString(MonoService.KEY_RESULT))).writeText("0")
    }
    val processes = object : ActivityManager() {
        override val runningAppProcesses: List<RunningAppProcessInfo> get() =
            listOf(RunningAppProcessInfo("fixture.launcher:launcher", 100), RunningAppProcessInfo("other:builder", 101)) +
                builders.keys.map { RunningAppProcessInfo(packageName + suffix(it), pid(it)) }
    }
    private fun suffix(type: Class<*>) = if (type == MonoService::class.java) ":builder" else ":builder2"
    private fun pid(type: Class<*>) = if (type == MonoService::class.java) 201 else 202
    override fun getSystemService(name: String): Any = processes
    override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        connections[connection] = flags
        if (flags and BIND_AUTO_CREATE != 0) {
            builders.putIfAbsent(intent.target, endpoint(intent.target))
            created += intent.target
        } else {
            cleanupFlags += flags
            if (intent.target in unavailable) return false
        }
        val binder = builders[intent.target] ?: return false
        if (reply || flags and BIND_AUTO_CREATE != 0) connection.onServiceConnected(ComponentName(), binder)
        return true
    }
    override fun unbindService(connection: ServiceConnection) { connections.remove(connection) }
    private fun endpoint(type: Class<*>) = object : IBinder {
        override fun send(message: Message) {
            when (message.what) {
                MonoService.MSG_RUN -> runAction(message)
                MonoService.MSG_QUIT -> { quit += type; builders.remove(type, this) }
                else -> error("unexpected message")
            }
        }
    }
    fun addStale(type: Class<*>) { builders[type] = endpoint(type) }
}

fun main() = runBlocking {
    var cases = 0
    suspend fun test(name: String, action: suspend (HostContext, File) -> Unit) {
        val root = Files.createTempDirectory("builder-lifecycle-").toFile()
        try {
            val context = HostContext(root)
            val dll = File(root, "fixture.dll").apply { writeText("metadata fixture; never executed") }
            withTimeout(15_000) { action(context, dll) }
            check(context.connections.isEmpty())
            check(context.cleanupFlags.all { it == 0 })
            check(context.processes.runningAppProcesses.orEmpty().any { it.processName == "fixture.launcher:launcher" })
            cases++; println("PASS: $name")
        } finally { root.deleteRecursively() }
    }
    test("stale builders receive a targeted quit without stopping the launcher or another package") { context, dll ->
        context.addStale(MonoService::class.java)
        context.addStale(MonoServiceAlt::class.java)
        check(MonoRuntime.exec(context, dll).code == 0)
        check(context.quit.toSet() == setOf(MonoService::class.java, MonoServiceAlt::class.java))
        check(context.created.size == 1 && context.cleanupFlags.size == 2)
    }
    test("the next tool can retire a completed run's lingering service") { context, dll ->
        check(MonoRuntime.exec(context, dll).output == "fixture output\n")
        check(MonoRuntime.exec(context, dll).code == 0)
        check(context.quit.size == 1 && context.created.size == 2)
    }
    test("an unreachable occupied slot is skipped without terminating any other process") { context, dll ->
        context.addStale(MonoService::class.java)
        context.unavailable += MonoService::class.java
        check(MonoRuntime.exec(context, dll).code == 0)
        check(context.created == listOf(MonoServiceAlt::class.java))
        check(context.builders.containsKey(MonoService::class.java) && context.quit.isEmpty())
    }
    test("two game preparations cannot mistake an active tool for a stale builder") { context, dll ->
        val started = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val count = AtomicInteger()
        val overlapped = AtomicInteger()
        context.runAction = { message ->
            if (active.incrementAndGet() != 1) overlapped.incrementAndGet()
            count.incrementAndGet()
            started.complete(Unit)
            Thread.sleep(150)
            File(requireNotNull(message.data.getString(MonoService.KEY_RESULT))).writeText("0")
            active.decrementAndGet()
        }
        val first = async(Dispatchers.Default) { MonoRuntime.exec(context, dll) }
        started.await()
        val second = async(Dispatchers.Default) { MonoRuntime.exec(context, dll) }
        check(first.await().code == 0 && second.await().code == 0)
        check(count.get() == 2 && overlapped.get() == 0)
        check(context.quit.size == 1)
    }
    test("cancelling a waiting preparation leaves the active service running") { context, dll ->
        val started = CompletableDeferred<Unit>()
        context.runAction = { started.complete(Unit) }
        val active = async(Dispatchers.Default) { MonoRuntime.exec(context, dll) }
        started.await()
        val queued = async(Dispatchers.Default) { MonoRuntime.exec(context, dll) }
        delay(100)
        queued.cancelAndJoin()
        check(context.created.size == 1 && context.quit.isEmpty())
        active.cancelAndJoin()
        check(context.quit.size == 1 && context.builders.isEmpty())
    }
    test("cancelling stale cleanup releases its binding without creating another process") { context, dll ->
        context.addStale(MonoService::class.java)
        context.addStale(MonoServiceAlt::class.java)
        context.reply = false
        val run = async(Dispatchers.Default) { MonoRuntime.exec(context, dll) }
        while (context.cleanupFlags.isEmpty()) delay(20)
        run.cancelAndJoin()
        check(context.created.isEmpty() && context.quit.isEmpty())
    }
    println("$cases builder lifecycle checks passed; Android binder/native execution is simulated")
}
