package com.analogwings.batterycurrent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class BatteryCapacityEstimator(private val context: Context) {
    data class DisplayState(
        val estimateMah: Int?,
        val warningText: String?,
        val isEventActive: Boolean,
        val isEventArmed: Boolean
    )

    data class DailyEstimateSummary(
        val timestampMs: Long,
        val averageCapacityMah: Int,
        val sampleCount: Int
    )

    private data class ActiveEvent(
        val direction: Int,
        val startTimestampMs: Long,
        val startChargeMah: Double
    )

    private data class DailyCapacityReading(
        val timestampMs: Long,
        val averageCapacityMah: Int,
        val sampleCount: Int
    )

    private data class CapacityWindow(
        val timestampMs: Long,
        val averageCapacityMah: Int
    )

    private data class MovingAverageSnapshot(
        val timestampMs: Long,
        val movingAverageMah: Int,
        val totalReadingCount: Int,
        val windowReadingCount: Int
    )

    private val eventsFile: File
        get() = File(context.filesDir, "battery_capacity_events.csv")

    private val readingsFile: File
        get() = File(context.filesDir, "battery_capacity_estimates.csv")

    private val movingAverageFile: File
        get() = File(context.filesDir, "battery_capacity_moving_average.csv")

    private val legacyCalibrationFile: File
        get() = File(context.filesDir, "battery_capacity_calibration.csv")

    private val prefs by lazy {
        context.getSharedPreferences("battery_capacity_estimator", Context.MODE_PRIVATE)
    }

    init {
        legacyCalibrationFile.delete()
    }

    fun processSample(
        batteryPercent: Int?,
        totalChargeMah: Double,
        averageMilliAmps: Double,
        temperatureC: Double?,
        voltageMv: Int?
    ): DisplayState {
        if (batteryPercent == null || abs(averageMilliAmps) < MINIMUM_CURRENT_MA) {
            return displayState()
        }

        val direction = if (averageMilliAmps > 0.0) CHARGING else DISCHARGING
        val previousPercent = prefs.getInt(LAST_BATTERY_PERCENT_KEY, UNKNOWN_PERCENT)
            .takeIf { it != UNKNOWN_PERCENT }
        processEventSample(batteryPercent, previousPercent, totalChargeMah, direction)
        prefs.edit().putInt(LAST_BATTERY_PERCENT_KEY, batteryPercent).apply()
        return displayState()
    }

    fun resetSegment(totalChargeMah: Double) {
        clearActiveEvent()
        prefs.edit().putBoolean(EVENT_PAUSED_AFTER_RESET_KEY, true).apply()
    }

    fun clearWarning() {
        latestCapacityWindow()?.let { window ->
            prefs.edit()
                .putLong(WARNING_REFERENCE_TIMESTAMP_KEY, window.timestampMs)
                .putInt(WARNING_REFERENCE_CAPACITY_KEY, window.averageCapacityMah)
                .apply()
        }
    }

    fun displayState(): DisplayState {
        val activeEvent = readActiveEvent()
        return DisplayState(
            estimateMah = latestMovingAverageEstimate() ?: latestDailyEstimate(),
            warningText = buildWarningText(),
            isEventActive = activeEvent != null,
            isEventArmed = activeEvent == null && isAtCapacityEventArmingLevel()
        )
    }

    fun recentDailyEstimates(limit: Int = 10): List<DailyEstimateSummary> {
        return readReadings()
            .takeLast(limit.coerceAtLeast(1))
            .map { reading ->
                DailyEstimateSummary(
                    timestampMs = reading.timestampMs,
                    averageCapacityMah = reading.averageCapacityMah,
                    sampleCount = reading.sampleCount
                )
            }
    }

    private fun processEventSample(
        batteryPercent: Int,
        previousPercent: Int?,
        totalChargeMah: Double,
        direction: Int
    ) {
        if (isPausedAfterReset()) {
            if (shouldStartEvent(batteryPercent, previousPercent, direction)) {
                clearPausedAfterReset()
            } else if (batteryPercent in (EVENT_LOW_PERCENT + 1) until EVENT_HIGH_PERCENT) {
                clearPausedAfterReset()
                return
            } else {
                return
            }
        }

        val activeEvent = readActiveEvent()
        if (activeEvent == null || activeEvent.direction != direction) {
            if (activeEvent != null) {
                clearActiveEvent()
                pauseUntilNextThresholdCrossing()
                return
            }
            maybeStartEvent(batteryPercent, previousPercent, totalChargeMah, direction)
            return
        }

        when (direction) {
            CHARGING -> {
                if (batteryPercent >= EVENT_HIGH_PERCENT) {
                    completeEvent(activeEvent, totalChargeMah)
                    clearActiveEvent()
                }
            }

            DISCHARGING -> {
                if (batteryPercent <= EVENT_LOW_PERCENT) {
                    completeEvent(activeEvent, totalChargeMah)
                    clearActiveEvent()
                }
            }
        }
    }

    private fun isPausedAfterReset(): Boolean {
        return prefs.getBoolean(EVENT_PAUSED_AFTER_RESET_KEY, false)
    }

    private fun clearPausedAfterReset() {
        prefs.edit().remove(EVENT_PAUSED_AFTER_RESET_KEY).apply()
    }

    private fun maybeStartEvent(
        batteryPercent: Int,
        previousPercent: Int?,
        totalChargeMah: Double,
        direction: Int
    ) {
        val shouldStart = shouldStartEvent(batteryPercent, previousPercent, direction)
        if (!shouldStart) {
            clearActiveEvent()
            return
        }

        writeActiveEvent(ActiveEvent(
            direction = direction,
            startTimestampMs = System.currentTimeMillis(),
            startChargeMah = totalChargeMah
        ))
    }

    private fun shouldStartEvent(batteryPercent: Int, previousPercent: Int?, direction: Int): Boolean {
        return when (direction) {
            CHARGING -> batteryPercent == EVENT_LOW_PERCENT ||
                    (previousPercent != null &&
                            previousPercent < EVENT_LOW_PERCENT &&
                            batteryPercent in (EVENT_LOW_PERCENT + 1) until EVENT_HIGH_PERCENT)
            DISCHARGING -> batteryPercent == EVENT_HIGH_PERCENT ||
                    (previousPercent != null &&
                            previousPercent > EVENT_HIGH_PERCENT &&
                            batteryPercent in (EVENT_LOW_PERCENT + 1) until EVENT_HIGH_PERCENT)
            else -> false
        }
    }

    private fun isAtCapacityEventArmingLevel(): Boolean {
        val batteryPercent = prefs.getInt(LAST_BATTERY_PERCENT_KEY, UNKNOWN_PERCENT)
        return batteryPercent != UNKNOWN_PERCENT &&
                (batteryPercent <= EVENT_LOW_PERCENT || batteryPercent >= EVENT_HIGH_PERCENT)
    }

    private fun completeEvent(activeEvent: ActiveEvent, endChargeMah: Double) {
        val capacityMah = (abs(endChargeMah - activeEvent.startChargeMah) * 100.0 / EVENT_PERCENT_SPAN)
            .roundToInt()
        if (capacityMah <= 0) return

        val now = System.currentTimeMillis()
        appendEvent(
            startTimestampMs = activeEvent.startTimestampMs,
            direction = activeEvent.direction,
            startChargeMah = activeEvent.startChargeMah,
            endTimestampMs = now,
            endChargeMah = endChargeMah
        )
        appendDailyReading(now, capacityMah)
        appendMovingAverageReading(now, capacityMah)
    }

    private fun appendEvent(
        startTimestampMs: Long,
        direction: Int,
        startChargeMah: Double,
        endTimestampMs: Long,
        endChargeMah: Double
    ) {
        val header = "Time_date_start,Direction,mAh_start,Time_date_end,mAh_end"
        val row = listOf(
            formatEventTimestamp(startTimestampMs),
            directionLabel(direction),
            startChargeMah.roundToInt().toString(),
            formatEventTimestamp(endTimestampMs),
            endChargeMah.roundToInt().toString()
        ).joinToString(",")

        if (!eventsFile.exists() || eventsFile.length() == 0L) {
            eventsFile.writeText("$header\n$row")
        } else {
            eventsFile.appendText("\n$row")
        }
    }

    private fun appendDailyReading(timestampMs: Long, capacityMah: Int) {
        val readings = readReadings().toMutableList()
        val today = dayStartMs(timestampMs)
        val todayIndex = readings.indexOfFirst { it.timestampMs == today }
        if (todayIndex >= 0) {
            val existing = readings[todayIndex]
            val newCount = existing.sampleCount + 1
            val newAverage = ((existing.averageCapacityMah * existing.sampleCount + capacityMah).toDouble() / newCount)
                .roundToInt()
            readings[todayIndex] = DailyCapacityReading(today, newAverage, newCount)
        } else {
            readings.add(DailyCapacityReading(today, capacityMah, 1))
        }

        readings.sortBy { it.timestampMs }
        while (readings.size > MAX_STORED_DAYS) {
            readings.removeAt(0)
        }
        readingsFile.writeText(readings.joinToString("\n") { reading ->
            "${reading.timestampMs},${reading.averageCapacityMah},${reading.sampleCount}"
        })
    }

    private fun readActiveEvent(): ActiveEvent? {
        val direction = prefs.getInt(ACTIVE_EVENT_DIRECTION_KEY, 0)
        if (direction == 0) return null
        val startTimestampMs = prefs.getLong(ACTIVE_EVENT_START_TIMESTAMP_KEY, 0L)
        val startChargeMah = prefs.getFloat(ACTIVE_EVENT_START_CHARGE_KEY, 0f).toDouble()
        if (startTimestampMs <= 0L) return null
        return ActiveEvent(direction, startTimestampMs, startChargeMah)
    }

    private fun writeActiveEvent(event: ActiveEvent) {
        prefs.edit()
            .putInt(ACTIVE_EVENT_DIRECTION_KEY, event.direction)
            .putLong(ACTIVE_EVENT_START_TIMESTAMP_KEY, event.startTimestampMs)
            .putFloat(ACTIVE_EVENT_START_CHARGE_KEY, event.startChargeMah.toFloat())
            .apply()
    }

    private fun clearActiveEvent() {
        prefs.edit()
            .remove(ACTIVE_EVENT_DIRECTION_KEY)
            .remove(ACTIVE_EVENT_START_TIMESTAMP_KEY)
            .remove(ACTIVE_EVENT_START_CHARGE_KEY)
            .apply()
    }

    private fun pauseUntilNextThresholdCrossing() {
        prefs.edit().putBoolean(EVENT_PAUSED_AFTER_RESET_KEY, true).apply()
    }

    private fun latestDailyEstimate(): Int? {
        return readReadings().lastOrNull()?.averageCapacityMah
    }

    private fun appendMovingAverageReading(timestampMs: Long, capacityMah: Int) {
        val samples = readMovingAverageSamples().toMutableList()
        samples.add(capacityMah)
        while (samples.size > MOVING_AVERAGE_READING_COUNT) {
            samples.removeAt(0)
        }

        val totalReadingCount = prefs.getInt(TOTAL_CAPACITY_READING_COUNT_KEY, 0) + 1
        val movingAverageMah = samples.average().roundToInt()
        prefs.edit()
            .putString(MOVING_AVERAGE_SAMPLES_KEY, samples.joinToString(","))
            .putInt(TOTAL_CAPACITY_READING_COUNT_KEY, totalReadingCount)
            .putInt(LATEST_MOVING_AVERAGE_KEY, movingAverageMah)
            .apply()

        if (totalReadingCount % MOVING_AVERAGE_READING_COUNT == 0) {
            appendMovingAverageSnapshot(
                MovingAverageSnapshot(
                    timestampMs = timestampMs,
                    movingAverageMah = movingAverageMah,
                    totalReadingCount = totalReadingCount,
                    windowReadingCount = samples.size
                )
            )
        }
    }

    private fun latestMovingAverageEstimate(): Int? {
        val latest = prefs.getInt(LATEST_MOVING_AVERAGE_KEY, 0)
        if (latest > 0) return latest

        val samples = readMovingAverageSamples()
        return samples.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    }

    private fun readMovingAverageSamples(): List<Int> {
        return prefs.getString(MOVING_AVERAGE_SAMPLES_KEY, null)
            ?.split(",")
            ?.mapNotNull { it.toIntOrNull()?.takeIf { value -> value > 0 } }
            ?: emptyList()
    }

    private fun appendMovingAverageSnapshot(snapshot: MovingAverageSnapshot) {
        val header = "timestampMs,date,movingAverageMah,totalReadingCount,windowReadingCount"
        val row = listOf(
            snapshot.timestampMs.toString(),
            formatEventTimestamp(snapshot.timestampMs),
            snapshot.movingAverageMah.toString(),
            snapshot.totalReadingCount.toString(),
            snapshot.windowReadingCount.toString()
        ).joinToString(",")

        if (!movingAverageFile.exists() || movingAverageFile.length() == 0L) {
            movingAverageFile.writeText("$header\n$row")
        } else {
            movingAverageFile.appendText("\n$row")
        }
    }

    private fun buildWarningText(): String? {
        val window = latestCapacityWindow() ?: return null
        val referenceCapacity = prefs.getInt(WARNING_REFERENCE_CAPACITY_KEY, 0)
        val referenceTimestamp = prefs.getLong(WARNING_REFERENCE_TIMESTAMP_KEY, 0L)
        if (referenceCapacity <= 0) {
            prefs.edit()
                .putLong(WARNING_REFERENCE_TIMESTAMP_KEY, window.timestampMs)
                .putInt(WARNING_REFERENCE_CAPACITY_KEY, window.averageCapacityMah)
                .apply()
            return null
        }

        val nextWarningCapacity = referenceCapacity * (1.0 - WARNING_DROP_FRACTION)
        return if (window.averageCapacityMah <= nextWarningCapacity) {
            "Battery capacity dropped by more than 1% since ${formatDate(referenceTimestamp)}"
        } else {
            null
        }
    }

    private fun latestCapacityWindow(): CapacityWindow? {
        return readMovingAverageSnapshots().lastOrNull()?.let { snapshot ->
            CapacityWindow(
                timestampMs = snapshot.timestampMs,
                averageCapacityMah = snapshot.movingAverageMah
            )
        }
    }

    private fun readMovingAverageSnapshots(): List<MovingAverageSnapshot> {
        if (!movingAverageFile.exists()) return emptyList()

        return movingAverageFile.readLines().mapNotNull { line ->
            if (line.startsWith("timestampMs,")) return@mapNotNull null
            val parts = line.split(",")
            if (parts.size < 5) return@mapNotNull null
            MovingAverageSnapshot(
                timestampMs = parts[0].toLongOrNull() ?: return@mapNotNull null,
                movingAverageMah = parts[2].toIntOrNull() ?: return@mapNotNull null,
                totalReadingCount = parts[3].toIntOrNull() ?: return@mapNotNull null,
                windowReadingCount = parts[4].toIntOrNull() ?: return@mapNotNull null
            )
        }.sortedBy { it.timestampMs }
    }

    private fun readReadings(): List<DailyCapacityReading> {
        if (!readingsFile.exists()) return emptyList()
        val readingsByDay = linkedMapOf<Long, DailyCapacityReading>()
        readingsFile.readLines().forEach { line ->
            val parts = line.split(",")
            if (parts.size < 2) return@forEach

            val timestamp = parts[0].toLongOrNull() ?: return@forEach
            val day = dayStartMs(timestamp)
            val capacity = parts[1].toIntOrNull() ?: return@forEach
            val sampleCount = parts.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val existing = readingsByDay[day]
            readingsByDay[day] = if (existing == null) {
                DailyCapacityReading(day, capacity, sampleCount)
            } else {
                val newCount = existing.sampleCount + sampleCount
                val newAverage =
                    ((existing.averageCapacityMah * existing.sampleCount + capacity * sampleCount).toDouble() / newCount)
                        .roundToInt()
                DailyCapacityReading(day, newAverage, newCount)
            }
        }
        return readingsByDay.values.sortedBy { it.timestampMs }
    }

    private fun dayStartMs(timestampMs: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timestampMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun formatDate(timestampMs: Long): String {
        if (timestampMs <= 0L) return "the stored reference date"
        return SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(timestampMs))
    }

    private fun formatEventTimestamp(timestampMs: Long): String {
        return SimpleDateFormat("yyyy_MM_dd_HHmm", Locale.US).format(Date(timestampMs))
    }

    private fun directionLabel(direction: Int): String {
        return if (direction == CHARGING) "charge" else "discharge"
    }

    private companion object {
        private const val MINIMUM_CURRENT_MA = 20.0
        private const val CHARGING = 1
        private const val DISCHARGING = -1
        private const val EVENT_LOW_PERCENT = 25
        private const val EVENT_HIGH_PERCENT = 75
        private const val EVENT_PERCENT_SPAN = 50.0
        private const val MAX_STORED_DAYS = 400
        private const val WARNING_DROP_FRACTION = 0.01
        private const val MOVING_AVERAGE_READING_COUNT = 100
        private const val ACTIVE_EVENT_DIRECTION_KEY = "active_event_direction"
        private const val ACTIVE_EVENT_START_TIMESTAMP_KEY = "active_event_start_timestamp_ms"
        private const val ACTIVE_EVENT_START_CHARGE_KEY = "active_event_start_charge_mah"
        private const val EVENT_PAUSED_AFTER_RESET_KEY = "event_paused_after_reset"
        private const val LAST_BATTERY_PERCENT_KEY = "last_battery_percent"
        private const val UNKNOWN_PERCENT = -1
        private const val WARNING_REFERENCE_TIMESTAMP_KEY = "warning_reference_timestamp_ms"
        private const val WARNING_REFERENCE_CAPACITY_KEY = "warning_reference_capacity_mah"
        private const val MOVING_AVERAGE_SAMPLES_KEY = "moving_average_capacity_samples"
        private const val TOTAL_CAPACITY_READING_COUNT_KEY = "total_capacity_reading_count"
        private const val LATEST_MOVING_AVERAGE_KEY = "latest_moving_average_mah"
    }
}
