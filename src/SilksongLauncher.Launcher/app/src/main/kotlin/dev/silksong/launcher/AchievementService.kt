package dev.silksong.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.IBinder
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.Stats
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bridges Silksong's Android Steamworks shim to the authenticated JavaSteam session. */
class AchievementService : Service() {
    companion object {
        const val APP_ID = 1030300
        private const val CHANNEL_ID = "steam-achievements-v2"
        private const val NOTIFICATION_ID = 1030300
        private const val SOCKET_NAME = "silksong-achievements-v1"
        private const val STEAM_TIMEOUT_SECONDS = 25L
        private const val ACTION_NOTIFICATION_DISMISSED = "dev.silksong.launcher.ACHIEVEMENT_NOTIFICATION_DISMISSED"
        private const val ACTION_SAFE_SHUTDOWN = "dev.silksong.launcher.ACHIEVEMENT_SAFE_SHUTDOWN"
        @Volatile private var active = false

        fun isActive(): Boolean = active

        fun start(context: Context) {
            LauncherLog.log("Achievements: requesting synchronization service start")
            val intent = Intent(context, AchievementService::class.java)
            try {
                val component = if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
                LauncherLog.log("Achievements: service start accepted: $component")
            } catch (t: Throwable) {
                LauncherLog.log("Achievements: service start failed", t)
                throw t
            }
        }

        /**
         * Requests an orderly stop without starting the service if it is not
         * already running. The live service receives this app-local broadcast,
         * flushes any pending Steam achievement write, tears down JavaSteam and
         * its socket, removes the foreground notification, then stops itself.
         */
        fun stopSafely(context: Context) {
            LauncherLog.log("Achievements: safe shutdown requested")
            context.sendBroadcast(Intent(ACTION_SAFE_SHUTDOWN).setPackage(context.packageName))
        }
    }

    private data class AchievementLocation(val statId: Int, val bitIndex: Int)

    private val running = AtomicBoolean(true)
    private val ready = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val achievementLocations = ConcurrentHashMap<String, AchievementLocation>()
    private val remotelyUnlocked = ConcurrentHashMap.newKeySet<String>()
    private val pendingUnlocks = ConcurrentHashMap.newKeySet<String>()
    private val storeLock = Any()

    private var server: LocalServerSocket? = null
    private var steam: SteamSession? = null
    @Volatile private var steamUser: SteamUser? = null
    @Volatile private var steamUserStats: SteamUserStats? = null
    @Volatile private var notificationText = "Connecting to Steam…"

    private val notificationDismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_NOTIFICATION_DISMISSED || !running.get() || shuttingDown.get()) return
            LauncherLog.log("Achievements: status notification dismissed; restoring while service is active")
            android.os.Handler(mainLooper).postDelayed({
                if (running.get() && !shuttingDown.get()) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(notificationText))
                }
            }, 250)
        }
    }

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SAFE_SHUTDOWN) beginSafeShutdown()
        }
    }

    override fun onCreate() {
        super.onCreate()
        active = true
        LauncherLog.log("Achievements: service created")
        createNotificationChannel()
        registerReceivers()
        startForeground(NOTIFICATION_ID, notification(notificationText))
        executor.execute { serve() }
        executor.execute { initializeSteam() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LauncherLog.log("Achievements: service onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initializeSteam() {
        val credentials = TokenStore(this).read()
        if (credentials == null) {
            failReady("Achievements disabled: no Steam credentials")
            return
        }

        try {
            LauncherLog.log("Achievements: connecting to Steam CM")
            val session = SteamSession()
            steam = session
            session.logOn(credentials)
            LauncherLog.log("Achievements: authenticated with Steam")

            val depot = DepotLocation.resolve(this)
            if (depot == null || !DepotFetcher.isPresent(depot)) {
                throw IllegalStateException("game was not installed through the launcher Steam depot path")
            }
            LauncherLog.log("Achievements: launcher Steam depot verified")

            val user = session.steamClient.getHandler(SteamUser::class.java)
                ?: throw IllegalStateException("JavaSteam SteamUser handler unavailable")
            val stats = session.steamClient.getHandler(SteamUserStats::class.java)
                ?: throw IllegalStateException("JavaSteam SteamUserStats handler unavailable")
            if (user.steamID == null) throw IllegalStateException("Steam logged on but account SteamID is unavailable")
            steamUser = user
            steamUserStats = stats

            LauncherLog.log("Achievements: requesting authoritative Steam achievement state for app $APP_ID")
            val snapshot = fetchUserStats()
            LauncherLog.log("Achievements: Steam authorized user-stats access for app $APP_ID")
            rebuildAchievementIndex(snapshot)
            if (achievementLocations.isEmpty()) {
                throw IllegalStateException("Steam returned no named Silksong achievements in the user-stats schema")
            }

            if (shuttingDown.get()) return
            ready.set(true)
            LauncherLog.log("Achievements: READY — ${achievementLocations.size} Steam achievement API names mapped")
            updateNotification("Connected to Steam • ${achievementLocations.size} achievements tracked")
        } catch (t: Throwable) {
            if (!shuttingDown.get()) failReady("Achievement Steam session failed", t)
        }
    }

    private fun failReady(message: String, error: Throwable? = null) {
        ready.set(false)
        if (error == null) LauncherLog.log(message) else LauncherLog.log(message, error)
        if (!shuttingDown.get()) updateNotification("Steam achievement synchronization unavailable")
    }

    private fun fetchUserStats(): UserStatsCallback {
        val user = steamUser ?: throw IllegalStateException("SteamUser not initialized")
        val stats = steamUserStats ?: throw IllegalStateException("SteamUserStats not initialized")
        val steamId = user.steamID ?: throw IllegalStateException("SteamID unavailable")
        val callback = stats.getUserStats(APP_ID, steamId).toFuture().get(STEAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (callback.result != EResult.OK) throw IllegalStateException("getUserStats($APP_ID) failed: ${callback.result}")
        return callback
    }

    private fun rebuildAchievementIndex(snapshot: UserStatsCallback) {
        val newLocations = HashMap<String, AchievementLocation>()
        val newUnlocked = HashSet<String>()
        for (achievement in snapshot.getExpandedAchievements()) {
            val name = achievement.name?.takeIf { it.isNotBlank() } ?: continue
            val encoded = achievement.achievementId
            val bitIndex = encoded % 100
            val statId = encoded / 100
            if (statId <= 0 || bitIndex !in 0..31) {
                LauncherLog.log("Achievements: ignoring invalid schema mapping $name -> stat=$statId bit=$bitIndex")
                continue
            }
            newLocations[name] = AchievementLocation(statId, bitIndex)
            if (achievement.isUnlocked) newUnlocked.add(name)
        }
        achievementLocations.clear()
        achievementLocations.putAll(newLocations)
        remotelyUnlocked.clear()
        remotelyUnlocked.addAll(newUnlocked)
        LauncherLog.log("Achievements: schema mapped ${newLocations.size} name(s), ${newUnlocked.size} already unlocked on Steam")
    }

    private fun setAchievement(name: String): Boolean {
        if (!ready.get() || shuttingDown.get()) {
            LauncherLog.log("SetAchievement($name) rejected: Steam bridge is not ready")
            return false
        }
        val location = achievementLocations[name]
        if (location == null) {
            LauncherLog.log("SetAchievement($name) rejected: name is not present in Steam's Silksong schema")
            return false
        }
        if (remotelyUnlocked.contains(name)) {
            LauncherLog.log("SetAchievement($name): already unlocked on Steam")
            return true
        }
        pendingUnlocks.add(name)
        LauncherLog.log("SetAchievement($name): queued as stat ${location.statId} bit ${location.bitIndex}")
        updateNotification("Achievement queued • waiting for Steam confirmation")
        return true
    }

    private fun getAchievement(name: String): Boolean {
        if (!ready.get() || shuttingDown.get()) return false
        if (!achievementLocations.containsKey(name)) {
            LauncherLog.log("GetAchievement($name): unknown Steam achievement name")
            return false
        }
        val unlocked = remotelyUnlocked.contains(name) || pendingUnlocks.contains(name)
        LauncherLog.log("GetAchievement($name): $unlocked")
        return unlocked
    }

    private fun storeStats(): Boolean = synchronized(storeLock) {
        if (!ready.get()) {
            LauncherLog.log("StoreStats rejected: Steam bridge is not ready")
            return@synchronized false
        }
        val names = pendingUnlocks.toSet()
        if (names.isEmpty()) {
            LauncherLog.log("StoreStats: no pending achievement changes")
            return@synchronized true
        }
        val statsHandler = steamUserStats ?: return@synchronized false
        val user = steamUser ?: return@synchronized false
        val steamId = user.steamID ?: return@synchronized false

        for (attempt in 1..2) {
            try {
                LauncherLog.log("StoreStats: refreshing Steam state (attempt $attempt/2)")
                val snapshot = fetchUserStats()
                rebuildAchievementIndex(snapshot)
                val blockMasks = HashMap<Int, Int>()
                for (block in snapshot.achievementBlocks) {
                    var mask = 0
                    for (i in block.unlockTime.indices.take(32)) if (block.unlockTime[i] != 0) mask = mask or (1 shl i)
                    blockMasks[block.achievementId] = mask
                }
                val changedBlocks = HashSet<Int>()
                for (name in names) {
                    val location = achievementLocations[name] ?: run {
                        LauncherLog.log("StoreStats: $name disappeared from Steam schema; refusing unsafe write")
                        return@synchronized false
                    }
                    blockMasks[location.statId] = (blockMasks[location.statId] ?: 0) or (1 shl location.bitIndex)
                    changedBlocks.add(location.statId)
                }
                val payload = changedBlocks.sorted().map { statId -> Stats(statId = statId, statValue = blockMasks.getValue(statId)) }
                LauncherLog.log("StoreStats: submitting ${names.size} achievement(s) in ${payload.size} stat block(s) to Steam")
                val callback = statsHandler.storeUserStats(APP_ID, payload, steamId, steamId, snapshot.crcStats)
                    .toFuture().get(STEAM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                LauncherLog.log("StoreStats: Steam response=${callback.result}, outOfDate=${callback.statsOutOfDate}, failedValidation=${callback.statsFailedValidation.size}")
                if (callback.result == EResult.OK && !callback.statsOutOfDate && callback.statsFailedValidation.isEmpty()) {
                    pendingUnlocks.removeAll(names)
                    remotelyUnlocked.addAll(names)
                    LauncherLog.log("StoreStats: SUCCESS — Steam accepted ${names.joinToString()}")
                    if (!shuttingDown.get()) updateNotification("Connected to Steam • achievements synchronized")
                    return@synchronized true
                }
                if (!callback.statsOutOfDate || attempt == 2) {
                    callback.statsFailedValidation.forEach {
                        LauncherLog.log("StoreStats validation failure: stat ${it.statId} reverted to ${it.revertedStatValue}")
                    }
                    LauncherLog.log("StoreStats: FAILED — Steam did not accept achievement update")
                    if (!shuttingDown.get()) updateNotification("Steam achievement synchronization needs attention")
                    return@synchronized false
                }
                LauncherLog.log("StoreStats: Steam state was out of date; retrying with fresh state")
            } catch (t: Throwable) {
                LauncherLog.log("StoreStats attempt $attempt failed", t)
                if (attempt == 2) {
                    if (!shuttingDown.get()) updateNotification("Steam achievement synchronization needs attention")
                    return@synchronized false
                }
            }
        }
        false
    }

    private fun handle(line: String): Boolean {
        if (shuttingDown.get()) return false
        val parts = line.trimEnd('\n').split('\t', limit = 2)
        val command = parts[0]
        if (command != "PING") LauncherLog.log("Achievements IPC: $command${parts.getOrNull(1)?.let { " $it" } ?: ""}")
        return when (command) {
            "PING", "REQUEST" -> ready.get()
            "SET" -> parts.getOrNull(1)?.let(::setAchievement) ?: false
            "GET" -> parts.getOrNull(1)?.let(::getAchievement) ?: false
            "STORE" -> storeStats()
            else -> false
        }
    }

    private fun serve() {
        try {
            server = LocalServerSocket(SOCKET_NAME)
            LauncherLog.log("Achievements: IPC socket listening ($SOCKET_NAME)")
            while (running.get()) {
                val socket = server!!.accept()
                executor.execute { handleClient(socket) }
            }
        } catch (t: Throwable) {
            if (running.get() && !shuttingDown.get()) LauncherLog.log("Achievement socket stopped", t)
        }
    }

    private fun handleClient(socket: LocalSocket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(s.outputStream, Charsets.UTF_8), true)
            val line = reader.readLine() ?: return
            writer.print(if (handle(line)) "1\n" else "0\n")
            writer.flush()
        }
    }

    private fun registerReceivers() {
        val dismissFilter = IntentFilter(ACTION_NOTIFICATION_DISMISSED)
        val shutdownFilter = IntentFilter(ACTION_SAFE_SHUTDOWN)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(notificationDismissReceiver, dismissFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(shutdownReceiver, shutdownFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(notificationDismissReceiver, dismissFilter)
            @Suppress("DEPRECATION")
            registerReceiver(shutdownReceiver, shutdownFilter)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Steam achievements", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Persistent status for Silksong Steam achievement synchronization"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun updateNotification(text: String) {
        notificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val dismissIntent = Intent(ACTION_NOTIFICATION_DISMISSED).setPackage(packageName)
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this,
            NOTIFICATION_ID,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)

        builder
            .setContentTitle("Silksong achievements")
            .setContentText(text)
            .setSubText("Steam synchronization service active")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setDeleteIntent(dismissPendingIntent)

        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build().apply {
            flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        }
    }

    private fun beginSafeShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        LauncherLog.log("Achievements: beginning safe shutdown")
        notificationText = "Closing safely • finishing Steam synchronization"
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(notificationText))

        executor.execute {
            try {
                if (ready.get() && pendingUnlocks.isNotEmpty()) {
                    LauncherLog.log("Achievements: flushing ${pendingUnlocks.size} pending unlock(s) before exit")
                    runCatching { storeStats() }
                        .onFailure { LauncherLog.log("Achievements: final Steam flush failed", it) }
                }
            } finally {
                running.set(false)
                ready.set(false)
                runCatching { server?.close() }
                steam?.close()
                steam = null
                steamUser = null
                steamUserStats = null

                android.os.Handler(mainLooper).post {
                    LauncherLog.log("Achievements: safe shutdown complete")
                    if (Build.VERSION.SDK_INT >= 24) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                    stopSelf()
                    // This service shares :launcher with the UI. By the time we
                    // reach here all Steam/socket/notification work is finished,
                    // so terminating that process completes an explicit Exit
                    // without touching the separate game process.
                    android.os.Handler(mainLooper).postDelayed({
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }, 150)
                }
            }
        }
    }

    override fun onDestroy() {
        active = false
        LauncherLog.log("Achievements: synchronization service stopping")
        if (!shuttingDown.get() && ready.get() && pendingUnlocks.isNotEmpty()) {
            runCatching { storeStats() }
        }
        running.set(false)
        ready.set(false)
        runCatching { server?.close() }
        steam?.close()
        steam = null
        steamUser = null
        steamUserStats = null
        runCatching { unregisterReceiver(notificationDismissReceiver) }
        runCatching { unregisterReceiver(shutdownReceiver) }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        executor.shutdownNow()
        super.onDestroy()
    }
}
