package com.analogwings.batterycurrent

import android.content.Context

object BatteryCapacityReference {
    private const val PREFS_NAME = "battery_capacity_reference"
    private const val ORIGINAL_CAPACITY_MAH_KEY = "original_capacity_mah"
    private const val PROMPT_SEEN_KEY = "prompt_seen"

    fun originalCapacityMah(context: Context): Int? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(ORIGINAL_CAPACITY_MAH_KEY, 0)
            .takeIf { it > 0 }
    }

    fun hasSeenPrompt(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PROMPT_SEEN_KEY, false)
    }

    fun saveOriginalCapacityMah(context: Context, capacityMah: Int?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PROMPT_SEEN_KEY, true)
        if (capacityMah != null && capacityMah > 0) {
            editor.putInt(ORIGINAL_CAPACITY_MAH_KEY, capacityMah)
        } else {
            editor.remove(ORIGINAL_CAPACITY_MAH_KEY)
        }
        editor.apply()
    }

    fun markPromptSeen(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PROMPT_SEEN_KEY, true)
            .apply()
    }
}
