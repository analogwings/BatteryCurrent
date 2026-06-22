package com.analogwings.batterycurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryCapacityEstimatorTest {
    @Test
    fun includedEventWeightedEstimate_ignoresExcludedEventsImmediately() {
        val day = 24L * 60L * 60L * 1000L
        val events = listOf(
            event(endTimestampMs = day, capacityMah = 4000),
            event(endTimestampMs = 2 * day, capacityMah = 8000),
            event(endTimestampMs = 3 * day, capacityMah = 4000)
        )

        assertEquals(5333, BatteryCapacityEstimator.includedEventWeightedEstimate(events))

        val withOutlierExcluded = events.mapIndexed { index, event ->
            if (index == 1) event.copy(isExcluded = true) else event
        }

        assertEquals(4000, BatteryCapacityEstimator.includedEventWeightedEstimate(withOutlierExcluded))
    }

    @Test
    fun includedEventWeightedEstimate_usesPeukertAdjustedValueWhenAvailable() {
        val events = listOf(
            event(endTimestampMs = 1L, capacityMah = 5000, peukertAdjustedCapacityMah = 4500),
            event(endTimestampMs = 2L, capacityMah = 5500, peukertAdjustedCapacityMah = 4700)
        )

        assertEquals(4600, BatteryCapacityEstimator.includedEventWeightedEstimate(events))
    }

    @Test
    fun includedEventWeightedEstimate_returnsNullWhenAllEventsExcluded() {
        val events = listOf(
            event(endTimestampMs = 1L, capacityMah = 5000, isExcluded = true),
            event(endTimestampMs = 2L, capacityMah = 5500, isExcluded = true)
        )

        assertNull(BatteryCapacityEstimator.includedEventWeightedEstimate(events))
    }

    private fun event(
        endTimestampMs: Long,
        capacityMah: Int,
        peukertAdjustedCapacityMah: Int? = null,
        isExcluded: Boolean = false
    ): BatteryCapacityEstimator.CapacityEventSummary {
        return BatteryCapacityEstimator.CapacityEventSummary(
            eventId = "event-$endTimestampMs-$capacityMah-$isExcluded",
            startTimestampMs = endTimestampMs - 1000L,
            endTimestampMs = endTimestampMs,
            direction = "discharge",
            avgCurrentMa = 500.0,
            avgTempC = 30.0,
            avgVoltageMv = 3900.0,
            mahAdded = capacityMah,
            capacityEstimateMah = capacityMah,
            peukertK = null,
            peukertAdjustedCapacityMah = peukertAdjustedCapacityMah,
            isExcluded = isExcluded
        )
    }
}
