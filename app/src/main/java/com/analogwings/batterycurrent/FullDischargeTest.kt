package com.analogwings.batterycurrent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object FullDischargeTest {
    data class Result(
        val startTimestampText: String,
        val endTimestampText: String?,
        val dischargedMah: Int?,
        val capacityEstimateMah: Int,
        val avgTempC: Double?,
        val avgVoltageV: Double?,
        val avgCurrentMa: Double?
    )

    private const val PREFS_NAME = "battery_current_full_discharge_test"
    private const val MODE_ENABLED_KEY = "mode_enabled"
    private const val ACTIVE_KEY = "active"
    private const val PENDING_START_KEY = "pending_start"
    private const val START_TIMESTAMP_KEY = "start_timestamp_ms"
    private const val START_CHARGE_KEY = "start_charge_mah"
    private const val TEMP_COUNT_KEY = "temp_count"
    private const val TEMP_SUM_KEY = "temp_sum_c"
    private const val VOLTAGE_COUNT_KEY = "voltage_count"
    private const val VOLTAGE_SUM_KEY = "voltage_sum_mv"
    private const val CURRENT_COUNT_KEY = "current_count"
    private const val CURRENT_SUM_KEY = "current_sum_ma"
    private const val LAST_SAMPLE_TIMESTAMP_KEY = "last_sample_timestamp_ms"
    private const val DISCONNECT_TIMESTAMP_KEY = "disconnect_timestamp_ms"
    private const val FILE_NAME = "battery_calibration_tests.csv"
    private const val START_PERCENT = 99
    private const val END_PERCENT = 15
    private const val FULL_PERCENT = 100
    private const val CALIBRATION_SPAN_FRACTION = 0.84
    private const val INTERRUPTION_STALE_MS = 45_000L

    fun isModeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(MODE_ENABLED_KEY, false)
    }

    fun setModeEnabled(context: Context, enabled: Boolean) {
        val editor = prefs(context).edit().putBoolean(MODE_ENABLED_KEY, enabled)
        if (!enabled) clearTestState(editor)
        editor.apply()
    }

    fun abortActive(context: Context) {
        clearTestState(prefs(context).edit())
            .putBoolean(MODE_ENABLED_KEY, false)
            .apply()
    }

    fun isActive(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(ACTIVE_KEY, false) || prefs.getBoolean(PENDING_START_KEY, false)
    }

    fun isMeasurementActive(context: Context): Boolean {
        return prefs(context).getBoolean(ACTIVE_KEY, false)
    }

    fun isPostDisconnectWaitingForStart(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(PENDING_START_KEY, false) &&
            prefs.getLong(DISCONNECT_TIMESTAMP_KEY, 0L) > 0L
    }

    fun foregroundPrefix(context: Context, nowMs: Long = System.currentTimeMillis()): String? {
        if (!isModeEnabled(context)) return null
        val prefs = prefs(context)
        val elapsedMinutes = postDisconnectElapsedMinutes(prefs, nowMs)
        return if (elapsedMinutes != null) {
            "CAL+$elapsedMinutes:"
        } else {
            "CAL:"
        }
    }

    fun shouldBlinkForegroundPrefix(context: Context): Boolean {
        return false
    }

    fun statusText(context: Context, nowMs: Long = System.currentTimeMillis()): String? {
        if (!isModeEnabled(context)) return null
        val prefs = prefs(context)
        return when {
            prefs.getBoolean(ACTIVE_KEY, false) -> "Calibrating battery capacity: discharge to 15%."
            prefs.getBoolean(PENDING_START_KEY, false) -> pendingStatusText(prefs, nowMs)
            else -> "Calibration enabled."
        }
    }

    fun latestCapacityEstimateMah(context: Context): Int? {
        return latestResult(context)?.capacityEstimateMah
    }

    fun latestResult(context: Context): Result? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size < 2) return null
            val headers = lines.first().split(",").map { it.trim() }
            val startIndex = headers.indexOf("Time_date_start")
            val endIndex = headers.indexOf("Time_date_end")
            val dischargedIndex = headers.indexOf("mAh_discharged")
            val capacityIndex = headers.indexOf("CalibrationCapacity_mAh")
                .takeIf { it >= 0 }
                ?: headers.indexOf("CapacityEstimate_mAh")
            val tempIndex = headers.indexOf("AvgTemp_C")
            val voltageIndex = headers.indexOf("AvgVoltage_V")
            val currentIndex = headers.indexOf("AvgCurrent_mA")
            if (startIndex < 0 || capacityIndex < 0) return null
            lines.asReversed()
                .dropLast(1)
                .mapNotNull { line ->
                    val parts = line.split(",")
                    Result(
                        startTimestampText = parts.getOrNull(startIndex)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
                        endTimestampText = parts.getOrNull(endIndex)?.trim()?.takeIf { it.isNotEmpty() },
                        dischargedMah = parts.getOrNull(dischargedIndex)?.trim()?.toIntOrNull(),
                        capacityEstimateMah = parts.getOrNull(capacityIndex)?.trim()?.toIntOrNull() ?: return@mapNotNull null,
                        avgTempC = parts.getOrNull(tempIndex)?.trim()?.toDoubleOrNull(),
                        avgVoltageV = parts.getOrNull(voltageIndex)?.trim()?.toDoubleOrNull(),
                        avgCurrentMa = parts.getOrNull(currentIndex)?.trim()?.toDoubleOrNull()
                    )
                }
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun abortIfStale(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(ACTIVE_KEY, false)) return
        val lastSampleMs = prefs.getLong(LAST_SAMPLE_TIMESTAMP_KEY, 0L)
        if (lastSampleMs <= 0L || nowMs - lastSampleMs > INTERRUPTION_STALE_MS) {
            clearTestState(prefs.edit())
                .putBoolean(MODE_ENABLED_KEY, false)
                .apply()
        }
    }

    fun abortIfIllegalPowerState(context: Context, pluggedIn: Boolean): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(MODE_ENABLED_KEY, false)) return false

        val pending = prefs.getBoolean(PENDING_START_KEY, false)
        val active = prefs.getBoolean(ACTIVE_KEY, false)
        val illegalPendingPlugIn = pending && pluggedIn
        val illegalActivePlugIn = active && pluggedIn
        if (!illegalPendingPlugIn && !illegalActivePlugIn) return false

        clearTestState(prefs.edit())
            .putBoolean(MODE_ENABLED_KEY, false)
            .apply()
        return true
    }

    fun armCalibrationSetup(
        context: Context,
        batteryPercent: Int?,
        nowMs: Long = System.currentTimeMillis()
    ): StartResult {
        if (!isModeEnabled(context)) return StartResult.MODE_DISABLED
        if (batteryPercent == null || batteryPercent < FULL_PERCENT) return StartResult.NOT_FULL
        prefs(context).edit()
            .putBoolean(PENDING_START_KEY, true)
            .putBoolean(ACTIVE_KEY, false)
            .putInt(TEMP_COUNT_KEY, 0)
            .putFloat(TEMP_SUM_KEY, 0f)
            .putInt(VOLTAGE_COUNT_KEY, 0)
            .putFloat(VOLTAGE_SUM_KEY, 0f)
            .putInt(CURRENT_COUNT_KEY, 0)
            .putFloat(CURRENT_SUM_KEY, 0f)
            .putLong(LAST_SAMPLE_TIMESTAMP_KEY, nowMs)
            .apply()
        return StartResult.PENDING
    }

    fun markMeasurementStarted(
        context: Context,
        totalChargeMah: Double,
        nowMs: Long = System.currentTimeMillis()
    ) {
        prefs(context).edit()
            .putBoolean(PENDING_START_KEY, false)
            .putBoolean(ACTIVE_KEY, true)
            .putLong(START_TIMESTAMP_KEY, nowMs)
            .putFloat(START_CHARGE_KEY, totalChargeMah.toFloat())
            .putLong(LAST_SAMPLE_TIMESTAMP_KEY, nowMs)
            .apply()
    }

    fun processSample(
        context: Context,
        batteryPercent: Int?,
        pluggedIn: Boolean,
        totalChargeMah: Double,
        currentMa: Double,
        temperatureC: Double?,
        voltageMv: Int?,
        nowMs: Long = System.currentTimeMillis()
    ): SampleResult {
        val prefs = prefs(context)
        if (prefs.getBoolean(PENDING_START_KEY, false)) {
            if (batteryPercent == null) {
                clearTestState(prefs.edit())
                    .putBoolean(MODE_ENABLED_KEY, false)
                    .apply()
                return SampleResult.ABORTED
            }

            if (pluggedIn) {
                clearTestState(prefs.edit())
                    .putBoolean(MODE_ENABLED_KEY, false)
                    .apply()
                return SampleResult.ABORTED
            }

            val disconnectMs = prefs.getLong(DISCONNECT_TIMESTAMP_KEY, 0L)
            val editor = prefs.edit().putLong(LAST_SAMPLE_TIMESTAMP_KEY, nowMs)
            if (disconnectMs <= 0L) {
                editor.putLong(DISCONNECT_TIMESTAMP_KEY, nowMs)
            }
            editor.apply()

            if (batteryPercent <= END_PERCENT) {
                clearTestState(prefs.edit())
                    .putBoolean(MODE_ENABLED_KEY, false)
                    .apply()
                return SampleResult.ABORTED
            }

            if (batteryPercent > START_PERCENT) return SampleResult.PENDING

            return SampleResult.READY_TO_START
        }

        if (!prefs.getBoolean(ACTIVE_KEY, false)) return SampleResult.INACTIVE
        if (pluggedIn || currentMa >= 0.0 || batteryPercent == null) {
            clearTestState(prefs.edit())
                .putBoolean(MODE_ENABLED_KEY, false)
                .apply()
            return SampleResult.ABORTED
        }

        val editor = prefs.edit()
        if (temperatureC != null) {
            val count = prefs.getInt(TEMP_COUNT_KEY, 0)
            val sum = prefs.getFloat(TEMP_SUM_KEY, 0f).toDouble()
            editor.putInt(TEMP_COUNT_KEY, count + 1)
                .putFloat(TEMP_SUM_KEY, (sum + temperatureC).toFloat())
        }
        if (voltageMv != null && voltageMv > 0) {
            val count = prefs.getInt(VOLTAGE_COUNT_KEY, 0)
            val sum = prefs.getFloat(VOLTAGE_SUM_KEY, 0f).toDouble()
            editor.putInt(VOLTAGE_COUNT_KEY, count + 1)
                .putFloat(VOLTAGE_SUM_KEY, (sum + voltageMv).toFloat())
        }
        val currentCount = prefs.getInt(CURRENT_COUNT_KEY, 0)
        val currentSum = prefs.getFloat(CURRENT_SUM_KEY, 0f).toDouble()
        editor.putInt(CURRENT_COUNT_KEY, currentCount + 1)
            .putFloat(CURRENT_SUM_KEY, (currentSum + abs(currentMa)).toFloat())
            .putLong(LAST_SAMPLE_TIMESTAMP_KEY, nowMs)
            .apply()

        if (batteryPercent > END_PERCENT) return SampleResult.ACTIVE

        appendCompletedTest(context, prefs, totalChargeMah, nowMs)
        clearTestState(prefs.edit()).putBoolean(MODE_ENABLED_KEY, false).apply()
        return SampleResult.COMPLETED
    }

    private fun appendCompletedTest(
        context: Context,
        prefs: android.content.SharedPreferences,
        endChargeMah: Double,
        endTimestampMs: Long
    ) {
        val startTimestampMs = prefs.getLong(START_TIMESTAMP_KEY, 0L)
        val startChargeMah = prefs.getFloat(START_CHARGE_KEY, 0f).toDouble()
        if (startTimestampMs <= 0L) return

        val tempCount = prefs.getInt(TEMP_COUNT_KEY, 0)
        val voltageCount = prefs.getInt(VOLTAGE_COUNT_KEY, 0)
        val currentCount = prefs.getInt(CURRENT_COUNT_KEY, 0)
        val avgTempC = averageOrNull(prefs.getFloat(TEMP_SUM_KEY, 0f).toDouble(), tempCount)
        val avgVoltageV = averageOrNull(prefs.getFloat(VOLTAGE_SUM_KEY, 0f).toDouble(), voltageCount)?.div(1000.0)
        val avgCurrentMa = averageOrNull(prefs.getFloat(CURRENT_SUM_KEY, 0f).toDouble(), currentCount)
        val dischargedMah = abs(endChargeMah - startChargeMah).roundToInt()
        val capacityMah = (dischargedMah / CALIBRATION_SPAN_FRACTION).roundToInt()
        val row = listOf(
            formatTimestamp(startTimestampMs),
            formatTimestamp(endTimestampMs),
            dischargedMah.toString(),
            capacityMah.toString(),
            avgTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            avgVoltageV?.let { String.format(Locale.US, "%.3f", it) } ?: "",
            avgCurrentMa?.let { String.format(Locale.US, "%.0f", it) } ?: ""
        ).joinToString(",")

        val file = File(context.filesDir, FILE_NAME)
        val header = "Time_date_start,Time_date_end,mAh_discharged,CalibrationCapacity_mAh,AvgTemp_C,AvgVoltage_V,AvgCurrent_mA"
        if (!file.exists() || file.length() == 0L) {
            file.writeText("$header\n$row")
        } else {
            file.appendText("\n$row")
        }
    }

    private fun clearTestState(editor: android.content.SharedPreferences.Editor): android.content.SharedPreferences.Editor {
        return editor
            .remove(PENDING_START_KEY)
            .remove(ACTIVE_KEY)
            .remove(START_TIMESTAMP_KEY)
            .remove(START_CHARGE_KEY)
            .remove(TEMP_COUNT_KEY)
            .remove(TEMP_SUM_KEY)
            .remove(VOLTAGE_COUNT_KEY)
            .remove(VOLTAGE_SUM_KEY)
            .remove(CURRENT_COUNT_KEY)
            .remove(CURRENT_SUM_KEY)
            .remove(LAST_SAMPLE_TIMESTAMP_KEY)
            .remove(DISCONNECT_TIMESTAMP_KEY)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun pendingStatusText(prefs: android.content.SharedPreferences, nowMs: Long): String {
        return when {
            prefs.getLong(DISCONNECT_TIMESTAMP_KEY, 0L) > 0L -> {
                val elapsed = postDisconnectElapsedMinutes(prefs, nowMs) ?: 0
                "Cal: discharge to 99% (${elapsed} min since start)."
            }
            else -> "Cal: discharge to 99%."
        }
    }

    private fun postDisconnectElapsedMinutes(prefs: android.content.SharedPreferences, nowMs: Long): Int? {
        if (!prefs.getBoolean(PENDING_START_KEY, false)) return null
        val disconnectMs = prefs.getLong(DISCONNECT_TIMESTAMP_KEY, 0L)
        if (disconnectMs <= 0L) return null
        return (((nowMs - disconnectMs).coerceAtLeast(0L)) / 60_000L).toInt()
    }

    private fun averageOrNull(sum: Double, count: Int): Double? {
        return count.takeIf { it > 0 }?.let { sum / it }
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy_MM_dd_HHmm", Locale.US).format(Date(timestampMs))
    }

    enum class StartResult {
        MODE_DISABLED,
        NOT_FULL,
        PENDING
    }

    enum class SampleResult {
        INACTIVE,
        PENDING,
        READY_TO_START,
        ACTIVE,
        COMPLETED,
        ABORTED
    }
}
