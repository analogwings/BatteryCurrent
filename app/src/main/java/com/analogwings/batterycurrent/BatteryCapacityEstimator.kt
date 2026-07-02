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
        val sampleCount: Int,
        val isExcludedOnly: Boolean = false
    )

    data class CapacityEventSummary(
        val eventId: String,
        val startTimestampMs: Long,
        val endTimestampMs: Long,
        val direction: String,
        val avgCurrentMa: Double?,
        val avgTempC: Double?,
        val avgVoltageMv: Double?,
        val mahAdded: Int,
        val capacityEstimateMah: Int,
        val peukertK: Double?,
        val peukertAdjustedCapacityMah: Int?,
        val isExcluded: Boolean
    )

    data class SocBucketSummary(
        val bucketStartPct: Int,
        val bucketEndPct: Int,
        val learnedMah: Double?,
        val learnedWh: Double?,
        val sampleCount: Int,
        val avgCurrentMa: Double?,
        val avgTempC: Double?
    )

    data class SocLinearityPoint(
        val bucketStartPct: Int,
        val bucketEndPct: Int,
        val midpointPct: Int,
        val deviationFromIdeal: Double,
        val learnedMah: Double,
        val idealMah: Double,
        val sampleCount: Int,
        val isFittedBucketAverage: Boolean = false
    )

    data class CapacityStats(
        val referenceCapacityMah: Int?,
        val referenceCurrentMa: Double?,
        val peukertK: Double?,
        val chargeEquivalentCycles: Double,
        val dischargeEquivalentCycles: Double,
        val chargeStats: DirectionStats,
        val dischargeStats: DirectionStats
    )

    data class DirectionStats(
        val eventCount: Int,
        val averageCurrentMa: Double?,
        val averageCRate: Double?,
        val averageRawCapacityMah: Int?,
        val averageCapacityAtReferenceCurrentMah: Int?,
        val lowRateEventCount: Int,
        val nearReferenceEventCount: Int,
        val highRateEventCount: Int
    )

    private data class SocBucketSample(
        val timestampMs: Long,
        val bucketStartPct: Int,
        val bucketEndPct: Int,
        val learnedMah: Double,
        val currentMa: Double,
        val temperatureC: Double?
    )

    private data class ActiveEvent(
        val direction: Int,
        val startTimestampMs: Long,
        val startChargeMah: Double,
        val currentSampleCount: Int,
        val currentMagnitudeSumMa: Double,
        val temperatureSampleCount: Int,
        val temperatureSumC: Double,
        val voltageSampleCount: Int,
        val voltageSumMv: Double
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

    private val socBucketsFile: File
        get() = File(context.filesDir, "battery_soc_buckets.csv")

    private val socBucketSamplesFile: File
        get() = File(context.filesDir, "battery_soc_bucket_samples.csv")

    private val prefs by lazy {
        context.getSharedPreferences("battery_capacity_estimator", Context.MODE_PRIVATE)
    }

    private val thresholds: CapacityThresholdPreference.Thresholds
        get() = CapacityThresholdPreference.load(context)

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
        updateEquivalentCycles(batteryPercent, previousPercent, direction)
        updateSocBucketTable(batteryPercent, previousPercent, totalChargeMah, direction, averageMilliAmps, temperatureC, voltageMv)
        processEventSample(batteryPercent, previousPercent, totalChargeMah, direction, averageMilliAmps, temperatureC, voltageMv)
        prefs.edit().putInt(LAST_BATTERY_PERCENT_KEY, batteryPercent).apply()
        return displayState()
    }

    fun resetSegment(totalChargeMah: Double) {
        clearActiveEvent()
        prefs.edit()
            .putBoolean(EVENT_PAUSED_AFTER_RESET_KEY, true)
            .remove(LAST_SOC_BUCKET_PERCENT_KEY)
            .remove(LAST_SOC_BUCKET_CHARGE_MAH_KEY)
            .remove(LAST_SOC_BUCKET_DIRECTION_KEY)
            .apply()
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
            estimateMah = recentIncludedEventWeightedEstimate() ?: recentDailyWeightedEstimate() ?: latestDailyEstimate(),
            warningText = buildWarningText(),
            isEventActive = activeEvent != null,
            isEventArmed = activeEvent == null && isAtCapacityEventArmingLevel()
        )
    }

    fun recentDailyEstimates(limit: Int = 10): List<DailyEstimateSummary> {
        val includedRows = readReadings().associateBy { it.timestampMs }
        val allEventDays = readCapacityEvents(includeExcluded = true)
            .groupBy { dayStartMs(it.endTimestampMs) }
        val rows = (includedRows.keys + allEventDays.keys)
            .distinct()
            .sorted()
            .map { reading ->
                val included = includedRows[reading]
                if (included != null) {
                    DailyEstimateSummary(
                        timestampMs = included.timestampMs,
                        averageCapacityMah = included.averageCapacityMah,
                        sampleCount = included.sampleCount
                    )
                } else {
                    val events = allEventDays[reading].orEmpty()
                    DailyEstimateSummary(
                        timestampMs = reading,
                        averageCapacityMah = 0,
                        sampleCount = events.size,
                        isExcludedOnly = true
                    )
                }
            }
        return rows.takeLast(limit.coerceAtLeast(1))
    }

    fun estimateNearTimestamp(timestampMs: Long, windowDays: Int = 3): Int? {
        val readings = readReadings()
        if (readings.isEmpty()) return null
        val windowMs = windowDays.coerceAtLeast(1).toLong() * 24L * 60L * 60L * 1000L
        val nearby = readings.filter { abs(it.timestampMs - timestampMs) <= windowMs }
            .takeIf { it.isNotEmpty() }
            ?: listOf(readings.minBy { abs(it.timestampMs - timestampMs) })
        val totalCount = nearby.sumOf { it.sampleCount }
        if (totalCount <= 0) return null
        val weightedTotal = nearby.sumOf { it.averageCapacityMah.toLong() * it.sampleCount.toLong() }
        return (weightedTotal.toDouble() / totalCount).roundToInt()
    }

    fun recentWeightedEstimate(limit: Int = 10): Int? {
        return recentIncludedEventWeightedEstimate(limit) ?: recentDailyWeightedEstimate(limit)
    }

    fun capacityStats(): CapacityStats {
        val referenceCapacity = BatteryCapacityReference.originalCapacityMah(context)
            ?: recentDailyWeightedEstimate()
            ?: latestDailyEstimate()
        val referenceCurrent = referenceCapacity?.let { it * REFERENCE_C_RATE }
        val peukertK = latestStoredPeukertK()
        val events = readCapacityEvents(includeExcluded = false)
        return CapacityStats(
            referenceCapacityMah = referenceCapacity,
            referenceCurrentMa = referenceCurrent,
            peukertK = peukertK,
            chargeEquivalentCycles = prefs.getFloat(CHARGE_EQUIVALENT_CYCLES_KEY, 0f).toDouble(),
            dischargeEquivalentCycles = prefs.getFloat(DISCHARGE_EQUIVALENT_CYCLES_KEY, 0f).toDouble(),
            chargeStats = buildDirectionStats(events.filter { it.direction.equals("charge", ignoreCase = true) }, referenceCapacity, referenceCurrent, peukertK),
            dischargeStats = buildDirectionStats(events.filter { it.direction.equals("discharge", ignoreCase = true) }, referenceCapacity, referenceCurrent, peukertK)
        )
    }

    fun eventsForDay(dayTimestampMs: Long): List<CapacityEventSummary> {
        val targetDay = dayStartMs(dayTimestampMs)
        return readCapacityEvents(includeExcluded = true)
            .filter { event ->
                dayStartMs(event.startTimestampMs) == targetDay || dayStartMs(event.endTimestampMs) == targetDay
            }
            .sortedBy { it.startTimestampMs }
    }

    fun setCapacityEventExcluded(eventId: String, excluded: Boolean): Boolean {
        if (!eventsFile.exists()) return false
        ensureEventsHeader(CAPACITY_EVENTS_HEADER)

        val lines = eventsFile.readLines().filter { it.isNotBlank() }
        if (lines.size < 2) return false
        val headers = lines.first().split(",").map { it.trim() }
        val excludedIndex = headers.indexOfFirst { it.equals("Excluded", ignoreCase = true) }
        if (excludedIndex < 0) return false

        val startIndex = headers.indexOf("Time_date_start")
        val directionIndex = headers.indexOf("Direction")
        val startMahIndex = headers.indexOf("mAh_start")
        val endIndex = headers.indexOf("Time_date_end")
        val endMahIndex = headers.indexOf("mAh_end")
        var changed = false
        val updatedRows = lines.drop(1).map { line ->
            val parts = line.split(",").toMutableList()
            while (parts.size <= excludedIndex) parts.add("")
            if (capacityEventId(parts, startIndex, directionIndex, startMahIndex, endIndex, endMahIndex) == eventId) {
                parts[excludedIndex] = excluded.toString()
                changed = true
            }
            parts.joinToString(",")
        }

        if (!changed) return false
        eventsFile.writeText((listOf(CAPACITY_EVENTS_HEADER) + updatedRows).joinToString("\n"))
        rebuildCapacitySummariesFromEvents()
        return true
    }

    private fun readCapacityEvents(includeExcluded: Boolean = false): List<CapacityEventSummary> {
        if (!eventsFile.exists()) return emptyList()
        ensureEventsHeader(CAPACITY_EVENTS_HEADER)
        val lines = eventsFile.readText()
            .replace("\\n", "\n")
            .lines()
            .filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()

        val headers = lines.first().split(",").map { it.trim() }
        fun indexOf(vararg names: String): Int {
            return names.mapNotNull { name ->
                headers.indexOfFirst { it.equals(name, ignoreCase = true) }.takeIf { it >= 0 }
            }.firstOrNull() ?: -1
        }

        val startIndex = indexOf("Time_date_start")
        val directionIndex = indexOf("Direction")
        val startMahIndex = indexOf("mAh_start")
        val endIndex = indexOf("Time_date_end")
        val endMahIndex = indexOf("mAh_end")
        val avgCurrentIndex = indexOf("AvgCurrent_mA")
        val avgTempIndex = indexOf("AvgTemp_C")
        val avgVoltageIndex = indexOf("AvgVoltage_mV", "AvgVoltageMv", "AvgVoltage")
        val peukertIndex = indexOf("PeukertK", "Peukert_k", "PeukertConstant")
        val adjustedIndex = indexOf("PeukertAdjustedCapacity_mAh")
        val lowThresholdIndex = indexOf("LowPercent")
        val highThresholdIndex = indexOf("HighPercent")
        val excludedIndex = indexOf("Excluded")

        return lines.drop(1).mapNotNull { line ->
            val parts = line.split(",")
            val startTimestamp = parseEventTimestamp(parts.getOrNull(startIndex)?.trim()) ?: return@mapNotNull null
            val endTimestamp = parseEventTimestamp(parts.getOrNull(endIndex)?.trim()) ?: startTimestamp

            val startMah = parts.getOrNull(startMahIndex)?.toDoubleOrNull() ?: return@mapNotNull null
            val endMah = parts.getOrNull(endMahIndex)?.toDoubleOrNull() ?: return@mapNotNull null
            val capacityEstimate = extrapolateCapacityMah(
                deltaMah = abs(endMah - startMah),
                spanPercent = eventSpanPercent(parts, lowThresholdIndex, highThresholdIndex)
            )
            val excluded = parts.getOrNull(excludedIndex)?.toBooleanStrictOrNull() ?: false
            if (excluded && !includeExcluded) return@mapNotNull null

            CapacityEventSummary(
                eventId = capacityEventId(parts, startIndex, directionIndex, startMahIndex, endIndex, endMahIndex),
                startTimestampMs = startTimestamp,
                endTimestampMs = endTimestamp,
                direction = parts.getOrNull(directionIndex)?.ifBlank { null } ?: "event",
                avgCurrentMa = parts.getOrNull(avgCurrentIndex)?.toDoubleOrNull(),
                avgTempC = parts.getOrNull(avgTempIndex)?.toDoubleOrNull(),
                avgVoltageMv = parts.getOrNull(avgVoltageIndex)?.toDoubleOrNull(),
                mahAdded = abs(endMah - startMah).roundToInt(),
                capacityEstimateMah = capacityEstimate,
                peukertK = parts.getOrNull(peukertIndex)?.toDoubleOrNull(),
                peukertAdjustedCapacityMah = parts.getOrNull(adjustedIndex)?.toIntOrNull(),
                isExcluded = excluded
            )
        }.sortedBy { it.startTimestampMs }
    }

    private fun buildDirectionStats(
        events: List<CapacityEventSummary>,
        referenceCapacityMah: Int?,
        referenceCurrentMa: Double?,
        peukertK: Double?
    ): DirectionStats {
        if (events.isEmpty()) {
            return DirectionStats(0, null, null, null, null, 0, 0, 0)
        }

        val currents = events.mapNotNull { it.avgCurrentMa }
        val averageCurrent = currents.takeIf { it.isNotEmpty() }?.average()
        val normalizedCapacities = events.mapNotNull { event ->
            normalizedCapacityAtReferenceCurrent(event.capacityEstimateMah, event.avgCurrentMa, referenceCurrentMa, peukertK)
        }
        val cRates = if (referenceCapacityMah != null && referenceCapacityMah > 0) {
            currents.map { it / referenceCapacityMah }
        } else {
            emptyList()
        }
        return DirectionStats(
            eventCount = events.size,
            averageCurrentMa = averageCurrent,
            averageCRate = cRates.takeIf { it.isNotEmpty() }?.average(),
            averageRawCapacityMah = events.map { it.capacityEstimateMah }.average().roundToInt(),
            averageCapacityAtReferenceCurrentMah = normalizedCapacities.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            lowRateEventCount = cRates.count { it < LOW_C_RATE_BOUND },
            nearReferenceEventCount = cRates.count { it in LOW_C_RATE_BOUND..HIGH_C_RATE_BOUND },
            highRateEventCount = cRates.count { it > HIGH_C_RATE_BOUND }
        )
    }

    fun socBucketSummaries(): List<SocBucketSummary> {
        migrateLegacyTopSocBucketIfNeeded()
        return readSocBuckets().map { bucket ->
            val learnedMah = bucket.sampleCount.takeIf { it > 0 }
                ?.let { bucket.totalMah / it * SOC_BUCKET_PERCENT_SPAN }
            val learnedWh = bucket.sampleCount.takeIf { it > 0 }
                ?.let { bucket.totalWh / it * SOC_BUCKET_PERCENT_SPAN }
            SocBucketSummary(
                bucketStartPct = bucket.bucketStartPct,
                bucketEndPct = bucket.bucketEndPct,
                learnedMah = learnedMah,
                learnedWh = learnedWh,
                sampleCount = bucket.sampleCount,
                avgCurrentMa = bucket.currentSampleCount.takeIf { it > 0 }?.let { bucket.currentSumMa / it },
                avgTempC = bucket.temperatureSampleCount.takeIf { it > 0 }?.let { bucket.temperatureSumC / it }
            )
        }
    }

    fun socLinearityPoints(): List<SocLinearityPoint> {
        migrateLegacyTopSocBucketIfNeeded()
        val learnedBuckets = socBucketLinearityPoints()
        val idealMah = learnedBuckets
            .takeIf { it.isNotEmpty() }
            ?.firstOrNull()
            ?.idealMah

        val displaySamples = readSocBucketSamples()
            .takeIf { it.isNotEmpty() && idealMah != null && idealMah > 0.0 }
            ?.let { samples ->
                filterSocLinearityOutliers(samples).takeLast(MAX_SOC_LINEARITY_DISPLAY_SAMPLES)
            }
            ?.map { sample ->
                SocLinearityPoint(
                    bucketStartPct = sample.bucketStartPct,
                    bucketEndPct = sample.bucketEndPct,
                    midpointPct = (sample.bucketStartPct + sample.bucketEndPct) / 2,
                    deviationFromIdeal = (sample.learnedMah - idealMah!!) / idealMah,
                    learnedMah = sample.learnedMah,
                    idealMah = idealMah,
                    sampleCount = 1
                )
            }
            ?: emptyList()

        return learnedBuckets + displaySamples
    }

    private fun socBucketLinearityPoints(): List<SocLinearityPoint> {
        val learnedBuckets = filteredSocBucketAveragesForLinearity(socBucketSummaries())
        if (learnedBuckets.isEmpty()) return emptyList()

        val totalSamples = learnedBuckets.sumOf { it.sampleCount }
        if (totalSamples <= 0) return emptyList()

        val idealMah = learnedBuckets.sumOf { (it.learnedMah ?: 0.0) * it.sampleCount } / totalSamples
        if (idealMah <= 0.0) return emptyList()

        return learnedBuckets.mapNotNull { bucket ->
            val learnedMah = bucket.learnedMah ?: return@mapNotNull null
            SocLinearityPoint(
                bucketStartPct = bucket.bucketStartPct,
                bucketEndPct = bucket.bucketEndPct,
                midpointPct = (bucket.bucketStartPct + bucket.bucketEndPct) / 2,
                deviationFromIdeal = (learnedMah - idealMah) / idealMah,
                learnedMah = learnedMah,
                idealMah = idealMah,
                sampleCount = bucket.sampleCount,
                isFittedBucketAverage = true
            )
        }
    }

    private fun filterSocLinearityOutliers(samples: List<SocBucketSample>): List<SocBucketSample> {
        if (samples.size < MIN_SOC_OUTLIER_FILTER_SAMPLES) return samples

        val learnedValues = samples.map { it.learnedMah }.filter { it > 0.0 }.sorted()
        if (learnedValues.size < MIN_SOC_OUTLIER_FILTER_SAMPLES) return samples

        val q1 = percentile(learnedValues, 0.25)
        val median = percentile(learnedValues, 0.50)
        val q3 = percentile(learnedValues, 0.75)
        val iqr = q3 - q1
        if (median <= 0.0 || iqr <= 0.0) return samples

        val lowerFence = maxOf(0.0, q1 - SOC_OUTLIER_IQR_MULTIPLIER * iqr, median * SOC_OUTLIER_MIN_MEDIAN_RATIO)
        val upperFence = minOf(q3 + SOC_OUTLIER_IQR_MULTIPLIER * iqr, median * SOC_OUTLIER_MAX_MEDIAN_RATIO)
        val filtered = samples.filter { it.learnedMah in lowerFence..upperFence }

        return if (filtered.size >= maxOf(MIN_SOC_OUTLIER_FILTER_SAMPLES / 2, samples.size / 2)) {
            filtered
        } else {
            samples
        }
    }

    private fun percentile(sortedValues: List<Double>, fraction: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val clampedFraction = fraction.coerceIn(0.0, 1.0)
        val index = clampedFraction * (sortedValues.size - 1)
        val lowerIndex = index.toInt().coerceIn(0, sortedValues.lastIndex)
        val upperIndex = (lowerIndex + 1).coerceAtMost(sortedValues.lastIndex)
        val weight = index - lowerIndex
        return sortedValues[lowerIndex] * (1.0 - weight) + sortedValues[upperIndex] * weight
    }

    private fun processEventSample(
        batteryPercent: Int,
        previousPercent: Int?,
        totalChargeMah: Double,
        direction: Int,
        averageMilliAmps: Double,
        temperatureC: Double?,
        voltageMv: Int?
    ) {
        val currentThresholds = thresholds
        if (isPausedAfterReset()) {
            if (shouldStartEvent(batteryPercent, previousPercent, direction)) {
                clearPausedAfterReset()
            } else if (batteryPercent in (currentThresholds.lowPercent + 1) until currentThresholds.highPercent) {
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
            maybeStartEvent(batteryPercent, previousPercent, totalChargeMah, direction, averageMilliAmps, temperatureC, voltageMv)
            return
        }

        val updatedActiveEvent = addEventSample(activeEvent, averageMilliAmps, temperatureC, voltageMv)

        when (direction) {
            CHARGING -> {
                if (batteryPercent >= currentThresholds.highPercent) {
                    completeEvent(updatedActiveEvent, totalChargeMah)
                    clearActiveEvent()
                }
            }

            DISCHARGING -> {
                if (batteryPercent <= currentThresholds.lowPercent) {
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
        temperatureC: Double?,
        voltageMv: Int?
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
            temperatureSumC = temperatureC ?: 0.0,
            voltageSampleCount = if (voltageMv != null) 1 else 0,
            voltageSumMv = voltageMv?.toDouble() ?: 0.0
        ))
    }

    private fun shouldStartEvent(batteryPercent: Int, previousPercent: Int?, direction: Int): Boolean {
        val currentThresholds = thresholds
        return when (direction) {
            CHARGING -> batteryPercent == currentThresholds.lowPercent ||
                    (previousPercent != null &&
                            previousPercent < currentThresholds.lowPercent &&
                            batteryPercent in (currentThresholds.lowPercent + 1) until currentThresholds.highPercent)
            DISCHARGING -> batteryPercent == currentThresholds.highPercent ||
                    (previousPercent != null &&
                            previousPercent > currentThresholds.highPercent &&
                            batteryPercent in (currentThresholds.lowPercent + 1) until currentThresholds.highPercent)
            else -> false
        }
    }

    private fun isAtCapacityEventArmingLevel(): Boolean {
        val currentThresholds = thresholds
        val batteryPercent = prefs.getInt(LAST_BATTERY_PERCENT_KEY, UNKNOWN_PERCENT)
        return batteryPercent != UNKNOWN_PERCENT &&
                (batteryPercent <= currentThresholds.lowPercent || batteryPercent >= currentThresholds.highPercent)
    }

    private fun completeEvent(activeEvent: ActiveEvent, endChargeMah: Double) {
        val currentThresholds = thresholds
        val capacityMah = extrapolateCapacityMah(
            deltaMah = abs(endChargeMah - activeEvent.startChargeMah),
            spanPercent = currentThresholds.spanPercent
        )
        if (capacityMah <= 0) return

        val now = System.currentTimeMillis()
        val avgCurrentMa = activeEvent.currentMagnitudeSumMa
            .takeIf { activeEvent.currentSampleCount > 0 }
            ?.let { it / activeEvent.currentSampleCount }
        val avgTempC = activeEvent.temperatureSumC
            .takeIf { activeEvent.temperatureSampleCount > 0 }
            ?.let { it / activeEvent.temperatureSampleCount }
        val avgVoltageMv = activeEvent.voltageSumMv
            .takeIf { activeEvent.voltageSampleCount > 0 }
            ?.let { it / activeEvent.voltageSampleCount }
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
            avgVoltageMv = avgVoltageMv,
            peukertK = peukertK,
            peukertAdjustedCapacityMah = peukertAdjustedCapacityMah,
            lowPercent = currentThresholds.lowPercent,
            highPercent = currentThresholds.highPercent
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
        avgVoltageMv: Double?,
        peukertK: Double?,
        peukertAdjustedCapacityMah: Int?,
        lowPercent: Int,
        highPercent: Int
    ) {
        val row = listOf(
            formatEventTimestamp(startTimestampMs),
            directionLabel(direction),
            startChargeMah.roundToInt().toString(),
            formatEventTimestamp(endTimestampMs),
            endChargeMah.roundToInt().toString(),
            avgCurrentMa?.let { String.format(Locale.US, "%.0f", it) } ?: "",
            avgTempC?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            avgVoltageMv?.let { String.format(Locale.US, "%.0f", it) } ?: "",
            peukertK?.let { String.format(Locale.US, "%.4f", it) } ?: "",
            peukertAdjustedCapacityMah?.toString() ?: "",
            lowPercent.toString(),
            highPercent.toString(),
            false.toString()
        ).joinToString(",")

        ensureEventsHeader(CAPACITY_EVENTS_HEADER)
        if (!eventsFile.exists() || eventsFile.length() == 0L) {
            eventsFile.writeText("$CAPACITY_EVENTS_HEADER\n$row")
        } else {
            eventsFile.appendText("\n$row")
        }
    }

    private fun addEventSample(activeEvent: ActiveEvent, averageMilliAmps: Double, temperatureC: Double?, voltageMv: Int?): ActiveEvent {
        val updatedEvent = activeEvent.copy(
            currentSampleCount = activeEvent.currentSampleCount + 1,
            currentMagnitudeSumMa = activeEvent.currentMagnitudeSumMa + abs(averageMilliAmps),
            temperatureSampleCount = activeEvent.temperatureSampleCount + if (temperatureC != null) 1 else 0,
            temperatureSumC = activeEvent.temperatureSumC + (temperatureC ?: 0.0),
            voltageSampleCount = activeEvent.voltageSampleCount + if (voltageMv != null) 1 else 0,
            voltageSumMv = activeEvent.voltageSumMv + (voltageMv?.toDouble() ?: 0.0)
        )
        writeActiveEvent(updatedEvent)
        return updatedEvent
    }

    private fun updateEquivalentCycles(batteryPercent: Int, previousPercent: Int?, direction: Int) {
        if (previousPercent == null || previousPercent !in 0..100 || batteryPercent !in 0..100) return

        val percentDelta = when {
            direction == CHARGING && batteryPercent > previousPercent -> batteryPercent - previousPercent
            direction == DISCHARGING && batteryPercent < previousPercent -> previousPercent - batteryPercent
            else -> 0
        }
        if (percentDelta <= 0) return

        val key = if (direction == CHARGING) CHARGE_EQUIVALENT_CYCLES_KEY else DISCHARGE_EQUIVALENT_CYCLES_KEY
        val updatedCycles = prefs.getFloat(key, 0f).toDouble() + percentDelta / 100.0
        prefs.edit().putFloat(key, updatedCycles.toFloat()).apply()
    }

    private fun ensureEventsHeader(header: String) {
        if (!eventsFile.exists() || eventsFile.length() == 0L) return
        val lines = eventsFile.readLines()
        if (lines.isEmpty() || lines.first() == header) return
        if (lines.first().startsWith("Time_date_start,")) {
            val migratedRows = lines.drop(1).map { line ->
                val voltageMigratedParts = line.split(",").let { parts ->
                    if (parts.size == 9) {
                    // Old format had PeukertK immediately after AvgTemp_C. Insert blank AvgVoltage_mV.
                        parts.take(7) + "" + parts.drop(7)
                    } else {
                        parts
                    }
                }
                val thresholdMigratedParts = if (voltageMigratedParts.size == 10) {
                    // Legacy rows were recorded using the original 25-75% capacity window.
                    voltageMigratedParts + CapacityThresholdPreference.DEFAULT_LOW_PERCENT.toString() +
                            CapacityThresholdPreference.DEFAULT_HIGH_PERCENT.toString()
                } else {
                    voltageMigratedParts
                }
                val excludedMigratedParts = if (thresholdMigratedParts.size == 12) {
                    thresholdMigratedParts + false.toString()
                } else {
                    thresholdMigratedParts
                }
                excludedMigratedParts.joinToString(",")
            }
            eventsFile.writeText((listOf(header) + migratedRows).joinToString("\n"))
        }
    }

    private fun capacityEventId(
        parts: List<String>,
        startIndex: Int,
        directionIndex: Int,
        startMahIndex: Int,
        endIndex: Int,
        endMahIndex: Int
    ): String {
        return listOf(
            parts.getOrNull(startIndex).orEmpty(),
            parts.getOrNull(directionIndex).orEmpty(),
            parts.getOrNull(startMahIndex).orEmpty(),
            parts.getOrNull(endIndex).orEmpty(),
            parts.getOrNull(endMahIndex).orEmpty()
        ).joinToString("|")
    }

    private fun rebuildCapacitySummariesFromEvents() {
        readingsFile.delete()
        movingAverageFile.delete()
        prefs.edit()
            .remove(MOVING_AVERAGE_SAMPLES_KEY)
            .remove(TOTAL_CAPACITY_READING_COUNT_KEY)
            .remove(LATEST_MOVING_AVERAGE_KEY)
            .remove(LATEST_PEUKERT_K_KEY)
            .apply()

        val activeEvents = readCapacityEvents(includeExcluded = false)
        activeEvents.forEach { event ->
            val capacityMah = event.peukertAdjustedCapacityMah ?: event.capacityEstimateMah
            appendDailyReading(event.endTimestampMs, capacityMah)
            appendMovingAverageReading(event.endTimestampMs, capacityMah)
        }
        val latestPeukertK = activeEvents.lastOrNull { it.peukertK != null }?.peukertK
        if (latestPeukertK != null) {
            prefs.edit().putFloat(LATEST_PEUKERT_K_KEY, latestPeukertK.toFloat()).apply()
        }
    }

    private data class SocBucketAccumulator(
        val bucketStartPct: Int,
        val bucketEndPct: Int,
        val totalMah: Double,
        val totalWh: Double,
        val sampleCount: Int,
        val currentSampleCount: Int,
        val currentSumMa: Double,
        val temperatureSampleCount: Int,
        val temperatureSumC: Double
    )

    private fun updateSocBucketTable(
        batteryPercent: Int,
        previousPercent: Int?,
        totalChargeMah: Double,
        direction: Int,
        averageMilliAmps: Double,
        temperatureC: Double?,
        voltageMv: Int?
    ) {
        if (direction != DISCHARGING) {
            clearLastSocBucketPoint()
            clearTopSocBucket()
            return
        }

        val previous = previousPercent ?: run {
            storeLastSocBucketPoint(batteryPercent, totalChargeMah, direction)
            return
        }

        val previousDirection = prefs.getInt(LAST_SOC_BUCKET_DIRECTION_KEY, 0)
        if (previousDirection != direction) {
            clearTopSocBucket()
            storeLastSocBucketPoint(batteryPercent, totalChargeMah, direction)
            return
        }

        val previousChargeMah = prefs.getFloat(LAST_SOC_BUCKET_CHARGE_MAH_KEY, Float.NaN)
            .takeUnless { it.isNaN() }
            ?.toDouble()
        if (previousChargeMah == null) {
            storeLastSocBucketPoint(batteryPercent, totalChargeMah, direction)
            return
        }

        val clampedPrevious = previous.coerceIn(0, 100)
        val clampedCurrent = batteryPercent.coerceIn(0, 100)
        if (clampedCurrent == clampedPrevious) {
            return
        }

        updateTopSocBucket(
            clampedPrevious = clampedPrevious,
            clampedCurrent = clampedCurrent,
            previousChargeMah = previousChargeMah,
            totalChargeMah = totalChargeMah,
            averageMilliAmps = averageMilliAmps,
            temperatureC = temperatureC,
            voltageMv = voltageMv
        )

        val percentDelta = abs(clampedCurrent - clampedPrevious)
        val chargeDeltaMah = abs(totalChargeMah - previousChargeMah)

        if (percentDelta > 0 && chargeDeltaMah > 0.0) {
            val mahPerPercent = chargeDeltaMah / percentDelta
            val whPerPercent = mahPerPercent * ((voltageMv ?: 0).coerceAtLeast(0).toDouble() / 1000.0) / 1000.0
            val low = minOf(clampedPrevious, clampedCurrent)
            val high = maxOf(clampedPrevious, clampedCurrent)
            val buckets = readSocBuckets().associateBy { it.bucketStartPct }.toMutableMap()

            for (pct in low until high) {
                val bucketStart = ((pct / SOC_BUCKET_PERCENT_SPAN) * SOC_BUCKET_PERCENT_SPAN).coerceIn(0, 90)
                if (bucketStart >= TOP_SOC_BUCKET_START_PERCENT) continue
                val existing = buckets[bucketStart] ?: SocBucketAccumulator(
                    bucketStartPct = bucketStart,
                    bucketEndPct = bucketStart + SOC_BUCKET_PERCENT_SPAN,
                    totalMah = 0.0,
                    totalWh = 0.0,
                    sampleCount = 0,
                    currentSampleCount = 0,
                    currentSumMa = 0.0,
                    temperatureSampleCount = 0,
                    temperatureSumC = 0.0
                )
                buckets[bucketStart] = existing.copy(
                    totalMah = existing.totalMah + mahPerPercent,
                    totalWh = existing.totalWh + whPerPercent,
                    sampleCount = existing.sampleCount + 1,
                    currentSampleCount = existing.currentSampleCount + 1,
                    currentSumMa = existing.currentSumMa + abs(averageMilliAmps),
                    temperatureSampleCount = existing.temperatureSampleCount + if (temperatureC != null) 1 else 0,
                    temperatureSumC = existing.temperatureSumC + (temperatureC ?: 0.0)
                )
                appendSocBucketSample(
                    SocBucketSample(
                        timestampMs = System.currentTimeMillis(),
                        bucketStartPct = bucketStart,
                        bucketEndPct = bucketStart + SOC_BUCKET_PERCENT_SPAN,
                        learnedMah = mahPerPercent * SOC_BUCKET_PERCENT_SPAN,
                        currentMa = abs(averageMilliAmps),
                        temperatureC = temperatureC
                    )
                )
            }

            writeSocBuckets(buckets.values.sortedBy { it.bucketStartPct })
        }

        storeLastSocBucketPoint(batteryPercent, totalChargeMah, direction)
    }

    private fun updateTopSocBucket(
        clampedPrevious: Int,
        clampedCurrent: Int,
        previousChargeMah: Double,
        totalChargeMah: Double,
        averageMilliAmps: Double,
        temperatureC: Double?,
        voltageMv: Int?
    ) {
        if (clampedPrevious == 100 && clampedCurrent < 100 && !hasActiveTopSocBucket()) {
            startTopSocBucket(previousChargeMah)
        }

        if (!hasActiveTopSocBucket()) return

        if (clampedCurrent > TOP_SOC_BUCKET_START_PERCENT) {
            addTopSocBucketSample(averageMilliAmps, temperatureC)
            return
        }

        val topBucketEndChargeMah = if (clampedPrevious > TOP_SOC_BUCKET_START_PERCENT && clampedCurrent < TOP_SOC_BUCKET_START_PERCENT) {
            val fractionToThreshold = (clampedPrevious - TOP_SOC_BUCKET_START_PERCENT).toDouble() /
                    (clampedPrevious - clampedCurrent).toDouble()
            previousChargeMah + (totalChargeMah - previousChargeMah) * fractionToThreshold
        } else {
            totalChargeMah
        }
        completeTopSocBucket(topBucketEndChargeMah, averageMilliAmps, temperatureC, voltageMv)
    }

    private fun hasActiveTopSocBucket(): Boolean {
        return !prefs.getFloat(TOP_SOC_BUCKET_START_CHARGE_KEY, Float.NaN).isNaN()
    }

    private fun startTopSocBucket(startChargeMah: Double) {
        prefs.edit()
            .putFloat(TOP_SOC_BUCKET_START_CHARGE_KEY, startChargeMah.toFloat())
            .putInt(TOP_SOC_BUCKET_CURRENT_SAMPLE_COUNT_KEY, 0)
            .putFloat(TOP_SOC_BUCKET_CURRENT_SUM_KEY, 0f)
            .putInt(TOP_SOC_BUCKET_TEMPERATURE_SAMPLE_COUNT_KEY, 0)
            .putFloat(TOP_SOC_BUCKET_TEMPERATURE_SUM_KEY, 0f)
            .apply()
    }

    private fun addTopSocBucketSample(averageMilliAmps: Double, temperatureC: Double?) {
        val currentSampleCount = prefs.getInt(TOP_SOC_BUCKET_CURRENT_SAMPLE_COUNT_KEY, 0)
        val currentSum = prefs.getFloat(TOP_SOC_BUCKET_CURRENT_SUM_KEY, 0f).toDouble()
        val temperatureSampleCount = prefs.getInt(TOP_SOC_BUCKET_TEMPERATURE_SAMPLE_COUNT_KEY, 0)
        val temperatureSum = prefs.getFloat(TOP_SOC_BUCKET_TEMPERATURE_SUM_KEY, 0f).toDouble()

        prefs.edit()
            .putInt(TOP_SOC_BUCKET_CURRENT_SAMPLE_COUNT_KEY, currentSampleCount + 1)
            .putFloat(TOP_SOC_BUCKET_CURRENT_SUM_KEY, (currentSum + abs(averageMilliAmps)).toFloat())
            .putInt(TOP_SOC_BUCKET_TEMPERATURE_SAMPLE_COUNT_KEY, temperatureSampleCount + if (temperatureC != null) 1 else 0)
            .putFloat(TOP_SOC_BUCKET_TEMPERATURE_SUM_KEY, (temperatureSum + (temperatureC ?: 0.0)).toFloat())
            .apply()
    }

    private fun completeTopSocBucket(
        totalChargeMah: Double,
        averageMilliAmps: Double,
        temperatureC: Double?,
        voltageMv: Int?
    ) {
        val startChargeMah = prefs.getFloat(TOP_SOC_BUCKET_START_CHARGE_KEY, Float.NaN)
            .takeUnless { it.isNaN() }
            ?.toDouble() ?: return
        addTopSocBucketSample(averageMilliAmps, temperatureC)

        val learnedMah = abs(totalChargeMah - startChargeMah)
        if (learnedMah <= 0.0) {
            clearTopSocBucket()
            return
        }

        val currentSampleCount = prefs.getInt(TOP_SOC_BUCKET_CURRENT_SAMPLE_COUNT_KEY, 0)
        val currentSum = prefs.getFloat(TOP_SOC_BUCKET_CURRENT_SUM_KEY, 0f).toDouble()
        val temperatureSampleCount = prefs.getInt(TOP_SOC_BUCKET_TEMPERATURE_SAMPLE_COUNT_KEY, 0)
        val temperatureSum = prefs.getFloat(TOP_SOC_BUCKET_TEMPERATURE_SUM_KEY, 0f).toDouble()
        val learnedWh = learnedMah * ((voltageMv ?: 0).coerceAtLeast(0).toDouble() / 1000.0) / 1000.0
        val buckets = readSocBuckets().associateBy { it.bucketStartPct }.toMutableMap()
        val existing = buckets[TOP_SOC_BUCKET_START_PERCENT] ?: SocBucketAccumulator(
            bucketStartPct = TOP_SOC_BUCKET_START_PERCENT,
            bucketEndPct = 100,
            totalMah = 0.0,
            totalWh = 0.0,
            sampleCount = 0,
            currentSampleCount = 0,
            currentSumMa = 0.0,
            temperatureSampleCount = 0,
            temperatureSumC = 0.0
        )

        buckets[TOP_SOC_BUCKET_START_PERCENT] = existing.copy(
            totalMah = existing.totalMah + learnedMah / SOC_BUCKET_PERCENT_SPAN,
            totalWh = existing.totalWh + learnedWh / SOC_BUCKET_PERCENT_SPAN,
            sampleCount = existing.sampleCount + 1,
            currentSampleCount = existing.currentSampleCount + currentSampleCount,
            currentSumMa = existing.currentSumMa + currentSum,
            temperatureSampleCount = existing.temperatureSampleCount + temperatureSampleCount,
            temperatureSumC = existing.temperatureSumC + temperatureSum
        )
        writeSocBuckets(buckets.values.sortedBy { it.bucketStartPct })
        appendSocBucketSample(
            SocBucketSample(
                timestampMs = System.currentTimeMillis(),
                bucketStartPct = TOP_SOC_BUCKET_START_PERCENT,
                bucketEndPct = 100,
                learnedMah = learnedMah,
                currentMa = currentSampleCount.takeIf { it > 0 }?.let { currentSum / it } ?: 0.0,
                temperatureC = temperatureSampleCount.takeIf { it > 0 }?.let { temperatureSum / it }
            )
        )
        clearTopSocBucket()
    }

    private fun storeLastSocBucketPoint(batteryPercent: Int, totalChargeMah: Double, direction: Int) {
        prefs.edit()
            .putInt(LAST_SOC_BUCKET_PERCENT_KEY, batteryPercent.coerceIn(0, 100))
            .putFloat(LAST_SOC_BUCKET_CHARGE_MAH_KEY, totalChargeMah.toFloat())
            .putInt(LAST_SOC_BUCKET_DIRECTION_KEY, direction)
            .apply()
    }

    private fun clearLastSocBucketPoint() {
        prefs.edit()
            .remove(LAST_SOC_BUCKET_PERCENT_KEY)
            .remove(LAST_SOC_BUCKET_CHARGE_MAH_KEY)
            .remove(LAST_SOC_BUCKET_DIRECTION_KEY)
            .apply()
    }

    private fun clearTopSocBucket() {
        prefs.edit()
            .remove(TOP_SOC_BUCKET_START_CHARGE_KEY)
            .remove(TOP_SOC_BUCKET_CURRENT_SAMPLE_COUNT_KEY)
            .remove(TOP_SOC_BUCKET_CURRENT_SUM_KEY)
            .remove(TOP_SOC_BUCKET_TEMPERATURE_SAMPLE_COUNT_KEY)
            .remove(TOP_SOC_BUCKET_TEMPERATURE_SUM_KEY)
            .apply()
    }

    private fun readSocBuckets(): List<SocBucketAccumulator> {
        migrateLegacyTopSocBucketIfNeeded()
        if (!socBucketsFile.exists()) return emptyList()
        return socBucketsFile.readLines().mapNotNull { line ->
            if (line.startsWith("BucketStartPct,")) return@mapNotNull null
            val parts = line.split(",")
            if (parts.size < 9) return@mapNotNull null
            SocBucketAccumulator(
                bucketStartPct = parts[0].toIntOrNull() ?: return@mapNotNull null,
                bucketEndPct = parts[1].toIntOrNull() ?: return@mapNotNull null,
                totalMah = parts[2].toDoubleOrNull() ?: return@mapNotNull null,
                totalWh = parts[3].toDoubleOrNull() ?: 0.0,
                sampleCount = parts[4].toIntOrNull() ?: 0,
                currentSampleCount = parts[5].toIntOrNull() ?: 0,
                currentSumMa = parts[6].toDoubleOrNull() ?: 0.0,
                temperatureSampleCount = parts[7].toIntOrNull() ?: 0,
                temperatureSumC = parts[8].toDoubleOrNull() ?: 0.0
            )
        }.sortedBy { it.bucketStartPct }
    }

    private fun writeSocBuckets(buckets: List<SocBucketAccumulator>) {
        val header = "BucketStartPct,BucketEndPct,TotalMah,TotalWh,SampleCount,CurrentSampleCount,CurrentSum_mA,TempSampleCount,TempSum_C"
        val rows = buckets.map { bucket ->
            listOf(
                bucket.bucketStartPct.toString(),
                bucket.bucketEndPct.toString(),
                String.format(Locale.US, "%.3f", bucket.totalMah),
                String.format(Locale.US, "%.5f", bucket.totalWh),
                bucket.sampleCount.toString(),
                bucket.currentSampleCount.toString(),
                String.format(Locale.US, "%.1f", bucket.currentSumMa),
                bucket.temperatureSampleCount.toString(),
                String.format(Locale.US, "%.1f", bucket.temperatureSumC)
            ).joinToString(",")
        }
        socBucketsFile.writeText((listOf(header) + rows).joinToString("\n"))
    }

    private fun migrateLegacyTopSocBucketIfNeeded() {
        if (prefs.getBoolean(TOP_SOC_BUCKET_STRICT_MIGRATED_KEY, false)) return

        if (socBucketsFile.exists()) {
            val filteredRows = socBucketsFile.readLines().filter { line ->
                line.startsWith("BucketStartPct,") ||
                        line.split(",").firstOrNull()?.toIntOrNull() != TOP_SOC_BUCKET_START_PERCENT
            }
            socBucketsFile.writeText(filteredRows.joinToString("\n"))
        }

        if (socBucketSamplesFile.exists()) {
            val filteredRows = socBucketSamplesFile.readLines().filter { line ->
                line.startsWith("TimestampMs,") ||
                        line.split(",").getOrNull(1)?.toIntOrNull() != TOP_SOC_BUCKET_START_PERCENT
            }
            socBucketSamplesFile.writeText(filteredRows.joinToString("\n"))
        }

        clearTopSocBucket()
        prefs.edit().putBoolean(TOP_SOC_BUCKET_STRICT_MIGRATED_KEY, true).apply()
    }

    private fun readSocBucketSamples(): List<SocBucketSample> {
        if (!socBucketSamplesFile.exists()) return emptyList()
        return socBucketSamplesFile.readLines().mapNotNull { line ->
            if (line.startsWith("TimestampMs,")) return@mapNotNull null
            val parts = line.split(",")
            if (parts.size < 6) return@mapNotNull null
            SocBucketSample(
                timestampMs = parts[0].toLongOrNull() ?: return@mapNotNull null,
                bucketStartPct = parts[1].toIntOrNull() ?: return@mapNotNull null,
                bucketEndPct = parts[2].toIntOrNull() ?: return@mapNotNull null,
                learnedMah = parts[3].toDoubleOrNull() ?: return@mapNotNull null,
                currentMa = parts[4].toDoubleOrNull() ?: 0.0,
                temperatureC = parts[5].toDoubleOrNull()
            )
        }.sortedBy { it.timestampMs }
    }

    private fun appendSocBucketSample(sample: SocBucketSample) {
        val header = "TimestampMs,BucketStartPct,BucketEndPct,LearnedMah,Current_mA,Temp_C"
        val existingRows = if (socBucketSamplesFile.exists()) {
            socBucketSamplesFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("TimestampMs,") }
        } else {
            emptyList()
        }
        val newRow = listOf(
            sample.timestampMs.toString(),
            sample.bucketStartPct.toString(),
            sample.bucketEndPct.toString(),
            String.format(Locale.US, "%.3f", sample.learnedMah),
            String.format(Locale.US, "%.1f", sample.currentMa),
            sample.temperatureC?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        ).joinToString(",")
        val rows = balancedRecentSocSampleRows(existingRows + newRow)
        socBucketSamplesFile.writeText((listOf(header) + rows).joinToString("\n"))
    }

    private fun balancedRecentSocSampleRows(rows: List<String>): List<String> {
        val byBucket = rows
            .mapNotNull { row ->
                val bucketStart = row.split(",", limit = 4).getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                bucketStart to row
            }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()

        return byBucket.values
            .flatMap { bucketRows -> bucketRows.takeLast(MAX_SOC_BUCKET_SAMPLES_PER_BUCKET) }
            .takeLast(MAX_SOC_BUCKET_SAMPLES)
    }

    private data class PeukertEventReading(
        val capacityMah: Int,
        val avgCurrentMa: Double
    )

    private fun estimatePeukertK(capacityMah: Int, avgCurrentMa: Double?): Double? {
        if (capacityMah <= 0 || avgCurrentMa == null || avgCurrentMa < MINIMUM_PEUKERT_CURRENT_MA) {
            return latestStoredPeukertK()
        }

        val currentReading = PeukertEventReading(capacityMah, avgCurrentMa)
        val readings = (readPeukertEventReadings() + currentReading)
            .filter { it.capacityMah > 0 && it.avgCurrentMa >= MINIMUM_PEUKERT_CURRENT_MA }
            .takeLast(PEUKERT_K_FIT_EVENT_COUNT)

        val fittedK = fitPeukertK(readings)
        val previousK = latestStoredPeukertK()
        val newK = when {
            fittedK == null -> previousK
            previousK == null -> fittedK
            else -> (previousK * (1.0 - PEUKERT_K_LEARNING_RATE) + fittedK * PEUKERT_K_LEARNING_RATE)
                .coerceIn(PEUKERT_K_MIN, PEUKERT_K_MAX)
        }

        if (newK != null) {
            prefs.edit().putFloat(LATEST_PEUKERT_K_KEY, newK.toFloat()).apply()
        }
        return newK
    }

    private fun fitPeukertK(readings: List<PeukertEventReading>): Double? {
        if (readings.size < 2) return null

        val currentRatio = readings.maxOf { it.avgCurrentMa } / readings.minOf { it.avgCurrentMa }
        if (currentRatio < MINIMUM_PEUKERT_CURRENT_SPREAD_RATIO) return null

        val xValues = readings.map { ln(it.avgCurrentMa) }
        val yValues = readings.map { ln(it.capacityMah.toDouble()) }
        val xMean = xValues.average()
        val yMean = yValues.average()

        var numerator = 0.0
        var denominator = 0.0
        for (index in readings.indices) {
            val dx = xValues[index] - xMean
            val dy = yValues[index] - yMean
            numerator += dx * dy
            denominator += dx * dx
        }
        if (denominator <= 0.0) return null

        val slope = numerator / denominator
        val rawK = 1.0 - slope
        return rawK.takeIf { it.isFinite() }?.coerceIn(PEUKERT_K_MIN, PEUKERT_K_MAX)
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

    private fun normalizedCapacityAtReferenceCurrent(
        capacityMah: Int,
        avgCurrentMa: Double?,
        referenceCurrentMa: Double?,
        peukertK: Double?
    ): Int? {
        if (capacityMah <= 0 || avgCurrentMa == null || referenceCurrentMa == null) return null
        if (avgCurrentMa < MINIMUM_PEUKERT_CURRENT_MA || referenceCurrentMa <= 0.0) return null
        val k = peukertK ?: 1.0
        return (capacityMah * (avgCurrentMa / referenceCurrentMa).pow(k - 1.0))
            .roundToInt()
            .takeIf { it > 0 }
    }

    private fun readPeukertEventReadings(): List<PeukertEventReading> {
        if (!eventsFile.exists()) return emptyList()
        ensureEventsHeader(CAPACITY_EVENTS_HEADER)
        val headers = eventsFile.readLines().firstOrNull()?.split(",")?.map { it.trim() } ?: return emptyList()
        val excludedIndex = headers.indexOfFirst { it.equals("Excluded", ignoreCase = true) }
        return eventsFile.readLines().drop(1).mapNotNull { line ->
            val parts = line.split(",")
            if (parts.size < 8) return@mapNotNull null
            if (parts.getOrNull(excludedIndex)?.toBooleanStrictOrNull() == true) return@mapNotNull null
            val startCharge = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            val endCharge = parts[4].toDoubleOrNull() ?: return@mapNotNull null
            val avgCurrent = parts[5].toDoubleOrNull() ?: return@mapNotNull null
            val capacity = extrapolateCapacityMah(
                deltaMah = abs(endCharge - startCharge),
                spanPercent = eventSpanPercent(parts, lowThresholdIndex = 10, highThresholdIndex = 11)
            )
            PeukertEventReading(capacity, avgCurrent)
        }
    }

    private fun extrapolateCapacityMah(deltaMah: Double, spanPercent: Int = thresholds.spanPercent): Int {
        return (deltaMah * 100.0 / spanPercent.coerceAtLeast(1)).roundToInt()
    }

    private fun eventSpanPercent(parts: List<String>, lowThresholdIndex: Int, highThresholdIndex: Int): Int {
        val low = parts.getOrNull(lowThresholdIndex)?.toIntOrNull()
            ?: CapacityThresholdPreference.DEFAULT_LOW_PERCENT
        val high = parts.getOrNull(highThresholdIndex)?.toIntOrNull()
            ?: CapacityThresholdPreference.DEFAULT_HIGH_PERCENT
        return (high - low).takeIf { it > 0 }
            ?: (CapacityThresholdPreference.DEFAULT_HIGH_PERCENT - CapacityThresholdPreference.DEFAULT_LOW_PERCENT)
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
            temperatureSumC = prefs.getFloat(ACTIVE_EVENT_TEMPERATURE_SUM_KEY, 0f).toDouble(),
            voltageSampleCount = prefs.getInt(ACTIVE_EVENT_VOLTAGE_SAMPLE_COUNT_KEY, 0),
            voltageSumMv = prefs.getFloat(ACTIVE_EVENT_VOLTAGE_SUM_KEY, 0f).toDouble()
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
            .putInt(ACTIVE_EVENT_VOLTAGE_SAMPLE_COUNT_KEY, event.voltageSampleCount)
            .putFloat(ACTIVE_EVENT_VOLTAGE_SUM_KEY, event.voltageSumMv.toFloat())
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
            .remove(ACTIVE_EVENT_VOLTAGE_SAMPLE_COUNT_KEY)
            .remove(ACTIVE_EVENT_VOLTAGE_SUM_KEY)
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

    private fun recentIncludedEventWeightedEstimate(limitDays: Int = 10): Int? {
        val events = readCapacityEvents(includeExcluded = false)
        return includedEventWeightedEstimate(events, limitDays)
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

    private fun parseEventTimestamp(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy_MM_dd_HHmm", Locale.US).parse(text)?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun directionLabel(direction: Int): String {
        return if (direction == CHARGING) "charge" else "discharge"
    }

    internal companion object {
        fun includedEventWeightedEstimate(
            events: List<CapacityEventSummary>,
            limitDays: Int = 10
        ): Int? {
            val includedEvents = events.filterNot { it.isExcluded }
            if (includedEvents.isEmpty()) return null

            val recentDays = includedEvents
                .map { dayStartMsForEstimate(it.endTimestampMs) }
                .distinct()
                .takeLast(limitDays.coerceAtLeast(1))
                .toSet()
            val recentEvents = includedEvents.filter { dayStartMsForEstimate(it.endTimestampMs) in recentDays }
            if (recentEvents.isEmpty()) return null

            return recentEvents
                .map { it.peukertAdjustedCapacityMah ?: it.capacityEstimateMah }
                .average()
                .roundToInt()
        }

        fun filteredSocBucketAveragesForLinearity(
            buckets: List<SocBucketSummary>
        ): List<SocBucketSummary> {
            val sampleQualified = buckets
                .filter { bucket ->
                    val learnedMah = bucket.learnedMah
                    learnedMah != null &&
                            learnedMah > 0.0 &&
                            bucket.sampleCount >= minimumSamplesForSocBucketAverage(bucket)
                }
                .sortedBy { it.bucketStartPct }
            if (sampleQualified.size < MIN_SOC_BUCKET_AVERAGE_OUTLIER_BUCKETS) return sampleQualified

            val learnedValues = sampleQualified.mapNotNull { it.learnedMah }.sorted()
            val q1 = percentileForSorted(learnedValues, 0.25)
            val median = percentileForSorted(learnedValues, 0.50)
            val q3 = percentileForSorted(learnedValues, 0.75)
            val iqr = q3 - q1
            if (median <= 0.0 || iqr <= 0.0) return sampleQualified

            val lowerFence = maxOf(
                0.0,
                q1 - SOC_BUCKET_AVERAGE_OUTLIER_IQR_MULTIPLIER * iqr,
                median * SOC_BUCKET_AVERAGE_MIN_MEDIAN_RATIO
            )
            val upperFence = minOf(
                q3 + SOC_BUCKET_AVERAGE_OUTLIER_IQR_MULTIPLIER * iqr,
                median * SOC_BUCKET_AVERAGE_MAX_MEDIAN_RATIO
            )
            val filtered = sampleQualified.filter { bucket ->
                val learnedMah = bucket.learnedMah ?: return@filter false
                learnedMah in lowerFence..upperFence
            }

            return if (filtered.size >= MIN_SOC_BUCKET_AVERAGE_OUTLIER_BUCKETS - 1) {
                filtered
            } else {
                sampleQualified
            }
        }

        private fun minimumSamplesForSocBucketAverage(bucket: SocBucketSummary): Int {
            return if (bucket.bucketStartPct >= TOP_SOC_BUCKET_START_PERCENT) {
                MIN_TOP_SOC_BUCKET_AVERAGE_SAMPLES
            } else {
                MIN_SOC_BUCKET_AVERAGE_SAMPLES
            }
        }

        private fun percentileForSorted(sortedValues: List<Double>, fraction: Double): Double {
            if (sortedValues.isEmpty()) return 0.0
            val clampedFraction = fraction.coerceIn(0.0, 1.0)
            val index = clampedFraction * (sortedValues.size - 1)
            val lowerIndex = index.toInt().coerceIn(0, sortedValues.lastIndex)
            val upperIndex = (lowerIndex + 1).coerceAtMost(sortedValues.lastIndex)
            val weight = index - lowerIndex
            return sortedValues[lowerIndex] * (1.0 - weight) + sortedValues[upperIndex] * weight
        }

        private fun dayStartMsForEstimate(timestampMs: Long): Long {
            return Calendar.getInstance().apply {
                timeInMillis = timestampMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        private const val MINIMUM_CURRENT_MA = 20.0
        private const val CHARGING = 1
        private const val DISCHARGING = -1
        private const val MAX_STORED_DAYS = 400
        private const val WARNING_DROP_FRACTION = 0.01
        private const val MOVING_AVERAGE_READING_COUNT = 50
        private const val ACTIVE_EVENT_DIRECTION_KEY = "active_event_direction"
        private const val ACTIVE_EVENT_START_TIMESTAMP_KEY = "active_event_start_timestamp_ms"
        private const val ACTIVE_EVENT_START_CHARGE_KEY = "active_event_start_charge_mah"
        private const val ACTIVE_EVENT_CURRENT_SAMPLE_COUNT_KEY = "active_event_current_sample_count"
        private const val ACTIVE_EVENT_CURRENT_SUM_KEY = "active_event_current_sum_ma"
        private const val ACTIVE_EVENT_TEMPERATURE_SAMPLE_COUNT_KEY = "active_event_temperature_sample_count"
        private const val ACTIVE_EVENT_TEMPERATURE_SUM_KEY = "active_event_temperature_sum_c"
        private const val ACTIVE_EVENT_VOLTAGE_SAMPLE_COUNT_KEY = "active_event_voltage_sample_count"
        private const val ACTIVE_EVENT_VOLTAGE_SUM_KEY = "active_event_voltage_sum_mv"
        private const val CAPACITY_EVENTS_HEADER = "Time_date_start,Direction,mAh_start,Time_date_end,mAh_end,AvgCurrent_mA,AvgTemp_C,AvgVoltage_mV,PeukertK,PeukertAdjustedCapacity_mAh,LowPercent,HighPercent,Excluded"
        private const val EVENT_PAUSED_AFTER_RESET_KEY = "event_paused_after_reset"
        private const val LAST_BATTERY_PERCENT_KEY = "last_battery_percent"
        private const val CHARGE_EQUIVALENT_CYCLES_KEY = "charge_equivalent_cycles"
        private const val DISCHARGE_EQUIVALENT_CYCLES_KEY = "discharge_equivalent_cycles"
        private const val UNKNOWN_PERCENT = -1
        private const val WARNING_REFERENCE_TIMESTAMP_KEY = "warning_reference_timestamp_ms"
        private const val WARNING_REFERENCE_CAPACITY_KEY = "warning_reference_capacity_mah"
        private const val ACKNOWLEDGED_WARNING_READING_COUNT_KEY = "acknowledged_warning_reading_count"
        private const val MOVING_AVERAGE_SAMPLES_KEY = "moving_average_capacity_samples"
        private const val TOTAL_CAPACITY_READING_COUNT_KEY = "total_capacity_reading_count"
        private const val LATEST_MOVING_AVERAGE_KEY = "latest_moving_average_mah"
        private const val LATEST_PEUKERT_K_KEY = "latest_peukert_k"
        private const val LAST_SOC_BUCKET_PERCENT_KEY = "last_soc_bucket_percent"
        private const val LAST_SOC_BUCKET_CHARGE_MAH_KEY = "last_soc_bucket_charge_mah"
        private const val LAST_SOC_BUCKET_DIRECTION_KEY = "last_soc_bucket_direction"
        private const val TOP_SOC_BUCKET_START_CHARGE_KEY = "top_soc_bucket_start_charge_mah"
        private const val TOP_SOC_BUCKET_CURRENT_SAMPLE_COUNT_KEY = "top_soc_bucket_current_sample_count"
        private const val TOP_SOC_BUCKET_CURRENT_SUM_KEY = "top_soc_bucket_current_sum_ma"
        private const val TOP_SOC_BUCKET_TEMPERATURE_SAMPLE_COUNT_KEY = "top_soc_bucket_temperature_sample_count"
        private const val TOP_SOC_BUCKET_TEMPERATURE_SUM_KEY = "top_soc_bucket_temperature_sum_c"
        private const val TOP_SOC_BUCKET_STRICT_MIGRATED_KEY = "top_soc_bucket_strict_migrated"
        private const val SOC_BUCKET_PERCENT_SPAN = 10
        private const val TOP_SOC_BUCKET_START_PERCENT = 90
        private const val MAX_SOC_BUCKET_SAMPLES = 2000
        private const val MAX_SOC_BUCKET_SAMPLES_PER_BUCKET = 200
        private const val MAX_SOC_LINEARITY_DISPLAY_SAMPLES = 100
        private const val MIN_SOC_BUCKET_AVERAGE_SAMPLES = 3
        private const val MIN_TOP_SOC_BUCKET_AVERAGE_SAMPLES = 5
        private const val MIN_SOC_BUCKET_AVERAGE_OUTLIER_BUCKETS = 5
        private const val SOC_BUCKET_AVERAGE_OUTLIER_IQR_MULTIPLIER = 1.5
        private const val SOC_BUCKET_AVERAGE_MIN_MEDIAN_RATIO = 0.70
        private const val SOC_BUCKET_AVERAGE_MAX_MEDIAN_RATIO = 1.30
        private const val MIN_SOC_OUTLIER_FILTER_SAMPLES = 8
        private const val SOC_OUTLIER_IQR_MULTIPLIER = 1.5
        private const val SOC_OUTLIER_MIN_MEDIAN_RATIO = 0.25
        private const val SOC_OUTLIER_MAX_MEDIAN_RATIO = 2.0
        private const val PEUKERT_REFERENCE_CURRENT_MA = 1000.0
        private const val MINIMUM_PEUKERT_CURRENT_MA = 50.0
        private const val MINIMUM_PEUKERT_CURRENT_SPREAD_RATIO = 1.33
        private const val PEUKERT_K_FIT_EVENT_COUNT = 10
        private const val PEUKERT_K_LEARNING_RATE = 0.35
        private const val PEUKERT_K_MIN = 1.0
        private const val PEUKERT_K_MAX = 1.30
        private const val REFERENCE_C_RATE = 0.2
        private const val LOW_C_RATE_BOUND = 0.15
        private const val HIGH_C_RATE_BOUND = 0.25
    }
}
