package com.analogwings.batterycurrent

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

object BatteryCapacitySummary {
    data class Distribution(
        val count: Int,
        val meanMah: Double?,
        val standardDeviationMah: Double?
    )

    data class Summary(
        val current: Distribution,
        val quick: Distribution,
        val calibration: Distribution,
        val averageDischargeCurrentMa: Double?,
        val averageTemperatureC: Double?,
        val usesDailyFallback: Boolean = false,
        val referenceCapacityMah: Int? = null
    ) {
        fun displayText(): String {
            val estimate = current.meanMah?.let { String.format(Locale.US, "%.0f mAh", it) }
                ?: "Collecting data"
            val changeSinceNew = referenceCapacityMah?.takeIf { it > 0 }?.let { originalMah ->
                current.meanMah?.takeIf { it.isFinite() && it > 0.0 }?.let { currentMah ->
                    String.format(Locale.US, " (%+.1f%%)", (currentMah - originalMah) * 100.0 / originalMah)
                }
            }.orEmpty()
            val spread = current.standardDeviationMah?.let { String.format(Locale.US, "%.0f mAh", it) }
                ?: "n/a (need 2 events)"
            val referenceCapacity = referenceCapacityMah?.takeIf { it > 0 }?.toDouble()
                ?: current.meanMah?.takeIf { it.isFinite() && it > 0.0 }
            val currentText = averageDischargeCurrentMa?.let { currentMa ->
                val cRateText = referenceCapacity?.let { String.format(Locale.US, " (%.2fC)", currentMa / it) }.orEmpty()
                String.format(Locale.US, "%.0fmA", currentMa) + cRateText
            } ?: "n/a"
            val temperatureText = averageTemperatureC?.let { String.format(Locale.US, "%.1f\u00B0C", it) } ?: "n/a"
            val sourceText = if (usesDailyFallback) {
                "Historical daily estimates; event uncertainty unavailable"
            } else {
                "${quick.count} quick + ${calibration.count} full calibration events"
            }
            return "Current battery capacity: $estimate$changeSinceNew\n" +
                "Std deviation (1\u03C3): $spread\n" +
                "Avg Discharge: $currentText   Avg temp: $temperatureText\n" + sourceText
        }
    }

    fun load(context: Context, estimator: BatteryCapacityEstimator = BatteryCapacityEstimator(context)): Summary {
        val quickEvents = estimator.calibrationEvents()
        val calibrationResults = FullDischargeTest.allResults(context)
        val adjustedCapacities = calibrationResults.map { result ->
            usageBasedCalibrationCapacity(result, estimator)
        }
        val summary = calculate(quickEvents, calibrationResults, adjustedCapacities).copy(
            referenceCapacityMah = BatteryCapacityReference.originalCapacityMah(context)
        )
        if (summary.current.count > 0) return summary
        val history = estimator.capacityEstimateHistory()
        val sampleCount = history.sumOf { it.sampleCount.toLong() }
        if (sampleCount <= 0L) return summary
        val mean = history.sumOf { it.averageCapacityMah.toDouble() * it.sampleCount } / sampleCount
        return summary.copy(
            current = Distribution(0, mean, null),
            usesDailyFallback = true
        )
    }

    internal fun calculate(
        quickEvents: List<BatteryCapacityEstimator.CapacityEventSummary>,
        calibrationResults: List<FullDischargeTest.Result>,
        adjustedCalibrationCapacities: List<Int> = calibrationResults.map { it.capacityEstimateMah }
    ): Summary {
        require(adjustedCalibrationCapacities.size == calibrationResults.size)
        val includedQuickEvents = quickEvents.filter { !it.isExcluded && it.capacityEstimateMah > 0 }
        val validCalibrations = calibrationResults.zip(adjustedCalibrationCapacities)
            .filter { (result, capacity) -> result.capacityEstimateMah > 0 && capacity > 0 }
        val quickValues = includedQuickEvents.map { it.capacityEstimateMah.toDouble() }
        val calibrationValues = validCalibrations.map { it.first.capacityEstimateMah.toDouble() }
        val currentValues = quickValues + validCalibrations.map { it.second.toDouble() }
        val dischargeEvents = includedQuickEvents.filter { it.direction.equals("discharge", ignoreCase = true) }
        val currents = (dischargeEvents.mapNotNull { it.avgCurrentMa } +
            validCalibrations.mapNotNull { it.first.avgCurrentMa })
            .filter { it.isFinite() && abs(it) > 0.0 }
            .map { abs(it) }
        val temperatures = (dischargeEvents.mapNotNull { it.avgTempC } +
            validCalibrations.mapNotNull { it.first.avgTempC }).filter { it.isFinite() }
        return Summary(
            current = distribution(currentValues),
            quick = distribution(quickValues),
            calibration = distribution(calibrationValues),
            averageDischargeCurrentMa = currents.takeIf { it.isNotEmpty() }?.average(),
            averageTemperatureC = temperatures.takeIf { it.isNotEmpty() }?.average()
        )
    }

    internal fun distribution(values: List<Double>): Distribution {
        val validValues = values.filter { it.isFinite() && it > 0.0 }
        val mean = validValues.takeIf { it.isNotEmpty() }?.average()
        val deviation = if (validValues.size > 1 && mean != null) {
            sqrt(validValues.sumOf { (it - mean) * (it - mean) } / (validValues.size - 1))
        } else {
            null
        }
        return Distribution(validValues.size, mean, deviation)
    }

    internal fun usageBasedCalibrationCapacity(
        result: FullDischargeTest.Result,
        estimator: BatteryCapacityEstimator
    ): Int {
        val timestamp = try {
            SimpleDateFormat("yyyy_MM_dd_HHmm", Locale.US).parse(result.startTimestampText)?.time
        } catch (_: Exception) {
            null
        } ?: return result.capacityEstimateMah
        val anchorEstimate = estimator.estimateNearTimestamp(timestamp)
        val recentEstimate = estimator.recentWeightedEstimate()
        if (anchorEstimate == null || recentEstimate == null || anchorEstimate <= 0) {
            return result.capacityEstimateMah
        }
        val ratio = (recentEstimate.toDouble() / anchorEstimate).coerceIn(0.95, 1.05)
        return (result.capacityEstimateMah * ratio).roundToInt()
    }
}
