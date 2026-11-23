package com.codleander.gamebooster

import android.content.Context
import android.content.Intent
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Optional private DNS.
 *
 * Android only lets system apps write the global "Private DNS" setting, so an ordinary app
 * changes DNS the same way every DNS app does: [DnsVpnService] brings up a tunnel that
 * advertises a single virtual DNS address, only that address is routed into the tunnel, and
 * every query captured there is forwarded to a DNS-over-TLS resolver. Game traffic never
 * enters the tunnel, so bandwidth and ping are untouched.
 */
object PrivateDns {
    const val PRESET_OFF = -1
    const val PRESET_CLOUDFLARE = 0
    const val PRESET_GOOGLE = 1
    const val PRESET_QUAD9 = 2
    const val PRESET_ADGUARD = 3
    const val PRESET_CUSTOM = 4

    /** An IP (used for the socket) and the TLS hostname (used for SNI and certificate checks). */
    data class Resolver(val preset: Int, val ip: String, val host: String)

    private val builtIn = listOf(
        Resolver(PRESET_CLOUDFLARE, "1.1.1.1", "one.one.one.one"),
        Resolver(PRESET_GOOGLE, "8.8.8.8", "dns.google"),
        Resolver(PRESET_QUAD9, "9.9.9.9", "dns.quad9.net"),
        Resolver(PRESET_ADGUARD, "94.140.14.14", "dns.adguard-dns.com")
    )

    fun builtInResolvers(): List<Resolver> = builtIn

    fun titleRes(preset: Int): Int = when (preset) {
        PRESET_CLOUDFLARE -> R.string.dns_cloudflare
        PRESET_GOOGLE -> R.string.dns_google
        PRESET_QUAD9 -> R.string.dns_quad9
        PRESET_ADGUARD -> R.string.dns_adguard
        PRESET_CUSTOM -> R.string.dns_custom
        else -> R.string.dns_off
    }

    /** The resolver the user picked, or null when private DNS is off or still unset. */
    fun resolver(context: Context): Resolver? = when (val preset = Prefs.dnsPreset(context)) {
        PRESET_OFF -> null
        PRESET_CUSTOM -> {
            val ip = Prefs.dnsIp(context)
            val host = Prefs.dnsHost(context)
            if (isValidIp(ip) && isValidHost(host)) Resolver(PRESET_CUSTOM, ip, host) else null
        }
        else -> builtIn.firstOrNull { it.preset == preset }
    }

    /** The resolver serving lookups right now, or null when private DNS is off. */
    fun active(context: Context): Resolver? =
        if (Prefs.dnsEnabled(context)) resolver(context) else null

    fun label(context: Context): String =
        context.getString(titleRes(active(context)?.preset ?: PRESET_OFF))

    fun enable(context: Context, preset: Int, ip: String = "", host: String = "") {
        Prefs.setDnsPreset(context, preset)
        if (preset == PRESET_CUSTOM) {
            Prefs.setDnsIp(context, ip.trim())
            Prefs.setDnsHost(context, host.trim())
        }
        Prefs.setDnsEnabled(context, true)
        ensureStarted(context)
    }

    fun disable(context: Context) {
        Prefs.setDnsEnabled(context, false)
        runCatching { context.stopService(Intent(context, DnsVpnService::class.java)) }
    }

    /**
     * Brings the tunnel back after a restart or a process death. It is a no-op while the
     * service is already running, and it clears the stored state when the resolver is gone.
     */
    fun ensureStarted(context: Context) {
        if (!Prefs.dnsEnabled(context)) return
        if (resolver(context) == null) {
            Prefs.setDnsEnabled(context, false)
            return
        }
        val intent = Intent(context, DnsVpnService::class.java)
        intent.action = DnsVpnService.ACTION_START
        runCatching { context.startService(intent) }
    }

    /**
     * A keep-alive DNS-over-TLS client.
     *
     * One TLS connection is reused for every query, so a lookup costs one round trip instead
     * of a handshake plus a round trip. A connection the resolver closed is detected on the
     * next exchange, dropped and re-opened once.
     */
    class Session(
        private val resolver: Resolver,
        private val protect: ((Socket) -> Boolean)? = null
    ) {
        private var socket: SSLSocket? = null
        private val lock = Any()

        fun exchange(query: ByteArray, timeoutMs: Int): ByteArray? = synchronized(lock) {
            exchangeLocked(query, timeoutMs)
        }

        private fun exchangeLocked(query: ByteArray, timeoutMs: Int): ByteArray? {
            var attempt = 0
            while (attempt < 2) {
                val connection = connection(timeoutMs) ?: return null
                val answer = runCatching { transfer(connection, query, timeoutMs) }.getOrNull()
                if (answer != null) return answer
                close()
                attempt++
            }
            return null
        }

        fun close() {
            runCatching { socket?.close() }
            socket = null
        }

        private fun connection(timeoutMs: Int): SSLSocket? {
            socket?.let { if (!it.isClosed) return it }
            return try {
                val plain = Socket()
                protect?.invoke(plain)
                plain.connect(InetSocketAddress(resolver.ip, 853), timeoutMs)
                plain.soTimeout = timeoutMs
                plain.tcpNoDelay = true
                val secure = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plain, resolver.host, 853, true) as SSLSocket
                secure.useClientMode = true
                secure.startHandshake()
                socket = secure
                secure
            } catch (error: IOException) {
                null
            } catch (error: SecurityException) {
                null
            }
        }

        /** DNS-over-TLS frames every message with a two-byte big-endian length. */
        private fun transfer(connection: SSLSocket, query: ByteArray, timeoutMs: Int): ByteArray? {
            connection.soTimeout = timeoutMs
            val out = connection.outputStream
            out.write((query.size ushr 8) and 0xFF)
            out.write(query.size and 0xFF)
            out.write(query)
            out.flush()

            val input = connection.inputStream
            val high = input.read()
            val low = input.read()
            if (high < 0 || low < 0) return null
            val length = (high shl 8) or low
            if (length < 12 || length > 8192) return null
            val answer = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(answer, read, length - read)
                if (count <= 0) return null
                read += count
            }
            return answer
        }
    }

    fun isValidIp(value: String): Boolean {
        val parts = value.trim().split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return@all false
            val octet = part.toIntOrNull() ?: return@all false
            octet in 0..255
        }
    }

    /** A DoT hostname: certificate validation needs a name, a bare IP is not enough. */
    fun isValidHost(value: String): Boolean {
        val host = value.trim()
        if (host.isEmpty() || host.length > 253 || isValidIp(host)) return false
        if (!host.contains('.')) return false
        return host.split('.').all { part ->
            part.isNotEmpty() && part.length <= 63 &&
                part.all { it.isLetterOrDigit() || it == '-' } &&
                !part.startsWith('-') && !part.endsWith('-')
        }
    }
}
