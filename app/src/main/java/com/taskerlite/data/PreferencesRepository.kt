package com.taskerlite.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tasker_lite")

class PreferencesRepository(context: Context) {

    val context: Context = context.applicationContext
    
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val stateKey = stringPreferencesKey("app_state")

    val stateFlow: Flow<AppState> = context.dataStore.data.map { prefs ->
        prefs[stateKey]?.let { decode(it) } ?: AppState()
    }

    suspend fun getState(): AppState = stateFlow.first()

    suspend fun update(transform: (AppState) -> AppState) {
        context.dataStore.edit { prefs ->
            val current = prefs[stateKey]?.let { decode(it) } ?: AppState()
            prefs[stateKey] = json.encodeToString(transform(current))
        }
    }

    suspend fun setServiceEnabled(enabled: Boolean) {
        update { it.copy(serviceEnabled = enabled) }
    }

    suspend fun saveBulbs(bulbs: List<Bulb>) {
        update { it.copy(bulbs = bulbs) }
    }

    suspend fun upsertBulb(bulb: Bulb) {
        update { state ->
            val without = state.bulbs.filterNot { it.id == bulb.id }
            val list = if (bulb.isDefault) {
                without.map { it.copy(isDefault = false) } + bulb
            } else {
                without + bulb
            }
            state.copy(bulbs = list)
        }
    }

    suspend fun deleteBulb(id: String) {
        update { it.copy(bulbs = it.bulbs.filterNot { b -> b.id == id }) }
    }

    suspend fun saveRules(rules: List<Rule>) {
        update { it.copy(rules = rules) }
    }

    suspend fun upsertRule(rule: Rule) {
        update { state ->
            val without = state.rules.filterNot { it.id == rule.id }
            state.copy(rules = without + rule)
        }
    }

    suspend fun deleteRule(id: String) {
        update { it.copy(rules = it.rules.filterNot { r -> r.id == id }) }
    }

    suspend fun saveRoutines(routines: List<ScheduledRoutine>) {
        update { it.copy(routines = routines) }
    }

    suspend fun upsertRoutine(routine: ScheduledRoutine) {
        update { state ->
            val without = state.routines.filterNot { it.id == routine.id }
            state.copy(routines = without + routine)
        }
    }

    suspend fun deleteRoutine(id: String) {
        update { it.copy(routines = it.routines.filterNot { r -> r.id == id }) }
    }

    suspend fun setLocation(location: GeoLocation?) {
        update { it.copy(location = location) }
    }

    suspend fun appendLog(entry: LogEntry, maxEntries: Int = 100) {
        update { state ->
            val next = (listOf(entry) + state.log).take(maxEntries)
            state.copy(log = next)
        }
    }

    suspend fun clearLog() {
        update { it.copy(log = emptyList()) }
    }

    private fun decode(raw: String): AppState =
        runCatching { json.decodeFromString<AppState>(raw) }.getOrElse { AppState() }
}

