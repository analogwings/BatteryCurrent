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

        val x = prefs.getInt(X_KEY, 0)
        val y = prefs.getInt(Y_KEY, 0)
        val clamped = clampToReachableArea(context, x, y)
        if (clamped.first != x || clamped.second != y) {
            savePosition(context, clamped.first, clamped.second)
        }
        return clamped
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        val (safeX, safeY) = clampToReachableArea(context, x, y)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(X_KEY, safeX)
            .putInt(Y_KEY, safeY)
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

    private fun clampToReachableArea(context: Context, x: Int, y: Int): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val maxX = ((metrics.widthPixels / 2) - HORIZONTAL_EDGE_GUARD_PX).coerceAtLeast(0)
        val maxY = ((metrics.heightPixels / 2) - VERTICAL_EDGE_GUARD_PX).coerceAtLeast(0)
        return x.coerceIn(-maxX, maxX) to y.coerceIn(-maxY, maxY)
    }

    private const val HORIZONTAL_EDGE_GUARD_PX = 120
    private const val VERTICAL_EDGE_GUARD_PX = 160
}
