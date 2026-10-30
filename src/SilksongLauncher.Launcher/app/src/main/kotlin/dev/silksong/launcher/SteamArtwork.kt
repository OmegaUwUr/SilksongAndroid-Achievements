package dev.silksong.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Bounded, recycled-view-aware artwork loader. Requests only public official Steam CDN images. */
object SteamArtwork {
    private val requests = java.util.WeakHashMap<ImageView, Job>()
    private val gate = Semaphore(4)
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val hintWrites = java.util.concurrent.atomic.AtomicInteger()
    private fun hintsDirectory(context: Context) = File(context.cacheDir, "steam-art-hints").apply { mkdirs() }

    fun remember(context: Context, appId: Int, hints: Map<String, List<String>>) {
        if (hints.values.all { it.isEmpty() }) return
        val directory = hintsDirectory(context)
        val data = JSONObject()
        hints.forEach { (kind, paths) -> data.put(kind, JSONArray(paths.filter(SteamArtPaths::safe).take(8))) }
        val file = android.util.AtomicFile(File(directory, "$appId.json")); val output = file.startWrite()
        try { output.write(data.toString().toByteArray()); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
        if (hintWrites.incrementAndGet() % 100 == 0)
            directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedByDescending { it.lastModified() }.drop(2000).forEach { it.delete() }
    }

    fun load(view: ImageView, appId: Int, kind: String, scope: CoroutineScope, ready: (Boolean) -> Unit = {}) {
        require(appId > 0 && kind in listOf("portrait", "hero", "logo"))
        val key = "$appId.$kind"
        requests.remove(view)?.cancel()
        view.tag = key; view.setImageDrawable(null)
        ready(false)
        val request = scope.launch {
            val bitmap = cache.get(key) ?: withContext(Dispatchers.IO) {
                gate.withPermit {
                    cache.get(key) ?: fetch(view.context.applicationContext, appId, kind)?.also { cache.put(key, it) }
                }
            }
            if (isActive && view.tag == key) { bitmap?.let(view::setImageBitmap); ready(bitmap != null) }
        }
        requests[view] = request
        request.invokeOnCompletion {
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (requests[view] === request) requests.remove(view) }
        }
    }

    private fun fetch(context: Context, appId: Int, kind: String): Bitmap? {
        val filename = when (kind) { "portrait" -> "library_600x900.jpg"; "hero" -> "library_hero.jpg"; else -> "logo.png" }
        val saved = runCatching {
            val file = android.util.AtomicFile(File(hintsDirectory(context), "$appId.json"))
            check(file.baseFile.length() <= 8192)
            val array = JSONObject(String(file.readFully(), Charsets.UTF_8)).optJSONArray(kind) ?: JSONArray()
            (0 until array.length().coerceAtMost(8)).map { array.getString(it) }.filter(SteamArtPaths::safe)
        }.getOrDefault(emptyList())
        val pinned = if (appId == 1030300) when (kind) {
            "hero" -> listOf("70d7e70ae2fd0f8a46661d4a425cd84479dc7a61/$filename")
            "logo" -> listOf("98878a81ca9047352403db7e19e3942239ea8bf1/$filename")
            else -> emptyList()
        } else emptyList()
        val urls = (saved + pinned + filename).distinct().map {
            "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/$appId/$it"
        } + "https://cdn.akamai.steamstatic.com/steam/apps/$appId/$filename"
        val directory = File(context.cacheDir, "steam-artwork").apply { mkdirs() }
        for (url in urls) {
            try {
                val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                val file = File(directory, name)
                val bytes = if (file.isFile && file.length() in 1..8_388_608 && System.currentTimeMillis() - file.lastModified() < 7 * 86400_000L)
                    file.readBytes() else download(URL(url)).also {
                        val temporary = File(directory, "$name.${Thread.currentThread().id}.part")
                        temporary.writeBytes(it); temporary.renameTo(file)
                    }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                check(bounds.outWidth in 1..16384 && bounds.outHeight in 1..16384)
                val limit = if (kind == "hero") 1600 else 600
                val options = BitmapFactory.Options()
                while (bounds.outWidth / options.inSampleSize.coerceAtLeast(1) > limit || bounds.outHeight / options.inSampleSize.coerceAtLeast(1) > limit * 2)
                    options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: continue
                var remaining = 96L * 1024 * 1024
                directory.listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }.forEachIndexed { index, cached ->
                    if (index >= 180 || cached.length() > remaining) cached.delete() else remaining -= cached.length()
                }
                return image
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Show the game title if official artwork is unavailable. */ }
        }
        return null
    }

    private fun download(url: URL, redirects: Int = 0): ByteArray {
        require(url.protocol == "https" && (url.host.endsWith(".steamstatic.com") || url.host == "steamcdn-a.akamaihd.net"))
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000; connection.readTimeout = 12_000; connection.instanceFollowRedirects = false
            if (connection.responseCode in 300..399) {
                check(redirects < 2)
                return download(URL(url, connection.getHeaderField("Location")), redirects + 1)
            }
            check(connection.responseCode == 200 && connection.contentLengthLong <= 8_388_608)
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; check(output.size() + count <= 8_388_608); output.write(buffer, 0, count) }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}
