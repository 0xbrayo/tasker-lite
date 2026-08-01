package com.taskerlite

import com.taskerlite.miio.MiioProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiioProtocolTest {

    private val tokenHex = "257692bc6f7d1a3769bf34c54a390785"
    private val token = MiioProtocol.parseToken(tokenHex)

    @Test
    fun parseToken_length16() {
        assertEquals(16, token.size)
    }

    @Test
    fun encryptDecrypt_roundTrip() {
        val plain = """{"id":1,"method":"miIO.info","params":[]}""" + "\u0000"
        val encrypted = MiioProtocol.encrypt(plain.toByteArray(Charsets.UTF_8), token)
        val decrypted = MiioProtocol.decrypt(encrypted, token)
        assertEquals(plain, decrypted.toString(Charsets.UTF_8))
    }

    @Test
    fun encrypt_matchesPythonMiioVector() {
        val plain = """{"id":1,"method":"miIO.info","params":[]}""" + "\u0000"
        val encrypted = MiioProtocol.encrypt(plain.toByteArray(Charsets.UTF_8), token)
        // Produced by python-miio Utils.encrypt with same token/plaintext
        val expected = "39e582cba97727f4f6baada41698db0fbc346db7210befc9840c696a3707098aa334df2d74732143b25ed0b532a48c2c"
        assertEquals(expected, encrypted.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun buildPacket_hasMagicAndLength() {
        val deviceId = byteArrayOf(0x32, 0x96.toByte(), 0x5d, 0x83.toByte())
        val payload = MiioProtocol.encrypt(
            """{"id":1,"method":"miIO.info","params":[]}""".toByteArray() + 0,
            token,
        )
        val packet = MiioProtocol.buildPacket(deviceId, 1000, payload, token)
        assertEquals(0x21.toByte(), packet[0])
        assertEquals(0x31.toByte(), packet[1])
        val length = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        assertEquals(packet.size, length)
        assertTrue(packet.size > 32)
        assertArrayEquals(deviceId, packet.copyOfRange(8, 12))
    }

    @Test
    fun keyIv_stable() {
        val (k1, iv1) = MiioProtocol.keyAndIv(token)
        val (k2, iv2) = MiioProtocol.keyAndIv(token)
        assertArrayEquals(k1, k2)
        assertArrayEquals(iv1, iv2)
        assertEquals(16, k1.size)
        assertEquals(16, iv1.size)
    }
}
