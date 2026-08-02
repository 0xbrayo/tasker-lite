package com.taskerlite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.taskerlite.location.LocationHelper
import com.taskerlite.service.AutomationService
import com.taskerlite.ui.AppViewModel
import com.taskerlite.ui.AppViewModelFactory
import com.taskerlite.ui.BulbsScreen
import com.taskerlite.ui.HomeScreen
import com.taskerlite.ui.LogScreen
import com.taskerlite.ui.RoutinesScreen
import com.taskerlite.ui.RulesScreen
import com.taskerlite.ui.theme.TaskerLiteTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results unused — user can re-enable from settings */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRuntimePermissions()

        val app = application as TaskerLiteApp
        // Restart automation service if user previously enabled it
        lifecycleScope.launch {
            if (app.repository.getState().serviceEnabled) {
                AutomationService.start(applicationContext)
            }
        }

        setContent {
            TaskerLiteTheme {
                val vm: AppViewModel = viewModel(
                    factory = AppViewModelFactory(app.repository, applicationContext)
                )
                TaskerLiteAppScaffold(vm)
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
        }
        // Location is optional at first launch — requested from Routines tab when needed
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun requestLocationPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("bulbs", "Bulbs", Icons.Default.Lightbulb),
    Tab("rules", "Rules", Icons.AutoMirrored.Filled.Rule),
    Tab("routines", "Routines", Icons.Default.Schedule),
    Tab("log", "Log", Icons.AutoMirrored.Filled.List),
)

@Composable
fun TaskerLiteAppScaffold(vm: AppViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: "home"
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.statusMessage.collectAsStateWithLifecycle()
    val discovering by vm.discovering.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? MainActivity
    var locationPermTick by remember { mutableStateOf(0) }
    val hasLocationPermission = remember(locationPermTick) {
        LocationHelper.hasLocationPermission(context)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                HomeScreen(
                    state = state,
                    statusMessage = status,
                    onToggleService = vm::setServiceEnabled,
                    onQuickOn = { vm.quickPower(true) },
                    onQuickOff = { vm.quickPower(false) },
                    onQuickDim = { vm.quickDim() },
                    onQuickSunrise = { vm.quickSunrise() },
                    onRefreshStatus = { vm.refreshDefaultBulbStatus() },
                )
            }
            composable("bulbs") {
                BulbsScreen(
                    bulbs = state.bulbs,
                    discovering = discovering,
                    statusMessage = status,
                    onDiscover = { vm.discoverBulbs() },
                    onAddManual = { name, ip, token -> vm.addManualBulb(name, ip, token) },
                    onSetDefault = { vm.setDefaultBulb(it) },
                    onDelete = { vm.deleteBulb(it) },
                    onTest = { vm.testBulb(it) },
                    onToggle = { id, on -> vm.toggleBulb(id, on) },
                )
            }
            composable("rules") {
                RulesScreen(
                    rules = state.rules,
                    onToggle = { id, enabled -> vm.setRuleEnabled(id, enabled) },
                    onDelete = { vm.deleteRule(it) },
                    onResetDefaults = { vm.resetDefaultRules() },
                    onUpdateAction = { id, action -> vm.updateRuleAction(id, action) },
                )
            }
            composable("routines") {
                RoutinesScreen(
                    routines = state.routines,
                    location = state.location,
                    hasLocationPermission = hasLocationPermission,
                    statusMessage = status,
                    onToggle = { id, enabled -> vm.setRoutineEnabled(id, enabled) },
                    onDelete = { vm.deleteRoutine(it) },
                    onUpdateAction = { id, action -> vm.updateRoutineAction(id, action) },
                    onUpdateOffset = { id, offset -> vm.updateRoutineOffset(id, offset) },
                    onRefreshLocation = {
                        locationPermTick++
                        vm.refreshLocation()
                    },
                    onRequestLocationPermission = {
                        activity?.requestLocationPermissions()
                        locationPermTick++
                    },
                    onTestSunsetRamp = { vm.quickSunsetRamp() },
                    onResetDefaults = { vm.resetDefaultRoutines() },
                )
            }
            composable("log") {
                LogScreen(
                    entries = state.log,
                    onClear = { vm.clearLog() },
                )
            }
        }
    }
}
