package com.xraytunnel.app.core

import com.jcraft.jsch.ChannelDirectTCPIP
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * UDP عبر SSH بأسلوب badvpn-udpgw (نفس ما تستعمله تطبيقات SSH الأخرى).
 *
 * يفتح قناة SSH واحدة `direct-tcpip` إلى 127.0.0.1:<udpgwPort> على السيرفر (حيث يعمل badvpn-udpgw)
 * ثم يضمّن فيها كل حزم UDP لكل الاتصالات (multiplexing) عبر معرّف اتصال conid.
 *
 * صيغة الإطار (كل الأرقام صغيرة-النهاية LE إلا العنوان والمنفذ فهما بترتيب الشبكة):
 *   [len:2 LE] [flags:1] [conid:2 LE] [ip:4 | ip6:16] [port:2 BE] [payload...]
 * حيث len = طول كل ما بعده. flags: 1=KEEPALIVE، 2=REBIND، 4=DNS، 8=IPV6.
 */
internal class UdpGwMux(
    /** يجهّز قناة direct-tcpip إلى (host, port) على السيرفر من الجلسة الحالية، دون أن يوصلها. */
    private val newChannel: (host: String, port: Int) -> ChannelDirectTCPIP,
    private val port: Int,
    private val onProblem: (String) -> Unit
) {
    fun interface Listener {
        fun onPacket(srcIp: ByteArray, srcPort: Int, data: ByteArray)
    }

    private class Link(val ch: ChannelDirectTCPIP, val input: InputStream, val out: OutputStream)

    private val listeners = ConcurrentHashMap<Int, Listener>()
    private val linkLock = Any()
    private val writeLock = Any()
    private val idLock = Any()
    private var nextId = 1

    @Volatile private var link: Link? = null
    @Volatile private var closed = false
    @Volatile private var warned = false
    /** بعد فشل فتح القناة لا نعيد المحاولة قبل هذا الوقت (حتى لا تفتح كل حزمة UDP قناة جديدة). */
    @Volatile private var retryAt = 0L

    /** يحجز conid جديداً مربوطاً بهذا المستمع (الردود القادمة بهذا المعرّف تذهب إليه). -1 إن امتلأت المعرّفات. */
    fun allocate(l: Listener): Int = synchronized(idLock) {
        repeat(MAX_CONID) {
            val id = nextId
            nextId = if (nextId >= MAX_CONID) 1 else nextId + 1
            if (listeners.putIfAbsent(id, l) == null) return id
        }
        -1
    }

    fun release(conId: Int) {
        listeners.remove(conId)
    }

    /** يرسل حزمة UDP إلى (ip:port). ip بطول 4 (IPv4) أو 16 (IPv6). يرجع false إن تعذّر الإرسال. */
    fun send(conId: Int, ip: ByteArray, dstPort: Int, data: ByteArray, off: Int, len: Int): Boolean {
        if (closed) return false
        val v6 = ip.size == 16
        if (!v6 && ip.size != 4) return false
        val addrLen = ip.size
        val bodyLen = 1 + 2 + addrLen + 2 + len
        if (bodyLen > 0xFFFF) return false

        val buf = ByteArray(2 + bodyLen)
        buf[0] = (bodyLen and 0xFF).toByte()
        buf[1] = ((bodyLen shr 8) and 0xFF).toByte()
        buf[2] = (if (v6) FLAG_IPV6 else 0).toByte()
        buf[3] = (conId and 0xFF).toByte()
        buf[4] = ((conId shr 8) and 0xFF).toByte()
        System.arraycopy(ip, 0, buf, 5, addrLen)
        buf[5 + addrLen] = ((dstPort shr 8) and 0xFF).toByte()
        buf[6 + addrLen] = (dstPort and 0xFF).toByte()
        System.arraycopy(data, off, buf, 7 + addrLen, len)

        val l = ensureLink() ?: return false
        return try {
            synchronized(writeLock) {
                l.out.write(buf)
                l.out.flush()
            }
            true
        } catch (_: Throwable) {
            dropLink(l)
            false
        }
    }

    /** يحاول فتح قناة UDPGW الآن (للتحقق المبكر من أن السيرفر يشغّل badvpn-udpgw). */
    fun probe(): Boolean = ensureLink() != null

    fun close() {
        closed = true
        listeners.clear()
        link?.let { dropLink(it) }
    }

    // ------------------------------------------------------------------ internals

    private fun ensureLink(): Link? {
        link?.let { return it }
        synchronized(linkLock) {
            link?.let { return it }
            if (closed) return null
            if (System.currentTimeMillis() < retryAt) return null
            return try {
                val ch = newChannel("127.0.0.1", port)
                val input = ch.inputStream
                val out = ch.outputStream
                ch.connect(CONNECT_TIMEOUT_MS)
                val l = Link(ch, input, out)
                link = l
                val t = Thread({ readLoop(l) }, "udpgw-reader")
                t.isDaemon = true
                t.start()
                l
            } catch (e: Throwable) {
                retryAt = System.currentTimeMillis() + RETRY_BACKOFF_MS
                if (!closed) reportProblem(e)
                null
            }
        }
    }

    private fun reportProblem(e: Throwable) {
        if (warned) return
        warned = true
        val m = e.message.orEmpty()
        onProblem(
            when {
                m.contains("administratively prohibited", true) ->
                    "UDPGW: the SSH server forbids port forwarding, so UDP cannot work."
                else ->
                    "UDPGW is not reachable on the server (127.0.0.1:$port). " +
                        "Run badvpn-udpgw on the server or change the UDPGW port; UDP will not work until then."
            }
        )
    }

    private fun dropLink(l: Link) {
        synchronized(linkLock) {
            if (link === l) link = null
        }
        try { l.ch.disconnect() } catch (_: Exception) {}
    }

    private fun readLoop(l: Link) {
        val din = DataInputStream(BufferedInputStream(l.input, 32 * 1024))
        try {
            while (!closed) {
                val lo = din.read()
                if (lo < 0) break
                val hi = din.read()
                if (hi < 0) break
                val len = lo or (hi shl 8)
                val body = ByteArray(len)
                din.readFully(body)
                if (len < 3) continue

                val flags = body[0].toInt() and 0xFF
                if (flags and FLAG_KEEPALIVE != 0) continue
                val conId = (body[1].toInt() and 0xFF) or ((body[2].toInt() and 0xFF) shl 8)
                val addrLen = if (flags and FLAG_IPV6 != 0) 16 else 4
                val hdr = 3 + addrLen + 2
                if (len < hdr) continue

                val ip = body.copyOfRange(3, 3 + addrLen)
                val srcPort = ((body[3 + addrLen].toInt() and 0xFF) shl 8) or (body[4 + addrLen].toInt() and 0xFF)
                val data = body.copyOfRange(hdr, len)
                try {
                    listeners[conId]?.onPacket(ip, srcPort, data)
                } catch (_: Throwable) {
                }
            }
        } catch (_: IOException) {
        } catch (_: Throwable) {
        } finally {
            // سيُعاد فتح القناة تلقائياً عند أول إرسال تالٍ (مثلاً بعد إعادة اتصال SSH).
            dropLink(l)
        }
    }

    companion object {
        private const val FLAG_KEEPALIVE = 1
        private const val FLAG_IPV6 = 8
        private const val MAX_CONID = 65535
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val RETRY_BACKOFF_MS = 5_000L
    }
}
