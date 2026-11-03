package com.xraytunnel.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

/** نتيجة فحص الاشتراك؛ 304 تعني أن ملف GitHub لم يتغير. */
data class SubscriptionFetchResult(
    val servers: List<Server>?,
    val etag: String?,
    val lastModified: String?,
    val notModified: Boolean
)

/** يجلب السيرفرات من GitHub مع دعم الفحص الشرطي لتقليل استهلاك الشبكة. */
object SubscriptionUpdater {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(url: String): SubscriptionFetchResult = fetchConditional(url, null, null)

    /** يرسل ETag وLast-Modified؛ عند عدم وجود تغيير يعيد 304 دون تنزيل محتوى الملف. */
    suspend fun fetchConditional(
        url: String,
        etag: String?,
        lastModified: String?
    ): SubscriptionFetchResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
            .header("User-Agent", "XrayTunnel/1.0")
            .header("Cache-Control", "no-cache")
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        if (!lastModified.isNullOrBlank()) builder.header("If-Modified-Since", lastModified)

        client.newCall(builder.build()).execute().use { response ->
            val responseEtag = response.header("ETag") ?: etag
            val responseModified = response.header("Last-Modified") ?: lastModified
            if (response.code == 304) {
                return@withContext SubscriptionFetchResult(null, responseEtag, responseModified, true)
            }
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) error("Subscription response is empty")
            val parsed = parseContent(body)
            if (parsed.isEmpty()) error("No valid servers found in subscription")
            SubscriptionFetchResult(parsed, responseEtag, responseModified, false)
        }
    }

    /**
     * يقبل: نصاً عادياً (سطر لكل رابط: vless:// vmess:// trojan:// ss:// ssh:// ...)، أو Base64،
     * أو JSON (مصفوفة سيرفرات أو {"servers":[...]}) بنفس حقول «مشاركة JSON» — مناسب لبايلود SSH الطويل.
     * يمكن خلط V2Ray وSSH في نفس الملف.
     */
    fun parseContent(text: String): List<Server> {
        val t = text.trim().removePrefix("\uFEFF").trim()

        if (t.startsWith("[") || t.startsWith("{")) {
            parseJson(t)?.takeIf { it.isNotEmpty() }?.let { return it }
        }

        // ملف .ovpn كامل (عدة أسطر)
        if (t.contains("remote ") && (t.contains("client") || t.contains("dev tun") || t.contains("proto "))) {
            LinkParser.ovpnConfig(t)?.let { return listOf(it) }
        }

        val decoded = if (t.contains("://")) t else (LinkParser.b64(t) ?: t)
        return decoded.lineSequence()
            .mapNotNull { LinkParser.parse(it) }
            .distinctBy { it.identityKey() }
            .toList()
    }

    private fun parseJson(t: String): List<Server>? = try {
        val arr: JSONArray = if (t.startsWith("[")) {
            JSONArray(t)
        } else {
            val o = JSONObject(t)
            o.optJSONArray("servers") ?: JSONArray().put(o)
        }

        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val s = Server.fromJson(o)
            val fixed = s.copy(
                id = UUID.randomUUID().toString(),
                subId = "",
                locked = false,
                name = s.name.ifBlank { s.protocol.uppercase() },
                port = if (s.port in 1..65535) s.port else if (s.protocol == "ssh") 22 else 443
            )
            fixed.takeIf {
                it.address.isNotBlank() && (it.protocol != "ssh" || it.username.isNotBlank())
            }
        }.distinctBy { it.identityKey() }
    } catch (_: Exception) {
        null
    }
}
