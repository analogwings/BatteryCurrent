package com.analogwings.batterycurrent

import android.content.Context

object OverlayThemePreference {
    private const val PREFS_NAME = "foreground_overlay_theme"
    private const val LIGHT_BACKGROUND_KEY = "light_background"

    fun isLightBackgroundEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(LIGHT_BACKGROUND_KEY, false)
    }

    fun setLightBackgroundEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(LIGHT_BACKGROUND_KEY, enabled)
            .apply()
    }
}
