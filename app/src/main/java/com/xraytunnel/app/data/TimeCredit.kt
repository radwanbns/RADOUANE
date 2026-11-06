package com.xraytunnel.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.File
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local usage credit. Primary store: SharedPreferences.
 * Secondary: encrypted file in public Downloads (survives clear-data / reinstall on many devices).
 * Also relies on Android Auto Backup when the user has Google backup enabled.
 */
object TimeCredit {
    const val REWARD_MILLIS = 60L * 60L * 1000L
    private const val PREFS = "vpn_time_credit"
    private const val KEY_REMAINING = "remaining_ms"
    private const val KEY_SEEDED = "seeded"
    /** Free credit on first install: 6 minutes. */
    private const val INITIAL_CREDIT = 6L * 60L * 1000L
    private const val MAX_CREDIT = 24L * 60L * 60L * 1000L
    private const val BACKUP_NAME = "xraytunnel_time_credit.dat"

    @Synchronized
    fun remaining(context: Context): Long {
        val p = prefs(context)
        val local = if (p.contains(KEY_REMAINING)) p.getLong(KEY_REMAINING, 0L) else -1L
        val external = readExternal(context)
        val best = when {
            local < 0L && external < 0L -> {
                // First run ever: seed 6 minutes and write both stores.
                writeAll(context, INITIAL_CREDIT)
                INITIAL_CREDIT
            }
            local < 0L -> {
                writePrefs(context, external)
                external
            }
            external < 0L -> {
                writeExternal(context, local)
                local
            }
            else -> {
                // SharedPreferences is authoritative while installed. Taking max(local, backup)
                // could resurrect stale credit after a deduction if the external write failed.
                val v = local.coerceIn(0L, MAX_CREDIT)
                if (v != external) writeExternal(context, v)
                v
            }
        }
        return best.coerceAtLeast(0L)
    }

    @Synchronized
    fun grantReward(context: Context): Long {
        val next = (remaining(context) + REWARD_MILLIS).coerceAtMost(MAX_CREDIT)
        writeAll(context, next)
        return next
    }

    /** Called once per second only by the active VPN service. */
    @Synchronized
    fun consumeSecond(context: Context): Long {
        val next = (remaining(context) - 1_000L).coerceAtLeast(0L)
        writeAll(context, next)
        return next
    }

    fun format(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0L) + 999L) / 1_000L
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, secs)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun writePrefs(context: Context, value: Long) {
        prefs(context).edit()
            .putLong(KEY_REMAINING, value.coerceAtLeast(0L))
            .putBoolean(KEY_SEEDED, true)
            .commit()
    }

    private fun writeAll(context: Context, value: Long) {
        val v = value.coerceIn(0L, MAX_CREDIT)
        writePrefs(context, v)
        writeExternal(context, v)
    }

    private fun key(context: Context): ByteArray {
        val id = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "xraytunnel"
        } catch (_: Exception) {
            "xraytunnel"
        }
        val seed = (id + "|xraytunnel-time-v1").toByteArray(Charsets.UTF_8)
        val out = ByteArray(32)
        for (i in out.indices) out[i] = seed[i % seed.size]
        return out
    }

    private fun encrypt(context: Context, value: Long): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val id = key(context)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(id, "AES"), GCMParameterSpec(128, nonce))
        val plain = ByteBuffer.allocate(8).putLong(value).array()
        return nonce + cipher.doFinal(plain)
    }

    private fun decrypt(context: Context, data: ByteArray): Long {
        if (data.size < 13) return -1L
        val nonce = data.copyOfRange(0, 12)
        val ct = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(context), "AES"), GCMParameterSpec(128, nonce))
        val plain = cipher.doFinal(ct)
        return ByteBuffer.wrap(plain).long
    }

    private fun writeExternal(context: Context, value: Long) {
        try {
            val payload = Base64.encodeToString(encrypt(context, value), Base64.NO_WRAP)
            // 1) App-specific external (cleared on uninstall, helps clear-data if not wiped)
            context.getExternalFilesDir(null)?.let { dir ->
                File(dir, BACKUP_NAME).writeText(payload, Charsets.UTF_8)
            }
            // 2) Public Downloads via MediaStore (often survives reinstall)
            writeMediaStore(context, payload)
            // 3) Legacy public Downloads on older devices
            if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (dir != null) {
                    if (!dir.exists()) dir.mkdirs()
                    File(dir, BACKUP_NAME).writeText(payload, Charsets.UTF_8)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun writeMediaStore(context: Context, payload: String) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val resolver = context.contentResolver
            // Update existing
            val existing = findMediaUri(context)
            if (existing != null) {
                resolver.openOutputStream(existing, "wt")?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                return
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            resolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (_: Exception) {
        }
    }

    private fun findMediaUri(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val sel = "${MediaStore.Downloads.DISPLAY_NAME}=?"
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                sel,
                arrayOf(BACKUP_NAME),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** @return remaining ms, or -1 if missing. */
    private fun readExternal(context: Context): Long {
        try {
            context.getExternalFilesDir(null)?.let { dir ->
                val f = File(dir, BACKUP_NAME)
                if (f.isFile) {
                    val v = decodePayload(context, f.readText(Charsets.UTF_8))
                    if (v >= 0L) return v
                }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = findMediaUri(context) ?: return -1L
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val text = input.readBytes().toString(Charsets.UTF_8)
                    val v = decodePayload(context, text)
                    if (v >= 0L) return v
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val f = File(dir, BACKUP_NAME)
                if (f.isFile) {
                    val v = decodePayload(context, f.readText(Charsets.UTF_8))
                    if (v >= 0L) return v
                }
            }
        } catch (_: Exception) {
        }
        return -1L
    }

    private fun decodePayload(context: Context, text: String): Long {
        return try {
            val data = Base64.decode(text.trim(), Base64.NO_WRAP)
            decrypt(context, data).coerceIn(0L, MAX_CREDIT)
        } catch (_: Exception) {
            -1L
        }
    }
}
