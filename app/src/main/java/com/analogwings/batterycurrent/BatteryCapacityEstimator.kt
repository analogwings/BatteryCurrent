package com.analogwings.batterycurrent

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
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
        val startChargeMah: Double,
        val currentSampleCount: Int,
        val currentMagnitudeSumMa: Double,
        val temperatureSampleCount: Int,
        val temperatureSumC: Double
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
        processEventSample(batteryPercent, previousPercent, totalChargeMah, direction, averageMilliAmps, temperatureC)
        prefs.edit().putInt(LAST_BATTERY_PERCENT_KEY, batteryPercent).apply()
        return displayState()
    }

    fun resetSegment(totalChargeMah: Double) {
        clearActiveEvent()
        prefs.edit().putBoolean(EVENT_PAUSED_AFTER_RESET_KEY, true).apply()
    }

    fun clearWarning() {
        readMovingAverageSnapshots().lastOrNull()?.let { snapshot ->
            prefs.edit()
                .putInt(ACKNOWLEDGED_WARNING_READING_COUNT_KEY, snapshot.totalReadingCount)
                .apply()
        }
    }

    fun displayState(): DisplayState {
        val activeEvent = readActiveEvent()
        return DisplayState(
            estimateMah = recentDailyWeightedEstimate() ?: latestDailyEstimate(),
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
        direction: Int,
        averageMilliAmps: Double,
        temperatureC: Double?
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
            maybeStartEvent(batteryPercent, previousPercent, totalChargeMah, direction, averageMilliAmps, temperatureC)
            return
        }

        val updatedActiveEvent = addEventSample(activeEvent, averageMilliAmps, temperatureC)

        when (direction) {
            CHARGING -> {
                if (batteryPercent >= EVENT_HIGH_PERCENT) {
                    completeEvent(updatedActiveEvent, totalChargeMah)
                    clearActiveEvent()
                }
            }

            DISCHARGING -> {
                if (batteryPercent <= EVENT_LOW_PERCENT) {
                    completeEvent(updatedActiveEvent, totalChargeMah)
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
        direction: Int,
        averageMilliAmps: Double,
        temperatureC: Double?
    ) {
        val shouldStart = shouldStartEvent(batteryPercent, previousPercent, direction)
        if (!shouldStart) {
            clearActiveEvent()
            return
        }

        writeActiveEvent(ActiveEvent(
            direction = direction,
            startTimestampMs = System.currentTimeMillis(),
            startChargeMah = totalChargeMah,
            currentSampleCount = 1,
            currentMagnitudeSumMa = abs(averageMilliAmps),
            temperatureSampleCount = if (temperatureC != null) 1 else 0,
            temperatureSumC = temperatureC ?: 0.0
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
        val avgCurrentMa = activeEvent.currentMagnitudeSumMa
            .takeIf { activeEvent.currentSampleCount > 0 }
            ?.let { it / activeEvent.currentSampleCount }
        val avgTempC = activeEvent.temperatureSumC
            .takeIf { activeEvent.temperatureSampleCount > 0 }
            ?.let { it / activeEvent.temperatureSampleCount }
        val peukertK = estimatePeukertK(capacityMah, avgCurrentMa)
        val peukertAdjustedCapacityMah = peukertAdjustedCapacity(capacityMah, avgCurrentMa, peukertK)

        appendEvent(
            startTimestampMs = activeEvent.startTimestampMs,
            direction = activeEvent.direction,
            startChargeMah = activeEvent.startChargeMah,
            endTimestampMs = now,
            endChargeMah = endChargeMah,
            avgCurrentMa = avgCurrentMa,
            avgTempC = avgTempC,
            peukertK = peukertK,
            peukertAdjustedCapacityMah = peukertAdjustedCapacityMah
        )
        appendDailyReading(now, peukertAdjustedCapacityMah ?: capacityMah)
        appendMovingAverageReading(now, peukertAdjustedCapacityMah ?: capacityMah)
    }

    private fun appendEvent(
        startTimestampMs: Long,
        direction: Int,
        startChargeMah: Double,
        endTimestampMs: Long,
        endChargeMah: Double,
        avgCurrentMa: Double?,
        avgTempC: Double?,
        peukertK: Double?,
        peukertAdjustedCapacityMah: Int?
    ) {
        val header = "Time_date_start,Direction,mAh_start,Time_date_end,mAh_end,AvgCurrent_mA,AvgTemp_C,PeukertK,PeukertAdjustedCapacity_mAh"
        val row = listOf(
            formatEventTimestamp(startTimestampMs),
            directionLabel(direction),
            startChargeMah.roundToInt().toString(),
            formatEventTimestamp(endTimestampMs),
            endChargeMah.roundToInt().toString(),
            avgCurrentMa?.let { String.format(Locale.US, "%.0f", it) } ?: "",
            avgTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            peukertK?.let { String.format(Locale.US, "%.4f", it) } ?: "",
            peukertAdjustedCapacityMah?.toString() ?: ""
        ).joinToString(",")

        ensureEventsHeader(header)
        if (!eventsFile.exists() || eventsFile.length() == 0L) {
            eventsFile.writeText("$header\\n$row")
        } else {
            eventsFile.appendText("\\n$row")
        }
    }

    private fun addEventSample(activeEvent: ActiveEvent, averageMilliAmps: Double, temperatureC: Double?): ActiveEvent {
        val updatedEvent = activeEvent.copy(
            currentSampleCount = activeEvent.currentSampleCount + 1,
            currentMagnitudeSumMa = activeEvent.currentMagnitudeSumMa + abs(averageMilliAmps),
            temperatureSampleCount = activeEvent.temperatureSampleCount + if (temperatureC != null) 1 else 0,
            temperatureSumC = activeEvent.temperatureSumC + (temperatureC ?: 0.0)
        )
        writeActiveEvent(updatedEvent)
        return updatedEvent
    }

    private fun ensureEventsHeader(header: String) {
        if (!eventsFile.exists() || eventsFile.length() == 0L) return
        val lines = eventsFile.readLines()
        if (lines.isEmpty() || lines.first() == header) return
        if (lines.first().startsWith("Time_date_start,")) {
            eventsFile.writeText((listOf(header) + lines.drop(1)).joinToString("\n"))
        }
    }

    private data class PeukertEventReading(
        val capacityMah: Int,
        val avgCurrentMa: Double
    )

    private fun estimatePeukertK(capacityMah: Int, avgCurrentMa: Double?): Double? {
        if (capacityMah <= 0 || avgCurrentMa == null || avgCurrentMa < MINIMUM_PEUKERT_CURRENT_MA) {
            return latestStoredPeukertK()
        }

        val candidates = readPeukertEventReadings()
            .filter { it.capacityMah > 0 && it.avgCurrentMa >= MINIMUM_PEUKERT_CURRENT_MA }
            .mapNotNull { previous ->
                val currentRatio = avgCurrentMa / previous.avgCurrentMa
                if (currentRatio < MINIMUM_PEUKERT_CURRENT_RATIO || currentRatio > (1.0 / MINIMUM_PEUKERT_CURRENT_RATIO)) {
                    val rawK = 1.0 + ln(capacityMah.toDouble() / previous.capacityMah.toDouble()) /
                        ln(previous.avgCurrentMa / avgCurrentMa)
                    rawK.takeIf { it.isFinite() && it in PEUKERT_K_MIN..PEUKERT_K_MAX }
                } else {
                    null
                }
            }

        val newK = when {
            candidates.isNotEmpty() -> candidates.average().coerceIn(PEUKERT_K_MIN, PEUKERT_K_MAX)
            else -> latestStoredPeukertK()
        }
        if (newK != null) {
            prefs.edit().putFloat(LATEST_PEUKERT_K_KEY, newK.toFloat()).apply()
        }
        return newK
    }

    private fun latestStoredPeukertK(): Double? {
        val stored = prefs.getFloat(LATEST_PEUKERT_K_KEY, Float.NaN)
        return stored.takeUnless { it.isNaN() }?.toDouble()
    }

    private fun peukertAdjustedCapacity(capacityMah: Int, avgCurrentMa: Double?, peukertK: Double?): Int? {
        if (capacityMah <= 0 || avgCurrentMa == null || peukertK == null) return null
        if (avgCurrentMa < MINIMUM_PEUKERT_CURRENT_MA) return null
        return (capacityMah * (avgCurrentMa / PEUKERT_REFERENCE_CURRENT_MA).pow(peukertK - 1.0))
            .roundToInt()
            .takeIf { it > 0 }
    }

    private fun readPeukertEventReadings(): List<PeukertEventReading> {
        if (!eventsFile.exists()) return emptyList()
        return eventsFile.readLines().drop(1).mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size < 9) return@mapNotNull null
            val startCharge = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            val endCharge = parts[4].toDoubleOrNull() ?: return@mapNotNull null
            val avgCurrent = parts[5].toDoubleOrNull() ?: return@mapNotNull null
            val capacity = (abs(endCharge - startCharge) * 100.0 / EVENT_PERCENT_SPAN).roundToInt()
            PeukertEventReading(capacity, avgCurrent)
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
        return ActiveEvent(
            direction = direction,
            startTimestampMs = startTimestampMs,
            startChargeMah = startChargeMah,
            currentSampleCount = prefs.getInt(ACTIVE_EVENT_CURRENT_SAMPLE_COUNT_KEY, 0),
            currentMagnitudeSumMa = prefs.getFloat(ACTIVE_EVENT_CURRENT_SUM_KEY, 0f).toDouble(),
            temperatureSampleCount = prefs.getInt(ACTIVE_EVENT_TEMPERATURE_SAMPLE_COUNT_KEY, 0),
            temperatureSumC = prefs.getFloat(ACTIVE_EVENT_TEMPERATURE_SUM_KEY, 0f).toDouble()
        )
    }

    private fun writeActiveEvent(event: ActiveEvent) {
        prefs.edit()
            .putInt(ACTIVE_EVENT_DIRECTION_KEY, event.direction)
            .putLong(ACTIVE_EVENT_START_TIMESTAMP_KEY, event.startTimestampMs)
            .putFloat(ACTIVE_EVENT_START_CHARGE_KEY, event.startChargeMah.toFloat())
            .putInt(ACTIVE_EVENT_CURRENT_SAMPLE_COUNT_KEY, event.currentSampleCount)
            .putFloat(ACTIVE_EVENT_CURRENT_SUM_KEY, event.currentMagnitudeSumMa.toFloat())
            .putInt(ACTIVE_EVENT_TEMPERATURE_SAMPLE_COUNT_KEY, event.temperatureSampleCount)
            .putFloat(ACTIVE_EVENT_TEMPERATURE_SUM_KEY, event.temperatureSumC.toFloat())
            .apply()
    }

    private fun clearActiveEvent() {
        prefs.edit()
            .remove(ACTIVE_EVENT_DIRECTION_KEY)
            .remove(ACTIVE_EVENT_START_TIMESTAMP_KEY)
            .remove(ACTIVE_EVENT_START_CHARGE_KEY)
            .remove(ACTIVE_EVENT_CURRENT_SAMPLE_COUNT_KEY)
            .remove(ACTIVE_EVENT_CURRENT_SUM_KEY)
            .remove(ACTIVE_EVENT_TEMPERATURE_SAMPLE_COUNT_KEY)
            .remove(ACTIVE_EVENT_TEMPERATURE_SUM_KEY)
            .apply()
    }

    private fun pauseUntilNextThresholdCrossing() {
        prefs.edit().putBoolean(EVENT_PAUSED_AFTER_RESET_KEY, true).apply()
    }

    private fun latestDailyEstimate(): Int? {
        return readReadings().lastOrNull()?.averageCapacityMah
    }

    private fun recentDailyWeightedEstimate(limit: Int = 10): Int? {
        val readings = readReadings().takeLast(limit.coerceAtLeast(1))
        val totalCount = readings.sumOf { it.sampleCount }
        if (totalCount <= 0) return null

        val weightedTotal = readings.sumOf { it.averageCapacityMah.toLong() * it.sampleCount.toLong() }
        return (weightedTotal.toDouble() / totalCount).roundToInt()
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
        val snapshots = readMovingAverageSnapshots()
        if (snapshots.size < 2) return null

        val previous = snapshots[snapshots.lastIndex - 1]
        val current = snapshots.last()
        val acknowledgedCount = prefs.getInt(ACKNOWLEDGED_WARNING_READING_COUNT_KEY, 0)
        if (current.totalReadingCount <= acknowledgedCount) return null

        val nextWarningCapacity = previous.movingAverageMah * (1.0 - WARNING_DROP_FRACTION)
        return if (current.movingAverageMah <= nextWarningCapacity) {
            "Battery capacity dropped by more than 1% since ${formatDate(previous.timestampMs)}"
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
        private const val ACTIVE_EVENT_CURRENT_SAMPLE_COUNT_KEY = "active_event_current_sample_count"
        private const val ACTIVE_EVENT_CURRENT_SUM_KEY = "active_event_current_sum_ma"
        private const val ACTIVE_EVENT_TEMPERATURE_SAMPLE_COUNT_KEY = "active_event_temperature_sample_count"
        private const val ACTIVE_EVENT_TEMPERATURE_SUM_KEY = "active_event_temperature_sum_c"
        private const val EVENT_PAUSED_AFTER_RESET_KEY = "event_paused_after_reset"
        private const val LAST_BATTERY_PERCENT_KEY = "last_battery_percent"
        private const val UNKNOWN_PERCENT = -1
        private const val WARNING_REFERENCE_TIMESTAMP_KEY = "warning_reference_timestamp_ms"
        private const val WARNING_REFERENCE_CAPACITY_KEY = "warning_reference_capacity_mah"
        private const val ACKNOWLEDGED_WARNING_READING_COUNT_KEY = "acknowledged_warning_reading_count"
        private const val MOVING_AVERAGE_SAMPLES_KEY = "moving_average_capacity_samples"
        private const val TOTAL_CAPACITY_READING_COUNT_KEY = "total_capacity_reading_count"
        private const val LATEST_MOVING_AVERAGE_KEY = "latest_moving_average_mah"
        private const val LATEST_PEUKERT_K_KEY = "latest_peukert_k"
        private const val PEUKERT_REFERENCE_CURRENT_MA = 1000.0
        private const val MINIMUM_PEUKERT_CURRENT_MA = 50.0
        private const val MINIMUM_PEUKERT_CURRENT_RATIO = 0.75
        private const val PEUKERT_K_MIN = 1.0
        private const val PEUKERT_K_MAX = 1.30
    }
}
