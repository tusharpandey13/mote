package dev.mote.remote.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An `err` from the helper (code per docs/protocol.md), or a local protocol failure. */
class HelperError(val code: String, message: String, val retryAfter: Int? = null) : IOException(message)

/** Shared client: no Origin header (native client), WebSocket ping every 20 s so a dead Mac is noticed in ~40 s. */
fun helperHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.SECONDS)
    .pingInterval(20, TimeUnit.SECONDS)
    .build()

/**
 * One WebSocket to one helper, opened up to `hello`. Incoming messages land in [inbox]; the channel
 * closes (with the cause, if any) when the socket goes away. Pure JVM: used by the app and by the
 * integration test against a real `--dry-run` helper.
 */
class WsSession private constructor(
    private val ws: WebSocket,
    val inbox: Channel<Msg>,
    val hello: Msg.Hello,
    val address: String,
) {
    fun send(json: String): Boolean = ws.send(json)
    /** Bytes queued but not yet sent; used to avoid piling up pointer moves on a slow link. */
    fun queued(): Long = ws.queueSize()
    fun close() { ws.close(1000, null); inbox.close() }

    /** Next message that isn't a broadcast `state` / `pong`, or throws [HelperError] on `err`. */
    suspend fun reply(timeoutMs: Long = 5000): Msg {
        while (true) {
            val m = withTimeoutOrNull(timeoutMs) { inbox.receive() } ?: throw HelperError("timeout", "No reply from the Mac")
            when (m) {
                is Msg.State, is Msg.Pong -> continue
                is Msg.Err -> throw HelperError(m.code, m.msg, m.retryAfter)
                else -> return m
            }
        }
    }

    /** `pair` with the 6-digit code; returns the device id and token (shown once). */
    suspend fun pair(code: String, name: String): Msg.Paired {
        send(Proto.pair(code, name))
        return reply() as? Msg.Paired ?: throw HelperError("bad", "Unexpected reply to pair")
    }

    /** HMAC challenge: [sign] maps the hello nonce to hex(HMAC-SHA256(token, nonce)). */
    suspend fun auth(deviceId: String, sign: (String) -> String): Msg.Authed {
        send(Proto.auth(deviceId, sign(hello.nonce)))
        return reply() as? Msg.Authed ?: throw HelperError("bad", "Unexpected reply to auth")
    }

    companion object {
        fun url(host: String, port: Int): String {
            val h = if (':' in host && !host.startsWith("[")) "[${host.substringBefore('%')}]" else host
            return "ws://$h:$port/ws"
        }

        /** Connects and waits for `hello`. Throws [IOException] if the host doesn't answer in time. */
        suspend fun open(http: OkHttpClient, host: String, port: Int, timeoutMs: Long = 4000): WsSession {
            val inbox = Channel<Msg>(Channel.UNLIMITED)
            val ws = http.newWebSocket(Request.Builder().url(url(host, port)).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { Proto.parse(text)?.let { inbox.trySend(it) } }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    inbox.close(IOException(t.message ?: "connection failed", t))
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); inbox.close() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { inbox.close() }
            })
            val first = try {
                withTimeoutOrNull(timeoutMs) { inbox.receive() }
            } catch (e: Exception) {
                ws.cancel(); throw e as? IOException ?: IOException(e.message, e)
            }
            val hello = first as? Msg.Hello ?: run { ws.cancel(); throw IOException("No Mote Helper at $host:$port") }
            if (hello.v != Proto.VERSION) { ws.cancel(); throw HelperError("version", "Mote Helper speaks protocol v${hello.v}; update it") }
            return WsSession(ws, inbox, hello, host)
        }
    }
}

/** Result of a successful pairing: what the app stores (token goes to [Vault]). */
class Pairing(val hello: Msg.Hello, val address: String, val deviceId: String, val token: ByteArray) {
    /** hostId for helpers before 1.1 (no hostId in hello): fall back to the address. */
    val hostId: String get() = hello.hostId ?: "addr:$address"
}

/** Opens [host], pairs with [code] and closes. The caller then stores the credential and connects normally. */
suspend fun pairWith(http: OkHttpClient, host: String, port: Int, code: String, deviceName: String): Pairing {
    val s = WsSession.open(http, host, port)
    try {
        val p = s.pair(code, deviceName)
        val token = Proto.hexToBytes(p.token)?.takeIf { it.size == 32 } ?: throw HelperError("bad", "Malformed token")
        return Pairing(s.hello, host, p.id, token)
    } finally {
        s.close()
    }
}
