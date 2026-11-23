package com.codleander.gamebooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * DNS-only tunnel behind the optional private DNS setting.
 *
 * The tunnel owns one virtual address and one DNS server address, and only the DNS server
 * address is routed inside it. Android hands that address to every app as the system
 * resolver, so lookups are captured here, forwarded to the chosen DNS-over-TLS resolver and
 * written back as a normal UDP answer. Everything else, game traffic included, stays on the
 * untouched physical connection.
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.codleander.gamebooster.DNS_START"
        const val ACTION_STOP = "com.codleander.gamebooster.DNS_STOP"
        private const val CHANNEL_ID = "private_dns"
        private const val NOTIFICATION_ID = 1002

        private const val TUN_IP = "10.111.222.2"
        private const val TUN_DNS = "10.111.222.1"
        private const val MTU = 1500
        private const val DNS_PORT = 53
        private const val UPSTREAM_TIMEOUT_MS = 4000
        private const val WORKERS = 4
    }

    private var tunnel: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null
    private var pump: Thread? = null
    private val workers = Executors.newFixedThreadPool(WORKERS)
    private val writeLock = Any()
    private val sessionLock = Any()
    private var session: PrivateDns.Session? = null
    private var sessionKey = ""

    @Volatile
    private var running = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (PrivateDns.resolver(this) == null) {
                    Prefs.setDnsEnabled(this, false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!startTunnel()) {
                    Prefs.setDnsEnabled(this, false)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(): Boolean {
        if (running) return true
        val builder = Builder()
            .setSession(getString(R.string.private_dns))
            .setMtu(MTU)
            .addAddress(TUN_IP, 32)
            .addDnsServer(TUN_DNS)
            .addRoute(TUN_DNS, 32)
            .setBlocking(true)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        val descriptor = runCatching { builder.establish() }.getOrNull() ?: return false

        tunnel = descriptor
        output = runCatching { FileOutputStream(descriptor.fileDescriptor) }.getOrNull()
        if (output == null) {
            stopTunnel()
            return false
        }
        running = true
        pump = Thread({ readLoop(descriptor) }, "dns-tunnel").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun readLoop(descriptor: ParcelFileDescriptor) {
        val input = FileInputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(MTU)
        while (running) {
            val length = try {
                input.read(buffer)
            } catch (error: Exception) {
                break
            }
            if (length < 0) break
            if (length == 0) continue
            val query = parse(buffer, length) ?: continue
            runCatching { workers.execute { answer(query) } }
        }
        runCatching { input.close() }
    }

    /** Pulls the source of a UDP/53 packet out of a captured IPv4 frame. */
    private fun parse(packet: ByteArray, length: Int): Query? {
        if (length < 29) return null
        if ((packet[0].toInt() and 0xF0) != 0x40) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength + 8) return null
        if (packet[9].toInt() != 17) return null
        val destinationPort = ((packet[headerLength + 2].toInt() and 0xFF) shl 8) or
            (packet[headerLength + 3].toInt() and 0xFF)
        if (destinationPort != DNS_PORT) return null
        val udpLength = ((packet[headerLength + 4].toInt() and 0xFF) shl 8) or
            (packet[headerLength + 5].toInt() and 0xFF)
        val payloadLength = udpLength - 8
        if (payloadLength < 12 || headerLength + 8 + payloadLength > length) return null
        val sourcePort = ((packet[headerLength].toInt() and 0xFF) shl 8) or
            (packet[headerLength + 1].toInt() and 0xFF)
        return Query(
            serverIp = packet.copyOfRange(16, 20),
            clientIp = packet.copyOfRange(12, 16),
            clientPort = sourcePort,
            payload = packet.copyOfRange(headerLength + 8, headerLength + 8 + payloadLength)
        )
    }

    /** Reuses one keep-alive TLS connection per resolver, and only per resolver. */
    private fun sessionFor(resolver: PrivateDns.Resolver): PrivateDns.Session {
        val key = resolver.ip + "|" + resolver.host
        synchronized(sessionLock) {
            val current = session
            if (current != null && sessionKey == key) return current
            current?.close()
            val fresh = PrivateDns.Session(resolver) { socket -> protect(socket) }
            session = fresh
            sessionKey = key
            return fresh
        }
    }

    private fun answer(query: Query) {
        val resolver = PrivateDns.resolver(this) ?: return
        val reply = sessionFor(resolver).exchange(query.payload, UPSTREAM_TIMEOUT_MS) ?: return
        if (reply.size < 12) return
        var packet = response(query, reply)
        if (packet.size > MTU) packet = response(query, truncated(reply))
        val stream = output ?: return
        synchronized(writeLock) {
            runCatching {
                stream.write(packet)
                stream.flush()
            }
        }
    }

    /**
     * An answer too large for one tunnel frame is cut back to its question with the TC bit
     * set, which is the standard way of telling a resolver to retry with a smaller payload.
     */
    private fun truncated(reply: ByteArray): ByteArray {
        var end = 12
        while (end < reply.size && reply[end].toInt() != 0) {
            end += (reply[end].toInt() and 0xFF) + 1
        }
        end = (end + 1 + 4).coerceAtMost(reply.size)
        val head = reply.copyOf(end)
        head[2] = (head[2].toInt() or 0x82).toByte()
        head[3] = (head[3].toInt() or 0x80).toByte()
        for (index in 6 until 12) head[index] = 0
        return head
    }

    /** Builds the IPv4 + UDP answer that goes back to the app that asked. */
    private fun response(query: Query, payload: ByteArray): ByteArray {
        val ipLength = 20
        val udpLength = 8 + payload.size
        val total = ipLength + udpLength
        val packet = ByteArray(total)

        packet[0] = 0x45
        packet[2] = ((total ushr 8) and 0xFF).toByte()
        packet[3] = (total and 0xFF).toByte()
        packet[6] = 0x40
        packet[8] = 64
        packet[9] = 17
        System.arraycopy(query.serverIp, 0, packet, 12, 4)
        System.arraycopy(query.clientIp, 0, packet, 16, 4)

        packet[20] = ((DNS_PORT ushr 8) and 0xFF).toByte()
        packet[21] = (DNS_PORT and 0xFF).toByte()
        packet[22] = ((query.clientPort ushr 8) and 0xFF).toByte()
        packet[23] = (query.clientPort and 0xFF).toByte()
        packet[24] = ((udpLength ushr 8) and 0xFF).toByte()
        packet[25] = (udpLength and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, 28, payload.size)

        val headerSum = checksum(packet, 0, ipLength, 0)
        packet[10] = ((headerSum ushr 8) and 0xFF).toByte()
        packet[11] = (headerSum and 0xFF).toByte()

        val udpSum = udpChecksum(query.serverIp, query.clientIp, packet, ipLength, udpLength)
        val sum = if (udpSum == 0) 0xFFFF else udpSum
        packet[26] = ((sum ushr 8) and 0xFF).toByte()
        packet[27] = (sum and 0xFF).toByte()
        return packet
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int, initial: Int): Int {
        var sum = initial
        var index = offset
        val end = offset + length
        while (index + 1 < end) {
            sum += ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
            index += 2
        }
        if (index < end) sum += (data[index].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun udpChecksum(
        sourceIp: ByteArray,
        destinationIp: ByteArray,
        packet: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = 0
        for (ip in arrayOf(sourceIp, destinationIp)) {
            var index = 0
            while (index < 4) {
                sum += ((ip[index].toInt() and 0xFF) shl 8) or (ip[index + 1].toInt() and 0xFF)
                index += 2
            }
        }
        sum += 17
        sum += length
        return checksum(packet, offset, length, sum)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.dns_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.private_dns_sub)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val open = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val off = PendingIntent.getService(
            this,
            1,
            Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_network)
            .setContentTitle(getString(R.string.private_dns))
            .setContentText(getString(R.string.dns_notif_active, PrivateDns.label(this)))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_close),
                    getString(R.string.dns_notif_stop),
                    off
                ).build()
            )
            .build()
    }

    private fun stopTunnel() {
        running = false
        runCatching { output?.close() }
        output = null
        runCatching { tunnel?.close() }
        tunnel = null
        pump = null
        synchronized(sessionLock) {
            session?.close()
            session = null
        }
        workers.shutdownNow()
        Prefs.setDnsEnabled(this, false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private data class Query(
        val serverIp: ByteArray,
        val clientIp: ByteArray,
        val clientPort: Int,
        val payload: ByteArray
    )
}
