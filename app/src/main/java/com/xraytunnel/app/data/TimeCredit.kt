package com.xraytunnel.app.data

import android.content.Context

/** Local usage credit. Credit is consumed by the VPN service only while its tunnel is Connected. */
object TimeCredit {
    const val REWARD_MILLIS = 60L * 60L * 1000L
    private const val PREFS = "vpn_time_credit"
    private const val KEY_REMAINING = "remaining_ms"
    /** رصيد مجاني عند أول تثبيت: 6 دقائق فقط. */
    private const val INITIAL_CREDIT = 6L * 60L * 1000L

    @Synchronized fun remaining(context: Context): Long {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getLong(KEY_REMAINING, INITIAL_CREDIT).coerceAtLeast(0L)
    }

    @Synchronized fun grantReward(context: Context): Long {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = (p.getLong(KEY_REMAINING, INITIAL_CREDIT) + REWARD_MILLIS)
            .coerceAtMost(24L * 60L * 60L * 1000L)
        p.edit().putLong(KEY_REMAINING, next).apply()
        return next
    }

    /** Called once per second only by the active VPN service. */
    @Synchronized fun consumeSecond(context: Context): Long {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = (p.getLong(KEY_REMAINING, INITIAL_CREDIT) - 1_000L).coerceAtLeast(0L)
        p.edit().putLong(KEY_REMAINING, next).apply()
        return next
    }

    fun format(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0L) + 999L) / 1_000L
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, secs)
    }
}
