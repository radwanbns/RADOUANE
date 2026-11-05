package com.xraytunnel.app.core

import android.content.Context
import com.xraytunnel.app.data.Server
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * SlowDNS = نفق dnstt (بروتوكول Noise + KCP + smux فوق استعلامات DNS TXT).
 *
 * التنفيذ: عميل dnstt الأصلي (Go) مضمَّن كملف تنفيذي باسم `libdnstt.so` داخل jniLibs
 * (يبنيه build.yml)، فيستخرجه النظام إلى nativeLibraryDir ويصبح قابلاً للتشغيل.
 * يستمع العميل على 127.0.0.1:<منفذ محلي>، وكل اتصال TCP إليه يصبح stream إلى سيرفر dnstt
 * الذي يمرّره إلى sshd. فيتصل JSch بهذا المنفذ المحلي كأنه سيرفر SSH عادي.
 *
 * العملية تعمل بنفس UID التطبيق، لذلك تُستثنى من الـ VPN (addDisallowedApplication) ولا تدخل حلقة.
 */
internal class SlowDnsClient(
    private val context: Context,
    private val server: Server
) {
    @Volatile private var proc: Process? = null
    @Volatile var localPort: Int = 0
        private set

    /** يشغّل العملية ويرجع المنفذ المحلي. يرمي IllegalStateException برسالة مفهومة عند الفشل. */
    @Synchronized
    fun start(): Int {
        validate()
        val bin = File(context.applicationInfo.nativeLibraryDir, BIN_NAME)
        if (!bin.exists()) {
            throw IllegalStateException(
                "SlowDNS core ($BIN_NAME) is not included in this build for this device's CPU. " +
                    "Rebuild the app with the dnstt step enabled (see README)."
            )
        }
        if (!bin.canExecute()) bin.setExecutable(true)

        val port = freePort()
        val cmd = ArrayList<String>()
        cmd += bin.absolutePath
        cmd += resolverArgs(server.dnsResolver.trim().ifBlank { DEFAULT_RESOLVER })
        cmd += listOf("-pubkey", server.dnsKey.trim().lowercase())
        cmd += server.dnsNs.trim().trimEnd('.')
        cmd += "127.0.0.1:$port"

        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw IllegalStateException("Could not start the SlowDNS core: ${e.message ?: e.javaClass.simpleName}")
        }
        proc = p
        localPort = port

        // قراءة مخرجات العملية (تمنع امتلاء الأنبوب كذلك). لا نكشف تفاصيل السيرفرات المقفلة.
        val hide = server.locked || server.isManaged
        val reader = Thread({
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (!hide) ConnLog.add(ConnLog.Level.DEBUG, "dnstt: ${line.take(300)}")
                    }
                }
            } catch (_: Exception) {
            }
        }, "slowdns-log")
        reader.isDaemon = true
        reader.start()

        // إن كانت المعاملات خاطئة يخرج dnstt فوراً؛ ننتظر قليلاً لنلتقط ذلك بدل انتظار مهلة SSH.
        val deadline = System.currentTimeMillis() + STARTUP_GRACE_MS
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) {
                throw IllegalStateException("The SlowDNS core exited immediately (exit code ${p.exitValue()}). Check NS domain, public key and resolver.")
            }
            Thread.sleep(100)
        }
        return port
    }

    /** يعيد تشغيل العملية إن كانت قد ماتت (يُستدعى قبل كل إعادة اتصال SSH). */
    @Synchronized
    fun ensureRunning() {
        val p = proc
        if (p == null || !p.isAlive) {
            ConnLog.add(ConnLog.Level.WARN, "SlowDNS core stopped — restarting it")
            start()
        }
    }

    @Synchronized
    fun stop() {
        val p = proc ?: return
        proc = null
        try { p.destroy() } catch (_: Exception) {}
        // destroy() = SIGTERM؛ نضمن الإنهاء إن تأخّر.
        Thread {
            try {
                Thread.sleep(500)
                if (p.isAlive) p.destroyForcibly()
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------------ helpers

    private fun validate() {
        val ns = server.dnsNs.trim().trimEnd('.')
        if (ns.isEmpty() || !NS_RE.matches(ns)) {
            throw IllegalStateException("SlowDNS: invalid NS domain.")
        }
        if (!KEY_RE.matches(server.dnsKey.trim())) {
            throw IllegalStateException("SlowDNS: invalid public key (expected 64 hex characters).")
        }
    }

    /** resolver → خيارات dnstt: https://… = DoH ، tls://host[:853] = DoT ، غير ذلك = UDP عادي (ip[:53]). */
    private fun resolverArgs(r: String): List<String> {
        val v = r.trim()
        return when {
            v.startsWith("https://", true) -> listOf("-doh", v)
            v.startsWith("tls://", true) || v.startsWith("dot://", true) -> {
                val hp = v.substringAfter("://")
                listOf("-dot", if (hp.contains(":")) hp else "$hp:853")
            }
            else -> {
                val clean = v.removePrefix("udp://")
                // IPv6 بين أقواس أو عنوان بلا منفذ
                val withPort = when {
                    clean.startsWith("[") && clean.contains("]:") -> clean
                    clean.startsWith("[") -> "$clean:53"
                    clean.count { it == ':' } > 1 -> "[$clean]:53" // IPv6 خام
                    clean.contains(":") -> clean
                    else -> "$clean:53"
                }
                listOf("-udp", withPort)
            }
        }
    }

    private fun freePort(): Int {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { return it.localPort }
    }

    companion object {
        const val BIN_NAME = "libdnstt.so"
        const val DEFAULT_RESOLVER = "8.8.8.8:53"
        private const val STARTUP_GRACE_MS = 800L
        private val KEY_RE = Regex("^[0-9a-fA-F]{64}$")
        private val NS_RE = Regex("^(?=.{1,253}$)([A-Za-z0-9_]([A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?\\.)+[A-Za-z]{2,63}$")
    }
}
