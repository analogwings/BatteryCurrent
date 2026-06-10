package com.analogwings.batterycurrent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.analogwings.batterycurrent.ui.theme.BatteryCurrentTheme

class MainActivity : ComponentActivity() {

    private var waitingForOverlayPermission = false
    private var pendingStartAction = BatteryCurrentService.ACTION_SHOW_OVERLAY
    private val monitoringRunningState = mutableStateOf(false)
    private val fullDischargeModeState = mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                requestOverlayThenStart(action = pendingStartAction)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshStartupState()

        setContent {
            BatteryCurrentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BatteryCurrentScreen(
                        initialLightOverlayEnabled = OverlayThemePreference.isLightBackgroundEnabled(this),
                        initialAutoResetThresholdEnabled = AutoResetThresholdPreference.isResetOnThresholdEnabled(this),
                        initialCapacityThresholds = CapacityThresholdPreference.load(this),
                        initialOriginalCapacityMah = BatteryCapacityReference.originalCapacityMah(this),
                        initialShowCapacityPrompt = !BatteryCapacityReference.hasSeenPrompt(this),
                        fullDischargeModeEnabled = fullDischargeModeState.value,
                        monitoringRunning = monitoringRunningState.value,
                        onMonitorClick = {
                            if (monitoringRunningState.value) {
                                stopBatteryService()
                            } else {
                                requestPermissionThenStart(action = BatteryCurrentService.ACTION_SHOW_OVERLAY)
                            }
                        },
                        onLightOverlayChanged = { enabled ->
                            OverlayThemePreference.setLightBackgroundEnabled(this, enabled)
                        },
                        onAutoResetThresholdChanged = { enabled ->
                            AutoResetThresholdPreference.setResetOnThresholdEnabled(this, enabled)
                        },
                        onCapacityThresholdsChanged = { low, high ->
                            CapacityThresholdPreference.save(this, low, high)
                        },
                        onResetOverlayPosition = { resetForegroundOverlayPosition() },
                        onOriginalCapacityChanged = { capacityMah ->
                            BatteryCapacityReference.saveOriginalCapacityMah(this, capacityMah)
                        },
                        onOriginalCapacitySkipped = {
                            BatteryCapacityReference.markPromptSeen(this)
                        },
                        onFullDischargeStart = {
                            FullDischargeTest.setModeEnabled(this, true)
                            fullDischargeModeState.value = true
                            requestPermissionThenStart(action = BatteryCurrentService.ACTION_START_CALIBRATION_SETUP)
                        },
                        onFullDischargeModeOff = {
                            FullDischargeTest.setModeEnabled(this, false)
                            fullDischargeModeState.value = false
                        },
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStartupState()
        if (waitingForOverlayPermission && overlayPermissionGranted()) {
            waitingForOverlayPermission = false
            startBatteryServiceAndHideActivity(action = pendingStartAction)
        }
    }

    private fun requestPermissionThenStart(action: String) {
        pendingStartAction = action
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        requestOverlayThenStart(action = action)
    }

    private fun requestOverlayThenStart(action: String) {
        pendingStartAction = action
        if (!overlayPermissionGranted()) {
            waitingForOverlayPermission = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        startBatteryServiceAndHideActivity(action = action)
    }

    private fun overlayPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun startBatteryService(action: String = BatteryCurrentService.ACTION_SHOW_OVERLAY) {
        val intent = Intent(this, BatteryCurrentService::class.java).apply {
            this.action = action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startBatteryServiceAndHideActivity(action: String = BatteryCurrentService.ACTION_SHOW_OVERLAY) {
        startBatteryService(action = action)
        monitoringRunningState.value = true
        pendingStartAction = BatteryCurrentService.ACTION_SHOW_OVERLAY

        // Do not leave the full-screen activity in front; the floating readout is the control surface.
        moveTaskToBack(true)
    }

    private fun stopBatteryService() {
        val intent = Intent(this, BatteryCurrentService::class.java).apply {
            action = BatteryCurrentService.ACTION_STOP_MONITORING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        monitoringRunningState.value = false
        FullDischargeTest.setModeEnabled(this, false)
        fullDischargeModeState.value = false
    }

    private fun isMonitoringRunning(): Boolean {
        val prefs = getSharedPreferences(BatteryCurrentService.MONITOR_STATE_PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(BatteryCurrentService.MONITOR_RUNNING_KEY, false)) return false

        val lastHeartbeatMs = prefs.getLong(BatteryCurrentService.MONITOR_LAST_HEARTBEAT_MS_KEY, 0L)
        val heartbeatAgeMs = System.currentTimeMillis() - lastHeartbeatMs
        val serviceLooksAlive = BatteryCurrentService.isServiceAlive &&
            lastHeartbeatMs > 0L &&
            heartbeatAgeMs <= BatteryCurrentService.MONITOR_HEARTBEAT_STALE_MS
        if (!serviceLooksAlive) {
            prefs.edit()
                .putBoolean(BatteryCurrentService.MONITOR_RUNNING_KEY, false)
                .remove(BatteryCurrentService.MONITOR_LAST_HEARTBEAT_MS_KEY)
                .apply()
        }
        return serviceLooksAlive
    }

    private fun refreshStartupState() {
        val monitoringRunning = isMonitoringRunning()
        val calibrationLaunchPending = waitingForOverlayPermission ||
            pendingStartAction == BatteryCurrentService.ACTION_START_CALIBRATION_SETUP
        monitoringRunningState.value = monitoringRunning
        if (!monitoringRunning && !calibrationLaunchPending) {
            FullDischargeTest.setModeEnabled(this, false)
        }
        fullDischargeModeState.value = FullDischargeTest.isModeEnabled(this) &&
            (monitoringRunning || calibrationLaunchPending)
    }

    private fun resetForegroundOverlayPosition() {
        OverlayPositionPreference.resetPosition(this)
        if (!isMonitoringRunning()) return

        val intent = Intent(this, BatteryCurrentService::class.java).apply {
            action = BatteryCurrentService.ACTION_RESET_OVERLAY_POSITION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
private fun BatteryCurrentScreen(
    initialLightOverlayEnabled: Boolean,
    initialAutoResetThresholdEnabled: Boolean,
    initialCapacityThresholds: CapacityThresholdPreference.Thresholds,
    initialOriginalCapacityMah: Int?,
    initialShowCapacityPrompt: Boolean,
    fullDischargeModeEnabled: Boolean,
    monitoringRunning: Boolean,
    onMonitorClick: () -> Unit,
    onLightOverlayChanged: (Boolean) -> Unit,
    onAutoResetThresholdChanged: (Boolean) -> Unit,
    onCapacityThresholdsChanged: (Int, Int) -> CapacityThresholdPreference.Thresholds,
    onResetOverlayPosition: () -> Unit,
    onOriginalCapacityChanged: (Int?) -> Unit,
    onOriginalCapacitySkipped: () -> Unit,
    onFullDischargeStart: () -> Unit,
    onFullDischargeModeOff: () -> Unit,
    onClose: () -> Unit
) {
    var lightOverlayEnabled by remember { mutableStateOf(initialLightOverlayEnabled) }
    var autoResetThresholdEnabled by remember { mutableStateOf(initialAutoResetThresholdEnabled) }
    var capacityThresholds by remember { mutableStateOf(initialCapacityThresholds) }
    var originalCapacityMah by remember { mutableStateOf(initialOriginalCapacityMah) }
    var showCapacityDialog by remember { mutableStateOf(initialShowCapacityPrompt) }
    var showFullDischargeDialog by remember { mutableStateOf(false) }
    var showThresholdDialog by remember { mutableStateOf(false) }

    if (showCapacityDialog) {
        OriginalCapacityDialog(
            initialCapacityMah = originalCapacityMah,
            onSave = { capacityMah ->
                originalCapacityMah = capacityMah
                onOriginalCapacityChanged(capacityMah)
                showCapacityDialog = false
            },
            onSkip = {
                onOriginalCapacitySkipped()
                showCapacityDialog = false
            }
        )
    }

    if (showFullDischargeDialog) {
        FullDischargeTestDialog(
            modeEnabled = fullDischargeModeEnabled,
            onDismiss = { showFullDischargeDialog = false },
            onResetAndStart = {
                onFullDischargeStart()
                showFullDischargeDialog = false
            },
            onTurnOff = {
                onFullDischargeModeOff()
                showFullDischargeDialog = false
            }
        )
    }

    if (showThresholdDialog) {
        CapacityThresholdDialog(
            initialThresholds = capacityThresholds,
            onSave = { low, high ->
                val saved = onCapacityThresholdsChanged(low, high)
                capacityThresholds = saved
                showThresholdDialog = false
            },
            onDismiss = { showThresholdDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(44.dp))
            Text(
                modifier = Modifier.weight(1f),
                text = "Battery Current",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            StartupCloseButton(onClick = onClose)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Battery health data is being collected for capacity and degradation insights.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Start monitoring to show the floating readout, then tap it to open the graph.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(24.dp))

        SettingRow(label = "Reset foreground display to centre") {
            StartupActionButton(
                text = "■",
                onClick = onResetOverlayPosition
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingRow(label = "Battery Calibration") {
            StartupActionButton(
                text = if (fullDischargeModeEnabled) "ON" else "Start",
                onClick = { showFullDischargeDialog = true },
                indicatorColor = if (fullDischargeModeEnabled) Color(0xFF1FA64A) else Color(0xFFD93636)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingRow(label = "Light-theme foreground display") {
            Switch(
                checked = lightOverlayEnabled,
                colors = silverSwitchColors(),
                onCheckedChange = { enabled ->
                    lightOverlayEnabled = enabled
                    onLightOverlayChanged(enabled)
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingRow(label = "Reset graph at ${capacityThresholds.lowPercent}% / ${capacityThresholds.highPercent}%") {
            Switch(
                checked = autoResetThresholdEnabled,
                colors = silverSwitchColors(),
                onCheckedChange = { enabled ->
                    autoResetThresholdEnabled = enabled
                    onAutoResetThresholdChanged(enabled)
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingRow(label = "Capacity window: ${capacityThresholds.lowPercent}-${capacityThresholds.highPercent}%") {
            StartupActionButton(
                text = "Edit",
                onClick = { showThresholdDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingRow(
            label = originalCapacityMah?.let { "Battery capacity: ${it}mAh" } ?: "Battery capacity not set"
        ) {
            StartupActionButton(
                text = "Edit",
                onClick = { showCapacityDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

            SettingRow(label = "Battery Monitor") {
                StartupActionButton(
                    text = if (monitoringRunning) "ON" else "OFF",
                    onClick = onMonitorClick,
                    indicatorColor = if (monitoringRunning) Color(0xFF1FA64A) else Color(0xFFD93636)
                )
            }
    }
}

@Composable
private fun StartupCloseButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .width(44.dp)
            .height(40.dp)
            .clip(shape)
            .background(Color.White)
            .border(2.dp, Color.Black, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "X",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

@Composable
private fun FullDischargeTestDialog(
    modeEnabled: Boolean,
    onDismiss: () -> Unit,
    onResetAndStart: () -> Unit,
    onTurnOff: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Battery Calibration") },
        text = {
            Column {
                Text(
                    text = "For calibration, charge the phone to 100%, leave it connected long enough to fully top off, then press Start while Android still reports 100%.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Start has no effect unless the battery state reads 100%. After Start, disconnect the charger; measurement starts automatically when the phone falls to 99% and stops at 15%.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "If the phone powers off, the app is stopped, or a charger is connected during calibration, the incomplete calibration is discarded.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = if (modeEnabled) onTurnOff else onResetAndStart) {
                Text(if (modeEnabled) "Turn Off" else "Start")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CapacityThresholdDialog(
    initialThresholds: CapacityThresholdPreference.Thresholds,
    onSave: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var lowText by remember { mutableStateOf(initialThresholds.lowPercent.toString()) }
    var highText by remember { mutableStateOf(initialThresholds.highPercent.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capacity window") },
        text = {
            Column {
                Text(
                    text = "Set the low and high battery percentages used for capacity estimates, graph reset markers, and status dots. The low threshold must be at least 20%, and the window must be at least 40% wide. Example: 40% to 80% estimates capacity as measured mAh x 2.5.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = lowText,
                    onValueChange = { value -> lowText = value.filter { it.isDigit() }.take(2) },
                    label = { Text("Low %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = highText,
                    onValueChange = { value -> highText = value.filter { it.isDigit() }.take(2) },
                    label = { Text("High %") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        lowText.toIntOrNull() ?: CapacityThresholdPreference.DEFAULT_LOW_PERCENT,
                        highText.toIntOrNull() ?: CapacityThresholdPreference.DEFAULT_HIGH_PERCENT
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SettingRow(
    label: String,
    control: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        control()
    }
}

@Composable
private fun silverSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color(0xFFF7F9FA),
    checkedTrackColor = Color(0xFF8F9AA3),
    checkedBorderColor = Color(0xFFE8ECEF),
    uncheckedThumbColor = Color(0xFFD7DCE0),
    uncheckedTrackColor = Color(0xFF4E555C),
    uncheckedBorderColor = Color(0xFF99A1A8)
)

@Composable
private fun StartupActionButton(
    text: String,
    onClick: () -> Unit,
    indicatorColor: Color? = null
) {
    val shape = RoundedCornerShape(8.dp)
    val silverGradient = Brush.verticalGradient(
        listOf(
            Color(0xFFF8FAFB),
            Color(0xFFDDE2E6),
            Color(0xFFB8C0C7)
        )
    )

    Box(
        modifier = Modifier
            .width(64.dp)
            .height(44.dp)
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(silverGradient)
            .border(2.dp, Color(0xFF6F7780), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(8.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xCCFFFFFF),
                            Color(0x22FFFFFF)
                        )
                    )
                )
        )

        if (indicatorColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .width(64.dp)
                    .height(12.dp)
                    .padding(bottom = 1.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(indicatorColor)
            )
        } else {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(8.dp)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x18000000))
            )
        }

        if (text.isNotBlank()) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color(0xFF26313A),
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun OriginalCapacityDialog(
    initialCapacityMah: Int?,
    onSave: (Int?) -> Unit,
    onSkip: () -> Unit
) {
    var capacityText by remember { mutableStateOf(initialCapacityMah?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Original battery capacity") },
        text = {
            Column {
                Text(
                    text = "Enter the phone's original rated battery capacity in mAh. If you skip this, estimated capacity will stay green instead of using health color bands.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = capacityText,
                    onValueChange = { value ->
                        capacityText = value.filter { it.isDigit() }.take(6)
                    },
                    label = { Text("Capacity in mAh") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(capacityText.toIntOrNull())
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = onSkip) {
                Text("Skip")
            }
        }
    )
}
