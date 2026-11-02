package com.xraytunnel.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Remote switches only; executable ad code and the Start.io SDK remain inside the APK. */
data class AdsConfig(
    val enabled: Boolean = true,
    val bannerEnabled: Boolean = true,
    val interstitialEnabled: Boolean = true,
    val rewardedEnabled: Boolean = true,
    val rewardMinutes: Int = 60
)

object AdsRemoteConfig {
    // true = إعلانات Start.io تجريبية للتأكد من الدمج. اجعلها false عند النشر الحقيقي.
    const val TEST_ADS = false

    // true = يعرض رسالة على الشاشة بسبب فشل الإعلان (للتشخيص). اجعلها false قبل النشر.
    const val SHOW_AD_ERRORS = true

    // Upload ads.json to this GitHub repository path, or replace this URL with your own HTTPS raw URL.
    const val CONFIG_URL = "https://raw.githubusercontent.com/radwanbns/Redo-VPN/refs/heads/main/ads.json"
    private const val PREFS = "remote_ads_config"
    private const val CACHE = "config_json"

    val DEFAULT = AdsConfig()

    suspend fun load(context: Context): AdsConfig = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val jsonText = try {
            val connection = (URL(CONFIG_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
            }
            try {
                if (connection.responseCode in 200..299) {
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        .takeIf { it.length <= 16_384 }
                        ?.also { prefs.edit().putString(CACHE, it).apply() }
                } else null
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        } ?: prefs.getString(CACHE, null)

        parse(jsonText) ?: DEFAULT
    }

    private fun parse(raw: String?): AdsConfig? {
        if (raw.isNullOrBlank()) return null
        return try {
            val json = JSONObject(raw)
            AdsConfig(
                enabled = json.optBoolean("ads_enabled", true),
                bannerEnabled = json.optBoolean("banner_enabled", true),
                interstitialEnabled = json.optBoolean("interstitial_enabled", true),
                rewardedEnabled = json.optBoolean("rewarded_enabled", true),
                rewardMinutes = 60 // Reward duration is fixed at one hour after full video completion
            )
        } catch (_: Exception) {
            null
        }
    }
}
