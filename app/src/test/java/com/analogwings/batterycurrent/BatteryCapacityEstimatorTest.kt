package com.analogwings.batterycurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun socLinearityPointsForSamples_lineMatchesVisibleDotMeanInEveryBucket() {
        val samples = listOf(
            socSample(20, 300.0), socSample(20, 320.0), socSample(20, 340.0),
            socSample(30, 340.0), socSample(30, 360.0), socSample(30, 380.0),
            socSample(40, 380.0), socSample(40, 400.0), socSample(40, 420.0)
        )

        val points = BatteryCapacityEstimator.socLinearityPointsForSamples(samples)
        val line = points.filter { it.isFittedBucketAverage }
        val dots = points.filterNot { it.isFittedBucketAverage }

        assertEquals(3, line.size)
        assertEquals(9, dots.size)
        assertTrue(points.all { it.idealMah == 360.0 })
        line.forEach { bucket ->
            val bucketDots = dots.filter { it.bucketStartPct == bucket.bucketStartPct }
            assertEquals(bucketDots.map { it.deviationFromIdeal }.average(), bucket.deviationFromIdeal, 0.000001)
            assertEquals(bucketDots.map { it.learnedMah }.average(), bucket.learnedMah, 0.000001)
            assertEquals(bucketDots.size, bucket.sampleCount)
        }
    }

    @Test
    fun socLinearityPointsForSamples_dropsOldHighCapacityHistoryFromBothDotsAndLine() {
        val oldSamples = List(100) { index -> socSample(20, 600.0, index.toLong()) }
        val recentSamples = List(100) { index ->
            socSample(if (index % 2 == 0) 20 else 30, if (index % 2 == 0) 300.0 else 360.0, 100L + index)
        }

        val points = BatteryCapacityEstimator.socLinearityPointsForSamples(oldSamples + recentSamples)
        val line = points.filter { it.isFittedBucketAverage }

        assertEquals(100, points.count { !it.isFittedBucketAverage })
        assertTrue(points.all { it.idealMah == 330.0 })
        assertEquals(listOf(300.0, 360.0), line.map { it.learnedMah })
        assertEquals(-30.0 / 330.0, line.first().deviationFromIdeal, 0.000001)
        assertEquals(30.0 / 330.0, line.last().deviationFromIdeal, 0.000001)
    }

    @Test
    fun socLinearityPointsForSamples_referenceUsesTheSamePopulationSampleCounts() {
        val points = BatteryCapacityEstimator.socLinearityPointsForSamples(
            List(3) { socSample(20, 300.0) } + List(6) { socSample(30, 450.0) }
        )
        val line = points.filter { it.isFittedBucketAverage }

        assertTrue(points.all { it.idealMah == 400.0 })
        assertEquals(0.0, line.sumOf { it.deviationFromIdeal * it.sampleCount }, 0.000001)
    }

    @Test
    fun socLinearityPointsForSamples_requiresFiveVisibleTopBucketSamples() {
        val samples = List(3) { socSample(20, 400.0) } + List(4) { socSample(90, 410.0) }
        val sparsePoints = BatteryCapacityEstimator.socLinearityPointsForSamples(samples)
        val qualifiedPoints = BatteryCapacityEstimator.socLinearityPointsForSamples(samples + socSample(90, 410.0))

        assertEquals(listOf(20), sparsePoints.filter { it.isFittedBucketAverage }.map { it.bucketStartPct })
        assertEquals(4, sparsePoints.count { !it.isFittedBucketAverage && it.bucketStartPct == 90 })
        assertEquals(listOf(20, 90), qualifiedPoints.filter { it.isFittedBucketAverage }.map { it.bucketStartPct })
    }

    @Test
    fun socLinearityPointsForSamples_doesNotProduceInvalidReferenceValues() {
        val points = BatteryCapacityEstimator.socLinearityPointsForSamples(
            listOf(socSample(20, Double.NaN), socSample(20, Double.POSITIVE_INFINITY), socSample(20, 0.0))
        )

        assertTrue(points.isEmpty())
        assertTrue(BatteryCapacityEstimator.socLinearityPointsForSamples(emptyList()).isEmpty())
    }

    private fun socSample(startPct: Int, learnedMah: Double, timestampMs: Long = 1L): BatteryCapacityEstimator.SocBucketSample {
        return BatteryCapacityEstimator.SocBucketSample(timestampMs, startPct, startPct + 10, learnedMah, 500.0, 30.0)
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
