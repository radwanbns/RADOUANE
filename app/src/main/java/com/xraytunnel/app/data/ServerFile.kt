package com.xraytunnel.app.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ملف مشاركة السيرفرات (.xtun) — مثل ملفات NPV Tunnel:
 * المحتوى مشفّر دائماً (AES-GCM):
 *  - مقفل: يُستورد كسيرفر مخفي التفاصيل، لا يُعدَّل ولا يُصدَّر مجدداً (يمكن حذفه فقط).
 *  - غير مقفل: يُستورد كسيرفر عادي قابل للتعديل والمشاركة.
 */
object ServerFile {
    const val EXTENSION = "xtun"
    private const val FORMAT = "xray-tunnel"
    private const val VERSION = 1
    private const val MAX_BYTES = 2 * 1024 * 1024

    private val key: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256").digest("XrayTunnel/server-file/v1".toByteArray(Charsets.UTF_8))
    }

    /** يبني نص الملف (مشفّر دائماً). علامة القفل داخل الجزء المشفّر فلا يمكن تبديلها بتعديل الملف. */
    fun export(servers: List<Server>, locked: Boolean): String {
        val arr = JSONArray()
        servers.filter { it.isEditable }.forEach { s ->
            arr.put(s.toJson().apply { remove("id"); remove("subId"); remove("locked") })
        }
        val payload = JSONObject().put("locked", locked).put("servers", arr)
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("data", encrypt(payload.toString()))
            .toString()
    }

    /** يقرأ ملفاً مستورداً. يرجع null إن لم يكن بصيغة .xtun (ليجرّب المستدعي الصيغ النصية الأخرى). */
    fun parse(text: String): List<Server>? {
        if (text.length > MAX_BYTES) return null
        val root = try { JSONObject(text.trim()) } catch (_: Exception) { return null }
        if (root.optString("format") != FORMAT) return null
        val payload = try { JSONObject(decrypt(root.getString("data"))) } catch (_: Exception) { return emptyList() }
        val locked = payload.optBoolean("locked", true)
        val arr = payload.optJSONArray("servers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                Server.fromJson(arr.getJSONObject(i)).copy(
                    id = java.util.UUID.randomUUID().toString(),
                    subId = "",
                    locked = locked
                ).takeIf { it.address.isNotBlank() }
            } catch (_: Exception) { null }
        }
    }

    fun fileName(servers: List<Server>): String {
        val base = if (servers.size == 1) servers[0].name else "servers"
        val safe = base.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_').take(40).ifBlank { "servers" }
        return "$safe.$EXTENSION"
    }

    private fun encrypt(plain: String): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return Base64.encodeToString(nonce + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(data: String): String {
        val raw = Base64.decode(data, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }
}
