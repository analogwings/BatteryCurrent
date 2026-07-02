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

    @Test
    fun filteredSocBucketAveragesForLinearity_requiresMoreTopBucketSamples() {
        val buckets = listOf(
            socBucket(startPct = 20, learnedMah = 420.0, sampleCount = 3),
            socBucket(startPct = 30, learnedMah = 430.0, sampleCount = 3),
            socBucket(startPct = 40, learnedMah = 425.0, sampleCount = 3),
            socBucket(startPct = 50, learnedMah = 418.0, sampleCount = 3),
            socBucket(startPct = 90, learnedMah = 620.0, sampleCount = 4)
        )

        val filtered = BatteryCapacityEstimator.filteredSocBucketAveragesForLinearity(buckets)

        assertEquals(listOf(20, 30, 40, 50), filtered.map { it.bucketStartPct })
    }

    @Test
    fun filteredSocBucketAveragesForLinearity_removesSparseHighEndOutlierFromFittedCurve() {
        val buckets = listOf(
            socBucket(startPct = 20, learnedMah = 410.0, sampleCount = 8),
            socBucket(startPct = 30, learnedMah = 420.0, sampleCount = 8),
            socBucket(startPct = 40, learnedMah = 415.0, sampleCount = 8),
            socBucket(startPct = 50, learnedMah = 430.0, sampleCount = 8),
            socBucket(startPct = 60, learnedMah = 425.0, sampleCount = 8),
            socBucket(startPct = 70, learnedMah = 418.0, sampleCount = 8),
            socBucket(startPct = 80, learnedMah = 422.0, sampleCount = 8),
            socBucket(startPct = 90, learnedMah = 610.0, sampleCount = 5)
        )

        val filtered = BatteryCapacityEstimator.filteredSocBucketAveragesForLinearity(buckets)

        assertEquals(listOf(20, 30, 40, 50, 60, 70, 80), filtered.map { it.bucketStartPct })
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

    private fun socBucket(
        startPct: Int,
        learnedMah: Double,
        sampleCount: Int
    ): BatteryCapacityEstimator.SocBucketSummary {
        return BatteryCapacityEstimator.SocBucketSummary(
            bucketStartPct = startPct,
            bucketEndPct = (startPct + 10).coerceAtMost(100),
            learnedMah = learnedMah,
            learnedWh = null,
            sampleCount = sampleCount,
            avgCurrentMa = null,
            avgTempC = null
        )
    }
}
