package dev.silksong.launcher

import `in`.dragonbra.javasteam.steam.cdn.Client
import `in`.dragonbra.javasteam.steam.cdn.Server
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.types.ChunkData
import `in`.dragonbra.javasteam.util.compat.Consumer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private class Clock {
    var nanos = 0L
    val waits = mutableListOf<Long>()
    val retry = SteamContentRetry({ nanos }) { ms ->
        check(ms in 1..100)
        waits += ms
        nanos += TimeUnit.MILLISECONDS.toNanos(ms)
    }
}

private fun request(transport: SteamContentTransport, path: String = "/depot/1/chunk/a") =
    transport.httpClient.newCall(Request.Builder().url("https://content.invalid$path").build())

/** Only a loopback fixture is contacted; no Steam login or external HTTP is performed. */
private fun reply(transport: SteamContentTransport, code: Int, declared: Int = 4, body: String = "data") {
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
        socket.soTimeout = 5_000
        val server = thread(isDaemon = true) {
            socket.accept().use { connection ->
                connection.soTimeout = 5_000
                val input = connection.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                connection.getOutputStream().write(("HTTP/1.1 $code Fixture\r\nContent-Length: $declared\r\nRetry-After: 1\r\nConnection: close\r\n\r\n$body").toByteArray())
            }
        }
        try {
            transport.httpClient.newCall(Request.Builder()
                .url("http://127.0.0.1:${socket.localPort}/depot/1/chunk/a").build()).execute().use { it.body.bytes() }
        } finally {
            server.join(5_000)
            check(!server.isAlive)
        }
    }
}

fun main() {
    var cases = 0
    fun test(name: String, run: () -> Unit) { run(); cases++; println("PASS: $name") }
    test("healthy requests retain full parallelism without artificial waits") {
        val clock = Clock()
        repeat(100) { clock.retry.awaitAttempt { false } }
        check(clock.waits.isEmpty())
    }
    test("failed workers share exponentially spaced probes rather than rotating hosts in a spin") {
        val clock = Clock()
        clock.retry.failed(UnknownHostException("fixture"))
        clock.retry.awaitAttempt { false }
        check(clock.nanos == TimeUnit.MILLISECONDS.toNanos(500))
        clock.retry.failed(UnknownHostException("different CDN"))
        val before = clock.nanos
        repeat(8) { clock.retry.awaitAttempt { false } }
        check(clock.nanos - before == TimeUnit.SECONDS.toNanos(8))
        clock.retry.succeeded()
        val waits = clock.waits.size
        repeat(20) { clock.retry.awaitAttempt { false } }
        check(clock.waits.size == waits)
    }
    test("repeated DNS failures stop with an actionable error and a fresh session remains usable") {
        val clock = Clock()
        repeat(12) { clock.retry.failed(UnknownHostException("secret CDN URL")) }
        val error = runCatching { clock.retry.requireAvailable() }.exceptionOrNull()
        check(error is IOException && "Private DNS" in error.message.orEmpty())
        check("secret" !in error.message.orEmpty() && "resumes" in error.message.orEmpty())
        Clock().retry.requireAvailable()
    }
    test("retry backoff remains cancellable between short waits") {
        val clock = Clock()
        clock.retry.failed(UnknownHostException())
        val failure = runCatching { clock.retry.awaitAttempt { clock.waits.isNotEmpty() } }.exceptionOrNull()
        check(failure is InterruptedIOException && clock.waits.size == 1)
    }
    test("interruption preserves the worker interrupt flag") {
        val retry = SteamContentRetry({ 0L }) { throw InterruptedException("fixture") }
        retry.failed(UnknownHostException())
        try {
            check(runCatching { retry.awaitAttempt { false } }.exceptionOrNull() is InterruptedIOException)
            check(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    test("actual OkHttp DNS failures consume the budget across advertised hostnames") {
        val clock = Clock()
        var lookups = 0
        val transport = SteamContentTransport(OkHttpClient.Builder().retryOnConnectionFailure(false)
            .dns { lookups++; throw UnknownHostException("fixture DNS") }.build(), clock.retry)
        try {
            repeat(12) { n ->
                val call = transport.httpClient.newCall(Request.Builder().url("https://cdn$n.invalid/depot/1/chunk/a").build())
                check(runCatching { call.execute().close() }.exceptionOrNull() is UnknownHostException)
            }
            check(lookups == 12)
            check(runCatching { clock.retry.requireAvailable() }.exceptionOrNull() is IOException)
            check(runCatching { request(transport).execute().close() }.exceptionOrNull() is IOException)
            check(lookups == 12) // No thirteenth network attempt after the terminal failure.
        } finally { transport.httpClient.dispatcher.executorService.shutdownNow() }
    }
    test("non-content discovery failures cannot exhaust the download budget") {
        val clock = Clock()
        val transport = SteamContentTransport(OkHttpClient.Builder().retryOnConnectionFailure(false)
            .dns { throw UnknownHostException("fixture DNS") }.build(), clock.retry)
        try {
            repeat(15) { runCatching { request(transport, "/ISteamDirectory/GetCMList/v1").execute().close() } }
            clock.retry.requireAvailable()
            check(clock.waits.isEmpty())
        } finally { transport.httpClient.dispatcher.executorService.shutdownNow() }
    }
    test("a truncated successful HTTP body counts as failure rather than recovery") {
        val clock = Clock()
        val transport = SteamContentTransport(retry = clock.retry)
        try {
            repeat(11) { clock.retry.failed(UnknownHostException()) }
            check(runCatching { reply(transport, 200, declared = 20, body = "data") }.exceptionOrNull() is IOException)
            check(runCatching { clock.retry.requireAvailable() }.exceptionOrNull() is IOException)
        } finally { transport.httpClient.dispatcher.executorService.shutdownNow() }
    }
    test("a fully read successful chunk clears transient failures") {
        val clock = Clock()
        val transport = SteamContentTransport(retry = clock.retry)
        try {
            repeat(11) { clock.retry.failed(UnknownHostException()) }
            reply(transport, 200)
            clock.retry.failed(UnknownHostException())
            clock.retry.requireAvailable()
        } finally { transport.httpClient.dispatcher.executorService.shutdownNow() }
    }
    test("HTTP service failures count while authentication retains token negotiation") {
        for (code in listOf(408, 429, 503, 403)) {
            val clock = Clock()
            val transport = SteamContentTransport(retry = clock.retry)
            try {
                repeat(11) { clock.retry.failed(UnknownHostException()) }
                reply(transport, code)
                check((runCatching { clock.retry.requireAvailable() }.exceptionOrNull() is IOException) == (code != 403))
            } finally { transport.httpClient.dispatcher.executorService.shutdownNow() }
        }
    }
    test("real JavaSteam chunk downloads use the guarded session HTTP client") {
        val clock = Clock()
        var lookups = 0
        val transport = SteamContentTransport(OkHttpClient.Builder().retryOnConnectionFailure(false)
            .dns { lookups++; throw UnknownHostException("fixture DNS") }.build(), clock.retry)
        val steam = SteamClient(SteamConfiguration.create(Consumer { it.withHttpClient(transport.httpClient) }))
        Client(steam).use { cdn ->
            repeat(12) {
                val error = runBlocking { runCatching {
                    cdn.downloadDepotChunk(1, ChunkData().apply { chunkID = ByteArray(20); compressedLength = 4 },
                        Server.fromHostAndPort("content.invalid", 443), ByteArray(4))
                }.exceptionOrNull() }
                check(error is UnknownHostException)
            }
            check(lookups == 12 && runCatching { clock.retry.requireAvailable() }.isFailure)
        }
    }
    println("$cases content retry checks passed")
}
