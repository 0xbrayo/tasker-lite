package com.taskerlite.yeelight

import android.util.Log
import com.taskerlite.data.Bulb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.delay

/**
 * Discovers Yeelight / Xiaomi LAN-enabled bulbs via SSDP-like UDP on port 1982.
 *
 * Bulbs answer M-SEARCH with a unicast response to the source port, so a plain
 * [DatagramSocket] is enough. Multicast join is optional (for passive NOTIFY ads).
 */
object YeelightDiscovery {

    private const val TAG = "YeelightDiscovery"
    private const val MULTICAST_ADDR = "239.255.255.250"
    private const val MULTICAST_PORT = 1982
    private val CHARSET: Charset = Charsets.UTF_8

    private val SEARCH = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1982\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "ST: wifi_bulb\r\n" +
            "\r\n"
        )

    suspend fun discover(context: Context? = null, timeoutMs: Long = 4_000): List<Bulb> = withContext(Dispatchers.IO) {
        var multicastLock: WifiManager.MulticastLock? = null
        if (context != null) {
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("taskerlite:discovery")
                multicastLock?.setReferenceCounted(false)
                multicastLock?.acquire()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acquire MulticastLock", e)
            }
        }

        try {
            val found = ConcurrentHashMap<String, Bulb>()

            // Primary: ephemeral DatagramSocket — MulticastSocket() is already bound,
            // so never call bind() on it (that caused "already bound").
            runCatching {
                searchWithDatagramSocket(found, timeoutMs)
            }.onFailure { Log.w(TAG, "Datagram discovery failed", it) }

            // Optional: also listen on multicast for passive NOTIFY advertisements
            if (found.isEmpty()) {
                runCatching {
                    searchWithMulticastSocket(found, timeoutMs)
                }.onFailure { Log.w(TAG, "Multicast discovery failed", it) }
            }

            Log.i(TAG, "Discovered ${found.size} bulb(s)")
            found.values.toList()
        } finally {
            try {
                if (multicastLock?.isHeld == true) multicastLock?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing MulticastLock", e)
            }
        }
    }

    private suspend fun searchWithDatagramSocket(
        found: ConcurrentHashMap<String, Bulb>,
        timeoutMs: Long,
    ) {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.reuseAddress = true
            socket.soTimeout = 400

            val group = InetAddress.getByName(MULTICAST_ADDR)
            val data = SEARCH.toByteArray(CHARSET)

            // Retransmit — Wi‑Fi UDP is lossy
            repeat(4) {
                socket.send(DatagramPacket(data, data.size, group, MULTICAST_PORT))
                if (it < 3) delay(120)
            }

            receiveUntil(socket, found, timeoutMs)
        }
    }

    private suspend fun searchWithMulticastSocket(
        found: ConcurrentHashMap<String, Bulb>,
        timeoutMs: Long,
    ) {
        // null bind address → unbound socket; we bind once ourselves with reuse
        MulticastSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 400
            socket.bind(InetSocketAddress(0))

            tryJoinMulticast(socket)

            val group = InetAddress.getByName(MULTICAST_ADDR)
            val data = SEARCH.toByteArray(CHARSET)
            repeat(3) {
                socket.send(DatagramPacket(data, data.size, group, MULTICAST_PORT))
                if (it < 2) delay(120)
            }

            receiveUntil(socket, found, timeoutMs)
        }
    }

    private fun receiveUntil(
        socket: DatagramSocket,
        found: ConcurrentHashMap<String, Bulb>,
        timeoutMs: Long,
    ) {
        val buf = ByteArray(2048)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length, CHARSET)
                parseResponse(text)?.let { bulb ->
                    val key = bulb.yeelightId ?: bulb.ip
                    found.putIfAbsent(key, bulb)
                    Log.d(TAG, "Found bulb ${bulb.name} @ ${bulb.ip}")
                }
            } catch (_: SocketTimeoutException) {
                // keep waiting until deadline
            }
        }
    }

    fun parseResponse(text: String): Bulb? {
        if (!text.contains("yeelight://", ignoreCase = true) &&
            !text.contains("wifi_bulb", ignoreCase = true) &&
            !text.startsWith("HTTP/1.1 200", ignoreCase = true) &&
            !text.startsWith("NOTIFY", ignoreCase = true)
        ) {
            if (!text.contains("Location:", ignoreCase = true)) return null
        }

        val headers = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
            }
        }

        val location = headers["location"] ?: return null
        // yeelight://192.168.1.239:55443
        val hostPort = location
            .removePrefix("yeelight://")
            .removePrefix("Yeelight://")
        val parts = hostPort.split(":")
        val ip = parts.getOrNull(0) ?: return null
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 55443
        val id = headers["id"]
        val model = headers["model"]
        val name = headers["name"]
            ?.takeIf { it.isNotBlank() }
            ?: "Bulb ${ip.substringAfterLast('.')}"

        return Bulb(
            name = name,
            ip = ip,
            port = port,
            model = model,
            yeelightId = id,
            isDefault = false,
        )
    }

    private fun tryJoinMulticast(socket: MulticastSocket) {
        try {
            val group = InetAddress.getByName(MULTICAST_ADDR)
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            var joined = false
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                try {
                    socket.joinGroup(InetSocketAddress(group, MULTICAST_PORT), ni)
                    joined = true
                } catch (_: Exception) {
                }
            }
            if (!joined) {
                @Suppress("DEPRECATION")
                socket.joinGroup(group)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not join multicast group (search still sent)", e)
        }
    }
}
