package com.taskerlite.miio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Minimal miIO UDP client (port 54321) matching python-miio's encryption.
 */
class MiioProtocol(
    private val host: String,
    private val tokenHex: String,
    private val port: Int = 54321,
    private val timeoutMs: Int = 4_000,
) {
    private val token: ByteArray = parseToken(tokenHex)
    private val nextId = AtomicInteger(Random.nextInt(1, 1000))

    private var deviceId: ByteArray? = null
    private var deviceStamp: Int = 0

    suspend fun send(method: String, params: JsonElement? = null): JsonElement =
        withContext(Dispatchers.IO) {
            ensureHandshake()
            val id = nextId.incrementAndGet()
            val request = buildJsonObject {
                put("id", id)
                put("method", method)
                if (params != null) {
                    put("params", params)
                } else {
                    put("params", buildJsonArray { })
                }
            }
            val response = exchange(request)
            val obj = response.jsonObject
            val error = obj["error"]?.jsonObject
            if (error != null) {
                val code = error["code"]?.jsonPrimitive?.contentOrNull
                val message = error["message"]?.jsonPrimitive?.contentOrNull
                error("miIO error $code: $message")
            }
            obj["result"] ?: obj
        }

    suspend fun handshake(): Unit = withContext(Dispatchers.IO) {
        doHandshake()
    }

    private fun ensureHandshake() {
        if (deviceId == null) doHandshake()
    }

    private fun doHandshake(retries: Int = 3) {
        var last: Exception? = null
        repeat(retries) {
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    val hello = HELLO_PACKET
                    val addr = InetAddress.getByName(host)
                    socket.send(DatagramPacket(hello, hello.size, addr, port))
                    val buf = ByteArray(1024)
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    if (data.size < 32 || data[0] != 0x21.toByte() || data[1] != 0x31.toByte()) {
                        error("Invalid hello response (${data.size} bytes)")
                    }
                    deviceId = data.copyOfRange(8, 12)
                    deviceStamp = readUInt32(data, 12)
                    Log.d(TAG, "Handshake OK id=${deviceId!!.toHex()} stamp=$deviceStamp")
                    return
                }
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "Handshake attempt failed", e)
            }
        }
        throw last ?: IllegalStateException("Handshake failed")
    }

    private fun exchange(request: JsonObject, retries: Int = 3): JsonElement {
        var last: Exception? = null
        repeat(retries) { attempt ->
            try {
                if (attempt > 0) {
                    deviceId = null
                    doHandshake()
                }
                val stamp = deviceStamp + 1
                deviceStamp = stamp
                // Compact JSON + null terminator (python-miio compatible)
                val payloadJson = request.toString() + "\u0000"
                val encrypted = encrypt(payloadJson.toByteArray(Charsets.UTF_8), token)
                val packet = buildPacket(deviceId!!, stamp, encrypted, token)
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    val addr = InetAddress.getByName(host)
                    socket.send(DatagramPacket(packet, packet.size, addr, port))
                    val buf = ByteArray(4096)
                    val recv = DatagramPacket(buf, buf.size)
                    socket.receive(recv)
                    val data = recv.data.copyOf(recv.length)
                    return parseResponse(data)
                }
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "send attempt ${attempt + 1} failed", e)
                nextId.addAndGet(100)
            }
        }
        throw last ?: IllegalStateException("No response from device")
    }

    private fun parseResponse(data: ByteArray): JsonElement {
        if (data.size < 32) error("Short response")
        deviceStamp = readUInt32(data, 12)
        if (data.size == 32) {
            return JsonNull
        }
        val encrypted = data.copyOfRange(32, data.size)
        val plain = decrypt(encrypted, token)
            .toString(Charsets.UTF_8)
            .trimEnd('\u0000')
            .trim()
        Log.d(TAG, "← $plain")
        return json.parseToJsonElement(plain)
    }

    companion object {
        private const val TAG = "MiioProtocol"

        private val HELLO_PACKET: ByteArray = byteArrayOf(
            0x21, 0x31, 0x00, 0x20,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            // python-miio uses default dumps with spaces
            prettyPrint = false
        }

        fun parseToken(tokenHex: String): ByteArray {
            val cleaned = tokenHex.trim().lowercase().replace(" ", "")
            require(cleaned.length == 32) { "Token must be 32 hex characters" }
            return cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        fun keyAndIv(token: ByteArray): Pair<ByteArray, ByteArray> {
            val key = md5(token)
            val iv = md5(key + token)
            return key to iv
        }
        
        private val encryptCipher = ThreadLocal.withInitial { Cipher.getInstance("AES/CBC/PKCS5Padding") }
        private val decryptCipher = ThreadLocal.withInitial { Cipher.getInstance("AES/CBC/PKCS5Padding") }

        fun encrypt(plaintext: ByteArray, token: ByteArray): ByteArray {
            val (key, iv) = keyAndIv(token)
            val cipher = encryptCipher.get()!!
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(plaintext)
        }

        fun decrypt(ciphertext: ByteArray, token: ByteArray): ByteArray {
            val (key, iv) = keyAndIv(token)
            val cipher = decryptCipher.get()!!
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(ciphertext)
        }

        fun buildPacket(
            deviceId: ByteArray,
            stamp: Int,
            encrypted: ByteArray,
            token: ByteArray,
        ): ByteArray {
            require(deviceId.size == 4)
            val length = 32 + encrypted.size
            val header = ByteArray(16)
            header[0] = 0x21
            header[1] = 0x31
            header[2] = ((length ushr 8) and 0xFF).toByte()
            header[3] = (length and 0xFF).toByte()
            // unknown 4 bytes = 0
            System.arraycopy(deviceId, 0, header, 8, 4)
            writeUInt32(header, 12, stamp)

            val checksum = md5(header + token + encrypted)
            return header + checksum + encrypted
        }

        fun md5(data: ByteArray): ByteArray =
            MessageDigest.getInstance("MD5").digest(data)

        private fun readUInt32(data: ByteArray, offset: Int): Int {
            return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
        }

        private fun writeUInt32(data: ByteArray, offset: Int, value: Int) {
            data[offset] = ((value ushr 24) and 0xFF).toByte()
            data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            data[offset + 3] = (value and 0xFF).toByte()
        }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }
    }
}

object MiotProps {
    fun get(siid: Int, piid: Int, did: String = "p"): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("did", did)
                put("siid", siid)
                put("piid", piid)
            }
        )
    }

    fun set(siid: Int, piid: Int, value: JsonElement, did: String = "p"): JsonArray =
        buildJsonArray {
            add(setProp(siid, piid, value, did))
        }

    fun setProp(siid: Int, piid: Int, value: JsonElement, did: String = "p"): JsonObject =
        buildJsonObject {
            put("did", did)
            put("siid", siid)
            put("piid", piid)
            put("value", value)
        }

    fun setMultiple(vararg props: JsonObject): JsonArray = buildJsonArray {
        props.forEach { add(it) }
    }

    fun setBool(siid: Int, piid: Int, value: Boolean) =
        set(siid, piid, JsonPrimitive(value))

    fun setInt(siid: Int, piid: Int, value: Int) =
        set(siid, piid, JsonPrimitive(value))
        
    fun propBool(siid: Int, piid: Int, value: Boolean) =
        setProp(siid, piid, JsonPrimitive(value), "p")
        
    fun propInt(siid: Int, piid: Int, value: Int) =
        setProp(siid, piid, JsonPrimitive(value), "p")
}
