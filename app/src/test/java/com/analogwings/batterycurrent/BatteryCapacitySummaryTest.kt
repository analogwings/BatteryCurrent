package com.analogwings.batterycurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryCapacitySummaryTest {
    @Test
    fun combinesQuickAndFullEventsWithoutCountingExcludedEvents() {
        val summary = BatteryCapacitySummary.calculate(
            listOf(quickEvent(4000), quickEvent(9000, excluded = true)),
            listOf(calibration(4400))
        )

        assertEquals(2, summary.current.count)
        assertEquals(4200.0, summary.current.meanMah!!, 0.001)
        assertEquals(282.8427, summary.current.standardDeviationMah!!, 0.001)
        assertEquals(1, summary.quick.count)
    }

    @Test
    fun calibrationStatisticsUseAllRawCalibrationsWhileCurrentUsesUsageAdjustment() {
        val summary = BatteryCapacitySummary.calculate(
            emptyList(),
            listOf(calibration(4000), calibration(4400)),
            listOf(4200, 4620)
        )

        assertEquals(4200.0, summary.calibration.meanMah!!, 0.001)
        assertEquals(282.8427, summary.calibration.standardDeviationMah!!, 0.001)
        assertEquals(4410.0, summary.current.meanMah!!, 0.001)
        assertEquals(296.9848, summary.current.standardDeviationMah!!, 0.001)
    }

    @Test
    fun dischargeConditionsIgnoreChargingExcludedAndMissingMeasurements() {
        val summary = BatteryCapacitySummary.calculate(
            listOf(
                quickEvent(4000, currentMa = -400.0, temperatureC = 20.0),
                quickEvent(4200, direction = "charge", currentMa = 1800.0, temperatureC = 45.0),
                quickEvent(9000, excluded = true, currentMa = 3000.0, temperatureC = 60.0),
                quickEvent(4100, currentMa = null, temperatureC = null)
            ),
            listOf(calibration(4400, currentMa = 600.0, temperatureC = 30.0))
        )

        assertEquals(500.0, summary.averageDischargeCurrentMa!!, 0.001)
        assertEquals(25.0, summary.averageTemperatureC!!, 0.001)
        assertEquals(4, summary.current.count)
    }

    @Test
    fun dischargeCRateUsesConfiguredReferenceCapacity() {
        val summary = BatteryCapacitySummary.calculate(
            listOf(quickEvent(4700, currentMa = 471.0)),
            emptyList()
        ).copy(referenceCapacityMah = 5050)

        assertTrue(summary.displayText().contains("Avg Discharge: 471mA (0.09C)"))
    }

    @Test
    fun dischargeCRateFallsBackToCurrentEstimateWithoutReferenceCapacity() {
        val summary = BatteryCapacitySummary.calculate(
            listOf(quickEvent(4700, currentMa = 471.0)),
            emptyList()
        )

        assertTrue(summary.displayText().contains("Avg Discharge: 471mA (0.10C)"))
    }

    @Test
    fun capacityChangeSinceNewUsesConfiguredOriginalCapacity() {
        val summary = BatteryCapacitySummary.calculate(listOf(quickEvent(4389)), emptyList())
            .copy(referenceCapacityMah = 5050)

        assertEquals("Current battery capacity: 4389 mAh (-13.1%)", summary.displayText().lineSequence().first())
    }

    @Test
    fun capacityChangeSinceNewKeepsTheSignForAnIncrease() {
        val summary = BatteryCapacitySummary.calculate(listOf(quickEvent(5700)), emptyList())
            .copy(referenceCapacityMah = 5000)

        assertEquals("Current battery capacity: 5700 mAh (+14.0%)", summary.displayText().lineSequence().first())
    }

    @Test
    fun capacityChangeSinceNewIsOmittedWithoutAValidOriginalCapacityOrEstimate() {
        val summary = BatteryCapacitySummary.calculate(listOf(quickEvent(4389)), emptyList())
        val noEstimate = BatteryCapacitySummary.calculate(emptyList(), emptyList()).copy(referenceCapacityMah = 5050)

        assertEquals("Current battery capacity: 4389 mAh", summary.displayText().lineSequence().first())
        assertEquals("Current battery capacity: 4389 mAh", summary.copy(referenceCapacityMah = 0).displayText().lineSequence().first())
        assertEquals("Current battery capacity: Collecting data", noEstimate.displayText().lineSequence().first())
    }

    @Test
    fun singleEventDoesNotClaimZeroUncertainty() {
        val summary = BatteryCapacitySummary.calculate(emptyList(), listOf(calibration(4200)))

        assertEquals(4200.0, summary.current.meanMah!!, 0.001)
        assertNull(summary.current.standardDeviationMah)
        assertTrue(summary.displayText().contains("need 2 events"))
    }

    @Test
    fun noDataDoesNotInventAnEstimateOrConditions() {
        val summary = BatteryCapacitySummary.calculate(emptyList(), emptyList())

        assertEquals(0, summary.current.count)
        assertNull(summary.current.meanMah)
        assertNull(summary.averageDischargeCurrentMa)
        assertNull(summary.averageTemperatureC)
        assertTrue(summary.displayText().contains("Collecting data"))
    }

    @Test
    fun identicalMultipleEventsHaveZeroSampleDeviation() {
        val distribution = BatteryCapacitySummary.distribution(listOf(4200.0, 4200.0, 4200.0))

        assertEquals(0.0, distribution.standardDeviationMah!!, 0.001)
    }

    @Test
    fun includesHistoryBeyondGraphPointLimitAndFiltersInvalidValues() {
        val summary = BatteryCapacitySummary.calculate(
            List(250) { quickEvent(4000) },
            List(150) { calibration(4400) }
        )

        assertEquals(400, summary.current.count)
        assertEquals(4150.0, summary.current.meanMah!!, 0.001)
        assertEquals(1, BatteryCapacitySummary.distribution(listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 4000.0)).count)
    }

    @Test
    fun headlineCombinesBothFitsAtTodayWithMatchingSlopeAndPreservesExistingStatistics() {
        val original = BatteryCapacitySummary.calculate(
            listOf(quickEvent(4000, currentMa = 471.0, temperatureC = 32.9)),
            listOf(calibration(4800))
        ).copy(referenceCapacityMah = 5000)
        val summary = original.withCapacityTrends(
            listOf(trendPoint(0, 5000.0), trendPoint(10, 4900.0)),
            listOf(trendPoint(0, 4700.0), trendPoint(10, 4500.0)),
            trendPoint(20, 1.0).timestampMs
        )

        assertEquals(4550.0, summary.capacityEstimateMah!!, 0.001)
        assertEquals(4800.0, summary.fullCalibrationTrend!!.capacityMah, 0.001)
        assertEquals(4300.0, summary.partialCapacityTrend!!.capacityMah, 0.001)
        assertEquals(-456.5625, summary.combinedCapacityTrend!!.changeMahPerMonth, 0.001)
        assertEquals(original.current, summary.current)
        assertEquals(original.quick, summary.quick)
        assertEquals(original.calibration, summary.calibration)
        assertEquals(original.averageDischargeCurrentMa, summary.averageDischargeCurrentMa)
        assertEquals(original.averageTemperatureC, summary.averageTemperatureC)
        assertEquals("Current battery capacity: 4550 mAh (-9.0%)", summary.displayText().lineSequence().first())
        assertFalse(summary.displayText().contains("Fit today:"))
        assertTrue(summary.displayText().contains("Capacity change/month: -457 mAh (-9.13%)"))
        assertFalse(summary.displayText().contains("Full cal/month:"))
        assertFalse(summary.displayText().contains("Partial/month:"))
        assertEquals(1, summary.displayText().lineSequence().count { it.contains("/month:") })
        assertTrue(summary.displayText().contains("1 quick + 1 full calibration events"))
    }

    @Test
    fun headlineUsesPartialFitWhenFullCalibrationCannotBeFitted() {
        val summary = BatteryCapacitySummary.calculate(listOf(quickEvent(4700)), emptyList())
            .withCapacityTrends(
                listOf(trendPoint(0, 5000.0)),
                listOf(trendPoint(0, 4700.0), trendPoint(10, 4600.0)),
                trendPoint(20, 1.0).timestampMs
            )

        assertNull(summary.fullCalibrationTrend)
        assertEquals(4500.0, summary.capacityEstimateMah!!, 0.001)
        assertFalse(summary.displayText().contains("Fit today:"))
        assertEquals(summary.partialCapacityTrend, summary.combinedCapacityTrend)
        assertTrue(summary.displayText().contains("Capacity change/month: -304 mAh"))
    }

    @Test
    fun headlineFallsBackToFullFitWhenRecentCapacityGraphCannotBeFitted() {
        val summary = BatteryCapacitySummary.calculate(listOf(quickEvent(4000)), emptyList())
            .withCapacityTrends(
                listOf(trendPoint(0, 5000.0), trendPoint(10, 4900.0)),
                listOf(trendPoint(10, 4200.0)),
                trendPoint(20, 1.0).timestampMs
            )

        assertNull(summary.partialCapacityTrend)
        assertEquals(4800.0, summary.capacityEstimateMah!!, 0.001)
        assertEquals(summary.fullCalibrationTrend, summary.combinedCapacityTrend)
    }

    @Test
    fun partialFitIgnoresHistoryOutsideTheDefaultGraphWindow() {
        val summary = BatteryCapacitySummary.calculate(emptyList(), emptyList()).withCapacityTrends(
            emptyList(),
            listOf(trendPoint(0, 9000.0), trendPoint(30, 5000.0), trendPoint(120, 4100.0)),
            trendPoint(130, 1.0).timestampMs
        )

        assertEquals(4000.0, summary.capacityEstimateMah!!, 0.001)
        assertEquals(-304.375, summary.partialCapacityTrend!!.changeMahPerMonth, 0.001)
    }

    @Test
    fun unavailableOrNonpositiveFitsFallBackToTheExistingEstimate() {
        val original = BatteryCapacitySummary.calculate(listOf(quickEvent(4300)), emptyList())
        val summary = original.withCapacityTrends(
            listOf(trendPoint(0, 100.0), trendPoint(1, 50.0)),
            listOf(trendPoint(0, 200.0), trendPoint(1, 100.0)),
            trendPoint(3, 1.0).timestampMs
        )

        assertNull(summary.fullCalibrationTrend)
        assertNull(summary.partialCapacityTrend)
        assertNull(summary.combinedCapacityTrend)
        assertEquals(original.displayText(), summary.displayText())
        assertEquals(4300.0, summary.capacityEstimateMah!!, 0.001)
    }

    @Test
    fun fittedCapacityIsTheCRateFallbackWithoutAnOriginalCapacity() {
        val summary = BatteryCapacitySummary.calculate(listOf(quickEvent(6000, currentMa = 471.0)), emptyList())
            .withCapacityTrends(
                listOf(trendPoint(0, 6000.0), trendPoint(10, 6000.0)),
                listOf(trendPoint(0, 4900.0), trendPoint(10, 4800.0)),
                trendPoint(20, 1.0).timestampMs
            )

        assertEquals(5350.0, summary.capacityEstimateMah!!, 0.001)
        assertTrue(summary.displayText().contains("Avg Discharge: 471mA (0.09C)"))
    }

    @Test
    fun opposingSlopesAreCombinedWithoutForcingAGainToLookLikeDegradation() {
        val summary = BatteryCapacitySummary.calculate(emptyList(), emptyList()).copy(
            referenceCapacityMah = 5050,
            fullCalibrationTrend = BatteryCapacitySummary.CapacityTrend(5023.0, 180.0),
            partialCapacityTrend = BatteryCapacitySummary.CapacityTrend(4400.0, -49.0)
        )

        assertEquals(4711.5, summary.capacityEstimateMah!!, 0.001)
        assertEquals(65.5, summary.combinedCapacityTrend!!.changeMahPerMonth, 0.001)
        assertTrue(summary.displayText().contains("Capacity change/month: +66 mAh (+1.30%)"))
    }

    @Test
    fun combinedFitWeightsSourcesEquallyRatherThanCountingPartialEvents() {
        val summary = BatteryCapacitySummary.calculate(emptyList(), emptyList()).withCapacityTrends(
            listOf(trendPoint(0, 5000.0), trendPoint(10, 4990.0)),
            List(90) { day -> trendPoint(day, 4500.0 - day * 2.0) },
            trendPoint(90, 1.0).timestampMs
        )

        assertEquals(4615.0, summary.capacityEstimateMah!!, 0.001)
        assertEquals(-45.65625, summary.combinedCapacityTrend!!.changeMahPerMonth, 0.001)
    }

    @Test
    fun monthlyChangeKeepsIncreasesAndOmitsPercentageWithoutAReference() {
        assertEquals("+304 mAh (+6.09%)", BatteryCapacitySummary.monthlyChangeText(304.375, 5000))
        assertEquals("-304 mAh", BatteryCapacitySummary.monthlyChangeText(-304.375, null))
        assertEquals("-304 mAh", BatteryCapacitySummary.monthlyChangeText(-304.375, 0))
    }

    private fun trendPoint(day: Int, capacityMah: Double): CalibrationCapacityHistory.Point {
        return CalibrationCapacityHistory.Point(1_700_000_000_000L + day * 86_400_000L, capacityMah)
    }

    private fun calibration(capacityMah: Int, currentMa: Double? = null, temperatureC: Double? = null): FullDischargeTest.Result {
        return FullDischargeTest.Result("2026_09_22_2031", "2026_09_23_1230", null, capacityMah, temperatureC, null, currentMa)
    }

    private fun quickEvent(
        capacityMah: Int,
        excluded: Boolean = false,
        direction: String = "discharge",
        currentMa: Double? = null,
        temperatureC: Double? = null
    ): BatteryCapacityEstimator.CapacityEventSummary {
        return BatteryCapacityEstimator.CapacityEventSummary(
            eventId = "event-$capacityMah-$excluded-$direction",
            startTimestampMs = 1L,
            endTimestampMs = 2L,
            direction = direction,
            avgCurrentMa = currentMa,
            avgTempC = temperatureC,
            avgVoltageMv = null,
            mahAdded = capacityMah,
            capacityEstimateMah = capacityMah,
            peukertK = null,
            peukertAdjustedCapacityMah = null,
            isExcluded = excluded
        )
    }
}
