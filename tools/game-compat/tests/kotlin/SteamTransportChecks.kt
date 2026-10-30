package dev.silksong.launcher

import `in`.dragonbra.javasteam.steam.cdn.Client
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.coroutines.executeAsync

/** Actual async executor and JavaSteam clients. The interceptor prevents any network traffic. */
private fun asyncProbe(session: SteamSession) = runBlocking {
    val probe = session.steamClient.configuration.httpClient.newBuilder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("Fixture").body("ready".toResponseBody()).build()
    }.build()
    probe.newCall(Request.Builder().url("https://transport.invalid/").build()).executeAsync().use {
        check(it.body.string() == "ready")
    }
}

fun main() {
    var cases = 0
    fun test(name: String, run: () -> Unit) { run(); cases++; println("PASS: $name") }
    test("closing manifest inspection keeps the async executor usable for the payload downloader") {
        SteamSession().use { session ->
            SteamManifestInventory(session).use { }
            session.requireDownloadTransport()
            asyncProbe(session)
        }
    }
    test("independent readers can close and reopen within the same session") {
        SteamSession().use { session ->
            SteamManifestInventory(session).use { first ->
                SteamManifestInventory(session).use { second ->
                    first.close(); first.close()
                    asyncProbe(session)
                    second.close()
                }
            }
            SteamManifestInventory(session).use { asyncProbe(session) }
        }
    }
    test("a failed inspection does not poison a later download in that session") {
        SteamSession().use { session ->
            val failure = java.io.IOException("Fixture inspection failure")
            check(runCatching { SteamManifestInventory(session).use { throw failure } }.exceptionOrNull() === failure)
            asyncProbe(session)
        }
    }
    test("cancellation unwinds the reader without closing the owner's HTTP client") {
        SteamSession().use { session ->
            val cancelled = CancellationException("Fixture cancellation")
            check(runCatching { SteamManifestInventory(session).use { throw cancelled } }.exceptionOrNull() === cancelled)
            asyncProbe(session)
        }
    }
    test("a disposed reader rejects reads before any Steam RPC") {
        SteamSession().use { session ->
            val inventory = SteamManifestInventory(session)
            inventory.close()
            val error = runBlocking { runCatching { inventory.read(9, SteamLibraryRules.Depot(42, 100)) }.exceptionOrNull() }
            check(error is IllegalStateException && error.message == "Manifest inspection is closed.")
            asyncProbe(session)
        }
    }
    test("session shutdown releases the transport once and rejects subsequent work") {
        val session = SteamSession()
        SteamManifestInventory(session).use { }
        asyncProbe(session)
        val http = session.steamClient.configuration.httpClient
        session.close(); session.close()
        check(http.dispatcher.executorService.isShutdown)
        check(runCatching { session.requireDownloadTransport() }.isFailure)
        check(runCatching { SteamManifestInventory(session) }.isFailure)
    }
    test("sessions also release HTTP resources when no manifest reader was needed") {
        val session = SteamSession()
        asyncProbe(session)
        val http = session.steamClient.configuration.httpClient
        session.close()
        check(http.dispatcher.executorService.isShutdown)
    }
    test("closing one account session cannot shut down another session's executor") {
        SteamSession().use { first ->
            SteamSession().use { second ->
                check(first.steamClient.configuration.httpClient.dispatcher !== second.steamClient.configuration.httpClient.dispatcher)
                SteamManifestInventory(first).use { }
                first.close()
                SteamManifestInventory(second).use { }
                asyncProbe(second)
            }
        }
    }
    test("an externally disposed upstream client reproduces the fault and is detected as terminal") {
        SteamSession().use { session ->
            Client(session.steamClient).close()
            val failure = runCatching { asyncProbe(session) }.exceptionOrNull()
            check(failure is java.io.InterruptedIOException && failure.message == "executor rejected")
            val guard = runCatching { session.requireDownloadTransport() }.exceptionOrNull()
            check(guard is IllegalStateException && "Retry" in guard.message.orEmpty())
        }
    }
    println("$cases Steam transport checks passed")
}
