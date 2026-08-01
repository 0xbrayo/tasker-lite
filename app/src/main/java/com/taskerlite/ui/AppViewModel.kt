package com.taskerlite.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.taskerlite.bulb.BulbController
import com.taskerlite.data.AppState
import com.taskerlite.data.Bulb
import com.taskerlite.data.LightAction
import com.taskerlite.data.LogEntry
import com.taskerlite.data.PreferencesRepository
import com.taskerlite.data.defaultRules
import com.taskerlite.service.AutomationService
import com.taskerlite.service.RulesEngine
import com.taskerlite.yeelight.YeelightDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(
    private val repo: PreferencesRepository,
    private val appContext: Context,
) : ViewModel() {

    val state: StateFlow<AppState> = repo.stateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppState())

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val engine = RulesEngine(repo)

    fun setServiceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repo.setServiceEnabled(enabled)
            if (enabled) {
                AutomationService.start(appContext)
                _statusMessage.value = "Automation service started"
            } else {
                AutomationService.stop(appContext)
                _statusMessage.value = "Automation service stopped"
            }
        }
    }

    fun discoverBulbs() {
        viewModelScope.launch {
            _discovering.value = true
            _statusMessage.value = "Scanning LAN for Yeelight bulbs…"
            try {
                val found = YeelightDiscovery.discover(appContext)
                if (found.isEmpty()) {
                    _statusMessage.value =
                        "No Yeelight LAN bulbs found. For Xiaomi MIoT bulbs, add IP + token below."
                } else {
                    val current = repo.getState().bulbs
                    found.forEach { discovered ->
                        val existing = current.find {
                            it.yeelightId != null && it.yeelightId == discovered.yeelightId ||
                                it.ip == discovered.ip
                        }
                        if (existing == null) {
                            val makeDefault = current.isEmpty() && discovered == found.first()
                            repo.upsertBulb(discovered.copy(isDefault = makeDefault))
                        } else {
                            repo.upsertBulb(
                                existing.copy(
                                    ip = discovered.ip,
                                    port = discovered.port,
                                    model = discovered.model ?: existing.model,
                                    yeelightId = discovered.yeelightId ?: existing.yeelightId,
                                )
                            )
                        }
                    }
                    _statusMessage.value = "Found ${found.size} bulb(s)"
                }
            } catch (e: Exception) {
                Log.e("AppViewModel", "Discovery failed", e)
                _statusMessage.value =
                    "Discovery failed: ${e.message ?: e.javaClass.simpleName}. " +
                        "Add the bulb with IP + miIO token instead."
            } finally {
                _discovering.value = false
            }
        }
    }

    fun addManualBulb(name: String, ip: String, token: String) {
        viewModelScope.launch {
            val cleanIp = ip.trim()
            if (cleanIp.isEmpty()) {
                _statusMessage.value = "IP address required"
                return@launch
            }
            val cleanToken = token.trim().lowercase().replace(" ", "")
            if (cleanToken.isNotEmpty() && cleanToken.length != 32) {
                _statusMessage.value = "Token must be 32 hex characters (or leave empty for Yeelight LAN)"
                return@launch
            }
            if (cleanToken.isNotEmpty() && !cleanToken.all { it in "0123456789abcdef" }) {
                _statusMessage.value = "Token must be hexadecimal"
                return@launch
            }

            val makeDefault = repo.getState().bulbs.isEmpty()
            val usesMiio = cleanToken.length == 32
            val bulb = Bulb(
                name = name.ifBlank { "Bulb $cleanIp" },
                ip = cleanIp,
                port = if (usesMiio) 54321 else 55443,
                token = cleanToken.ifEmpty { null },
                model = if (usesMiio) "xiaomi.light.bulb" else null,
                isDefault = makeDefault,
            )
            repo.upsertBulb(bulb)
            _statusMessage.value = "Added ${bulb.name} (${bulb.protocolLabel})"

            // Auto-test when token provided
            if (usesMiio) {
                testBulb(bulb.id)
            }
        }
    }

    fun setDefaultBulb(id: String) {
        viewModelScope.launch {
            val state = repo.getState()
            val updated = state.bulbs.map { it.copy(isDefault = it.id == id) }
            repo.saveBulbs(updated)
        }
    }

    fun deleteBulb(id: String) {
        viewModelScope.launch { repo.deleteBulb(id) }
    }

    fun testBulb(id: String) {
        viewModelScope.launch {
            val bulb = repo.getState().bulbs.find { it.id == id } ?: return@launch
            val result = BulbController(bulb).ping()
            result.fold(
                onSuccess = { props ->
                    _statusMessage.value =
                        "${bulb.name} (${bulb.protocolLabel}): power=${props["power"]} bright=${props["bright"]}"
                    repo.appendLog(
                        LogEntry(
                            event = "TEST",
                            message = "${bulb.name} OK: $props",
                            success = true,
                        )
                    )
                    // Persist model if reported
                    props["model"]?.let { model ->
                        if (bulb.model == null) {
                            repo.upsertBulb(bulb.copy(model = model))
                        }
                    }
                },
                onFailure = { e ->
                    _statusMessage.value = "${bulb.name}: ${e.message}"
                    repo.appendLog(
                        LogEntry(
                            event = "TEST",
                            message = "${bulb.name}: ${e.message}",
                            success = false,
                        )
                    )
                }
            )
        }
    }

    fun toggleBulb(id: String, on: Boolean) {
        viewModelScope.launch {
            val bulb = repo.getState().bulbs.find { it.id == id } ?: return@launch
            BulbController(bulb).setPower(on).fold(
                onSuccess = { _statusMessage.value = "${bulb.name}: ${if (on) "on" else "off"}" },
                onFailure = { e -> _statusMessage.value = e.message }
            )
        }
    }

    fun quickPower(on: Boolean) {
        viewModelScope.launch {
            val bulb = defaultBulb() ?: run {
                _statusMessage.value = "Add a bulb first"
                return@launch
            }
            BulbController(bulb).setPower(on).fold(
                onSuccess = { _statusMessage.value = if (on) "On" else "Off" },
                onFailure = { e -> _statusMessage.value = e.message }
            )
        }
    }

    fun quickDim() {
        viewModelScope.launch {
            val bulb = defaultBulb() ?: run {
                _statusMessage.value = "Add a bulb first"
                return@launch
            }
            val action = LightAction.Scene(
                listOf(
                    LightAction.Power(true),
                    LightAction.ColorTemp(2700),
                    LightAction.Brightness(10),
                )
            )
            engine.runAction(bulb, action).fold(
                onSuccess = { _statusMessage.value = "Dim warm" },
                onFailure = { e -> _statusMessage.value = e.message }
            )
        }
    }

    fun quickSunrise() {
        viewModelScope.launch {
            val bulb = defaultBulb() ?: run {
                _statusMessage.value = "Add a bulb first"
                return@launch
            }
            // ~2 min 3-phase dawn (production alarm uses 30 min start_cf / phased)
            engine.runAction(bulb, LightAction.morningSunriseTest()).fold(
                onSuccess = {
                    _statusMessage.value = "Dawn started (2 min test · 3 phases)"
                },
                onFailure = { e -> _statusMessage.value = e.message }
            )
        }
    }

    fun refreshDefaultBulbStatus() {
        viewModelScope.launch {
            val bulb = defaultBulb() ?: run {
                _statusMessage.value = "No default bulb"
                return@launch
            }
            testBulb(bulb.id)
        }
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            val rule = repo.getState().rules.find { it.id == id } ?: return@launch
            repo.upsertRule(rule.copy(enabled = enabled))
        }
    }

    fun deleteRule(id: String) {
        viewModelScope.launch { repo.deleteRule(id) }
    }

    fun resetDefaultRules() {
        viewModelScope.launch {
            repo.saveRules(defaultRules())
            _statusMessage.value = "Restored default rules"
        }
    }

    fun updateRuleAction(id: String, action: LightAction) {
        viewModelScope.launch {
            val rule = repo.getState().rules.find { it.id == id } ?: return@launch
            repo.upsertRule(rule.copy(action = action))
        }
    }

    fun clearLog() {
        viewModelScope.launch { repo.clearLog() }
    }

    private suspend fun defaultBulb(): Bulb? {
        val bulbs = repo.getState().bulbs
        return bulbs.find { it.isDefault } ?: bulbs.firstOrNull()
    }
}

class AppViewModelFactory(
    private val repo: PreferencesRepository,
    private val context: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
            return AppViewModel(repo, context.applicationContext) as T
        }
        error("Unknown ViewModel: $modelClass")
    }
}
