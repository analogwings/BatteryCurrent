package com.analogwings.batterycurrent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object FullDischargeTest {
    private const val PREFS_NAME = "battery_current_full_discharge_test"
    private const val MODE_ENABLED_KEY = "mode_enabled"
    private const val ACTIVE_KEY = "active"
    private const val PENDING_START_KEY = "pending_start"
    private const val PENDING_START_AFTER_MS_KEY = "pending_start_after_ms"
    private const val START_TIMESTAMP_KEY = "start_timestamp_ms"
    private const val START_CHARGE_KEY = "start_charge_mah"
    private const val TEMP_COUNT_KEY = "temp_count"
    private const val TEMP_SUM_KEY = "temp_sum_c"
    private const val VOLTAGE_COUNT_KEY = "voltage_count"
    private const val VOLTAGE_SUM_KEY = "voltage_sum_mv"
    private const val CURRENT_COUNT_KEY = "current_count"
    private const val CURRENT_SUM_KEY = "current_sum_ma"
    private const val LAST_SAMPLE_TIMESTAMP_KEY = "last_sample_timestamp_ms"
    private const val FILE_NAME = "battery_full_discharge_tests.csv"
    private const val START_PERCENT = 100
    private const val END_PERCENT = 15
    private const val FULL_DISCHARGE_SPAN_FRACTION = 0.85
    private const val START_DELAY_MS = 10_000L
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

    fun latestCapacityEstimateMah(context: Context): Int? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size < 2) return null
            val headers = lines.first().split(",").map { it.trim() }
            val capacityIndex = headers.indexOf("CapacityEstimate_mAh")
            if (capacityIndex < 0) return null
            lines.asReversed()
                .dropLast(1)
                .mapNotNull { line ->
                    line.split(",").getOrNull(capacityIndex)?.trim()?.toIntOrNull()
                }
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun abortIfStale(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val prefs = prefs(context)
        if (!prefs.getBoolean(ACTIVE_KEY, false) && !prefs.getBoolean(PENDING_START_KEY, false)) return
        val lastSampleMs = prefs.getLong(LAST_SAMPLE_TIMESTAMP_KEY, 0L)
        if (lastSampleMs <= 0L || nowMs - lastSampleMs > INTERRUPTION_STALE_MS) {
            clearTestState(prefs.edit())
                .putBoolean(MODE_ENABLED_KEY, false)
                .apply()
        }
    }

    fun tryStartFromReset(
        context: Context,
        batteryPercent: Int?,
        pluggedIn: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): StartResult {
        if (!isModeEnabled(context)) return StartResult.MODE_DISABLED
        if (pluggedIn) {
            abortActive(context)
            return StartResult.CHARGER_CONNECTED
        }
        if (batteryPercent == null || batteryPercent < START_PERCENT) {
            abortActive(context)
            return StartResult.NOT_READY
        }

        prefs(context).edit()
            .putBoolean(PENDING_START_KEY, true)
            .putLong(PENDING_START_AFTER_MS_KEY, nowMs + START_DELAY_MS)
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
            if (pluggedIn || batteryPercent == null) {
                clearTestState(prefs.edit())
                    .putBoolean(MODE_ENABLED_KEY, false)
                    .apply()
                return SampleResult.ABORTED
            }
            val startAfterMs = prefs.getLong(PENDING_START_AFTER_MS_KEY, 0L)
            if (nowMs < startAfterMs) {
                prefs.edit().putLong(LAST_SAMPLE_TIMESTAMP_KEY, nowMs).apply()
                return SampleResult.PENDING
            }
            prefs.edit()
                .putBoolean(PENDING_START_KEY, false)
                .putBoolean(ACTIVE_KEY, true)
                .putLong(START_TIMESTAMP_KEY, nowMs)
                .putFloat(START_CHARGE_KEY, totalChargeMah.toFloat())
                .putLong(LAST_SAMPLE_TIMESTAMP_KEY, nowMs)
                .apply()
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
        val capacityMah = (dischargedMah / FULL_DISCHARGE_SPAN_FRACTION).roundToInt()
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
        val header = "Time_date_start,Time_date_end,mAh_discharged,CapacityEstimate_mAh,AvgTemp_C,AvgVoltage_V,AvgCurrent_mA"
        if (!file.exists() || file.length() == 0L) {
            file.writeText("$header\n$row")
        } else {
            file.appendText("\n$row")
        }
    }

    private fun clearTestState(editor: android.content.SharedPreferences.Editor): android.content.SharedPreferences.Editor {
        return editor
            .remove(PENDING_START_KEY)
            .remove(PENDING_START_AFTER_MS_KEY)
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
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun averageOrNull(sum: Double, count: Int): Double? {
        return count.takeIf { it > 0 }?.let { sum / it }
    }

    private fun formatTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy_MM_dd_HHmm", Locale.US).format(Date(timestampMs))
    }

    enum class StartResult {
        MODE_DISABLED,
        CHARGER_CONNECTED,
        NOT_READY,
        PENDING
    }

    enum class SampleResult {
        INACTIVE,
        PENDING,
        ACTIVE,
        COMPLETED,
        ABORTED
    }
}
