package com.xraytunnel.app.data

import android.content.Context

object DnsPrefs {
    private const val PREF = "dns_settings"
    fun primary(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("dns_primary", "1.1.1.1") ?: "1.1.1.1"
    fun secondary(ctx: Context): String =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("dns_secondary", "8.8.8.8") ?: "8.8.8.8"
    fun set(ctx: Context, primary: String, secondary: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("dns_primary", primary.trim().ifBlank { "1.1.1.1" })
            .putString("dns_secondary", secondary.trim().ifBlank { "8.8.8.8" })
            .apply()
    }
}
