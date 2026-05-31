package com.analogwings.batterycurrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.SpannedString
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import java.util.ArrayDeque
import java.util.ArrayList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

class BatteryCurrentService : Service() {

    companion object {
        const val ACTION_SHOW_OVERLAY = "com.analogwings.batterycurrent.SHOW_OVERLAY"
        const val ACTION_STOP_MONITORING = "com.analogwings.batterycurrent.STOP_MONITORING"
        const val ACTION_RESET_OVERLAY_POSITION = "com.analogwings.batterycurrent.RESET_OVERLAY_POSITION"
        const val MONITOR_STATE_PREFS_NAME = "battery_current_monitor_state"
        const val MONITOR_RUNNING_KEY = "monitor_running"
        const val MONITOR_LAST_HEARTBEAT_MS_KEY = "monitor_last_heartbeat_ms"
        const val MONITOR_HEARTBEAT_STALE_MS = 45_000L
        @Volatile
        var isServiceAlive = false
            private set
        private const val ENERGY_UNIT_MWH = "mWh"
        private const val ENERGY_UNIT_MAH = "mAh"
        private const val TEMPERATURE_UNIT_C = "C"
        private const val TEMPERATURE_UNIT_F = "F"
        private const val CALIBRATION_DOT_BLINK_MS = 1000L
        private const val GRAPH_BLINK_UPDATE_MS = 1000L
        private const val FOREGROUND_INDICATOR_UPDATE_MS = 1000L
        private const val RIGHT_AXIS_BATTERY = "battery"
        private const val RIGHT_AXIS_TEMPERATURE = "temperature"
        private const val RIGHT_AXIS_VOLTAGE = "voltage"
        private const val RIGHT_AXIS_CURRENT = "current"
    }

    private val channelId = "battery_current_silent_channel_v2"
    private val notificationId = 1001
    private val handler = Handler(Looper.getMainLooper())

    // Five 10-second samples = 50-second moving average.
    private val recentMilliAmpSamples = ArrayDeque<Double>()
    private val maxSamples = 5
    private val updateIntervalMs = 10_000L
    private val maxEnergyHistoryAgeMs = 24L * 60L * 60L * 1000L

    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null
    private var graphOverlayView: LinearLayout? = null
    private var capacityHistoryPopupView: View? = null
    private var capacityEventDetailsPopupView: View? = null
    private var socCurvePopupView: View? = null
    private var graphMenuCollapsed = false
    private val capacityEstimator by lazy { BatteryCapacityEstimator(this) }

    // Keep the foreground notification quiet/static so the status-bar icon does not flash.
    private var lastNotificationUpdateMs = 0L
    private val notificationUpdateIntervalMs = 10_000L

    // Persist overlay position after dragging; lock only controls whether it can move.
    // Coordinates are WindowManager.LayoutParams.x/y using Gravity.CENTER.
    private val overlayPrefsName = "battery_current_overlay_prefs"
    private val overlayLockedKey = "overlay_locked"

    private val energyPrefsName = "battery_current_energy_prefs"
    private val energySessionStartKey = "energy_session_start_ms"
    private val energyTotalKey = "energy_total_mwh"
    private val chargeTotalKey = "charge_total_mah"
    private val energyHistoryKey = "energy_history"
    private val energyUnitKey = "energy_unit"

    private val displayPrefsName = "battery_current_display_prefs"
    private val displayTimeKey = "display_time"
    private val displayCurrentKey = "display_current"
    private val displayTemperatureKey = "display_temperature"
    private val displayVoltageKey = "display_voltage"
    private val displayEnergyKey = "display_energy"
    private val displayBatteryKey = "display_battery"
    private val temperatureUnitKey = "temperature_unit"
    private val rightAxisModeKey = "right_axis_mode"

    // Overlay display styling follows the graph popup palette.
    private val overlayTextColor = Color.WHITE
    private val overlayDarkBackgroundColor = Color.argb(235, 24, 26, 33)
    private val overlayLightBackgroundColor = Color.argb(235, 245, 248, 244)
    private val graphChargeTextColor = Color.rgb(82, 220, 135)
    private val graphDischargeTextColor = Color.rgb(245, 105, 105)
    private val graphWarmTextColor = Color.rgb(255, 190, 70)
    private val graphCoolTextColor = Color.rgb(95, 220, 135)
    private val graphEstimateLabelColor = Color.rgb(245, 225, 170)
    private val graphBackgroundColor = Color.argb(242, 24, 26, 33)

    private val energyHistory = ArrayList<EnergyPoint>()
    private var sessionStartMs = 0L
    private var lastSampleTimestampMs = 0L
    private var totalNetEnergyMilliWattHours = 0.0
    private var totalNetChargeMilliAmpHours = 0.0

    // Display-only graph reset baseline. This must not affect active measurements,
    // capacity estimation, bucket learning, or persisted data files.
    private var graphDisplayZeroTimestampMs = 0L
    private var graphDisplayZeroEnergyMilliWattHours = 0.0
    private var graphDisplayZeroChargeMilliAmpHours = 0.0
    private var lastPluggedState = false
    private var latestVoltageMv: Int? = null
    private var latestRoundedMilliAmps: Int? = null
    private var latestTemperatureC: Double? = null
    private var latestBatteryPercent: Int? = null
    private var latestGraphBatteryPercent: Double? = null
    private var latestBatteryPercentHasFraction = false
    private var capacityDisplayState = BatteryCapacityEstimator.DisplayState(null, null, false, false)
    private var graphOverlayCreatedAtMs = 0L
    private var lastDisplay = CurrentDisplay(SpannedString("Starting..."))
    private var isUpdateScheduled = false

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateCurrentDisplay()
            scheduleNextUpdate()
        }
    }

    private val graphBlinkRunnable = object : Runnable {
        override fun run() {
            if (graphOverlayView == null) return
            updateGraphOverlay()
            handler.postDelayed(this, GRAPH_BLINK_UPDATE_MS)
        }
    }

    private val foregroundIndicatorRunnable = object : Runnable {
        override fun run() {
            if (overlayView == null) return
            refreshFloatingOverlayText()
            handler.postDelayed(this, FOREGROUND_INDICATOR_UPDATE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceAlive = true
        createNotificationChannel()
    }

    private fun setMonitoringRunning(running: Boolean) {
        val editor = getSharedPreferences(MONITOR_STATE_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(MONITOR_RUNNING_KEY, running)
        if (running) {
            editor.putLong(MONITOR_LAST_HEARTBEAT_MS_KEY, System.currentTimeMillis())
        } else {
            editor.remove(MONITOR_LAST_HEARTBEAT_MS_KEY)
        }
        editor.apply()
    }

    private fun updateMonitorHeartbeat() {
        getSharedPreferences(MONITOR_STATE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MONITOR_RUNNING_KEY, true)
            .putLong(MONITOR_LAST_HEARTBEAT_MS_KEY, System.currentTimeMillis())
            .apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundSafely()) {
            stopSelf()
            return START_NOT_STICKY
        }

        setMonitoringRunning(true)
        initializeEnergyTracking()
        val showOverlay = intent?.action == ACTION_SHOW_OVERLAY
        when (intent?.action) {
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_RESET_OVERLAY_POSITION -> {
                resetForegroundOverlayToCenter()
                if (overlayView == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
        }
        handler.removeCallbacks(updateRunnable)
        isUpdateScheduled = false
        if (showOverlay) {
            updateCurrentDisplay()
            createOverlayIfAllowed()
        }
        scheduleNextUpdate(immediate = !showOverlay)
        return START_STICKY
    }

    private fun startForegroundSafely(): Boolean {
        return try {
            startForeground(notificationId, buildNotification("Starting..."))
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun scheduleNextUpdate(immediate: Boolean = false) {
        if (isUpdateScheduled) return
        isUpdateScheduled = true
        handler.postDelayed(updateRunnable, if (immediate) 0L else updateIntervalMs)
    }

    private fun updateCurrentDisplay() {
        isUpdateScheduled = false
        updateMonitorHeartbeat()

        val display = try {
            val batteryStatus = readBatteryStatus()
            val reading = readBatteryCurrentMilliAmps(batteryStatus)
            val temperatureC = readBatteryTemperatureC(batteryStatus)
            if (reading == null) {
                CurrentDisplay(SpannedString("Unsupported"))
            } else {
                val voltageMv = readBatteryVoltageMillivolts(batteryStatus)
                latestVoltageMv = voltageMv
                readBatteryPercent(batteryStatus)?.let { batteryPercent ->
                    latestBatteryPercent = batteryPercent.percent.roundToInt().coerceIn(0, 100)
                    latestGraphBatteryPercent = batteryPercent.percent
                    latestBatteryPercentHasFraction = batteryPercent.hasFraction
                }
                addSampleAndFormat(reading, temperatureC, voltageMv, batteryStatus)
            }
        } catch (_: Exception) {
            CurrentDisplay(SpannedString("Unavailable"))
        }
        lastDisplay = display

        overlayView?.apply {
            text = display.text
            background = pillBackground()
        }

        updateGraphOverlay()

        val now = System.currentTimeMillis()
        if (now - lastNotificationUpdateMs >= notificationUpdateIntervalMs) {
            lastNotificationUpdateMs = now
            notifySafely(buildFullLiveDisplayText(now))
        }
    }

    private fun readBatteryStatus(): Intent? {
        return registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun readBatteryCurrentMilliAmps(batteryStatus: Intent?): Double? {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        // 1. Try standard API first (Pixel, many devices)
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (ua != Int.MIN_VALUE && ua != 0) {
            return normalizeCurrentForPlugState(ua / 1000.0, batteryStatus)
        }

        // 2. Fallback: try sysfs paths (Samsung / others)
        val paths = listOf(
            "/sys/class/power_supply/battery/current_now",
            "/sys/class/power_supply/battery/current_avg",
            "/sys/class/power_supply/battery/batt_current_ua",
            "/sys/class/power_supply/battery/batt_current_now"
        )

        for (path in paths) {
            try {
                val text = java.io.File(path).readText().trim()
                val value = text.toLongOrNull() ?: continue

                if (value != 0L) {
                    // Most are in microamps
                    var ma = value / 1000.0

                    // Fix sign using charging state
                    val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL

                    ma = if (isCharging) kotlin.math.abs(ma) else -kotlin.math.abs(ma)

                    return ma
                }
            } catch (_: Exception) {
                // ignore and try next
            }
        }

        return null
    }

    private fun normalizeCurrentForPlugState(milliAmps: Double, batteryStatus: Intent?): Double {
        val magnitude = abs(milliAmps)
        return if (isPluggedIn(batteryStatus)) magnitude else -magnitude
    }

    private fun readBatteryTemperatureC(batteryStatus: Intent?): Double? {
        if (batteryStatus == null) return null
        val tempTenthsC = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tempTenthsC == Int.MIN_VALUE) return null
        return tempTenthsC / 10.0
    }

    private fun readBatteryVoltageMillivolts(batteryStatus: Intent?): Int? {
        if (batteryStatus == null) return null
        val voltageMv = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
        return voltageMv.takeIf { it != Int.MIN_VALUE && it > 0 }
    }

    private fun readBatteryPercent(batteryStatus: Intent?): BatteryPercentReading? {
        if (batteryStatus == null) return null
        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return BatteryPercentReading(
            percent = ((level * 100.0) / scale).coerceIn(0.0, 100.0),
            hasFraction = scale > 100
        )
    }

    private fun initializeEnergyTracking() {
        if (sessionStartMs != 0L) return
        if (restoreEnergyTracking()) return

        val now = System.currentTimeMillis()
        sessionStartMs = now
        lastSampleTimestampMs = now
        lastPluggedState = isPluggedIn(readBatteryStatus())
        energyHistory.clear()
        energyHistory.add(EnergyPoint(now, 0.0, 0.0, latestGraphBatteryPercent, null, latestTemperatureC, latestVoltageMv))
        persistEnergyTracking()
    }

    private fun restoreEnergyTracking(): Boolean {
        val prefs = getSharedPreferences(energyPrefsName, Context.MODE_PRIVATE)
        val restoredSessionStartMs = prefs.getLong(energySessionStartKey, 0L)
        val restoredTotal = prefs.getFloat(energyTotalKey, Float.NaN)
        val restoredChargeTotal = prefs.getFloat(chargeTotalKey, Float.NaN)
        val restoredHistory = prefs.getString(energyHistoryKey, null)

        if (restoredSessionStartMs <= 0L || restoredTotal.isNaN() || restoredHistory.isNullOrBlank()) {
            return false
        }

        val restoredPoints = restoredHistory.split("|").mapNotNull { encodedPoint ->
            val parts = encodedPoint.split(",")
            if (parts.size < 2) return@mapNotNull null
            val timestampMs = parts[0].toLongOrNull() ?: return@mapNotNull null
            val energyMilliWattHours = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val chargeMilliAmpHours = parts.getOrNull(2)?.toDoubleOrNull()
                ?: (energyMilliWattHours / 4.0)
            val batteryPercent = parts.getOrNull(3)?.toDoubleOrNull()
            val currentMilliAmps = parts.getOrNull(4)?.toDoubleOrNull()
            val temperatureC = parts.getOrNull(5)?.toDoubleOrNull()
            val voltageMv = parts.getOrNull(6)?.toIntOrNull()
            EnergyPoint(timestampMs, energyMilliWattHours, chargeMilliAmpHours, batteryPercent, currentMilliAmps, temperatureC, voltageMv)
        }

        if (restoredPoints.isEmpty()) return false

        sessionStartMs = restoredSessionStartMs
        lastSampleTimestampMs = System.currentTimeMillis()
        totalNetEnergyMilliWattHours = restoredTotal.toDouble()
        totalNetChargeMilliAmpHours = if (restoredChargeTotal.isNaN()) {
            restoredPoints.last().chargeMilliAmpHours
        } else {
            restoredChargeTotal.toDouble()
        }
        lastPluggedState = isPluggedIn(readBatteryStatus())
        energyHistory.clear()
        energyHistory.addAll(restoredPoints)
        trimEnergyHistory(lastSampleTimestampMs)
        return true
    }

    private fun persistEnergyTracking() {
        if (sessionStartMs == 0L || energyHistory.isEmpty()) return

        val encodedHistory = energyHistory.joinToString("|") { point ->
            "${point.timestampMs},${point.energyMilliWattHours},${point.chargeMilliAmpHours},${point.batteryPercent ?: ""},${point.currentMilliAmps ?: ""},${point.temperatureC ?: ""},${point.voltageMv ?: ""}"
        }

        getSharedPreferences(energyPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putLong(energySessionStartKey, sessionStartMs)
            .putFloat(energyTotalKey, totalNetEnergyMilliWattHours.toFloat())
            .putFloat(chargeTotalKey, totalNetChargeMilliAmpHours.toFloat())
            .putString(energyHistoryKey, encodedHistory)
            .apply()
    }

    private fun isPluggedIn(batteryStatus: Intent?): Boolean {
        if (batteryStatus == null) return false
        return batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private data class CurrentDisplay(
        val text: CharSequence
    )

    private data class BatteryPercentReading(
        val percent: Double,
        val hasFraction: Boolean
    )

    private data class EnergyPoint(
        val timestampMs: Long,
        val energyMilliWattHours: Double,
        val chargeMilliAmpHours: Double,
        val batteryPercent: Double?,
        val currentMilliAmps: Double?,
        val temperatureC: Double?,
        val voltageMv: Int?
    )

    private enum class RightAxisMode(val storageValue: String, val label: String) {
        BATTERY(RIGHT_AXIS_BATTERY, "Batt %"),
        TEMPERATURE(RIGHT_AXIS_TEMPERATURE, "Temp"),
        VOLTAGE(RIGHT_AXIS_VOLTAGE, "Volt"),
        CURRENT(RIGHT_AXIS_CURRENT, "mA");

        fun next(): RightAxisMode {
            val modes = entries
            return modes[(ordinal + 1) % modes.size]
        }

        companion object {
            fun fromStorage(value: String?): RightAxisMode {
                return entries.firstOrNull { it.storageValue == value } ?: BATTERY
            }
        }
    }

    private fun addSampleAndFormat(
        milliAmps: Double,
        temperatureC: Double?,
        voltageMv: Int?,
        batteryStatus: Intent?
    ): CurrentDisplay {
        val pluggedNow = isPluggedIn(batteryStatus)
        if (pluggedNow != lastPluggedState) {
            recentMilliAmpSamples.clear()
            lastPluggedState = pluggedNow
        }

        recentMilliAmpSamples.addLast(milliAmps)
        while (recentMilliAmpSamples.size > maxSamples) {
            recentMilliAmpSamples.removeFirst()
        }

        val avg = recentMilliAmpSamples.average()
        val now = System.currentTimeMillis()
        integrateEnergy(now, avg, voltageMv, temperatureC)

        // Small values around zero tend to flicker. Display them as idle.
        val filtered = if (abs(avg) < 20.0) 0.0 else avg
        val rounded = (filtered / 10.0).roundToInt() * 10
        latestRoundedMilliAmps = rounded
        latestTemperatureC = temperatureC

        return CurrentDisplay(
            text = buildFloatingDisplayText(now)
        )
    }

    private fun integrateEnergy(now: Long, averageMilliAmps: Double, voltageMv: Int?, temperatureC: Double?) {
        if (lastSampleTimestampMs == 0L) {
            lastSampleTimestampMs = now
            return
        }

        val deltaMs = now - lastSampleTimestampMs
        lastSampleTimestampMs = now
        if (deltaMs <= 0L) return

        val elapsedHours = deltaMs / 3_600_000.0
        val volts = (voltageMv ?: 4000) / 1000.0
        totalNetEnergyMilliWattHours += averageMilliAmps * volts * elapsedHours
        totalNetChargeMilliAmpHours += averageMilliAmps * elapsedHours

        energyHistory.add(EnergyPoint(
            now,
            totalNetEnergyMilliWattHours,
            totalNetChargeMilliAmpHours,
            latestGraphBatteryPercent,
            averageMilliAmps,
            temperatureC,
            voltageMv
        ))
        trimEnergyHistory(now)
        capacityDisplayState = capacityEstimator.processSample(
            batteryPercent = latestBatteryPercent,
            totalChargeMah = totalNetChargeMilliAmpHours,
            averageMilliAmps = averageMilliAmps,
            temperatureC = temperatureC,
            voltageMv = voltageMv
        )
        persistEnergyTracking()
    }

    private fun trimEnergyHistory(now: Long, maxAgeMs: Long = maxEnergyHistoryAgeMs) {
        val cutoffMs = now - maxAgeMs
        while (energyHistory.size > 1 && energyHistory.first().timestampMs < cutoffMs) {
            energyHistory.removeAt(0)
        }
    }

    private fun formatEnergy(energyMilliWattHours: Double): String {
        val absoluteEnergy = abs(energyMilliWattHours)
        return if (absoluteEnergy >= 1000.0) {
            String.format(Locale.US, "%.2fWh", absoluteEnergy / 1000.0)
        } else {
            String.format(Locale.US, "%.0fmWh", absoluteEnergy)
        }
    }

    private fun formatCharge(chargeMilliAmpHours: Double): String {
        val absoluteCharge = abs(chargeMilliAmpHours)
        return if (absoluteCharge >= 1000.0) {
            String.format(Locale.US, "%.2fAh", absoluteCharge / 1000.0)
        } else {
            String.format(Locale.US, "%.0fmAh", absoluteCharge)
        }
    }

    private fun formatSelectedEnergy(): String {
        return if (isChargeUnitSelected()) {
            formatSignedCharge(totalNetChargeMilliAmpHours)
        } else {
            formatSignedEnergyValue(totalNetEnergyMilliWattHours)
        }
    }

    private fun formatSignedEnergyValue(energyMilliWattHours: Double): String {
        val sign = when {
            energyMilliWattHours > 0.0 -> "+"
            energyMilliWattHours < 0.0 -> "-"
            else -> ""
        }
        return "$sign${formatEnergy(energyMilliWattHours)}"
    }

    private fun formatSignedCharge(chargeMilliAmpHours: Double): String {
        val sign = when {
            chargeMilliAmpHours > 0.0 -> "+"
            chargeMilliAmpHours < 0.0 -> "-"
            else -> ""
        }
        return "$sign${formatCharge(chargeMilliAmpHours)}"
    }

    private fun selectedEnergyUnit(): String {
        return getSharedPreferences(displayPrefsName, Context.MODE_PRIVATE)
            .getString(energyUnitKey, ENERGY_UNIT_MWH)
            ?.takeIf { it == ENERGY_UNIT_MAH || it == ENERGY_UNIT_MWH }
            ?: ENERGY_UNIT_MWH
    }

    private fun isChargeUnitSelected(): Boolean = selectedEnergyUnit() == ENERGY_UNIT_MAH

    private fun toggleEnergyUnit() {
        val nextUnit = if (isChargeUnitSelected()) ENERGY_UNIT_MWH else ENERGY_UNIT_MAH
        getSharedPreferences(displayPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(energyUnitKey, nextUnit)
            .apply()
        refreshFloatingOverlayText()
        refreshGraphOverlay()
    }

    private fun refreshGraphOverlay() {
        if (graphOverlayView == null) return
        removeGraphOverlay()
        showGraphOverlay()
    }

    private fun selectedTemperatureUnit(): String {
        return getSharedPreferences(displayPrefsName, Context.MODE_PRIVATE)
            .getString(temperatureUnitKey, TEMPERATURE_UNIT_C)
            ?.takeIf { it == TEMPERATURE_UNIT_C || it == TEMPERATURE_UNIT_F }
            ?: TEMPERATURE_UNIT_C
    }

    private fun isFahrenheitSelected(): Boolean = selectedTemperatureUnit() == TEMPERATURE_UNIT_F

    private fun toggleTemperatureUnit() {
        val nextUnit = if (isFahrenheitSelected()) TEMPERATURE_UNIT_C else TEMPERATURE_UNIT_F
        getSharedPreferences(displayPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(temperatureUnitKey, nextUnit)
            .apply()
        refreshFloatingOverlayText()
        updateGraphOverlay()
    }

    private fun selectedRightAxisMode(): RightAxisMode? {
        if (!ProFeatureGate.isProEnabled(this)) return null

        return RightAxisMode.fromStorage(
            getSharedPreferences(displayPrefsName, Context.MODE_PRIVATE)
                .getString(rightAxisModeKey, RIGHT_AXIS_BATTERY)
        )
    }

    private fun cycleRightAxisMode() {
        val currentMode = selectedRightAxisMode() ?: return
        val nextMode = currentMode.next()
        getSharedPreferences(displayPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(rightAxisModeKey, nextMode.storageValue)
            .apply()
        updateGraphOverlay()
    }

    private fun formatElapsedTime(now: Long): String {
        val elapsedSeconds = ((now - sessionStartMs).coerceAtLeast(0L)) / 1000L
        if (elapsedSeconds < 60) {
            return "${elapsedSeconds}s"
        }

        val elapsedMinutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val hours = elapsedMinutes / 60
        val minutes = elapsedMinutes % 60
        return if (hours > 0) {
            String.format(Locale.US, "%dh %02dm", hours, minutes)
        } else {
            String.format(Locale.US, "%dm %02ds", minutes, seconds)
        }
    }

    private fun formatElapsedClock(now: Long): String {
        val rawElapsedSeconds = ((now - sessionStartMs).coerceAtLeast(0L)) / 1000L
        val elapsedSeconds = ((rawElapsedSeconds + 5L) / 10L) * 10L
        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    }

    private fun buildFloatingDisplayText(now: Long): SpannableString {
        val parts = ArrayList<String>()

        if (isDisplayFieldEnabled(displayTimeKey)) {
            parts.add(formatElapsedClock(now))
        }
        if (isDisplayFieldEnabled(displayCurrentKey)) {
            parts.add(formatCurrentText())
        }
        if (isDisplayFieldEnabled(displayTemperatureKey)) {
            parts.add(formatTemperatureText())
        }
        if (isDisplayFieldEnabled(displayVoltageKey)) {
            parts.add(formatVoltageText())
        }
        if (isDisplayFieldEnabled(displayEnergyKey)) {
            parts.add(formatSelectedEnergy())
        }
        if (isDisplayFieldEnabled(displayBatteryKey)) {
            parts.add(formatBatteryPercentText())
        }

        val text = parts.takeIf { it.isNotEmpty() }?.joinToString(" ") ?: formatCurrentText()
        val dotColor = foregroundCapacityStatusDotColor(now)
        val displayText = if (dotColor == null) text else "$text \u2022"
        return styleLiveDisplayText(displayText, useLightOverlayPalette = isLightOverlayEnabled()).apply {
            if (dotColor != null) {
                setSpan(
                    ForegroundColorSpan(dotColor),
                    displayText.length - 1,
                    displayText.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun foregroundCapacityStatusDotColor(now: Long): Int? {
        return when {
            capacityDisplayState.isEventActive -> {
                val showDot = ((now - graphOverlayCreatedAtMs.coerceAtLeast(sessionStartMs)) / CALIBRATION_DOT_BLINK_MS) % 2L == 0L
                if (showDot) graphCoolTextColor else Color.TRANSPARENT
            }
            capacityDisplayState.isEventArmed -> Color.rgb(255, 220, 35)
            else -> null
        }
    }

    private fun buildFullLiveDisplayText(now: Long): String {
        return listOf(
            formatElapsedClock(now),
            formatCurrentText(),
            formatTemperatureText(),
            formatVoltageText(),
            formatSelectedEnergy(),
            formatBatteryPercentText()
        ).joinToString(" ")
    }

    private fun buildGraphLiveDisplayText(now: Long): String {
        return listOf(
            formatGraphElapsedClock(now),
            formatCurrentText(),
            formatTemperatureText(),
            formatVoltageText(),
            formatSelectedGraphEnergy(),
            formatGraphBatteryPercentText()
        ).joinToString(" ")
    }

    private fun formatGraphElapsedClock(now: Long): String {
        val zeroMs = graphDisplayZeroTimeMs()
        val rawElapsedSeconds = ((now - zeroMs).coerceAtLeast(0L)) / 1000L
        val elapsedSeconds = ((rawElapsedSeconds + 5L) / 10L) * 10L
        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatSelectedGraphEnergy(): String {
        val relativeCharge = totalNetChargeMilliAmpHours - graphDisplayZeroChargeMilliAmpHours
        val relativeEnergy = totalNetEnergyMilliWattHours - graphDisplayZeroEnergyMilliWattHours
        return if (isChargeUnitSelected()) {
            formatSignedCharge(relativeCharge)
        } else {
            formatSignedEnergyValue(relativeEnergy)
        }
    }

    private fun isDisplayFieldEnabled(key: String): Boolean {
        return getSharedPreferences(displayPrefsName, Context.MODE_PRIVATE).getBoolean(key, true)
    }

    private fun setDisplayFieldEnabled(key: String, enabled: Boolean) {
        getSharedPreferences(displayPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, enabled)
            .apply()
    }

    private fun formatCurrentText(): String {
        return latestRoundedMilliAmps?.let {
            String.format(Locale.US, "%dmA", it)
        } ?: "--mA"
    }

    private fun formatTemperatureText(): String {
        return latestTemperatureC?.let { temperatureC ->
            if (isFahrenheitSelected()) {
                String.format(Locale.US, "%.0f\u00B0F", temperatureC * 9.0 / 5.0 + 32.0)
            } else {
                String.format(Locale.US, "%.0f\u00B0C", temperatureC)
            }
        } ?: "--\u00B0${selectedTemperatureUnit()}"
    }

    private fun formatVoltageText(): String {
        return latestVoltageMv?.let {
            String.format(Locale.US, "%.2fV", it / 1000.0)
        } ?: "--V"
    }

    private fun formatBatteryPercentText(): String {
        return latestBatteryPercent?.let {
            String.format(Locale.US, "%d%%", it)
        } ?: "--%"
    }

    private fun formatGraphBatteryPercentText(): String {
        return latestGraphBatteryPercent?.let {
            if (latestBatteryPercentHasFraction) {
                String.format(Locale.US, "%.1f%%", it)
            } else {
                String.format(Locale.US, "%.0f%%", it)
            }
        } ?: "--%"
    }

    private fun loadOverlayPosition(): Pair<Int, Int> {
        return OverlayPositionPreference.loadPosition(this) ?: (0 to 0)
    }

    private fun saveOverlayPosition(x: Int, y: Int) {
        OverlayPositionPreference.savePosition(this, x, y)
    }

    private fun resetForegroundOverlayToCenter() {
        OverlayPositionPreference.resetPosition(this)
        val view = overlayView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = 0
        params.y = 0
        updateOverlayLayoutSafely(view, params)
    }

    private fun isOverlayLocked(): Boolean {
        return getSharedPreferences(overlayPrefsName, Context.MODE_PRIVATE)
            .getBoolean(overlayLockedKey, false)
    }

    private fun setOverlayLocked(locked: Boolean) {
        if (locked) {
            (overlayView?.layoutParams as? WindowManager.LayoutParams)?.let { params ->
                saveOverlayPosition(params.x, params.y)
            }
        }

        getSharedPreferences(overlayPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(overlayLockedKey, locked)
            .apply()
    }

    private fun isLightOverlayEnabled(): Boolean {
        return OverlayThemePreference.isLightBackgroundEnabled(this)
    }

    private fun pillBackground(): GradientDrawable {
        val light = isLightOverlayEnabled()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(if (light) overlayLightBackgroundColor else overlayDarkBackgroundColor)
            setStroke(2, if (light) Color.argb(130, 20, 24, 30) else Color.argb(125, 255, 255, 255))
            cornerRadius = 44f
        }
    }

    private fun createOverlayIfAllowed() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = TextView(this).apply {
            text = lastDisplay.text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(overlayTextColor)
            background = pillBackground()
            setPadding(24, 10, 24, 10)
            minWidth = 128
            gravity = Gravity.CENTER
            elevation = 12f

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false

            setOnTouchListener { view, event ->
                val layoutParams = view.layoutParams as? WindowManager.LayoutParams
                    ?: return@setOnTouchListener false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (isOverlayLocked()) {
                            val dx = (event.rawX - initialTouchX).toInt()
                            val dy = (event.rawY - initialTouchY).toInt()
                            if (abs(dx) > 10 || abs(dy) > 10) {
                                isDragging = true
                            }
                            return@setOnTouchListener true
                        }

                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()

                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                        }

                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        updateOverlayLayoutSafely(view, layoutParams)
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            saveOverlayPosition(layoutParams.x, layoutParams.y)
                        } else {
                            showGraphOverlay()
                        }
                        true
                    }

                    else -> false
                }
            }
        }

        val (savedX, savedY) = loadOverlayPosition()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = savedX
            y = savedY
        }

        try {
            windowManager?.addView(overlayView, params)
            handler.removeCallbacks(foregroundIndicatorRunnable)
            handler.postDelayed(foregroundIndicatorRunnable, FOREGROUND_INDICATOR_UPDATE_MS)
        } catch (_: Exception) {
            overlayView = null
        }
    }

    private fun updateOverlayLayoutSafely(view: View, layoutParams: WindowManager.LayoutParams) {
        try {
            windowManager?.updateViewLayout(view, layoutParams)
        } catch (_: Exception) {
            overlayView = null
        }
    }

    private fun updateGraphLayoutSafely(view: View, layoutParams: WindowManager.LayoutParams) {
        try {
            windowManager?.updateViewLayout(view, layoutParams)
        } catch (_: Exception) {
            graphOverlayView = null
        }
    }

    private fun showGraphOverlay() {
        if (graphOverlayView != null) return

        val manager = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }

        val summaryView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }

        val graphView = EnergyGraphView(this).apply {
            onRightAxisLabelClick = { cycleRightAxisMode() }
            onViewportChanged = { updateGraphZoomResetButton() }
            onSingleFingerDragDelta = { dx, dy ->
                val view = graphOverlayView
                val layoutParams = view?.layoutParams as? WindowManager.LayoutParams
                if (view != null && layoutParams != null) {
                    layoutParams.x += dx
                    layoutParams.y += dy
                    updateGraphLayoutSafely(view, layoutParams)
                }
            }
        }
        graphOverlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(graphBackgroundColor)
            }
            setPadding(28, 24, 28, 24)
            elevation = 20f

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            setOnTouchListener { view, event ->
                val layoutParams = view.layoutParams as? WindowManager.LayoutParams
                    ?: return@setOnTouchListener false

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }

                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (graphView.beginExternalZoomGesture(event, view)) {
                            true
                        } else {
                            graphView.isZoomModeArmed()
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (graphView.isZoomModeArmed()) {
                            if (event.pointerCount >= 2) {
                                graphView.updateExternalZoomGesture(event, view)
                            }
                            return@setOnTouchListener true
                        }
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        updateGraphLayoutSafely(view, layoutParams)
                        true
                    }

                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_CANCEL,
                    MotionEvent.ACTION_UP -> {
                        graphView.endExternalZoomGesture()
                        true
                    }
                    else -> false
                }
            }

            addView(LinearLayout(this@BatteryCurrentService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(this@BatteryCurrentService).apply {
                    text = "\u2022"
                    textSize = 24f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.rgb(255, 220, 35))
                    gravity = Gravity.CENTER
                    visibility = View.GONE
                    includeFontPadding = false
                    setPadding(0, 0, 8, 0)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))

                addView(TextView(this@BatteryCurrentService).apply {
                    text = ProFeatureGate.appTitle(this@BatteryCurrentService)
                    textSize = 17f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                addView(Button(this@BatteryCurrentService).apply {
                    styleCloseButton(this)
                    text = "x"
                    setOnClickListener { removeGraphOverlay() }
                })
            })

            addView(summaryView.apply {
                setPadding(0, 10, 0, 18)
                setSingleLine(true)
            })

            addView(LinearLayout(this@BatteryCurrentService).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(graphView, LinearLayout.LayoutParams(878, 654))
            })

            addView(createGraphMenuCollapseToggle())

            addView(createDisplayTogglePanel())

            addView(createGraphActionPanel())

            addView(createCapacityEstimateView())

            addView(TextView(this@BatteryCurrentService).apply {
                text = ProFeatureGate.displayVersion(this@BatteryCurrentService)
                textSize = 10f
                setTextColor(Color.argb(150, 255, 255, 255))
                gravity = Gravity.END
                setPadding(0, 2, 8, 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        graphOverlayCreatedAtMs = System.currentTimeMillis()
        updateGraphOverlay()
        handler.removeCallbacks(graphBlinkRunnable)
        handler.postDelayed(graphBlinkRunnable, GRAPH_BLINK_UPDATE_MS)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            manager.addView(graphOverlayView, params)
        } catch (_: Exception) {
            graphOverlayView = null
        }
    }

    private fun updateGraphOverlay() {
        val container = graphOverlayView ?: return
        val titleRow = container.getChildAt(0) as? LinearLayout
        val summaryView = container.getChildAt(1) as? TextView ?: return
        val graphView = ((container.getChildAt(2) as? LinearLayout)?.getChildAt(0) as? EnergyGraphView) ?: return
        val capacityPanel = container.getChildAt(6) as? LinearLayout
        val versionView = container.getChildAt(container.childCount - 1) as? TextView

        val now = System.currentTimeMillis()
        capacityDisplayState = capacityEstimator.displayState()
        summaryView.text = buildLiveSummary(now)
        graphView.setPoints(
            graphDisplayPoints(),
            selectedEnergyUnit(),
            graphDisplayZeroTimeMs(),
            selectedRightAxisMode(),
            isFahrenheitSelected()
        )
        updateCapacityEstimatePanel(capacityPanel)
        updateGraphMenuVisibility(container)
        updateCapacityEventIndicator(titleRow, now)
        updateVersionIndicator(versionView, now)
        updateGraphZoomResetButton()
    }

    private fun createGraphMenuCollapseToggle(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 0, 14, 0)
            addView(TextView(this@BatteryCurrentService).apply {
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(255, 220, 35))
                gravity = Gravity.CENTER
                minWidth = 72
                minHeight = 44
                includeFontPadding = false
                setPadding(22, 6, 22, 6)
                text = if (graphMenuCollapsed) "\u25BC" else "\u25B2"
                isClickable = true
                setOnClickListener {
                    graphMenuCollapsed = !graphMenuCollapsed
                    removeCapacityHistoryPopup()
                    updateGraphOverlay()
                }
            })
        }
    }

    private fun updateGraphMenuVisibility(container: LinearLayout) {
        val toggleText = (container.getChildAt(3) as? LinearLayout)?.getChildAt(0) as? TextView
        toggleText?.text = if (graphMenuCollapsed) "\u25BC" else "\u25B2"

        container.childAtOrNull(4)?.visibility = if (graphMenuCollapsed) View.GONE else View.VISIBLE
        container.childAtOrNull(5)?.visibility = if (graphMenuCollapsed) View.GONE else View.VISIBLE
        if (graphMenuCollapsed) {
            container.childAtOrNull(6)?.visibility = View.GONE
        }
        container.childAtOrNull(container.childCount - 1)?.visibility =
            if (graphMenuCollapsed) View.GONE else View.VISIBLE
    }

    private fun LinearLayout.childAtOrNull(index: Int): View? {
        return if (index in 0 until childCount) getChildAt(index) else null
    }

    private fun updateGraphZoomResetButton() {
        val graphView = ((graphOverlayView?.getChildAt(2) as? LinearLayout)?.getChildAt(0) as? EnergyGraphView)
        graphView?.invalidate()
    }

    private fun updateVersionIndicator(versionView: TextView?, now: Long) {
        if (versionView == null) return
        versionView.text = ProFeatureGate.displayVersion(this)
    }

    private fun updateCapacityEventIndicator(titleRow: LinearLayout?, now: Long) {
        val dotView = titleRow?.getChildAt(0) as? TextView ?: return
        when {
            capacityDisplayState.isEventActive -> {
                val showDot = ((now - graphOverlayCreatedAtMs) / CALIBRATION_DOT_BLINK_MS) % 2L == 0L
                dotView.visibility = if (showDot) View.VISIBLE else View.INVISIBLE
                dotView.setTextColor(graphCoolTextColor)
            }
            capacityDisplayState.isEventArmed -> {
                dotView.visibility = View.VISIBLE
                dotView.setTextColor(Color.rgb(255, 220, 35))
            }
            else -> {
                dotView.visibility = View.GONE
            }
        }
    }

    private fun createCapacityEstimateView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 0)

            addView(TextView(this@BatteryCurrentService).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                visibility = View.GONE
                isClickable = true
                setOnClickListener {
                    if (ProFeatureGate.isProEnabled(this@BatteryCurrentService)) {
                        showCapacityHistoryPopup()
                    }
                }
            })

            addView(LinearLayout(this@BatteryCurrentService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                visibility = View.GONE

                addView(TextView(this@BatteryCurrentService).apply {
                    textSize = 12f
                    setTextColor(graphWarmTextColor)
                    setSingleLine(false)
                }, LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ))

                addView(Button(this@BatteryCurrentService).apply {
                    styleGraphMenuButton(this, textSizeSp = 11f)
                    text = "Clear"
                    setOnClickListener {
                        if (ProFeatureGate.isProEnabled(this@BatteryCurrentService)) {
                            capacityEstimator.clearWarning()
                            capacityDisplayState = capacityEstimator.displayState()
                        }
                        updateGraphOverlay()
                    }
                })
            })

        }
    }

    private fun updateCapacityEstimatePanel(panel: LinearLayout?) {
        if (panel == null) return
        if (!ProFeatureGate.isProEnabled(this)) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE

        val estimateView = panel.getChildAt(0) as? TextView
        val warningRow = panel.getChildAt(1) as? LinearLayout
        val warningView = warningRow?.getChildAt(0) as? TextView

        val estimateMah = capacityDisplayState.estimateMah
        if (estimateMah != null) {
            estimateView?.text = buildCapacityEstimateText(estimateMah)
            estimateView?.visibility = View.VISIBLE
            estimateView?.isEnabled = true
        } else {
            estimateView?.visibility = View.GONE
            estimateView?.isEnabled = false
            removeCapacityHistoryPopup()
        }

        val warningText = capacityDisplayState.warningText
        if (warningText != null) {
            warningView?.text = warningText
            warningRow?.visibility = View.VISIBLE
        } else {
            warningRow?.visibility = View.GONE
        }
    }

    private fun buildCapacityEstimateText(estimateMah: Int): SpannableString {
        val capacityLabel = "Estimated battery capacity: "
        val capacityValue = String.format(Locale.US, "%dmAh", estimateMah)
        val peukertLabel = "\nLoad Sensitivity: "
        val peukertValue = latestPeukertConstantText() ?: "not enough data"
        val fullText = capacityLabel + capacityValue + peukertLabel + peukertValue
        val peukertLabelStart = capacityLabel.length + capacityValue.length
        val peukertValueStart = peukertLabelStart + peukertLabel.length

        return SpannableString(fullText).apply {
            setSpan(
                ForegroundColorSpan(graphEstimateLabelColor),
                0,
                capacityLabel.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(capacityEstimateColor(estimateMah)),
                capacityLabel.length,
                capacityLabel.length + capacityValue.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(graphEstimateLabelColor),
                peukertLabelStart,
                peukertValueStart,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(graphCoolTextColor),
                peukertValueStart,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun latestPeukertConstantText(): String? {
        val eventsFile = java.io.File(filesDir, "battery_capacity_events.csv")
        if (!eventsFile.exists() || eventsFile.length() == 0L) return null

        return try {
            val lines = eventsFile.readLines().filter { it.isNotBlank() }
            if (lines.size < 2) return null

            val headers = lines.first().split(",").map { it.trim() }
            val peukertIndex = headers.indexOfFirst { header ->
                header.equals("PeukertK", ignoreCase = true) ||
                    header.equals("Peukert_k", ignoreCase = true) ||
                    header.equals("PeukertConstant", ignoreCase = true) ||
                    header.equals("Peukert's constant", ignoreCase = true)
            }
            if (peukertIndex < 0) return null

            lines.asReversed()
                .dropLast(1)
                .mapNotNull { line ->
                    val parts = line.split(",")
                    parts.getOrNull(peukertIndex)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.toDoubleOrNull()
                }
                .firstOrNull()
                ?.let { value -> String.format(Locale.US, "k=%.3f", value) }
        } catch (_: Exception) {
            null
        }
    }

    private fun capacityEstimateColor(estimateMah: Int): Int {
        val originalMah = BatteryCapacityReference.originalCapacityMah(this) ?: return graphCoolTextColor
        if (originalMah <= 0) return graphCoolTextColor

        val reductionPercent = ((originalMah - estimateMah).coerceAtLeast(0) * 100.0) / originalMah
        return when {
            reductionPercent > 30.0 -> graphDischargeTextColor
            reductionPercent > 20.0 -> Color.rgb(255, 145, 40)
            reductionPercent > 10.0 -> graphWarmTextColor
            else -> graphCoolTextColor
        }
    }

    private fun showCapacityHistoryPopup() {
        if (!ProFeatureGate.isProEnabled(this)) return
        val graphContainer = graphOverlayView ?: return
        if (capacityHistoryPopupView != null) {
            removeCapacityHistoryPopup()
            return
        }

        val rows = capacityEstimator.recentDailyEstimates(10)
        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val popup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(Color.argb(238, 24, 27, 32))
                setStroke(2, Color.argb(190, 255, 255, 255))
            }
            setPadding(18, 14, 18, 14)
            elevation = 28f

            addView(LinearLayout(this@BatteryCurrentService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@BatteryCurrentService).apply {
                    text = "Last 10 days capacity estimates"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(this@BatteryCurrentService).apply {
                    styleCloseButton(this)
                    text = "x"
                    setOnClickListener { removeCapacityHistoryPopup() }
                })
            })

            addCapacityHistoryRow("Date", "mAh", "#records", isHeader = true)
            if (rows.isEmpty()) {
                addView(TextView(this@BatteryCurrentService).apply {
                    text = "No daily estimates yet"
                    textSize = 12f
                    setTextColor(Color.argb(220, 255, 255, 255))
                    gravity = Gravity.CENTER
                    setPadding(0, 14, 0, 4)
                })
            } else {
                rows.forEach { row ->
                    addCapacityHistoryRow(
                        dateFormat.format(Date(row.timestampMs)),
                        row.averageCapacityMah.toString(),
                        row.sampleCount.toString(),
                        onClick = { showCapacityEventDetailsPopup(row.timestampMs) }
                    )
                }
            }
        }

        capacityHistoryPopupView = popup
        val insertIndex = (graphContainer.childCount - 1).coerceAtLeast(0)
        graphContainer.addView(popup, insertIndex, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 10, 0, 4)
        })
    }

    private fun LinearLayout.addCapacityHistoryRow(
        dateText: String,
        capacityText: String,
        countText: String,
        isHeader: Boolean = false,
        onClick: (() -> Unit)? = null
    ) {
        addView(LinearLayout(this@BatteryCurrentService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, if (isHeader) 10 else 3, 0, 3)
            isClickable = onClick != null
            if (onClick != null) {
                background = GradientDrawable().apply {
                    cornerRadius = 8f
                    setColor(Color.argb(18, 255, 255, 255))
                }
                setOnClickListener { onClick() }
            }

            addCapacityHistoryCell(dateText, 1.9f, Gravity.START, isHeader)
            addCapacityHistoryCell(capacityText, 1.0f, Gravity.END, isHeader)
            addCapacityHistoryCell(countText, 1.0f, Gravity.END, isHeader)
        })
    }

    private fun LinearLayout.addCapacityHistoryCell(
        value: String,
        weight: Float,
        gravityValue: Int,
        isHeader: Boolean
    ) {
        addView(TextView(this@BatteryCurrentService).apply {
            text = value
            textSize = if (isHeader) 11f else 12f
            setTextColor(if (isHeader) graphEstimateLabelColor else Color.WHITE)
            setTypeface(Typeface.MONOSPACE, if (isHeader) Typeface.BOLD else Typeface.NORMAL)
            gravity = gravityValue
            setSingleLine(true)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight))
    }


    private fun showCapacityEventDetailsPopup(dayTimestampMs: Long) {
        val graphContainer = graphOverlayView ?: return
        removeCapacityEventDetailsPopup()

        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
        val events = capacityEstimator.eventsForDay(dayTimestampMs)
        val popup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(Color.argb(242, 18, 20, 25))
                setStroke(2, Color.argb(190, 255, 255, 255))
            }
            setPadding(18, 14, 18, 14)
            elevation = 32f

            addView(LinearLayout(this@BatteryCurrentService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@BatteryCurrentService).apply {
                    text = "Events for ${dateFormat.format(Date(dayTimestampMs))}"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(this@BatteryCurrentService).apply {
                    styleCloseButton(this)
                    text = "x"
                    setOnClickListener { removeCapacityEventDetailsPopup() }
                })
            })

            val scrollContent = LinearLayout(this@BatteryCurrentService).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 0)
                if (events.isEmpty()) {
                    addView(TextView(this@BatteryCurrentService).apply {
                        text = "No event records for this date"
                        textSize = 12f
                        setTextColor(Color.argb(220, 255, 255, 255))
                        gravity = Gravity.CENTER
                        setPadding(0, 14, 0, 4)
                    })
                } else {
                    events.forEachIndexed { index, event ->
                        addCapacityEventDetailCard(index + 1, event)
                    }
                }
            }

            addView(ScrollView(this@BatteryCurrentService).apply {
                addView(scrollContent)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                460
            ).apply {
                setMargins(0, 6, 0, 0)
            })
        }

        capacityEventDetailsPopupView = popup
        val insertIndex = (graphContainer.childCount - 1).coerceAtLeast(0)
        graphContainer.addView(popup, insertIndex, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 10, 0, 4)
        })
    }

    private fun LinearLayout.addCapacityEventDetailCard(
        eventNumber: Int,
        event: BatteryCapacityEstimator.CapacityEventSummary
    ) {
        val timeFormat = SimpleDateFormat("h:mm a", Locale.US)
        addView(LinearLayout(this@BatteryCurrentService).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.argb(38, 255, 255, 255))
                setStroke(1, Color.argb(80, 255, 255, 255))
            }
            setPadding(14, 12, 14, 12)

            addView(TextView(this@BatteryCurrentService).apply {
                text = "Event $eventNumber (${event.direction})"
                textSize = 12f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(graphEstimateLabelColor)
            })
            addCapacityEventLine("Start", timeFormat.format(Date(event.startTimestampMs)))
            addCapacityEventLine("End", timeFormat.format(Date(event.endTimestampMs)))
            addCapacityEventLine("Avg current", event.avgCurrentMa?.let { String.format(Locale.US, "%.0f mA", it) } ?: "n/a")
            addCapacityEventLine("Avg temp", event.avgTempC?.let { String.format(Locale.US, "%.1f C", it) } ?: "n/a")
            addCapacityEventLine("Avg voltage", event.avgVoltageMv?.let { String.format(Locale.US, "%.0f mV", it) } ?: "n/a")
            addCapacityEventLine("mAh added", String.format(Locale.US, "%d mAh", event.mahAdded))
            addCapacityEventLine("Capacity est", String.format(Locale.US, "%d mAh", event.capacityEstimateMah))
            event.peukertAdjustedCapacityMah?.let {
                addCapacityEventLine("Peukert adjusted", String.format(Locale.US, "%d mAh", it))
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 7, 0, 7)
        })
    }

    private fun LinearLayout.addCapacityEventLine(label: String, value: String) {
        addView(LinearLayout(this@BatteryCurrentService).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@BatteryCurrentService).apply {
                text = "$label:"
                textSize = 11f
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setTextColor(Color.argb(220, 255, 255, 255))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
            addView(TextView(this@BatteryCurrentService).apply {
                text = value
                textSize = 11f
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setTextColor(Color.WHITE)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f))
        })
    }

    private fun showSocCurvePopup() {
        val graphContainer = graphOverlayView ?: return
        removeSocCurvePopup()

        val points = capacityEstimator.socLinearityPoints()
        val popup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 18f
                setColor(Color.argb(244, 18, 20, 25))
                setStroke(2, Color.argb(190, 255, 255, 255))
            }
            setPadding(18, 14, 18, 14)
            elevation = 32f

            addView(LinearLayout(this@BatteryCurrentService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@BatteryCurrentService).apply {
                    text = "SOC linearity deviation"
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(this@BatteryCurrentService).apply {
                    styleCloseButton(this)
                    text = "x"
                    setOnClickListener { removeSocCurvePopup() }
                })
            })

            addView(TextView(this@BatteryCurrentService).apply {
                text = "Deviation from ideal linear SOC. 0.05 means that bucket stores 5% more mAh than the learned average bucket."
                textSize = 11f
                setTextColor(Color.argb(220, 255, 255, 255))
                setPadding(0, 6, 0, 8)
            })

            addView(SocBucketCurveView(this@BatteryCurrentService).apply {
                setPoints(points)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                430
            ))

            val learnedBucketCount = points.size
            val maxDeviation = points.maxOfOrNull { abs(it.deviationFromIdeal) }
            addView(TextView(this@BatteryCurrentService).apply {
                text = if (learnedBucketCount == 0) {
                    "No SOC bucket data yet. Data fills in as battery % changes while monitoring."
                } else {
                    String.format(Locale.US, "%d buckets learned, max deviation %.3f", learnedBucketCount, maxDeviation ?: 0.0)
                }
                textSize = 11f
                setTextColor(graphEstimateLabelColor)
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 0)
            })
        }

        socCurvePopupView = popup
        val insertIndex = (graphContainer.childCount - 1).coerceAtLeast(0)
        graphContainer.addView(popup, insertIndex, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 10, 0, 4)
        })
    }

    private fun removeSocCurvePopup() {
        val view = socCurvePopupView ?: return
        (view.parent as? LinearLayout)?.removeView(view)
        socCurvePopupView = null
    }

    private fun removeCapacityEventDetailsPopup() {
        val view = capacityEventDetailsPopupView ?: return
        (view.parent as? LinearLayout)?.removeView(view)
        capacityEventDetailsPopupView = null
    }

    private fun removeCapacityHistoryPopup() {
        removeCapacityEventDetailsPopup()
        removeSocCurvePopup()
        val view = capacityHistoryPopupView ?: return
        (view.parent as? LinearLayout)?.removeView(view)
        capacityHistoryPopupView = null
    }

    private fun createDisplayTogglePanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10, 0, 0)

            addView(createDisplayToggleRow(
                "Time" to displayTimeKey,
                "Current" to displayCurrentKey,
                "Temp" to displayTemperatureKey
            ))
            addView(createDisplayToggleRow(
                "Volt" to displayVoltageKey,
                energyDisplayLabel() to displayEnergyKey,
                "Battery%" to displayBatteryKey
            ))
        }
    }

    private fun createGraphActionPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 18, 0, 0)

            addView(LinearLayout(this@BatteryCurrentService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER

                addView(Button(this@BatteryCurrentService).apply {
                    styleGraphMenuButton(this)
                    setTemperatureToggleText(this)
                    setOnClickListener {
                        toggleTemperatureUnit()
                        setTemperatureToggleText(this)
                    }
                })

                addView(Button(this@BatteryCurrentService).apply {
                    styleGraphMenuButton(this)
                    text = "Background"
                    setOnClickListener { removeOverlay() }
                })

                addView(Button(this@BatteryCurrentService).apply {
                    styleGraphMenuButton(this)
                    text = "Stop"
                    setOnClickListener { stopMonitoring() }
                })
            })

            addView(LinearLayout(this@BatteryCurrentService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER

                addView(createLockToggleButton())
                addView(createEnergyUnitToggleButton())
                addView(createResetButton())
            })

            addView(LinearLayout(this@BatteryCurrentService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER

                addView(Button(this@BatteryCurrentService).apply {
                    styleGraphMenuButton(this)
                    text = "SOC Curve"
                    setOnClickListener { showSocCurvePopup() }
                })
            })
        }
    }

    private fun createLockToggleButton(): Button {
        return Button(this).apply {
            styleGraphMenuButton(this)
            setLockToggleText(this)
            setOnClickListener {
                setOverlayLocked(!isOverlayLocked())
                setLockToggleText(this)
            }
        }
    }

    private fun setLockToggleText(button: Button) {
        button.text = if (isOverlayLocked()) "Unlock" else "Lock"
    }

    private fun createEnergyUnitToggleButton(): Button {
        return Button(this).apply {
            styleGraphMenuButton(this)
            setEnergyUnitToggleText(this)
            setOnClickListener {
                toggleEnergyUnit()
                setEnergyUnitToggleText(this)
            }
        }
    }

    private fun setEnergyUnitToggleText(button: Button) {
        button.text = selectedEnergyUnit()
    }

    private fun energyDisplayLabel(): String {
        return if (isChargeUnitSelected()) "Charge" else "Energy"
    }

    private fun setTemperatureToggleText(button: Button) {
        button.text = if (isFahrenheitSelected()) "\u00B0F" else "\u00B0C"
    }

    private fun createResetButton(): Button {
        return Button(this).apply {
            styleGraphMenuButton(this, textColor = graphDischargeTextColor)
            text = "Clear Data"
            setOnClickListener {
                val row = parent as? LinearLayout ?: return@setOnClickListener
                showResetConfirmation(row, this)
            }
        }
    }

    private fun showResetConfirmation(row: LinearLayout, resetButton: Button) {
        if (row.indexOfChild(resetButton) < 0) return

        row.removeAllViews()
        row.addView(TextView(this).apply {
            text = "Clear memory and reset graph?"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(12, 0, 12, 0)
        })
        row.addView(Button(this).apply {
            styleGraphMenuButton(this, textColor = graphDischargeTextColor)
            text = "Yes"
            setOnClickListener {
                resetMeasurementAndGraph()
                updateGraphOverlay()
                restoreSecondaryActionRow(row)
            }
        })
        row.addView(Button(this).apply {
            styleGraphMenuButton(this)
            text = "Cancel"
            setOnClickListener {
                restoreSecondaryActionRow(row)
            }
        })
    }

    private fun restoreSecondaryActionRow(row: LinearLayout) {
        row.removeAllViews()
        row.addView(createLockToggleButton())
        row.addView(createEnergyUnitToggleButton())
        row.addView(createResetButton())
    }

    private fun createDisplayToggleRow(vararg toggles: Pair<String, String>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            toggles.forEach { (label, key) ->
                addView(createDisplayToggleButton(label, key))
            }
        }
    }

    private fun createDisplayToggleButton(label: String, key: String): Button {
        return Button(this).apply {
            styleGraphMenuButton(
                this,
                textSizeSp = 12f,
                backgroundColor = Color.rgb(214, 245, 219),
                textColor = Color.rgb(45, 80, 45)
            )
            setDisplayToggleText(this, label, key)
            setOnClickListener {
                setDisplayFieldEnabled(key, !isDisplayFieldEnabled(key))
                setDisplayToggleText(this, label, key)
                refreshFloatingOverlayText()
            }
        }
    }

    private fun setDisplayToggleText(button: Button, label: String, key: String) {
        val mark = if (isDisplayFieldEnabled(key)) "x" else " "
        button.text = "[$mark] $label"
    }

    private fun styleGraphMenuButton(
        button: Button,
        textSizeSp: Float = 13f,
        backgroundColor: Int = Color.WHITE,
        textColor: Int = Color.rgb(70, 25, 10)
    ) {
        button.apply {
            textSize = textSizeSp
            isAllCaps = false
            includeFontPadding = false
            setSingleLine(true)
            backgroundTintList = ColorStateList.valueOf(backgroundColor)
            setTextColor(textColor)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(26, 14, 26, 14)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(12, 7, 12, 7)
            }
        }
    }

    private fun styleCloseButton(button: Button) {
        button.apply {
            textSize = 15f
            isAllCaps = false
            includeFontPadding = false
            setSingleLine(true)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f
                setColor(Color.argb(20, 255, 255, 255))
                setStroke(2, Color.argb(190, 255, 255, 255))
            }
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(18, 8, 18, 8)
        }
    }

    private fun refreshFloatingOverlayText() {
        val now = System.currentTimeMillis()
        val text = buildFloatingDisplayText(now)
        lastDisplay = lastDisplay.copy(text = text)
        overlayView?.apply {
            this.text = text
            background = pillBackground()
        }
    }

    private fun buildLiveSummary(now: Long): SpannableString {
        return styleLiveDisplayText(buildGraphLiveDisplayText(now), useLightOverlayPalette = false)
    }

    private fun styleLiveDisplayText(text: String, useLightOverlayPalette: Boolean): SpannableString {
        val summary = SpannableString(text)
        val neutralColor = if (useLightOverlayPalette) Color.rgb(28, 31, 36) else overlayTextColor
        val chargeTextColor = if (useLightOverlayPalette) Color.rgb(20, 125, 70) else graphChargeTextColor
        val dischargeTextColor = if (useLightOverlayPalette) Color.rgb(185, 45, 45) else graphDischargeTextColor
        val warmTextColor = if (useLightOverlayPalette) Color.rgb(190, 105, 0) else graphWarmTextColor
        val coolTextColor = if (useLightOverlayPalette) Color.rgb(20, 125, 70) else graphCoolTextColor
        val chargeColor = if ((latestRoundedMilliAmps ?: 0) >= 0) {
            chargeTextColor
        } else {
            dischargeTextColor
        }
        val temperatureColor = latestTemperatureC?.let { temperature ->
            when {
                temperature > 40.0 -> dischargeTextColor
                temperature >= 30.0 -> warmTextColor
                else -> coolTextColor
            }
        } ?: neutralColor

        summary.setSpan(
            ForegroundColorSpan(neutralColor),
            0,
            summary.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        summary.colorSpan(formatCurrentText(), chargeColor)
        summary.colorSpan(formatSelectedEnergy(), chargeColor)
        summary.colorSpan(formatSelectedGraphEnergy(), chargeColor)
        summary.colorSpan(formatTemperatureText(), temperatureColor)
        return summary
    }

    private fun SpannableString.colorSpan(target: String, color: Int) {
        val start = toString().indexOf(target)
        if (start < 0) return
        setSpan(
            ForegroundColorSpan(color),
            start,
            start + target.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun removeGraphOverlay() {
        removeCapacityHistoryPopup()
        removeSocCurvePopup()
        val view = graphOverlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
            // View may already be detached.
        }
        graphOverlayView = null
        handler.removeCallbacks(graphBlinkRunnable)
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun removeOverlay() {
        removeCapacityHistoryPopup()
        removeGraphOverlay()
        val view = overlayView
        if (view != null) {
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
                // View may already be detached.
            }
        }
        overlayView = null
        handler.removeCallbacks(foregroundIndicatorRunnable)
        windowManager = null
    }

    private fun stopMonitoring() {
        setMonitoringRunning(false)
        persistEnergyTracking()
        stopSelf()
    }

    private fun resetMeasurementAndGraph() {
        val now = System.currentTimeMillis()

        sessionStartMs = now
        lastSampleTimestampMs = now
        totalNetEnergyMilliWattHours = 0.0
        totalNetChargeMilliAmpHours = 0.0
        capacityEstimator.resetSegment(totalNetChargeMilliAmpHours)
        graphDisplayZeroTimestampMs = 0L
        graphDisplayZeroEnergyMilliWattHours = 0.0
        graphDisplayZeroChargeMilliAmpHours = 0.0
        lastPluggedState = isPluggedIn(readBatteryStatus())

        energyHistory.clear()
        energyHistory.add(EnergyPoint(
            now,
            0.0,
            0.0,
            latestGraphBatteryPercent,
            latestRoundedMilliAmps?.toDouble(),
            latestTemperatureC,
            latestVoltageMv
        ))

        // Overwrite persisted graph/measurement snapshot so old graph data does not return
        // after the app/service restarts. This does not modify CSV estimator data files.
        getSharedPreferences(energyPrefsName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        persistEnergyTracking()

        graphOverlayView
            ?.let { ((it as? LinearLayout)?.getChildAt(2) as? LinearLayout)?.getChildAt(0) as? EnergyGraphView }
            ?.resetViewport()

        refreshFloatingOverlayText()
    }

    private fun graphDisplayZeroTimeMs(): Long {
        return graphDisplayZeroTimestampMs.takeIf { it > 0L } ?: sessionStartMs
    }

    private fun graphDisplayPoints(): List<EnergyPoint> {
        val zeroMs = graphDisplayZeroTimestampMs
        if (zeroMs <= 0L) return energyHistory.toList()

        val points = ArrayList<EnergyPoint>()
        points.add(EnergyPoint(
            zeroMs,
            0.0,
            0.0,
            latestGraphBatteryPercent,
            latestRoundedMilliAmps?.toDouble(),
            latestTemperatureC,
            latestVoltageMv
        ))

        energyHistory
            .asSequence()
            .filter { it.timestampMs >= zeroMs }
            .forEach { point ->
                points.add(point.copy(
                    energyMilliWattHours = point.energyMilliWattHours - graphDisplayZeroEnergyMilliWattHours,
                    chargeMilliAmpHours = point.chargeMilliAmpHours - graphDisplayZeroChargeMilliAmpHours
                ))
            }

        return points
    }

    private fun buildNotification(text: String): Notification {
        val showOverlayIntent = Intent(this, BatteryCurrentService::class.java).apply {
            action = ACTION_SHOW_OVERLAY
        }
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val showOverlayPendingIntent = PendingIntent.getService(
            this,
            0,
            showOverlayIntent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Battery Current")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(showOverlayPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun notifySafely(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(notificationId, buildNotification(text))
        } catch (_: SecurityException) {
            // Notification permission may be revoked while the foreground service is running.
        } catch (_: RuntimeException) {
            // Notification manager can throw during shutdown or under heavy system churn.
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Battery Current",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Shows live battery charge/discharge current"
            channel.setShowBadge(false)

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isServiceAlive = false
        handler.removeCallbacks(updateRunnable)
        isUpdateScheduled = false
        setMonitoringRunning(false)
        persistEnergyTracking()
        removeOverlay()
        handler.removeCallbacks(foregroundIndicatorRunnable)
        recentMilliAmpSamples.clear()
        energyHistory.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class SocBucketCurveView(context: Context) : View(context) {
        private val points = ArrayList<BatteryCapacityEstimator.SocLinearityPoint>()
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 255, 255)
            strokeWidth = 2f
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(55, 255, 255, 255)
            strokeWidth = 1f
        }
        private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(95, 180, 255)
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val idealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = graphEstimateLabelColor
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            typeface = Typeface.MONOSPACE
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 255, 255, 255)
            textSize = 21f
            typeface = Typeface.MONOSPACE
        }
        private val bounds = RectF()

        fun setPoints(items: List<BatteryCapacityEstimator.SocLinearityPoint>) {
            points.clear()
            points.addAll(items.sortedWith(compareBy<BatteryCapacityEstimator.SocLinearityPoint> { it.midpointPct }.thenBy { it.deviationFromIdeal }))
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = 122f
            val top = 44f
            val right = width - 34f
            val bottom = height - 86f
            bounds.set(left, top, right, bottom)

            val maxAbsDeviation = points.maxOfOrNull { abs(it.deviationFromIdeal) } ?: 0.0
            val yLimit = niceDeviationLimit(maxAbsDeviation)

            drawDeviationTicks(canvas, yLimit)
            drawSocAxisTicks(canvas)

            canvas.drawLine(bounds.left, bounds.bottom, bounds.right, bounds.bottom, axisPaint)
            canvas.drawLine(bounds.left, bounds.top, bounds.left, bounds.bottom, axisPaint)

            val zeroY = yForDeviation(0.0, yLimit)
            canvas.drawLine(bounds.left, zeroY, bounds.right, zeroY, idealPaint)

            if (points.isEmpty()) {
                canvas.drawText("No SOC bucket data yet", bounds.left + 24f, bounds.centerY(), textPaint)
                canvas.drawText("Use the phone while monitoring so %SOC changes.", bounds.left + 24f, bounds.centerY() + 34f, labelPaint)
                drawAxisLabels(canvas)
                return
            }

            val plotted = points.map { point ->
                val x = xForPct(point.midpointPct)
                val y = yForDeviation(point.deviationFromIdeal, yLimit)
                Triple(point, x, y)
            }

            drawBestFitLine(canvas, yLimit)

            plotted.forEach { (point, x, y) ->
                pointPaint.color = if (point.sampleCount >= 3) {
                    Color.WHITE
                } else {
                    Color.argb(185, 255, 255, 255)
                }
                canvas.drawCircle(x, y, 5f, pointPaint)
            }

            canvas.drawText("dots = samples, line = best fit", bounds.right - 360f, 28f, labelPaint)
            drawAxisLabels(canvas)
        }

        private fun drawBestFitLine(canvas: Canvas, yLimit: Double) {
            if (points.size < 2) return

            val xMean = points.map { it.midpointPct.toDouble() }.average()
            val yMean = points.map { it.deviationFromIdeal }.average()
            var numerator = 0.0
            var denominator = 0.0
            points.forEach { point ->
                val dx = point.midpointPct - xMean
                numerator += dx * (point.deviationFromIdeal - yMean)
                denominator += dx * dx
            }
            if (denominator <= 0.0) return

            val slope = numerator / denominator
            val intercept = yMean - slope * xMean
            val minPct = points.minOf { it.midpointPct }.toDouble()
            val maxPct = points.maxOf { it.midpointPct }.toDouble()
            canvas.drawLine(
                xForPct(minPct),
                yForDeviation(intercept + slope * minPct, yLimit),
                xForPct(maxPct),
                yForDeviation(intercept + slope * maxPct, yLimit),
                curvePaint
            )
        }

        private fun drawAxisLabels(canvas: Canvas) {
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("% SOC", bounds.centerX(), height - 18f, labelPaint)

            canvas.save()
            canvas.rotate(-90f, 24f, bounds.centerY())
            canvas.drawText("% deviation", 24f, bounds.centerY(), labelPaint)
            canvas.restore()
            labelPaint.textAlign = Paint.Align.LEFT
        }

        private fun drawSocAxisTicks(canvas: Canvas) {
            labelPaint.textAlign = Paint.Align.CENTER
            for (pct in 0..100 step 10) {
                val x = xForPct(pct)
                canvas.drawLine(x, bounds.top, x, bounds.bottom, gridPaint)
                canvas.drawLine(x, bounds.bottom, x, bounds.bottom + 9f, axisPaint)
                canvas.drawText(pct.toString(), x, bounds.bottom + 34f, labelPaint)
            }
            labelPaint.textAlign = Paint.Align.LEFT
        }

        private fun xForPct(percent: Int): Float = xForPct(percent.toDouble())

        private fun xForPct(percent: Double): Float {
            val fraction = (percent.coerceIn(0.0, 100.0) / 100.0).toFloat()
            return bounds.left + fraction * bounds.width()
        }

        private fun yForDeviation(value: Double, yLimit: Double): Float {
            val fraction = ((value.coerceIn(-yLimit, yLimit) + yLimit) / (2.0 * yLimit)).toFloat()
            return bounds.bottom - fraction * bounds.height()
        }

        private fun drawDeviationTicks(canvas: Canvas, yLimit: Double) {
            val limitPct = (yLimit * 100.0).roundToInt().coerceAtLeast(5)
            val tickStepPct = (2.0 * limitPct) / 10.0

            labelPaint.textAlign = Paint.Align.RIGHT
            for (tickIndex in 0..10) {
                val pct = -limitPct + tickIndex * tickStepPct
                val value = pct / 100.0
                val y = yForDeviation(value, yLimit)
                canvas.drawLine(bounds.left, y, bounds.right, y, gridPaint)
                canvas.drawLine(bounds.left - 9f, y, bounds.left, y, axisPaint)
                canvas.drawText(formatDeviationTickLabel(pct), bounds.left - 16f, y + 7f, labelPaint)
            }
            labelPaint.textAlign = Paint.Align.LEFT
        }

        private fun formatDeviationTickLabel(valuePct: Double): String {
            val roundedInt = valuePct.roundToInt()
            return if (abs(valuePct - roundedInt) < 0.05) {
                roundedInt.toString()
            } else {
                String.format(Locale.US, "%.1f", valuePct)
            }
        }

        private fun niceDeviationLimit(value: Double): Double {
            val requiredPct = value.coerceAtLeast(0.05) * 100.0
            val cleanLimitsPct = doubleArrayOf(5.0, 10.0, 20.0, 50.0, 100.0)
            val selectedPct = cleanLimitsPct.firstOrNull { requiredPct <= it } ?: 100.0
            return selectedPct / 100.0
        }
    }

    private class EnergyGraphView(context: Context) : View(context) {
        private val points = ArrayList<EnergyPoint>()
        private var displayUnit = ENERGY_UNIT_MWH
        private var zeroTimestampMs = 0L
        private var rightAxisMode: RightAxisMode? = null
        private var useFahrenheit = false
        var onRightAxisLabelClick: (() -> Unit)? = null
        var onViewportChanged: (() -> Unit)? = null
        var onSingleFingerDragDelta: ((Int, Int) -> Unit)? = null
        private var customStartMs: Long? = null
        private var customDurationMs: Float? = null
        private var customRightAxisMin: Double? = null
        private var customRightAxisMax: Double? = null
        private var activeZoomAxis: ZoomAxis? = null
        private var isViewportGestureActive = false
        private var isRightAxisLabelTouchActive = false
        private var gestureStartDistance = 0f
        private var gestureStartMidX = 0f
        private var gestureStartMidY = 0f
        private var gestureStartVisibleStartMs = 0L
        private var gestureStartVisibleDurationMs = 0f
        private var gestureStartRightAxisMin: Double? = null
        private var gestureStartRightAxisMax: Double? = null
        private var gestureBaseRightAxisMin: Double? = null
        private var gestureBaseRightAxisMax: Double? = null
        private var gestureSourceView: View? = null
        private var isSingleFingerDragActive = false
        private var isZoomArmedTouchActive = false
        private var lastSingleRawX = 0f
        private var lastSingleRawY = 0f
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(82, 190, 128)
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val batteryLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(80, 165, 255)
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val rightAxisLabelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(135, 110, 115, 125)
            style = Paint.Style.FILL
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(185, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2.2f
        }
        private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val rightAxisZeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        private val batteryLowThresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 230, 95, 95)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
        }
        private val batteryHighThresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 82, 190, 128)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
        }
        private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 255, 255, 255)
            textSize = 28f
        }
        private val tickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 255, 255, 255)
            textSize = 26f
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 82, 190, 128)
            style = Paint.Style.FILL
        }
        private val resetZoomTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 38, 28)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val resetZoomBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 245, 248, 244)
            style = Paint.Style.FILL
        }
        private val zoomButtonBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 215, 35)
            style = Paint.Style.FILL
        }
        private val zoomButtonInactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 70, 76, 88)
            style = Paint.Style.FILL
        }
        private val zoomButtonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 50f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        private val fillPath = Path()
        private val plotBounds = RectF()
        private val rightAxisLabelHitRect = RectF()
        private val resetZoomHitRect = RectF()
        private val xZoomButtonHitRect = RectF()
        private val yZoomButtonHitRect = RectF()
        private val minVisibleDurationMs = 60_000f
        private val maxVisibleDurationMs = 24f * 60f * 60f * 1000f

        private data class TimeTickLabel(
            val x: Float,
            val text: String
        )

        private data class TracePoint(
            val x: Float,
            val y: Float,
            val value: Double
        )

        private data class RightAxisScale(
            val min: Double,
            val max: Double,
            val ticks: List<Double>
        ) {
            val range: Double = (max - min).coerceAtLeast(0.0001)
        }

        private enum class ZoomAxis {
            X,
            Y
        }

        fun setPoints(
            newPoints: List<EnergyPoint>,
            unit: String,
            zeroTimeMs: Long,
            newRightAxisMode: RightAxisMode?,
            newUseFahrenheit: Boolean
        ) {
            points.clear()
            points.addAll(newPoints)
            displayUnit = unit
            zeroTimestampMs = zeroTimeMs
            if (rightAxisMode != newRightAxisMode) {
                customRightAxisMin = null
                customRightAxisMax = null
                if (newRightAxisMode == null && activeZoomAxis == ZoomAxis.Y) {
                    activeZoomAxis = null
                }
            }
            rightAxisMode = newRightAxisMode
            useFahrenheit = newUseFahrenheit
            invalidate()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isRightAxisLabelTouchActive = rightAxisMode != null && rightAxisLabelHitRect.contains(event.x, event.y)
                    val isResetZoomTouch = resetZoomHitRect.contains(event.x, event.y)
                    val isXZoomButtonTouch = xZoomButtonHitRect.contains(event.x, event.y)
                    val isYZoomButtonTouch = yZoomButtonHitRect.contains(event.x, event.y)
                    isZoomArmedTouchActive = activeZoomAxis != null && plotBounds.contains(event.x, event.y)
                    isSingleFingerDragActive = !isRightAxisLabelTouchActive &&
                        !isResetZoomTouch &&
                        !isXZoomButtonTouch &&
                        !isYZoomButtonTouch &&
                        !isZoomArmedTouchActive &&
                        activeZoomAxis == null &&
                        plotBounds.contains(event.x, event.y)
                    lastSingleRawX = event.rawX
                    lastSingleRawY = event.rawY
                    return isRightAxisLabelTouchActive ||
                        isResetZoomTouch ||
                        isXZoomButtonTouch ||
                        isYZoomButtonTouch ||
                        isZoomArmedTouchActive ||
                        isSingleFingerDragActive
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (activeZoomAxis != null && event.pointerCount >= 2 && isInsidePlot(event)) {
                        isSingleFingerDragActive = false
                        beginViewportGesture(event, this)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2 && isViewportGestureActive) {
                        updateViewportGesture(event, gestureSourceView ?: this)
                        return true
                    }
                    if (event.pointerCount == 1 && isSingleFingerDragActive) {
                        val dx = (event.rawX - lastSingleRawX).roundToInt()
                        val dy = (event.rawY - lastSingleRawY).roundToInt()
                        lastSingleRawX = event.rawX
                        lastSingleRawY = event.rawY
                        if (dx != 0 || dy != 0) {
                            onSingleFingerDragDelta?.invoke(dx, dy)
                        }
                        return true
                    }
                    if (event.pointerCount == 1 && isZoomArmedTouchActive) {
                        return true
                    }
                }

                MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_CANCEL -> {
                    isViewportGestureActive = false
                    gestureSourceView = null
                    isSingleFingerDragActive = false
                    isZoomArmedTouchActive = false
                    isRightAxisLabelTouchActive = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (resetZoomHitRect.contains(event.x, event.y)) {
                        resetViewport()
                        onViewportChanged?.invoke()
                        return true
                    }
                    if (xZoomButtonHitRect.contains(event.x, event.y)) {
                        activeZoomAxis = ZoomAxis.X
                        invalidate()
                        return true
                    }
                    if (yZoomButtonHitRect.contains(event.x, event.y)) {
                        activeZoomAxis = ZoomAxis.Y
                        invalidate()
                        return true
                    }
                    if (isRightAxisLabelTouchActive && rightAxisLabelHitRect.contains(event.x, event.y)) {
                        isRightAxisLabelTouchActive = false
                        onRightAxisLabelClick?.invoke()
                        return true
                    }
                    val handledDrag = isSingleFingerDragActive || isZoomArmedTouchActive
                    isRightAxisLabelTouchActive = false
                    isSingleFingerDragActive = false
                    isZoomArmedTouchActive = false
                    return handledDrag
                }
            }
            return false
        }

        fun hasCustomViewport(): Boolean =
            (customStartMs != null && customDurationMs != null) ||
                (customRightAxisMin != null && customRightAxisMax != null) ||
                activeZoomAxis != null

        fun isZoomModeArmed(): Boolean = activeZoomAxis != null

        fun beginExternalZoomGesture(event: MotionEvent, sourceView: View): Boolean {
            if (activeZoomAxis == null || event.pointerCount < 2) return false

            beginViewportGesture(event, sourceView)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        fun updateExternalZoomGesture(event: MotionEvent, sourceView: View): Boolean {
            if (!isViewportGestureActive || activeZoomAxis == null || event.pointerCount < 2) return false

            updateViewportGesture(event, sourceView)
            return true
        }

        fun endExternalZoomGesture() {
            isViewportGestureActive = false
            gestureSourceView = null
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        fun resetViewport() {
            customStartMs = null
            customDurationMs = null
            customRightAxisMin = null
            customRightAxisMax = null
            activeZoomAxis = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val left = 104f
            val top = 24f
            val right = width - 106f
            val bottom = height - 142f
            plotBounds.set(left, top, right, bottom)

            if (points.size < 2) {
                drawTimeTicks(canvas, zeroTimestampMs.takeIf { it > 0L } ?: 0L, 60_000f)
                drawAxes(canvas)
                drawRightAxisUnitLabel(canvas)
                drawResetZoomButton(canvas)
                drawZoomAxisButtons(canvas)
                return
            }

            val defaultStartMs = zeroTimestampMs.takeIf { it > 0L } ?: points.first().timestampMs
            val defaultDurationMs = chooseVisibleDurationMs(points.last().timestampMs - defaultStartMs)
            val viewport = currentViewport(defaultStartMs, defaultDurationMs)
            val startMs = viewport.first
            val visibleDurationMs = viewport.second
            val visiblePoints = points.filter { point ->
                point.timestampMs >= startMs && point.timestampMs <= startMs + visibleDurationMs.toLong()
            }.ifEmpty { points }
            val rawMinEnergy = minOf(0.0, visiblePoints.minOf { graphValue(it) })
            val rawMaxEnergy = maxOf(0.0, visiblePoints.maxOf { graphValue(it) })
            val energyTicks = chooseEnergyTicks(rawMinEnergy, rawMaxEnergy)
            val minEnergy = energyTicks.first()
            val maxEnergy = energyTicks.last()
            val energyRange = (maxEnergy - minEnergy).coerceAtLeast(1.0)
            val energyStep = if (energyTicks.size > 1) {
                energyTicks[1] - energyTicks[0]
            } else {
                energyRange
            }
            val zeroY = yForEnergy(0.0, minEnergy, energyRange)
            val rightAxisScale = applyCustomRightAxisScale(chooseRightAxisScale(visiblePoints))

            drawEnergyTicks(canvas, energyTicks, minEnergy, energyRange, energyStep)
            drawRightAxisTicks(canvas, rightAxisScale)
            drawTimeTicks(canvas, startMs, visibleDurationMs)
            drawAxes(canvas)
            drawEnergyUnitLabel(canvas)
            drawRightAxisUnitLabel(canvas)

            fillPath.reset()
            val chartPoints = ArrayList<Pair<Float, Float>>()

            visiblePoints.forEachIndexed { index, point ->
                val elapsedMs = (point.timestampMs - startMs).coerceAtLeast(0L)
                val x = plotBounds.left + (elapsedMs.toFloat() / visibleDurationMs) * plotBounds.width()
                val y = yForEnergy(graphValue(point), minEnergy, energyRange)
                chartPoints.add(x to y)
                if (index == 0) {
                    fillPath.moveTo(x, zeroY)
                    fillPath.lineTo(x, y)
                } else {
                    fillPath.lineTo(x, y)
                }
            }

            fillPath.lineTo(plotBounds.right, zeroY)
            fillPath.close()

            fillPaint.color = Color.argb(22, 255, 255, 255)
            canvas.drawPath(fillPath, fillPaint)
            canvas.drawLine(plotBounds.left, zeroY, plotBounds.right, zeroY, zeroLinePaint)
            for (i in 1 until chartPoints.size) {
                val previous = chartPoints[i - 1]
                val current = chartPoints[i]
                linePaint.color = if (graphValue(visiblePoints[i]) >= graphValue(visiblePoints[i - 1])) {
                    Color.rgb(82, 190, 128)
                } else {
                    Color.rgb(230, 95, 95)
                }
                canvas.drawLine(previous.first, previous.second, current.first, current.second, linePaint)
            }
            drawRightAxisTrace(canvas, visiblePoints, startMs, visibleDurationMs, rightAxisScale)
            drawAxes(canvas)
            drawEnergyUnitLabel(canvas)
            drawRightAxisUnitLabel(canvas)
            drawRightAxisTicks(canvas, rightAxisScale)
            drawResetZoomButton(canvas)
            drawZoomAxisButtons(canvas)
        }

        private fun chooseVisibleDurationMs(elapsedMs: Long): Float {
            val safeElapsed = elapsedMs.coerceAtLeast(0L)
            val durationMs = when {
                safeElapsed <= 60_000L -> 60_000L
                safeElapsed <= 5 * 60_000L -> 5 * 60_000L
                safeElapsed <= 10 * 60_000L -> 10 * 60_000L
                safeElapsed <= 30 * 60_000L -> 30 * 60_000L
                safeElapsed <= 60 * 60_000L -> 60 * 60_000L
                safeElapsed <= 2 * 60 * 60_000L -> 2 * 60 * 60_000L
                else -> safeElapsed
            }
            return durationMs.toFloat()
        }

        private fun currentViewport(defaultStartMs: Long, defaultDurationMs: Float): Pair<Long, Float> {
            val customStart = customStartMs
            val customDuration = customDurationMs
            if (customStart != null && customDuration != null) {
                return clampViewport(customStart, customDuration)
            }

            val duration = defaultDurationMs.coerceIn(minVisibleDurationMs, maxVisibleDurationMs)
            val latestMs = points.lastOrNull()?.timestampMs ?: defaultStartMs
            val start = if (latestMs - defaultStartMs > duration.toLong()) {
                latestMs - duration.toLong()
            } else {
                defaultStartMs
            }
            return clampViewport(start, duration)
        }

        private fun clampViewport(startMs: Long, durationMs: Float): Pair<Long, Float> {
            if (points.isEmpty()) return startMs to durationMs

            val earliestMs = (zeroTimestampMs.takeIf { it > 0L } ?: points.first().timestampMs)
                .coerceAtMost(points.first().timestampMs)
            val latestMs = points.last().timestampMs
            val clampedDuration = durationMs.coerceIn(minVisibleDurationMs, maxVisibleDurationMs)
            val latestStartMs = latestMs - clampedDuration.toLong()
            val clampedStart = if (latestStartMs <= earliestMs) {
                earliestMs
            } else {
                startMs.coerceIn(earliestMs, latestStartMs)
            }
            return clampedStart to clampedDuration
        }

        private fun beginViewportGesture(event: MotionEvent, sourceView: View) {
            val defaultStartMs = zeroTimestampMs.takeIf { it > 0L } ?: points.firstOrNull()?.timestampMs ?: 0L
            val defaultDurationMs = chooseVisibleDurationMs((points.lastOrNull()?.timestampMs ?: defaultStartMs) - defaultStartMs)
            val viewport = currentViewport(defaultStartMs, defaultDurationMs)
            isViewportGestureActive = true
            gestureSourceView = sourceView
            gestureStartDistance = pointerDistance(event).coerceAtLeast(1f)
            gestureStartMidX = pointerMidX(event, sourceView)
            gestureStartMidY = pointerMidY(event, sourceView)
            gestureStartVisibleStartMs = viewport.first
            gestureStartVisibleDurationMs = viewport.second
            val visiblePoints = points.filter { point ->
                point.timestampMs >= viewport.first && point.timestampMs <= viewport.first + viewport.second.toLong()
            }.ifEmpty { points }
            val baseRightAxisScale = chooseRightAxisScale(visiblePoints)
            val rightAxisScale = applyCustomRightAxisScale(baseRightAxisScale)
            gestureStartRightAxisMin = rightAxisScale?.min
            gestureStartRightAxisMax = rightAxisScale?.max
            gestureBaseRightAxisMin = baseRightAxisScale?.min
            gestureBaseRightAxisMax = baseRightAxisScale?.max
        }

        private fun updateViewportGesture(event: MotionEvent, sourceView: View) {
            if (plotBounds.width() <= 0f) return

            val zoomRatio = scaleZoomRatio(
                gestureStartDistance / pointerDistance(event).coerceAtLeast(1f)
            )
            when (activeZoomAxis) {
                ZoomAxis.X -> updateTimeViewport(event, sourceView, zoomRatio)
                ZoomAxis.Y -> updateRightAxisViewport(event, sourceView, zoomRatio)
                null -> return
            }
            onViewportChanged?.invoke()
            invalidate()
        }

        private fun updateTimeViewport(event: MotionEvent, sourceView: View, zoomRatio: Float) {
            var nextDuration = (gestureStartVisibleDurationMs * zoomRatio).coerceIn(minVisibleDurationMs, maxVisibleDurationMs)
            val startFraction = ((gestureStartMidX - plotBounds.left) / plotBounds.width()).coerceIn(0f, 1f)
            val timeAtMidpoint = gestureStartVisibleStartMs + (gestureStartVisibleDurationMs * startFraction).toLong()
            val dragDeltaMs = ((pointerMidX(event, sourceView) - gestureStartMidX) / plotBounds.width() * nextDuration).toLong()
            var nextStart = timeAtMidpoint - (nextDuration * startFraction).toLong() - dragDeltaMs

            val clamped = clampViewport(nextStart, nextDuration)
            nextStart = clamped.first
            nextDuration = clamped.second
            customStartMs = nextStart
            customDurationMs = nextDuration
        }

        private fun updateRightAxisViewport(event: MotionEvent, sourceView: View, zoomRatio: Float) {
            if (rightAxisMode == null || plotBounds.height() <= 0f) return

            val startMin = gestureStartRightAxisMin ?: return
            val startMax = gestureStartRightAxisMax ?: return
            val baseMin = gestureBaseRightAxisMin ?: startMin
            val baseMax = gestureBaseRightAxisMax ?: startMax
            val startRange = (startMax - startMin).coerceAtLeast(0.0001)
            val baseScale = RightAxisScale(baseMin, baseMax, emptyList())
            val minRange = minimumRightAxisRange(baseScale)
            val maxRange = maximumRightAxisRange(baseScale)
            val nextRange = (startRange * zoomRatio).coerceIn(minRange, maxRange)
            val startFraction = ((gestureStartMidY - plotBounds.top) / plotBounds.height()).coerceIn(0f, 1f)
            val valueAtStartMidpoint = startMax - startRange * startFraction
            val dragDeltaValue = ((pointerMidY(event, sourceView) - gestureStartMidY) / plotBounds.height()) * nextRange
            val nextMax = valueAtStartMidpoint + nextRange * startFraction + dragDeltaValue
            val nextMin = nextMax - nextRange
            setCustomRightAxisScale(nextMin, nextMax, baseScale)
        }

        private fun scaleZoomRatio(rawRatio: Float): Float {
            val clamped = rawRatio.coerceIn(0.15f, 8f)
            return if (clamped > 1f) {
                clamped.toDouble().pow(1.65).toFloat().coerceAtMost(8f)
            } else {
                clamped
            }
        }

        private fun pointerDistance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 1f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        private fun pointerMidX(event: MotionEvent): Float {
            return pointerMidX(event, this)
        }

        private fun pointerMidX(event: MotionEvent, sourceView: View): Float {
            val sourceOffsetX = sourceOffsetX(sourceView)
            if (event.pointerCount < 2) return event.x + sourceOffsetX
            return (event.getX(0) + event.getX(1)) / 2f + sourceOffsetX
        }

        private fun pointerMidY(event: MotionEvent): Float {
            return pointerMidY(event, this)
        }

        private fun pointerMidY(event: MotionEvent, sourceView: View): Float {
            val sourceOffsetY = sourceOffsetY(sourceView)
            if (event.pointerCount < 2) return event.y + sourceOffsetY
            return (event.getY(0) + event.getY(1)) / 2f + sourceOffsetY
        }

        private fun sourceOffsetX(sourceView: View): Float {
            if (sourceView === this) return 0f

            val sourceLocation = IntArray(2)
            val graphLocation = IntArray(2)
            sourceView.getLocationOnScreen(sourceLocation)
            getLocationOnScreen(graphLocation)
            return (sourceLocation[0] - graphLocation[0]).toFloat()
        }

        private fun sourceOffsetY(sourceView: View): Float {
            if (sourceView === this) return 0f

            val sourceLocation = IntArray(2)
            val graphLocation = IntArray(2)
            sourceView.getLocationOnScreen(sourceLocation)
            getLocationOnScreen(graphLocation)
            return (sourceLocation[1] - graphLocation[1]).toFloat()
        }

        private fun isInsidePlot(event: MotionEvent): Boolean {
            if (event.pointerCount < 2) return false
            return plotBounds.contains(event.getX(0), event.getY(0)) ||
                plotBounds.contains(event.getX(1), event.getY(1))
        }

        private fun drawAxes(canvas: Canvas) {
            canvas.drawRect(plotBounds, axisPaint)
        }

        private fun drawEnergyUnitLabel(canvas: Canvas) {
            axisTextPaint.textAlign = Paint.Align.CENTER
            canvas.save()
            canvas.rotate(-90f, 22f, plotBounds.centerY())
            canvas.drawText(displayUnit, 22f, plotBounds.centerY(), axisTextPaint)
            canvas.restore()
        }

        private fun drawRightAxisUnitLabel(canvas: Canvas) {
            val mode = rightAxisMode ?: run {
                rightAxisLabelHitRect.setEmpty()
                return
            }

            axisTextPaint.textAlign = Paint.Align.CENTER
            val labelX = plotBounds.right + 82f
            val labelY = plotBounds.centerY()
            rightAxisLabelHitRect.set(labelX - 28f, labelY - 86f, labelX + 28f, labelY + 86f)
            canvas.drawRoundRect(rightAxisLabelHitRect, 10f, 10f, rightAxisLabelBackgroundPaint)
            canvas.save()
            canvas.rotate(90f, labelX, labelY)
            canvas.drawText(mode.label, labelX, labelY + 10f, axisTextPaint)
            canvas.restore()
        }

        private fun drawResetZoomButton(canvas: Canvas) {
            if (!hasCustomViewport()) {
                resetZoomHitRect.setEmpty()
                return
            }

            val text = "Rst Zoom"
            val horizontalPadding = 18f
            val verticalPadding = 15f
            val textWidth = resetZoomTextPaint.measureText(text)
            val left = plotBounds.left + 16f
            val bottom = plotBounds.bottom - 8f
            resetZoomHitRect.set(
                left,
                bottom - resetZoomTextPaint.textSize - verticalPadding * 2f,
                left + textWidth + horizontalPadding * 2f,
                bottom
            )
            canvas.drawRoundRect(resetZoomHitRect, 12f, 12f, resetZoomBackgroundPaint)
            val centeredBaseline = resetZoomHitRect.centerY() -
                (resetZoomTextPaint.descent() + resetZoomTextPaint.ascent()) / 2f
            canvas.drawText(text, resetZoomHitRect.centerX(), centeredBaseline, resetZoomTextPaint)
        }

        private fun drawZoomAxisButtons(canvas: Canvas) {
            val hitWidth = 96f
            val hitHeight = 78f
            val visualInset = 8f
            xZoomButtonHitRect.set(
                plotBounds.left - 18f,
                plotBounds.bottom + 40f,
                plotBounds.left - 18f + hitWidth,
                plotBounds.bottom + 40f + hitHeight
            )
            drawZoomAxisButton(canvas, xZoomButtonHitRect, "\u2194", activeZoomAxis == ZoomAxis.X, visualInset)

            if (rightAxisMode == null) {
                yZoomButtonHitRect.setEmpty()
                return
            }

            yZoomButtonHitRect.set(
                plotBounds.left - hitWidth - 22f,
                plotBounds.top + 8f,
                plotBounds.left - 22f,
                plotBounds.top + 8f + hitHeight
            )
            drawZoomAxisButton(canvas, yZoomButtonHitRect, "\u2195", activeZoomAxis == ZoomAxis.Y, visualInset)
        }

        private fun drawZoomAxisButton(
            canvas: Canvas,
            bounds: RectF,
            label: String,
            isActive: Boolean,
            visualInset: Float
        ) {
            val paint = if (isActive) zoomButtonBackgroundPaint else zoomButtonInactivePaint
            val visualBounds = RectF(bounds).apply {
                inset(visualInset, visualInset)
            }
            canvas.drawRoundRect(visualBounds, 12f, 12f, paint)
            val centeredBaseline = visualBounds.centerY() -
                (zoomButtonTextPaint.descent() + zoomButtonTextPaint.ascent()) / 2f
            zoomButtonTextPaint.color = if (isActive) Color.rgb(45, 38, 0) else Color.WHITE
            canvas.drawText(label, visualBounds.centerX(), centeredBaseline, zoomButtonTextPaint)
        }

        private fun drawRightAxisTicks(canvas: Canvas, scale: RightAxisScale?) {
            if (scale == null) return

            scale.ticks.forEach { tick ->
                val y = yForRightAxisValue(tick, scale)
                canvas.drawLine(plotBounds.right, y, plotBounds.right + 12f, y, tickPaint)
                tickTextPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(formatRightAxisTick(tick), plotBounds.right + 16f, y + 9f, tickTextPaint)
            }
        }

        private fun drawRightAxisTrace(
            canvas: Canvas,
            visiblePoints: List<EnergyPoint>,
            startMs: Long,
            visibleDurationMs: Float,
            scale: RightAxisScale?
        ) {
            if (scale == null) return

            drawBatteryThresholdLines(canvas, scale)

            val rightAxisPoints = buildRightAxisTracePoints(visiblePoints, startMs, visibleDurationMs, scale)
            if (rightAxisPoints.size < 2) return

            for (i in 1 until rightAxisPoints.size) {
                val previous = rightAxisPoints[i - 1]
                val current = rightAxisPoints[i]
                batteryLinePaint.color = rightAxisTraceColor(current.value)
                canvas.drawLine(previous.x, previous.y, current.x, current.y, batteryLinePaint)
            }
            drawRightAxisZeroLine(canvas, scale)
        }

        private fun drawBatteryThresholdLines(canvas: Canvas, scale: RightAxisScale) {
            if (rightAxisMode != RightAxisMode.BATTERY) return
            if (25.0 !in scale.min..scale.max && 75.0 !in scale.min..scale.max) return

            if (25.0 in scale.min..scale.max) {
                val lowY = yForRightAxisValue(25.0, scale)
                canvas.drawLine(plotBounds.left, lowY, plotBounds.right, lowY, batteryLowThresholdPaint)
            }
            if (75.0 in scale.min..scale.max) {
                val highY = yForRightAxisValue(75.0, scale)
                canvas.drawLine(plotBounds.left, highY, plotBounds.right, highY, batteryHighThresholdPaint)
            }
        }

        private fun rightAxisTraceColor(value: Double): Int {
            return if (rightAxisMode == RightAxisMode.CURRENT) {
                if (value >= 0.0) Color.rgb(80, 165, 255) else Color.rgb(255, 230, 65)
            } else {
                Color.rgb(80, 165, 255)
            }
        }

        private fun drawRightAxisZeroLine(canvas: Canvas, scale: RightAxisScale) {
            if (rightAxisMode != RightAxisMode.CURRENT || scale.min > 0.0 || scale.max < 0.0) return

            val y = yForRightAxisValue(0.0, scale)
            canvas.drawLine(plotBounds.left, y, plotBounds.right, y, rightAxisZeroLinePaint)
        }

        private fun buildRightAxisTracePoints(
            visiblePoints: List<EnergyPoint>,
            startMs: Long,
            visibleDurationMs: Float,
            scale: RightAxisScale
        ): List<TracePoint> {
            val rawPoints = visiblePoints.mapNotNull { point ->
                val value = rightAxisValue(point) ?: return@mapNotNull null
                val elapsedMs = (point.timestampMs - startMs).coerceAtLeast(0L)
                val x = plotBounds.left + (elapsedMs.toFloat() / visibleDurationMs) * plotBounds.width()
                val y = yForRightAxisValue(value, scale)
                TracePoint(x, y, value)
            }
            if (rawPoints.size < 2 || rightAxisMode == RightAxisMode.BATTERY) return rawPoints

            val bucketWidthPx = chooseSmoothingBucketWidthPx(visibleDurationMs)
            if (bucketWidthPx <= 1f) return rawPoints

            val smoothed = ArrayList<TracePoint>()
            var bucketStartX = rawPoints.first().x
            var sumX = 0.0
            var sumValue = 0.0
            var count = 0

            fun flushBucket() {
                if (count == 0) return
                val averageX = (sumX / count).toFloat()
                val averageValue = sumValue / count
                smoothed.add(TracePoint(averageX, yForRightAxisValue(averageValue, scale), averageValue))
                sumX = 0.0
                sumValue = 0.0
                count = 0
            }

            rawPoints.forEach { point ->
                if (point.x - bucketStartX > bucketWidthPx) {
                    flushBucket()
                    bucketStartX = point.x
                }
                sumX += point.x
                sumValue += point.value
                count += 1
            }
            flushBucket()

            return if (smoothed.size >= 2) smoothed else rawPoints
        }

        private fun chooseSmoothingBucketWidthPx(visibleDurationMs: Float): Float {
            return when {
                visibleDurationMs <= 30 * 60_000f -> 1f
                visibleDurationMs <= 2 * 60 * 60_000f -> 3f
                visibleDurationMs <= 6 * 60 * 60_000f -> 5f
                visibleDurationMs <= 12 * 60 * 60_000f -> 8f
                else -> 12f
            }
        }

        private fun chooseRightAxisScale(visiblePoints: List<EnergyPoint>): RightAxisScale? {
            return when (rightAxisMode) {
                null -> null
                RightAxisMode.BATTERY,
                RightAxisMode.VOLTAGE,
                RightAxisMode.TEMPERATURE,
                RightAxisMode.CURRENT -> chooseAutoRightAxisScale(visiblePoints.mapNotNull { rightAxisValue(it) })
            }
        }

        private fun applyCustomRightAxisScale(baseScale: RightAxisScale?): RightAxisScale? {
            val customMin = customRightAxisMin
            val customMax = customRightAxisMax
            if (baseScale == null || customMin == null || customMax == null || customMax <= customMin) {
                return baseScale
            }

            return RightAxisScale(customMin, customMax, chooseCustomRightAxisTicks(customMin, customMax))
        }

        private fun setCustomRightAxisScale(nextMin: Double, nextMax: Double, baseScale: RightAxisScale) {
            val range = (nextMax - nextMin).coerceAtLeast(minimumRightAxisRange(baseScale))
            var min = nextMin
            var max = min + range

            if (rightAxisMode == RightAxisMode.BATTERY || rightAxisMode == RightAxisMode.VOLTAGE) {
                val upperBound = if (rightAxisMode == RightAxisMode.BATTERY) 120.0 else baseScale.max
                val boundedRange = range.coerceAtMost(upperBound - baseScale.min)
                val center = (min + max) / 2.0
                min = (center - boundedRange / 2.0).coerceAtLeast(baseScale.min)
                max = min + boundedRange
                if (max > upperBound) {
                    max = upperBound
                    min = max - boundedRange
                }
            }

            customRightAxisMin = min
            customRightAxisMax = max
        }

        private fun minimumRightAxisRange(baseScale: RightAxisScale): Double {
            return when (rightAxisMode) {
                RightAxisMode.BATTERY -> 5.0
                RightAxisMode.VOLTAGE -> 0.05
                else -> (baseScale.range / 20.0).coerceAtLeast(0.0001)
            }
        }

        private fun maximumRightAxisRange(baseScale: RightAxisScale): Double {
            return when (rightAxisMode) {
                RightAxisMode.BATTERY -> 120.0 - baseScale.min
                RightAxisMode.VOLTAGE -> baseScale.range
                else -> (baseScale.range * 8.0).coerceAtLeast(minimumRightAxisRange(baseScale))
            }
        }

        private fun chooseCustomRightAxisTicks(min: Double, max: Double): List<Double> {
            val range = (max - min).coerceAtLeast(0.0001)
            val step = chooseNiceStep(range / 4.0)
            val firstTick = ceil(min / step) * step
            val ticks = ArrayList<Double>()
            var tick = firstTick
            while (tick <= max + step * 0.5 && ticks.size < 8) {
                ticks.add(if (abs(tick) < step / 1000.0) 0.0 else tick)
                tick += step
            }
            return if (ticks.size >= 2) ticks else listOf(min, max)
        }

        private fun chooseAutoRightAxisScale(values: List<Double>): RightAxisScale? {
            if (values.isEmpty()) return null

            val includeZero = rightAxisMode == RightAxisMode.CURRENT
            val minValue = values.minOrNull() ?: return null
            val maxValue = values.maxOrNull() ?: return null
            val rawMin = if (includeZero) minOf(0.0, minValue) else minValue
            val rawMax = if (includeZero) maxOf(0.0, maxValue) else maxValue
            val rawRange = (rawMax - rawMin).coerceAtLeast(minimumAutoRightAxisRange())
            val step = chooseNiceStep(rawRange / 4.0)
            var minTick = floor(rawMin / step) * step
            var maxTick = ceil(rawMax / step) * step
            if (rightAxisMode == RightAxisMode.BATTERY) {
                minTick = minTick.coerceAtLeast(0.0)
                maxTick = maxTick.coerceAtMost(100.0)
            } else if (rightAxisMode == RightAxisMode.VOLTAGE) {
                maxTick = maxTick.coerceAtMost(5.0)
            }
            if (maxTick <= minTick) {
                if (rightAxisMode == RightAxisMode.BATTERY && maxTick >= 100.0) {
                    minTick = (maxTick - step).coerceAtLeast(0.0)
                } else {
                    maxTick += step
                }
            }
            val ticks = ArrayList<Double>()
            var tick = minTick
            while (tick <= maxTick + step * 0.5) {
                ticks.add(if (abs(tick) < step / 1000.0) 0.0 else tick)
                tick += step
            }
            return RightAxisScale(minTick, maxTick, ticks)
        }

        private fun minimumAutoRightAxisRange(): Double {
            return when (rightAxisMode) {
                RightAxisMode.BATTERY -> 5.0
                RightAxisMode.VOLTAGE -> 0.05
                else -> 1.0
            }
        }

        private fun rightAxisValue(point: EnergyPoint): Double? {
            return when (rightAxisMode) {
                null -> null
                RightAxisMode.BATTERY -> point.batteryPercent
                RightAxisMode.TEMPERATURE -> point.temperatureC?.let {
                    if (useFahrenheit) it * 9.0 / 5.0 + 32.0 else it
                }
                RightAxisMode.VOLTAGE -> point.voltageMv?.let { it / 1000.0 }
                RightAxisMode.CURRENT -> point.currentMilliAmps
            }
        }

        private fun formatRightAxisTick(value: Double): String {
            return when (rightAxisMode) {
                RightAxisMode.VOLTAGE -> String.format(Locale.US, "%.2f", value)
                else -> String.format(Locale.US, "%.0f", value)
            }
        }

        private fun graphValue(point: EnergyPoint): Double {
            return if (displayUnit == ENERGY_UNIT_MAH) {
                point.chargeMilliAmpHours
            } else {
                point.energyMilliWattHours
            }
        }

        private fun drawEnergyTicks(
            canvas: Canvas,
            energyTicks: List<Double>,
            minEnergy: Double,
            energyRange: Double,
            energyStep: Double
        ) {
            tickTextPaint.textAlign = Paint.Align.RIGHT
            drawMinorEnergyTicks(canvas, minEnergy, energyRange, energyStep)

            energyTicks.forEach { energy ->
                val y = yForEnergy(energy, minEnergy, energyRange)
                canvas.drawLine(plotBounds.left, y, plotBounds.right, y, gridPaint)
                canvas.drawLine(plotBounds.left - 12f, y, plotBounds.left, y, tickPaint)
                canvas.drawLine(plotBounds.right, y, plotBounds.right + 12f, y, tickPaint)
                canvas.drawText(formatSignedAxis(energy), plotBounds.left - 16f, y + 9f, tickTextPaint)
            }
        }

        private fun drawMinorEnergyTicks(
            canvas: Canvas,
            minEnergy: Double,
            energyRange: Double,
            energyStep: Double
        ) {
            if (energyStep <= 0.0) return

            val minorStep = energyStep / 5.0
            var energy = minEnergy
            while (energy <= minEnergy + energyRange + minorStep * 0.5) {
                val majorPosition = (energy - minEnergy) / energyStep
                val isMajor = abs(majorPosition - majorPosition.roundToInt()) < 0.001
                if (!isMajor) {
                    val y = yForEnergy(energy, minEnergy, energyRange)
                    canvas.drawLine(plotBounds.left - 8f, y, plotBounds.left, y, minorTickPaint)
                    canvas.drawLine(plotBounds.right, y, plotBounds.right + 8f, y, minorTickPaint)
                }
                energy += minorStep
            }
        }

        private fun drawTimeTicks(canvas: Canvas, visibleStartMs: Long, visibleDurationMs: Float) {
            val tickStepMs = chooseTimeTickStepMs(visibleDurationMs)
            val zeroTimeMs = zeroTimestampMs.takeIf { it > 0L } ?: visibleStartMs
            val visibleStartOffsetMs = (visibleStartMs - zeroTimeMs).toFloat()
            val firstTickOffsetMs = floor(visibleStartOffsetMs / tickStepMs) * tickStepMs
            val visibleEndOffsetMs = visibleStartOffsetMs + visibleDurationMs
            val labels = ArrayList<TimeTickLabel>()
            drawMinorTimeTicks(canvas, visibleStartOffsetMs, visibleDurationMs, tickStepMs)

            var tickOffsetMs = firstTickOffsetMs
            while (tickOffsetMs <= visibleEndOffsetMs + tickStepMs * 0.5f) {
                val visibleTickMs = tickOffsetMs - visibleStartOffsetMs
                if (visibleTickMs >= -1f && visibleTickMs <= visibleDurationMs + 1f) {
                    val x = plotBounds.left + (visibleTickMs / visibleDurationMs) * plotBounds.width()
                canvas.drawLine(x, plotBounds.top, x, plotBounds.bottom, gridPaint)
                canvas.drawLine(x, plotBounds.bottom, x, plotBounds.bottom + 12f, tickPaint)
                canvas.drawLine(x, plotBounds.top - 12f, x, plotBounds.top, tickPaint)
                    labels.add(TimeTickLabel(x, formatDurationTick(tickOffsetMs)))
                }
                tickOffsetMs += tickStepMs
            }

            drawTimeTickLabels(canvas, labels)
        }

        private fun drawTimeTickLabels(canvas: Canvas, labels: List<TimeTickLabel>) {
            if (labels.isEmpty()) return

            val visibleLabels = labels.toMutableList()

            visibleLabels.forEach { label ->
                tickTextPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(label.text, label.x, plotBounds.bottom + 40f, tickTextPaint)
            }
        }

        private fun drawMinorTimeTicks(
            canvas: Canvas,
            visibleStartOffsetMs: Float,
            visibleDurationMs: Float,
            tickStepMs: Float
        ) {
            if (tickStepMs <= 0f) return

            val minorStepMs = tickStepMs / 5f
            val visibleEndOffsetMs = visibleStartOffsetMs + visibleDurationMs
            var tickOffsetMs = floor(visibleStartOffsetMs / minorStepMs) * minorStepMs
            while (tickOffsetMs <= visibleEndOffsetMs + minorStepMs * 0.5f) {
                val majorPosition = tickOffsetMs / tickStepMs
                val isMajor = abs(majorPosition - majorPosition.roundToInt()) < 0.001f
                if (!isMajor) {
                    val visibleTickMs = tickOffsetMs - visibleStartOffsetMs
                    if (visibleTickMs >= -1f && visibleTickMs <= visibleDurationMs + 1f) {
                        val x = plotBounds.left + (visibleTickMs / visibleDurationMs) * plotBounds.width()
                        canvas.drawLine(x, plotBounds.bottom, x, plotBounds.bottom + 8f, minorTickPaint)
                        canvas.drawLine(x, plotBounds.top - 8f, x, plotBounds.top, minorTickPaint)
                    }
                }
                tickOffsetMs += minorStepMs
            }
        }

        private fun yForEnergy(
            energyMilliWattHours: Double,
            minEnergy: Double,
            energyRange: Double
        ): Float {
            return plotBounds.bottom -
                ((energyMilliWattHours - minEnergy) / energyRange).toFloat() * plotBounds.height()
        }

        private fun yForRightAxisValue(value: Double, scale: RightAxisScale): Float {
            return plotBounds.bottom -
                ((value - scale.min) / scale.range).toFloat().coerceIn(0f, 1f) * plotBounds.height()
        }

        private fun chooseEnergyTicks(minEnergy: Double, maxEnergy: Double): List<Double> {
            if (minEnergy >= 0.0 && maxEnergy <= 5.0) {
                return listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0)
            }
            if (minEnergy >= -5.0 && maxEnergy <= 0.0) {
                return listOf(-5.0, -4.0, -3.0, -2.0, -1.0, 0.0)
            }

            val range = (maxEnergy - minEnergy).coerceAtLeast(1.0)
            val step = chooseNiceStep(range / 4.0)
            val firstTick = floor(minEnergy / step) * step
            val lastTick = ceil(maxEnergy / step) * step
            val ticks = ArrayList<Double>()
            var tick = firstTick

            while (tick <= lastTick + step * 0.5) {
                ticks.add(if (abs(tick) < step / 1000.0) 0.0 else tick)
                tick += step
            }

            return if (ticks.size >= 2) ticks else listOf(0.0, step)
        }

        private fun chooseNiceStep(rawStep: Double): Double {
            if (rawStep <= 0.0) return 1.0

            var magnitude = 1.0
            while (magnitude * 10.0 <= rawStep) {
                magnitude *= 10.0
            }
            while (magnitude > rawStep) {
                magnitude /= 10.0
            }

            val normalized = rawStep / magnitude
            val niceNormalized = when {
                normalized <= 1.0 -> 1.0
                normalized <= 2.0 -> 2.0
                normalized <= 5.0 -> 5.0
                else -> 10.0
            }
            return niceNormalized * magnitude
        }

        private fun chooseTimeTickStepMs(visibleDurationMs: Float): Float {
            return when {
                visibleDurationMs <= 60_000f -> 15_000f
                visibleDurationMs <= 5 * 60_000f -> 60_000f
                visibleDurationMs <= 10 * 60_000f -> 5 * 60_000f
                visibleDurationMs <= 30 * 60_000f -> 10 * 60_000f
                visibleDurationMs <= 60 * 60_000f -> 15 * 60_000f
                visibleDurationMs <= 2 * 60 * 60_000f -> 30 * 60_000f
                visibleDurationMs <= 12 * 60 * 60_000f -> 60 * 60_000f
                else -> 2 * 60 * 60_000f
            }
        }

        private fun formatDurationTick(durationMs: Float): String {
            val totalSeconds = (durationMs / 1000f).roundToInt()
            if (totalSeconds == 0) {
                return "0"
            }
            val sign = if (totalSeconds < 0) "-" else ""
            val absoluteSeconds = abs(totalSeconds)
            return if (absoluteSeconds < 60) {
                "$sign${absoluteSeconds}s"
            } else {
                val minutes = absoluteSeconds / 60
                val hours = minutes / 60
                if (hours > 0) {
                    val remainingMinutes = minutes % 60
                    if (remainingMinutes == 0) {
                        "$sign${hours}h"
                    } else {
                        String.format(Locale.US, "%s%dh%dm", sign, hours, remainingMinutes)
                    }
                } else {
                    "$sign${minutes}m"
                }
            }
        }

        private fun formatSignedAxis(energyMilliWattHours: Double): String {
            val sign = when {
                energyMilliWattHours > 0.0 -> "+"
                energyMilliWattHours < 0.0 -> "-"
                else -> ""
            }
            return String.format(Locale.US, "%s%.0f", sign, abs(energyMilliWattHours))
        }
    }
}
