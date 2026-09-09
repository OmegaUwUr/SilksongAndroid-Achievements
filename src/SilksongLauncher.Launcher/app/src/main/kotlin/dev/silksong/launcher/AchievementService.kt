package dev.silksong.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the long-lived Steam session while the Unity game process is running.
 *
 * Android puts the launcher in :launcher and Unity in the default process, so
 * a static Kotlin singleton cannot cross the process boundary. The service
 * lives in :launcher and exposes a tiny private AF_UNIX protocol to the
 * libsteam_api.so shim in the game process.
 */
class AchievementService : Service() {
    companion object {
        const val APP_ID = 1030300
        private const val CHANNEL_ID = "steam-achievements"
        private const val NOTIFICATION_ID = 1030300
        private const val SOCKET_NAME = "silksong-achievements-v1"
        private const val STORE_MIN_INTERVAL_MS = 5_000L

        fun start(context: android.content.Context) {
            val intent = Intent(context, AchievementService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private val running = AtomicBoolean(true)
    private val ready = AtomicBoolean(false)
    private val stats = AtomicReference<Any?>(null)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var server: LocalServerSocket? = null
    private var steam: SteamSession? = null
    @Volatile private var lastStore = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        executor.execute { initializeSteam() }
        executor.execute { serve() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initializeSteam() {
        val credentials = TokenStore(this).read()
        if (credentials == null) {
            LauncherLog.log("Achievements disabled: no Steam credentials")
            return
        }

        try {
            val session = SteamSession()
            steam = session
            session.logOn(credentials)

            // Depot download rights are already a strong ownership proof. We
            // additionally inspect the received license objects for app/depot
            // IDs so a stale/manual-file path can never submit achievements.
            val licenses = session.licenses()
            val depot = DepotLocation.resolve(this)
            if (depot == null || !DepotFetcher.isPresent(depot)) {
                LauncherLog.log("Achievements disabled: game was not installed through the launcher Steam depot path")
                session.close()
                steam = null
                return
            }
            if (!ownsSilksong(licenses)) {
                LauncherLog.log("Achievements disabled: Steam license does not prove ownership of $APP_ID")
                session.close()
                steam = null
                return
            }

            stats.set(resolveStatsObject(findStatsHandler(session)))
            if (stats.get() == null) {
                LauncherLog.log("Achievements disabled: JavaSteam SteamUserStats handler unavailable")
                return
            }

            invokeStats("requestCurrentStats")
            ready.set(true)
            LauncherLog.log("Steam achievement bridge ready for app $APP_ID")
        } catch (t: Throwable) {
            LauncherLog.log("Achievement Steam session failed", t)
        }
    }

    private fun resolveStatsObject(handler: Any?): Any? {
        if (handler == null) return null
        // JavaSteam 1.8 models app-specific stats through a Stats object.
        // Keep this reflective so a minor 1.8.x signature change does not make
        // the launcher uncompilable; older builds exposed the operations
        // directly on SteamUserStats.
        val method = handler.javaClass.methods.firstOrNull {
            (it.name == "getStats" || it.name == "getStatsForApp") && it.parameterTypes.size == 1
        }
        if (method != null) {
            runCatching {
                var value = method.invoke(handler, APP_ID)
                if (value is java.util.concurrent.Future<*>) value = value.get(15, java.util.concurrent.TimeUnit.SECONDS)
                if (value != null) return value
            }.onFailure { LauncherLog.log("JavaSteam getStats($APP_ID) failed", it) }
        }
        return handler
    }

    private fun findStatsHandler(session: SteamSession): Any? {
        val cls = Class.forName("in.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats")
        val existing = session.steamClient.javaClass.methods
            .firstOrNull { it.name == "getHandler" && it.parameterTypes.size == 1 }
            ?.invoke(session.steamClient, cls)
        if (existing != null) return existing

        // JavaSteam normally installs this handler as part of its default
        // client handler set. Keep a reflective fallback so a future 1.8.x
        // layout that does not auto-install it still works.
        val handler = cls.getDeclaredConstructor().newInstance()
        session.steamClient.javaClass.methods
            .firstOrNull { it.name == "addHandler" && it.parameterTypes.size == 1 }
            ?.invoke(session.steamClient, handler)
        return handler
    }

    private fun invokeStats(name: String, vararg args: Any?): Any? {
        val target = stats.get() ?: return null
        val methods = target.javaClass.methods.filter { it.name == name && it.parameterTypes.size == args.size }
        val method = methods.firstOrNull { m ->
            m.parameterTypes.withIndex().all { (i, type) ->
                val arg = args[i]
                arg == null || type.isAssignableFrom(arg.javaClass) ||
                    (type.isPrimitive && type == Int::class.javaPrimitiveType && arg is Int)
            }
        } ?: methods.firstOrNull() ?: return null
        return method.invoke(target, *args)
    }

    private fun achievement(name: String): Boolean {
        if (!ready.get()) return false
        return try {
            val result = invokeStats("setAchievement", name)
            result as? Boolean ?: true
        } catch (t: Throwable) {
            LauncherLog.log("SetAchievement($name) failed", t)
            false
        }
    }

    private fun getAchievement(name: String): Boolean {
        if (!ready.get()) return false
        return try {
            val result = invokeStats("getAchievement", name)
            result as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }

    private fun store(): Boolean {
        if (!ready.get()) return false
        val now = System.currentTimeMillis()
        if (now - lastStore < STORE_MIN_INTERVAL_MS) return true
        return try {
            val result = invokeStats("storeStats")
            lastStore = now
            result as? Boolean ?: true
        } catch (t: Throwable) {
            LauncherLog.log("StoreStats failed", t)
            false
        }
    }

    private fun handle(line: String): Boolean {
        val parts = line.trimEnd('\n').split('\t', limit = 2)
        return when (parts[0]) {
            "PING", "REQUEST" -> ready.get()
            "SET" -> parts.getOrNull(1)?.let { achievement(it) } ?: false
            "GET" -> parts.getOrNull(1)?.let { getAchievement(it) } ?: false
            "STORE" -> store()
            else -> false
        }
    }

    private fun serve() {
        try {
            server = LocalServerSocket(SOCKET_NAME)
            while (running.get()) {
                val socket = server!!.accept()
                executor.execute { handleClient(socket) }
            }
        } catch (e: Throwable) {
            if (running.get()) LauncherLog.log("Achievement socket stopped", e)
        }
    }

    private fun handleClient(socket: LocalSocket) {
        socket.use { s ->
            s.soTimeout = 2_000
            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8), true)
            val line = reader.readLine() ?: return
            writer.print(if (handle(line)) "1\n" else "0\n")
            writer.flush()
        }
    }

    private fun ownsSilksong(licenses: List<*>): Boolean {
        if (licenses.isEmpty()) return false
        return licenses.any { license ->
            val item = license ?: return@any false
            val text = item.toString()
            if (text.contains(APP_ID.toString())) return@any true
            val fields = item.javaClass.declaredFields
            fields.any { f ->
                runCatching {
                    f.isAccessible = true
                    val value = f.get(item)
                    value is Iterable<*> && value.any {
                        it?.toString() == APP_ID.toString() || it?.toString() == "1030303"
                    }
                }.getOrDefault(false)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Steam achievements", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(): Notification {
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Silksong Steam achievements")
                .setContentText("Steam achievement synchronization is active")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Silksong Steam achievements")
                .setContentText("Steam achievement synchronization is active")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() {
        running.set(false)
        ready.set(false)
        runCatching { store() }
        runCatching { server?.close() }
        steam?.close()
        steam = null
        executor.shutdownNow()
        super.onDestroy()
    }
}
