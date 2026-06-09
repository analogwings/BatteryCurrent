package com.analogwings.batterycurrent

import android.content.Context

object AutoResetThresholdPreference {
    private const val PREFS_NAME = "battery_current_auto_reset"
    private const val RESET_ON_THRESHOLD_KEY = "reset_on_25_75_crossing"

    fun isResetOnThresholdEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(RESET_ON_THRESHOLD_KEY, false)
    }

    fun setResetOnThresholdEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(RESET_ON_THRESHOLD_KEY, enabled)
            .apply()
    }
}
