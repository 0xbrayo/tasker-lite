package com.taskerlite

import com.taskerlite.yeelight.YeelightClient
import com.taskerlite.yeelight.YeelightDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YeelightClientTest {

    @Test
    fun buildCommand_formatsJsonRpc() {
        val json = YeelightClient.buildCommand(
            id = 1,
            method = "set_power",
            params = listOf("on", "smooth", 500),
        )
        assertTrue(json.contains("\"method\":\"set_power\""))
        assertTrue(json.contains("\"id\":1"))
        assertTrue(json.contains("\"on\""))
        assertTrue(json.contains("500"))
    }

    @Test
    fun buildCommand_setBright() {
        val json = YeelightClient.buildCommand(2, "set_bright", listOf(30, "smooth", 800))
        assertTrue(json.contains("set_bright"))
        assertTrue(json.contains("30"))
    }

    @Test
    fun parseDiscoveryResponse_extractsBulb() {
        val response = """
            HTTP/1.1 200 OK
            Cache-Control: max-age=3600
            Location: yeelight://192.168.1.50:55443
            id: 0x000000000015243f
            model: color
            fw_ver: 18
            power: on
            bright: 100
            name: bedroom
        """.trimIndent().replace("\n", "\r\n")

        val bulb = YeelightDiscovery.parseResponse(response)
        assertNotNull(bulb)
        assertEquals("192.168.1.50", bulb!!.ip)
        assertEquals(55443, bulb.port)
        assertEquals("color", bulb.model)
        assertEquals("bedroom", bulb.name)
        assertEquals("0x000000000015243f", bulb.yeelightId)
    }

    @Test
    fun parseDiscoveryResponse_defaultNameFromIp() {
        val response = """
            HTTP/1.1 200 OK
            Location: yeelight://10.0.0.12:55443
            id: 0xabc
            model: mono
        """.trimIndent()

        val bulb = YeelightDiscovery.parseResponse(response)
        assertNotNull(bulb)
        assertEquals("Bulb 12", bulb!!.name)
    }
}
