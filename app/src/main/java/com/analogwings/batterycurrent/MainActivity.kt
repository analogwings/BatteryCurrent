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
    private val monitoringRunningState = mutableStateOf(false)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                requestOverlayThenStart()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        monitoringRunningState.value = isMonitoringRunning()

        setContent {
            BatteryCurrentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BatteryCurrentScreen(
                        initialTemporaryProEnabled = ProFeatureGate.isTemporaryProEnabled(this),
                        initialLightOverlayEnabled = OverlayThemePreference.isLightBackgroundEnabled(this),
                        initialOriginalCapacityMah = BatteryCapacityReference.originalCapacityMah(this),
                        initialShowCapacityPrompt = !BatteryCapacityReference.hasSeenPrompt(this),
                        monitoringRunning = monitoringRunningState.value,
                        onMonitorClick = {
                            if (monitoringRunningState.value) {
                                stopBatteryService()
                            } else {
                                requestPermissionThenStart()
                            }
                        },
                        onTemporaryProChanged = { enabled ->
                            ProFeatureGate.setTemporaryProEnabled(this, enabled)
                        },
                        onLightOverlayChanged = { enabled ->
                            OverlayThemePreference.setLightBackgroundEnabled(this, enabled)
                        },
                        onResetOverlayPosition = { resetForegroundOverlayPosition() },
                        onOriginalCapacityChanged = { capacityMah ->
                            BatteryCapacityReference.saveOriginalCapacityMah(this, capacityMah)
                        },
                        onOriginalCapacitySkipped = {
                            BatteryCapacityReference.markPromptSeen(this)
                        },
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        monitoringRunningState.value = isMonitoringRunning()
        if (waitingForOverlayPermission && overlayPermissionGranted()) {
            waitingForOverlayPermission = false
            startBatteryServiceAndHideActivity()
        }
    }

    private fun requestPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        requestOverlayThenStart()
    }

    private fun requestOverlayThenStart() {
        if (!overlayPermissionGranted()) {
            waitingForOverlayPermission = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        startBatteryServiceAndHideActivity()
    }

    private fun overlayPermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun startBatteryService() {
        val intent = Intent(this, BatteryCurrentService::class.java).apply {
            action = BatteryCurrentService.ACTION_SHOW_OVERLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startBatteryServiceAndHideActivity() {
        startBatteryService()
        monitoringRunningState.value = true

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
    }

    private fun isMonitoringRunning(): Boolean {
        val prefs = getSharedPreferences(BatteryCurrentService.MONITOR_STATE_PREFS_NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(BatteryCurrentService.MONITOR_RUNNING_KEY, false)) return false

        val lastHeartbeatMs = prefs.getLong(BatteryCurrentService.MONITOR_LAST_HEARTBEAT_MS_KEY, 0L)
        val heartbeatAgeMs = System.currentTimeMillis() - lastHeartbeatMs
        val serviceLooksAlive = lastHeartbeatMs > 0L &&
            heartbeatAgeMs <= BatteryCurrentService.MONITOR_HEARTBEAT_STALE_MS
        if (!serviceLooksAlive) {
            prefs.edit()
                .putBoolean(BatteryCurrentService.MONITOR_RUNNING_KEY, false)
                .remove(BatteryCurrentService.MONITOR_LAST_HEARTBEAT_MS_KEY)
                .apply()
        }
        return serviceLooksAlive
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
    initialTemporaryProEnabled: Boolean,
    initialLightOverlayEnabled: Boolean,
    initialOriginalCapacityMah: Int?,
    initialShowCapacityPrompt: Boolean,
    monitoringRunning: Boolean,
    onMonitorClick: () -> Unit,
    onTemporaryProChanged: (Boolean) -> Unit,
    onLightOverlayChanged: (Boolean) -> Unit,
    onResetOverlayPosition: () -> Unit,
    onOriginalCapacityChanged: (Int?) -> Unit,
    onOriginalCapacitySkipped: () -> Unit,
    onClose: () -> Unit
) {
    var temporaryProEnabled by remember { mutableStateOf(initialTemporaryProEnabled) }
    var lightOverlayEnabled by remember { mutableStateOf(initialLightOverlayEnabled) }
    var originalCapacityMah by remember { mutableStateOf(initialOriginalCapacityMah) }
    var showCapacityDialog by remember { mutableStateOf(initialShowCapacityPrompt) }

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
            text = "Battery health data is being collected. Upgrade to Pro to view capacity and degradation insights.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "For testing, turn on temporary Pro mode before starting monitoring. The floating readout will appear, then tap it to open the graph.",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(24.dp))

        SettingRow(label = "Temporary Pro mode") {
            Switch(
                checked = temporaryProEnabled,
                colors = silverSwitchColors(),
                onCheckedChange = { enabled ->
                    temporaryProEnabled = enabled
                    onTemporaryProChanged(enabled)
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingRow(label = "Reset foreground display to centre") {
            StartupActionButton(
                text = "■",
                onClick = onResetOverlayPosition
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

        SettingRow(
            label = originalCapacityMah?.let { "Original capacity: ${it}mAh" } ?: "Original capacity not set"
        ) {
            StartupActionButton(
                text = "Edit",
                onClick = { showCapacityDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

            SettingRow(label = "Monitor") {
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
