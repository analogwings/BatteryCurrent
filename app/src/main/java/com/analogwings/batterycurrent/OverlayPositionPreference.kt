package com.analogwings.batterycurrent

import android.content.Context

object OverlayPositionPreference {
    private const val PREFS_NAME = "battery_current_overlay_prefs"
    private const val X_KEY = "overlay_x"
    private const val Y_KEY = "overlay_y"
    private const val HAS_SAVED_POSITION_KEY = "overlay_has_saved_position"

    fun loadPosition(context: Context): Pair<Int, Int>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(HAS_SAVED_POSITION_KEY, false)) return null

        return prefs.getInt(X_KEY, 0) to prefs.getInt(Y_KEY, 0)
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(X_KEY, x)
            .putInt(Y_KEY, y)
            .putBoolean(HAS_SAVED_POSITION_KEY, true)
            .apply()
    }

    fun resetPosition(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(X_KEY)
            .remove(Y_KEY)
            .putBoolean(HAS_SAVED_POSITION_KEY, false)
            .apply()
    }
}
