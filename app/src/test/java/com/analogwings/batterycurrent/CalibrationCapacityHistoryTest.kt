package com.analogwings.batterycurrent

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationCapacityHistoryTest {
    private val origin = 1_700_000_000_000L
    private val dayMs = 86_400_000L

    @Test
    fun pointsUseCompletionDatesAndKeepAllEventsInTimeOrder() {
        val results = listOf(
            result("2026_01_01_1000", "2026_04_02_1000", 4800),
            result("2026_03_01_1000", "2026_03_02_1000", 4900)
        )

        val points = CalibrationCapacityHistory.points(results)

        assertEquals(listOf(4900.0, 4800.0), points.map { it.capacityMah })
        assertEquals(timestamp("2026_03_02_1000"), points.first().timestampMs)
        assertEquals(150, CalibrationCapacityHistory.points(List(150) { results.first() }).size)
    }

    @Test
    fun pointsFallBackToStartDateAndRejectUnusableDates() {
        val points = CalibrationCapacityHistory.points(
            listOf(
                result("2026_04_01_1000", null, 4800),
                result("2026_04_02_1000", "2026_13_01_1000", 4700),
                result("bad-date", "2026_04_03_1000-extra", 4600),
                result("2026_04_04_1000", null, 0)
            )
        )

        assertEquals(2, points.size)
        assertEquals(timestamp("2026_04_01_1000"), points.first().timestampMs)
        assertEquals(timestamp("2026_04_02_1000"), points.last().timestampMs)
    }

    @Test
    fun linearFitRecoversKnownCapacityLossPerDay() {
        val points = listOf(point(0, 5000.0), point(1, 4980.0), point(2, 4960.0))
        val fit = CalibrationCapacityHistory.linearFit(points)!!

        assertEquals(-20.0, fit.slopeMahPerDay, 0.000001)
        assertEquals(5000.0, fit.interceptMah, 0.000001)
        assertEquals(1.0, fit.rSquared!!, 0.000001)
        assertEquals(4960.0, fit.capacityAt(origin + 2 * dayMs), 0.000001)
    }

    @Test
    fun linearFitUsesActualElapsedTimeInsteadOfEventIndex() {
        val points = listOf(point(10, 4900.0), point(0, 5000.0), point(1, 4990.0))
        val fit = CalibrationCapacityHistory.linearFit(points)!!

        assertEquals(-10.0, fit.slopeMahPerDay, 0.000001)
        assertEquals(1.0, fit.rSquared!!, 0.000001)
    }

    @Test
    fun linearFitReportsFitQualityForScatteredMeasurements() {
        val fit = CalibrationCapacityHistory.linearFit(
            listOf(point(0, 5000.0), point(1, 4900.0), point(2, 4700.0), point(3, 4800.0))
        )!!

        assertEquals(-80.0, fit.slopeMahPerDay, 0.000001)
        assertEquals(0.64, fit.rSquared!!, 0.000001)
    }

    @Test
    fun linearFitDoesNotInventATrendWithoutDistinctTimes() {
        assertNull(CalibrationCapacityHistory.linearFit(emptyList()))
        assertNull(CalibrationCapacityHistory.linearFit(listOf(point(0, 5000.0))))
        assertNull(CalibrationCapacityHistory.linearFit(listOf(point(0, 5000.0), point(0, 4900.0))))
    }

    @Test
    fun linearFitHandlesConstantCapacityAndInvalidMeasurements() {
        val fit = CalibrationCapacityHistory.linearFit(
            listOf(point(0, 5000.0), point(1, 5000.0), point(2, Double.NaN), point(3, 0.0))
        )!!

        assertEquals(0.0, fit.slopeMahPerDay, 0.000001)
        assertEquals(5000.0, fit.capacityAt(origin + dayMs), 0.000001)
        assertNull(fit.rSquared)
    }

    private fun point(day: Int, capacityMah: Double): CalibrationCapacityHistory.Point {
        return CalibrationCapacityHistory.Point(origin + day * dayMs, capacityMah)
    }

    private fun result(start: String, end: String?, capacityMah: Int): FullDischargeTest.Result {
        return FullDischargeTest.Result(start, end, null, capacityMah, null, null, null)
    }

    private fun timestamp(text: String): Long {
        return SimpleDateFormat("yyyy_MM_dd_HHmm", Locale.US).parse(text)!!.time
    }
}
