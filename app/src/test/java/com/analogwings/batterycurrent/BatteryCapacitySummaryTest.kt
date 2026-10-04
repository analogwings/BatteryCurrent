package com.analogwings.batterycurrent

import org.junit.Assert.assertEquals
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
