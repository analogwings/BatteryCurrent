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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
                        onStart = { requestPermissionThenStart() },
                        onStop = { stopBatteryService() }
                    )
                }
            }
        }

        // Normal launch behavior: start the monitor automatically, then put this
        // activity in the background. The notification can show the floating readout later.
        requestPermissionThenStart()
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
    onStart: () -> Unit,
    onStop: () -> Unit
) {
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
            text = "Starting monitor automatically. The floating readout will appear, then tap it to open the graph.",
            style = MaterialTheme.typography.bodyMedium
        )

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
