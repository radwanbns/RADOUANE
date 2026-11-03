package com.xraytunnel.app.core

import android.util.Log
import com.xraytunnel.app.data.Server
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * نفق OpenVPN عبر Payload + WebSocket (نفس أسلوب HTTP Custom / SSH WS).
 *
 * الفكرة:
 * 1. يتصل بالسيرفر (TCP أو عبر Proxy)
 * 2. يحقن الـ Payload (WebSocket upgrade أو أي بايلود مخصص)
 * 3. يفتح SOCKS5 محلي
 * 4. كل اتصال يمر عبر النفق بعد حقن البايلود
 *
 * ملاحظة: هذا ليس عميل OpenVPN كامل (لا يقوم بمصافحة OpenVPN الحقيقية).
 * هو طبقة Payload/WebSocket فوق الاتصال، مثل ما يفعل HTTP Custom.
 */
class OvpnTunnel(
    private val server: Server,
    private val onFatal: (String) -> Unit,
    private val onWarning: (String) -> Unit = {}
) {
    val socksUser: String = randomHex(6)
    val socksPass: String = randomHex(12)

    private val TAG = "OvpnTunnel"
    private val closed = AtomicBoolean(false)
    private var listener: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ovpn-tunnel").apply { isDaemon = true }
    }

    fun start(): Int {
        try {
            if (server.address.isBlank()) throw TunnelException("عنوان السيرفر فارغ.")
            if (server.port !in 1..65535) throw TunnelException("منفذ غير صالح.")

            // نختبر الاتصال + البايلود أولاً
            testConnection()

            val ss = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
            listener = ss
            pool.execute { acceptLoop(ss) }
            return ss.localPort
        } catch (e: Throwable) {
            close()
            throw IllegalStateException(friendlyError(e))
        }
    }

    /**
     * وضع التمرير الخام (لـ OpenVPN الحقيقي): بدون SOCKS. كل اتصال يصل إلى المنفذ المحلي يُفتح
     * له اتصال جديد بالسيرفر/البروكسي مع حقن البايلود (و TLS إن لزم) ثم تُمرَّر البايتات كما هي.
     * يرجع المنفذ المحلي الذي يجب أن يتصل به عميل OpenVPN (127.0.0.1).
     */
    fun startRawForward(): Int {
        try {
            if (server.address.isBlank()) throw TunnelException("عنوان السيرفر فارغ.")
            if (server.port !in 1..65535) throw TunnelException("منفذ غير صالح.")
            testConnection()
            val ss = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
            listener = ss
            pool.execute { acceptLoop(ss, raw = true) }
            return ss.localPort
        } catch (e: Throwable) {
            close()
            throw IllegalStateException(friendlyError(e))
        }
    }

    fun close() {
        closed.set(true)
        try { listener?.close() } catch (_: Exception) {}
        listener = null
        pool.shutdownNow()
    }

    /** يختبر أن الاتصال + البايلود ينجحان قبل فتح SOCKS */
    private fun testConnection() {
        val sock = createEndpointSocket()
        try {
            sock.close()
        } catch (_: Exception) {}
    }

    private fun acceptLoop(ss: ServerSocket, raw: Boolean = false) {
        while (!closed.get()) {
            try {
                val client = ss.accept() ?: break
                pool.execute { if (raw) handleRawClient(client) else handleSocksClient(client) }
            } catch (e: Exception) {
                if (!closed.get()) Log.e(TAG, "accept: ${e.message}")
            }
        }
    }

    /** تمرير خام: client(OpenVPN) <-> [payload/proxy/TLS] <-> السيرفر. يُغلَق الطرفان عند انتهاء أي اتجاه. */
    private fun handleRawClient(client: Socket) {
        var remote: Socket? = null
        try {
            client.tcpNoDelay = true
            val ep = createEndpoint()
            remote = ep.socket
            val done = java.util.concurrent.CountDownLatch(1)
            val cin = client.getInputStream()
            val cout = client.getOutputStream()
            Thread { try { pipe(cin, ep.output) } finally { done.countDown() } }.apply { isDaemon = true }.start()
            Thread { try { pipe(ep.input, cout) } finally { done.countDown() } }.apply { isDaemon = true }.start()
            done.await()
        } catch (e: Exception) {
            if (!closed.get()) Log.d(TAG, "raw client: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { remote?.close() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- SOCKS5 بسيط
    private fun handleSocksClient(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 30_000 // مهلة للمصافحة فقط
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // greeting
            val ver = cin.read()
            if (ver != 0x05) return
            val nMethods = cin.read()
            cin.skip(nMethods.toLong())
            // نطلب user/pass
            cout.write(byteArrayOf(0x05, 0x02))
            cout.flush()

            // auth
            if (cin.read() != 0x01) return
            val ulen = cin.read()
            val user = ByteArray(ulen).also { cin.read(it) }.toString(Charsets.UTF_8)
            val plen = cin.read()
            val pass = ByteArray(plen).also { cin.read(it) }.toString(Charsets.UTF_8)
            if (user != socksUser || pass != socksPass) {
                cout.write(byteArrayOf(0x01, 0x01))
                cout.flush()
                return
            }
            cout.write(byteArrayOf(0x01, 0x00))
            cout.flush()

            // request
            if (cin.read() != 0x05) return
            val cmd = cin.read()
            cin.read() // rsv
            val atyp = cin.read()
            val targetHost: String
            val targetPort: Int
            when (atyp) {
                0x01 -> { // IPv4
                    val ip = ByteArray(4).also { cin.read(it) }
                    targetHost = ip.joinToString(".") { (it.toInt() and 0xff).toString() }
                    targetPort = (cin.read() shl 8) or cin.read()
                }
                0x03 -> { // domain
                    val len = cin.read()
                    targetHost = ByteArray(len).also { cin.read(it) }.toString(Charsets.UTF_8)
                    targetPort = (cin.read() shl 8) or cin.read()
                }
                0x04 -> { // IPv6 - نرفضه ببساطة
                    cout.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0,0,0,0, 0,0))
                    cout.flush()
                    return
                }
                else -> return
            }

            if (cmd != 0x01) { // CONNECT only
                cout.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0))
                cout.flush()
                return
            }

            // نفتح الاتصال الحقيقي مع حقن البايلود
            val ep = createEndpoint()
            remote = ep.socket
            // بعد المصافحة: بدون مهلة حتى لا ينقطع التحميل/التصفح
            try { client.soTimeout = 0 } catch (_: Exception) {}
            try { remote.soTimeout = 0 } catch (_: Exception) {}
            // رد نجاح
            cout.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0))
            cout.flush()

            // pipe
            val t1 = Thread { pipe(cin, ep.output) }
            val t2 = Thread { pipe(ep.input, cout) }
            t1.start(); t2.start()
            t1.join(); t2.join()
        } catch (e: Exception) {
            if (!closed.get()) Log.d(TAG, "socks client: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { remote?.close() } catch (_: Exception) {}
        }
    }

    /** نتيجة الاتصال بعد حقن البايلود */
    private data class Endpoint(
        val socket: Socket,
        val input: InputStream,
        val output: OutputStream
    ) {
        fun close() {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- إنشاء الاتصال + Payload
    private fun createEndpoint(): Endpoint {
        val payloadOn = server.network == "ws" || server.payload.isNotBlank()
        val tlsOn = server.security == "tls"
        val useProxy = server.proxyHost.isNotBlank()
        val proxyPort = server.proxyPort.takeIf { it in 1..65535 } ?: 8080
        val targetPort = server.port.takeIf { it in 1..65535 }
            ?: throw TunnelException("منفذ السيرفر غير صالح (1–65535).")

        val raw = Socket()
        raw.tcpNoDelay = true
        raw.keepAlive = true
        raw.connect(
            if (useProxy) InetSocketAddress(server.proxyHost.trim(), proxyPort)
            else InetSocketAddress(server.address, targetPort),
            20_000
        )
        raw.soTimeout = 20_000

        var sock: Socket = raw
        var output: OutputStream = raw.getOutputStream()
        var input: InputStream = raw.getInputStream()

        if (useProxy) {
            // TCP → HTTP Proxy (CONNECT أو بايلود) → [TLS] → [بايلود إن وُجد]
            val pin = PushbackInputStream(raw.getInputStream(), 8192)
            // إذا كان هناك بايلود مخصص يُستخدم كطلب البروكسي، وإلا CONNECT قياسي لأي منفذ
            sendPayload(output, forProxy = true)
            readHttpResponse(pin)
            if (tlsOn) {
                sock = wrapTls(raw)
                output = sock.getOutputStream()
                input = sock.getInputStream()
            } else {
                input = pin
            }
            // إذا استُخدم CONNECT القياسي وكان هناك بايلود إضافي للنفق، أرسله بعد نجاح البروكسي
            if (payloadOn && server.payload.isNotBlank() &&
                !server.payload.contains("CONNECT", ignoreCase = true)
            ) {
                // تم إرسال CONNECT فقط؛ نرسل البايلود الآن عبر النفق
                // (إذا كان البايلود نفسه هو طلب البروكسي فقد أُرسل أعلاه)
            }
        } else {
            if (tlsOn) {
                sock = wrapTls(raw)
                output = sock.getOutputStream()
            }
            val pin = PushbackInputStream(sock.getInputStream(), 8192)
            if (payloadOn) {
                sendPayload(output, forProxy = false)
                readHttpResponse(pin)
            }
            input = pin
        }
        try { sock.soTimeout = 0 } catch (_: Exception) {}
        return Endpoint(sock, input, output)
    }

    /** للتوافق مع testConnection */
    private fun createEndpointSocket(): Socket {
        val ep = createEndpoint()
        return ep.socket
    }

    private fun wrapTls(raw: Socket): SSLSocket {
        val sni = server.sni.ifBlank { server.host }.ifBlank { server.address }
        val ctx = SSLContext.getInstance("TLS")
        if (server.insecure) {
            ctx.init(null, arrayOf<TrustManager>(TrustAll), SecureRandom())
        } else {
            ctx.init(null, null, null)
        }
        val ssl = ctx.socketFactory.createSocket(raw, sni, server.port, true) as SSLSocket
        if (!sni.matches(Regex("^[0-9.]+$")) && !sni.contains(':')) {
            val params = ssl.sslParameters
            params.serverNames = listOf<SNIServerName>(SNIHostName(sni))
            ssl.sslParameters = params
        }
        ssl.startHandshake()
        return ssl
    }

    private fun sendPayload(o: OutputStream, forProxy: Boolean) {
        val parts = Regex("\\[split\\]", RegexOption.IGNORE_CASE).split(buildPayload(forProxy))
        parts.forEachIndexed { i, part ->
            if (part.isNotEmpty()) {
                o.write(part.toByteArray(Charsets.UTF_8))
                o.flush()
            }
            if (i < parts.lastIndex) Thread.sleep(200)
        }
    }

    private fun buildPayload(forProxy: Boolean): String {
        val hostName = server.host.ifBlank { server.address }
        val hostPort = if (server.address.contains(":")) "[${server.address}]:${server.port}" else "${server.address}:${server.port}"
        val path = server.path.ifBlank { "/" }

        val custom = if (server.network == "ws" || server.payload.isNotBlank()) server.payload else ""
        var p = custom.ifBlank {
            if (forProxy) {
                "CONNECT $hostPort HTTP/1.1\r\nHost: $hostPort\r\nProxy-Connection: Keep-Alive\r\n\r\n"
            } else {
                "GET $path HTTP/1.1\r\nHost: $hostName\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: ${wsKey()}\r\nSec-WebSocket-Version: 13\r\n\r\n"
            }
        }

        p = p.replace("\r\n", "\n").replace("\n", "\r\n")
        p = p.replace("\\r\\n", "\r\n").replace("\\r", "\r").replace("\\n", "\n")

        p = tag(p, "[crlf]", "\r\n")
        p = tag(p, "[cr]", "\r")
        p = tag(p, "[lf]", "\n")
        p = tag(p, "[host]", hostName)
        p = tag(p, "[port]", server.port.toString())
        p = tag(p, "[host_port]", hostPort)
        p = tag(p, "[path]", path)
        p = tag(p, "[sni]", server.sni.ifBlank { hostName })
        p = tag(p, "[protocol]", "HTTP/1.1")
        p = tag(p, "[ua]", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
        p = tag(p, "[ws_key]", wsKey())

        return p
    }

    private fun tag(text: String, name: String, value: String): String =
        text.replace(Regex(Regex.escape(name), RegexOption.IGNORE_CASE)) { value }

    private fun wsKey(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun readHttpResponse(pin: PushbackInputStream) {
        val sb = StringBuilder()
        val buf = ByteArray(1)
        while (true) {
            val n = pin.read(buf)
            if (n <= 0) break
            sb.append(buf[0].toInt().toChar())
            val s = sb.toString()
            if (s.endsWith("\r\n\r\n") || s.endsWith("\n\n")) break
            if (sb.length > 8192) break
        }
        // ما كنتحققوش من 101، بعض السيرفرات كترجع 200 أو ما كترجعش رد واضح
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (!closed.get()) {
                val r = input.read(buffer)
                if (r == -1) break
                output.write(buffer, 0, r)
                output.flush()
            }
        } catch (_: Exception) {}
    }

    private fun friendlyError(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("timeout", true) || msg.contains("Timed out", true) ->
                "انتهت مهلة الاتصال. تحقق من العنوان والمنفذ والبايلود."
            msg.contains("Connection refused", true) ->
                "رفض الاتصال. تأكد من العنوان والمنفذ."
            msg.contains("Unable to resolve", true) || msg.contains("UnknownHost", true) ->
                "تعذر حل اسم المضيف."
            else -> msg
        }
    }

    private fun randomHex(len: Int): String {
        val b = ByteArray(len)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    class TunnelException(msg: String) : Exception(msg)

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
