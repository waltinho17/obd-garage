package com.walter.dashobd

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.walter.dashobd.ui.LocalSettings
import com.walter.dashobd.ui.SettingsScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.walter.dashobd.ui.ConnectionScreen
import com.walter.dashobd.ui.DashColors
import com.walter.dashobd.ui.DashScreen
import com.walter.dashobd.ui.DashTheme
import com.walter.dashobd.ui.LogListScreen
import com.walter.dashobd.ui.LogScreen
import com.walter.dashobd.ui.ScanScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: DashViewModel by viewModels()
    private var initialPage = 0
    private var initialLogTab = 0

    /**
     * Atalho de desenvolvimento, só em build debug, para conferir telas sem tocar no aparelho:
     * `adb shell am start -n com.walter.dashobd/.MainActivity --ez autoconnect true --ei page 1 --ei record 16 --ei logtab 1 --ez openlist true`
     */
    private fun runDebugScript() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val extras = intent.extras ?: return
        if (extras.getBoolean("openscan")) viewModel.openScan()
        if (!extras.getBoolean("autoconnect")) return
        initialPage = extras.getInt("page", 0)
        initialLogTab = extras.getInt("logtab", 0)
        val recordSeconds = extras.getInt("record", 0)
        val openList = extras.getBoolean("openlist")
        viewModel.connectSimulated()
        if (recordSeconds > 0) {
            lifecycleScope.launch {
                viewModel.state.first { it is ConnectionState.Connected }
                delay(1000)
                viewModel.toggleRecording()
                delay(recordSeconds * 1000L)
                viewModel.toggleRecording()
                if (openList) {
                    delay(1500)
                    viewModel.openLogList()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) runDebugScript()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = when (settings.theme) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.AUTO -> isSystemInDarkTheme()
            }
            LaunchedEffect(settings.orientation) {
                requestedOrientation = when (settings.orientation) {
                    OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalSettings provides settings,
                LocalDensity provides Density(baseDensity.density, baseDensity.fontScale * settings.textSize.scale),
            ) {
            DashTheme(darkTheme) {
                val settingsOpen by viewModel.settingsOpen.collectAsStateWithLifecycle()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val values by viewModel.values.collectAsStateWithLifecycle()
                val rate by viewModel.updatesPerSecond.collectAsStateWithLifecycle()
                val readMode by viewModel.readMode.collectAsStateWithLifecycle()
                val boostUnit by viewModel.boostUnit.collectAsStateWithLifecycle()
                val peak by viewModel.peakBoost.collectAsStateWithLifecycle()
                val supportedIds by viewModel.supportedIds.collectAsStateWithLifecycle()
                val engineKind by viewModel.engineKind.collectAsStateWithLifecycle()
                val vehicleName by viewModel.vehicleName.collectAsStateWithLifecycle()
                val recordingSince by viewModel.recordingSince.collectAsStateWithLifecycle()
                val entries by viewModel.logEntries.collectAsStateWithLifecycle()
                val logView by viewModel.logView.collectAsStateWithLifecycle()
                val scanOpen by viewModel.scanOpen.collectAsStateWithLifecycle()
                val devices by viewModel.devices.collectAsStateWithLifecycle()
                val scanError by viewModel.scanError.collectAsStateWithLifecycle()
                val lastDevice by viewModel.lastDevice.collectAsStateWithLifecycle()

                Box(Modifier.fillMaxSize().background(DashColors.Background).safeDrawingPadding()) {
                    when (val view = logView) {
                        is LogView.Detail -> LogScreen(
                            entry = view.entry,
                            session = view.session,
                            path = view.path,
                            onBack = viewModel::backFromLogs,
                            onEdit = { name, note -> viewModel.editLog(view.entry, name, note) },
                            onDelete = { viewModel.deleteLog(view.entry) },
                            initialTab = initialLogTab,
                        )
                        LogView.List -> LogListScreen(entries, viewModel::openLog, viewModel::closeLogs)
                        LogView.Closed -> when (val s = state) {
                            is ConnectionState.Connected -> DashScreen(
                                values, viewModel.history, peak, viewModel::resetPeak,
                                "${s.transport} · ${String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f", rate)}/s · ${readMode.label}",
                                initialPage,
                                recordingSince, viewModel::toggleRecording, viewModel::openLogList, viewModel::disconnect,
                                boostUnit, viewModel::cycleBoostUnit,
                                supportedIds, engineKind, vehicleName, viewModel.editableVehicleName(), viewModel::setVehicleName,
                            )
                            else -> if (scanOpen) {
                                ScanScreen(
                                    devices, s, scanError,
                                    onStart = viewModel::startScan,
                                    onStop = viewModel::stopScan,
                                    onPick = viewModel::connectBle,
                                    onDismissError = viewModel::dismissError,
                                    onBack = viewModel::closeScan,
                                )
                            } else {
                                if (settingsOpen) SettingsScreen(
                                    settings, boostUnit, viewModel::updateSettings, viewModel::setBoostUnit, viewModel::closeSettings,
                                ) else ConnectionScreen(
                                    s, entries.size, lastDevice,
                                    onOpenSettings = viewModel::openSettings,
                                    onConnectSimulated = viewModel::connectSimulated,
                                    onOpenScan = viewModel::openScan,
                                    onReconnect = viewModel::connectBle,
                                    onOpenLogs = viewModel::openLogList,
                                    onDismissError = viewModel::dismissError,
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
