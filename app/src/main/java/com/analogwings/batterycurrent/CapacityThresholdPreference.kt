package com.analogwings.batterycurrent

import android.content.Context

object CapacityThresholdPreference {
    data class Thresholds(
        val lowPercent: Int,
        val highPercent: Int
    ) {
        val spanPercent: Int = highPercent - lowPercent
    }

    const val DEFAULT_LOW_PERCENT = 25
    const val DEFAULT_HIGH_PERCENT = 75
    private const val PREFS_NAME = "battery_capacity_thresholds"
    private const val LOW_PERCENT_KEY = "low_percent"
    private const val HIGH_PERCENT_KEY = "high_percent"
    private const val MIN_LOW_PERCENT = 20
    private const val MAX_HIGH_PERCENT = 95
    private const val MIN_SPAN_PERCENT = 40

    fun load(context: Context): Thresholds {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val low = prefs.getInt(LOW_PERCENT_KEY, DEFAULT_LOW_PERCENT)
        val high = prefs.getInt(HIGH_PERCENT_KEY, DEFAULT_HIGH_PERCENT)
        return sanitize(low, high)
    }

    fun save(context: Context, lowPercent: Int, highPercent: Int): Thresholds {
        val thresholds = sanitize(lowPercent, highPercent)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(LOW_PERCENT_KEY, thresholds.lowPercent)
            .putInt(HIGH_PERCENT_KEY, thresholds.highPercent)
            .apply()
        return thresholds
    }

    private fun sanitize(lowPercent: Int, highPercent: Int): Thresholds {
        val low = lowPercent.coerceIn(MIN_LOW_PERCENT, MAX_HIGH_PERCENT - MIN_SPAN_PERCENT)
        val high = highPercent.coerceIn(low + MIN_SPAN_PERCENT, MAX_HIGH_PERCENT)
        return Thresholds(low, high)
    }
}
