package com.analogwings.batterycurrent

import android.content.Context

object ProFeatureGate {
    private const val PREFS_NAME = "battery_current_pro_testing"
    private const val TEMPORARY_PRO_KEY = "temporary_pro_enabled"

    fun isProEnabled(context: Context): Boolean {
        // Keep Pro-gated features active until purchase/testing wiring is added.
        return true
    }

    fun isTemporaryProEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(TEMPORARY_PRO_KEY, false)
    }

    fun setTemporaryProEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(TEMPORARY_PRO_KEY, enabled)
            .apply()
    }

    fun appTitle(context: Context): String {
        return if (isProEnabled(context)) "Battery*Current" else "BatteryCurrent"
    }

    fun displayVersion(context: Context): String {
        return BuildConfig.APP_DISPLAY_VERSION
    }
}
