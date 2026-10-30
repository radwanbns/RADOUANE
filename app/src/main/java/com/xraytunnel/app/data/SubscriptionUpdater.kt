package com.xraytunnel.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** يجلب السيرفرات من رابط خارجي (GitHub raw أو أي اشتراك). */
object SubscriptionUpdater {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(url: String): List<Server> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "XrayTunnel/1.0").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            parseContent(r.body?.string().orEmpty())
        }
    }

    /** يقبل نصاً عادياً (سطر لكل رابط) أو Base64. */
    fun parseContent(text: String): List<Server> {
        val t = text.trim()
        val decoded = if (t.contains("://")) t else (LinkParser.b64(t) ?: t)
        return decoded.lineSequence().mapNotNull { LinkParser.parse(it) }.toList()
    }
}
