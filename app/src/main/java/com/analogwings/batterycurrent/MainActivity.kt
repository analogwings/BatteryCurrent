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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.analogwings.batterycurrent.ui.theme.BatteryCurrentTheme

class MainActivity : ComponentActivity() {

    private var waitingForOverlayPermission = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                requestOverlayThenStart()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                        onStart = { requestPermissionThenStart() },
                        onStop = { stopBatteryService() },
                        onTemporaryProChanged = { enabled ->
                            ProFeatureGate.setTemporaryProEnabled(this, enabled)
                        },
                        onLightOverlayChanged = { enabled ->
                            OverlayThemePreference.setLightBackgroundEnabled(this, enabled)
                        },
                        onOriginalCapacityChanged = { capacityMah ->
                            BatteryCapacityReference.saveOriginalCapacityMah(this, capacityMah)
                        },
                        onOriginalCapacitySkipped = {
                            BatteryCapacityReference.markPromptSeen(this)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
        finish()
    }
}

@Composable
private fun BatteryCurrentScreen(
    initialTemporaryProEnabled: Boolean,
    initialLightOverlayEnabled: Boolean,
    initialOriginalCapacityMah: Int?,
    initialShowCapacityPrompt: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTemporaryProChanged: (Boolean) -> Unit,
    onLightOverlayChanged: (Boolean) -> Unit,
    onOriginalCapacityChanged: (Int?) -> Unit,
    onOriginalCapacitySkipped: () -> Unit
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
        Text(
            text = "Battery Current",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Temporary Pro mode",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = temporaryProEnabled,
                onCheckedChange = { enabled ->
                    temporaryProEnabled = enabled
                    onTemporaryProChanged(enabled)
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Light foreground background",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = lightOverlayEnabled,
                onCheckedChange = { enabled ->
                    lightOverlayEnabled = enabled
                    onLightOverlayChanged(enabled)
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = originalCapacityMah?.let { "Original capacity: ${it}mAh" } ?: "Original capacity not set",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { showCapacityDialog = true }) {
                Text(if (originalCapacityMah == null) "Add" else "Edit")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onStart
        ) {
            Text("Start Monitoring")
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onStop
        ) {
            Text("Stop Monitoring")
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
