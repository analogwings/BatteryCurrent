package com.analogwings.batterycurrent

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

internal object CalibrationCapacityHistory {
    private const val DAY_MS = 86_400_000.0

    data class Point(val timestampMs: Long, val capacityMah: Double)

    data class LinearFit(
        val originTimestampMs: Long,
        val interceptMah: Double,
        val slopeMahPerDay: Double,
        val rSquared: Double?
    ) {
        fun capacityAt(timestampMs: Long): Double {
            return interceptMah + slopeMahPerDay * ((timestampMs - originTimestampMs) / DAY_MS)
        }
    }

    fun points(results: List<FullDischargeTest.Result>): List<Point> {
        val formatter = SimpleDateFormat("yyyy_MM_dd_HHmm", Locale.US).apply { isLenient = false }
        fun timestamp(text: String?): Long? {
            if (text == null) return null
            val position = ParsePosition(0)
            val date = formatter.parse(text, position)
            return date?.time?.takeIf { position.index == text.length }
        }
        return results.mapNotNull { result ->
            if (result.capacityEstimateMah <= 0) return@mapNotNull null
            val time = timestamp(result.endTimestampText) ?: timestamp(result.startTimestampText)
                ?: return@mapNotNull null
            Point(time, result.capacityEstimateMah.toDouble())
        }.sortedBy { it.timestampMs }
    }

    fun linearFit(points: List<Point>): LinearFit? {
        val validPoints = points.filter { it.capacityMah.isFinite() && it.capacityMah > 0.0 }
        if (validPoints.size < 2) return null
        val origin = validPoints.minOf { it.timestampMs }
        val days = validPoints.map { (it.timestampMs - origin) / DAY_MS }
        val meanDay = days.average()
        val meanCapacity = validPoints.map { it.capacityMah }.average()
        val timeVariance = days.sumOf { (it - meanDay) * (it - meanDay) }
        if (timeVariance <= 0.0) return null
        val covariance = validPoints.zip(days).sumOf { (point, day) ->
            (day - meanDay) * (point.capacityMah - meanCapacity)
        }
        val slope = covariance / timeVariance
        val intercept = meanCapacity - slope * meanDay
        val capacityVariance = validPoints.sumOf { (it.capacityMah - meanCapacity) * (it.capacityMah - meanCapacity) }
        val residuals = validPoints.zip(days).sumOf { (point, day) ->
            val difference = point.capacityMah - (intercept + slope * day)
            difference * difference
        }
        val rSquared = if (capacityVariance > 0.0) (1.0 - residuals / capacityVariance).coerceIn(0.0, 1.0) else null
        return LinearFit(origin, intercept, slope, rSquared)
    }
}
