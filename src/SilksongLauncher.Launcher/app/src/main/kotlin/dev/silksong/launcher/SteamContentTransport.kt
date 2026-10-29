package dev.silksong.launcher

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** The upstream chunk downloader retries IO failures indefinitely, without a delay. */
internal class SteamContentRetry(
    private val now: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    private var failures = 0
    private var nextAttempt = 0L
    private var lastError: IOException? = null

    @Synchronized fun failed(error: IOException) {
        if (error is Unavailable) return
        failures = (failures + 1).coerceAtMost(12)
        lastError = error
        nextAttempt = maxOf(nextAttempt, now() + backoff())
    }

    @Synchronized fun succeeded() {
        failures = 0
        nextAttempt = 0L
        lastError = null
    }

    /** Called by the owning download loop even when upstream swallows a worker failure. */
    @Synchronized fun requireAvailable() {
        if (failures >= 12) throw unavailable()
    }

    fun awaitAttempt(cancelled: () -> Boolean) {
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Steam download cancelled")
            val wait = synchronized(this) {
                if (failures == 0) return
                val remaining = nextAttempt - now()
                if (remaining <= 0) {
                    // During an outage only one worker probes at each interval. A completed
                    // successful response restores the normal parallel download immediately.
                    nextAttempt = now() + backoff()
                    if (failures >= 12) throw unavailable()
                    return
                }
                TimeUnit.NANOSECONDS.toMillis(remaining).coerceIn(1, 100)
            }
            try { pause(wait) } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Steam download interrupted").apply { initCause(interrupted) }
            }
        }
    }

    private fun backoff(): Long = TimeUnit.MILLISECONDS.toNanos(500L shl (failures - 1).coerceIn(0, 4))

    private fun unavailable(): Unavailable {
        val cause = lastError
        val detail = when (cause) {
            is UnknownHostException -> "Android could not resolve Steam's content servers. Check the connection and Private DNS settings."
            is SocketTimeoutException -> "Steam's content servers repeatedly timed out. Check the connection."
            else -> "Steam's content servers repeatedly failed. Check the connection and try again."
        }
        return Unavailable("Steam download paused after repeated failures. $detail Retry resumes verified downloaded chunks.", cause)
    }

    private class Unavailable(message: String, cause: IOException?) : IOException(message, cause)
}

/** Keeps Steam login/discovery independent of content retry state and of other account sessions. */
internal class SteamContentTransport(base: OkHttpClient = OkHttpClient(), val retry: SteamContentRetry = SteamContentRetry()) {
    val httpClient: OkHttpClient = base.newBuilder()
        .addInterceptor { chain ->
            if (isContent(chain.request().url)) retry.awaitAttempt { chain.call().isCanceled() }
            chain.proceed(chain.request())
        }
        .eventListenerFactory { call ->
            if (!isContent(call.request().url)) EventListener.NONE else object : EventListener() {
                private var successful = false
                private var failed = false
                override fun responseHeadersEnd(call: Call, response: Response) {
                    successful = response.isSuccessful
                    failed = false
                    // Authentication failures retain the upstream CDN-token negotiation.
                    if (response.code == 408 || response.code == 429 || response.code in 500..599) {
                        failed = true
                        retry.failed(IOException("Steam content HTTP ${response.code}"))
                    }
                }
                override fun callFailed(call: Call, ioe: IOException) {
                    if (!failed && !call.isCanceled()) retry.failed(ioe)
                }
                override fun callEnd(call: Call) {
                    // Headers alone are not success: timeouts while reading a chunk body
                    // must contribute to the same failure budget as DNS/connect failures.
                    if (successful && !failed) retry.succeeded()
                }
            }
        }.build()

    private fun isContent(url: HttpUrl): Boolean = CONTENT_PATH.matches(url.encodedPath)

    private companion object {
        val CONTENT_PATH = Regex("/depot/[0-9]+/(?:manifest|chunk)/.*")
    }
}
