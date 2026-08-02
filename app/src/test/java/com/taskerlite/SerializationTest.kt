package com.taskerlite

import com.taskerlite.data.AppState
import com.taskerlite.data.Bulb
import com.taskerlite.data.LightAction
import com.taskerlite.data.Rule
import com.taskerlite.data.defaultRules
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun roundTrip_appState_withDefaultRules() {
        val state = AppState(
            bulbs = listOf(
                Bulb(name = "Bedroom", ip = "192.168.1.10", isDefault = true)
            ),
            rules = defaultRules(),
            serviceEnabled = true,
        )
        val encoded = json.encodeToString(state)
        val decoded = json.decodeFromString<AppState>(encoded)
        assertEquals(1, decoded.bulbs.size)
        assertEquals("192.168.1.10", decoded.bulbs.first().ip)
        assertEquals(defaultRules().size, decoded.rules.size)
        assertTrue(decoded.serviceEnabled)
        assertTrue(decoded.rules.first().action is LightAction.OnlyIfOn)
    }

    @Test
    fun roundTrip_powerAction() {
        val rule = Rule(
            event = "ALARM_TRIGGERED",
            action = LightAction.Power(on = false, durationMs = 800),
        )
        val encoded = json.encodeToString(rule)
        val decoded = json.decodeFromString<Rule>(encoded)
        val action = decoded.action as LightAction.Power
        assertEquals(false, action.on)
        assertEquals(800, action.durationMs)
    }
}
