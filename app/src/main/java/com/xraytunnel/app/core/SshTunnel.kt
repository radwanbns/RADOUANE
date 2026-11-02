package com.xraytunnel.app.core

import android.annotation.SuppressLint
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.xraytunnel.app.data.Server
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * نفق SSH: يتصل بالسيرفر (مباشرة / TLS / WebSocket + بايلود / WebSocket + TLS) على أي منفذ،
 * ثم يفتح SOCKS5 محلياً (127.0.0.1، بكلمة سر عشوائية) يحوّل كل اتصال إلى قناة direct-tcpip داخل SSH.
 * نواة Xray تستعمل هذا SOCKS كمخرج، فيبقى مسار TUN كما هو لـ V2Ray.
 */
class SshTunnel(
    private val server: Server,
    private val onFatal: (String) -> Unit,
    private val onWarning: (String) -> Unit = {}
) {
    val socksUser: String = randomHex(6)
    val socksPass: String = randomHex(12)

    @Volatile private var session: Session? = null
    @Volatile private var closed = false
    @Volatile private var warned = false
    private var listener: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ssh-tunnel").apply { isDaemon = true }
    }

    /** يتصل بـ SSH (متزامن) ثم يشغّل SOCKS المحلي. يرجع المنفذ المحلي. */
    fun start(): Int {
        try {
            if (server.username.isBlank()) throw TunnelException("اسم مستخدم SSH فارغ.")
            if (server.address.isBlank()) throw TunnelException("عنوان سيرفر SSH فارغ.")
            if (server.port !in 1..65535) throw TunnelException("منفذ غير صالح.")
            session = connectSession()
            val ss = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
            listener = ss
            pool.execute { acceptLoop(ss) }
            pool.execute { superviseLoop() }
            return ss.localPort
        } catch (e: Throwable) {
            val msg = friendlyError(server, e)
            close()
            throw IllegalStateException(msg)
        }
    }

    fun close() {
        closed = true
        try { listener?.close() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------- SSH session

    private fun connectSession(): Session {
        val jsch = JSch()
        val s = jsch.getSession(server.username, server.address, server.port)
        s.setPassword(server.secret)
        s.setProxy(EndpointProxy(server))
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        // الأقوى أولاً، ثم الخوارزميات القديمة لأن كثيراً من سيرفرات SSH المجانية قديمة.
        s.setConfig("kex", KEX)
        s.setConfig("server_host_key", HOST_KEYS)
        s.setServerAliveInterval(15_000)
        s.setServerAliveCountMax(3)
        s.connect(CONNECT_TIMEOUT_MS)
        return s
    }

    /** يراقب الجلسة ويعيد الاتصال بها تلقائياً (المنفذ المحلي وبيانات SOCKS ثابتة فلا يتأثر TUN). */
    private fun superviseLoop() {
        try {
            while (!closed) {
                Thread.sleep(2_000)
                if (closed) return
                val s = session
                if (s != null && s.isConnected) continue

                var lastError = "انقطع اتصال SSH."
                var ok = false
                for (attempt in 1..RECONNECT_ATTEMPTS) {
                    if (closed) return
                    try {
                        try { session?.disconnect() } catch (_: Exception) {}
                        session = connectSession()
                        ok = true
                        break
                    } catch (e: Throwable) {
                        lastError = friendlyError(server, e)
                        Thread.sleep(3_000L * attempt)
                    }
                }
                if (!ok) {
                    if (!closed) onFatal(lastError)
                    return
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    // ---------------------------------------------------------------- local SOCKS5

    private fun acceptLoop(ss: ServerSocket) {
        while (!closed) {
            val c = try { ss.accept() } catch (_: Exception) { return }
            try {
                pool.execute { handleClient(c) }
            } catch (_: Exception) {
                try { c.close() } catch (_: Exception) {}
                return
            }
        }
    }

    private fun handleClient(c: Socket) {
        var ch: ChannelDirectTCPIP? = null
        try {
            c.tcpNoDelay = true
            c.soTimeout = 10_000
            val cin = DataInputStream(BufferedInputStream(c.getInputStream()))
            val cout = c.getOutputStream()

            // 1) تحية SOCKS5: نطلب user/pass حتى لا يستعمل المنفذ تطبيق آخر في الهاتف.
            if (cin.readUnsignedByte() != 5) return
            val n = cin.readUnsignedByte()
            val methods = ByteArray(n)
            cin.readFully(methods)
            if (!methods.contains(2.toByte())) {
                cout.write(byteArrayOf(5, -1)); cout.flush(); return
            }
            cout.write(byteArrayOf(5, 2)); cout.flush()

            // 2) مصادقة RFC 1929
            if (cin.readUnsignedByte() != 1) return
            val ub = ByteArray(cin.readUnsignedByte()).also { cin.readFully(it) }
            val pb = ByteArray(cin.readUnsignedByte()).also { cin.readFully(it) }
            val authOk = String(ub, Charsets.UTF_8) == socksUser && String(pb, Charsets.UTF_8) == socksPass
            cout.write(byteArrayOf(1, if (authOk) 0 else 1)); cout.flush()
            if (!authOk) return

            // 3) الطلب
            if (cin.readUnsignedByte() != 5) return
            val cmd = cin.readUnsignedByte()
            cin.readUnsignedByte() // RSV
            val host: String = when (cin.readUnsignedByte()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { cin.readFully(it) }).hostAddress ?: return
                3 -> String(ByteArray(cin.readUnsignedByte()).also { cin.readFully(it) }, Charsets.UTF_8)
                4 -> InetAddress.getByAddress(ByteArray(16).also { cin.readFully(it) }).hostAddress ?: return
                else -> { reply(cout, 8); return }
            }
            val port = cin.readUnsignedShort()
            if (cmd != 1) { reply(cout, 7); return } // CONNECT فقط (لا UDP عبر SSH)

            val s = session
            if (s == null || !s.isConnected) { reply(cout, 1); return }

            val channel = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
            ch = channel
            channel.setHost(host)
            channel.setPort(port)
            channel.setOrgIPAddress("127.0.0.1")
            channel.setOrgPort(0)
            val chIn = channel.inputStream
            val chOut = channel.outputStream
            try {
                channel.connect(CHANNEL_TIMEOUT_MS)
            } catch (e: Exception) {
                if (!warned && (e.message ?: "").contains("administratively prohibited", true)) {
                    warned = true
                    onWarning("سيرفر SSH يمنع تحويل المنافذ (AllowTcpForwarding)؛ لن يعمل الإنترنت عبره.")
                }
                reply(cout, 5)
                return
            }

            reply(cout, 0)
            c.soTimeout = 0

            pool.execute {
                pump(chIn, cout)
                try { c.close() } catch (_: Exception) {}
            }
            pump(cin, chOut)
        } catch (_: Throwable) {
        } finally {
            try { ch?.disconnect() } catch (_: Exception) {}
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val CHANNEL_TIMEOUT_MS = 15_000
        private const val RECONNECT_ATTEMPTS = 5

        private const val KEX =
            "curve25519-sha256,curve25519-sha256@libssh.org,ecdh-sha2-nistp256,ecdh-sha2-nistp384," +
            "ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512," +
            "diffie-hellman-group14-sha256,diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1," +
            "diffie-hellman-group1-sha1"

        private const val HOST_KEYS =
            "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
            "rsa-sha2-512,rsa-sha2-256,ssh-rsa,ssh-dss"

        fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        /** رسالة خطأ مفهومة للمستخدم (لا تتضمن كلمة المرور ولا البايلود). */
        fun friendlyError(server: Server, e: Throwable): String {
            var root: Throwable = e
            while (root.cause != null && root.cause !== root) root = root.cause!!
            val all = "${e.message.orEmpty()} ${root.message.orEmpty()}"
            // سيرفرات الملفات المقفلة وسيرفرات التحديث تبقى تفاصيلها مخفية حتى في رسائل الخطأ.
            val hide = server.locked || server.isManaged
            return when {
                root is TunnelException -> root.message.orEmpty()
                all.contains("Auth fail", true) || all.contains("Auth cancel", true) ->
                    "فشل تسجيل الدخول SSH: اسم المستخدم أو كلمة المرور غير صحيحة."
                root is UnknownHostException ->
                    if (hide) "تعذر الوصول إلى عنوان السيرفر." else "تعذر الوصول إلى عنوان السيرفر: ${server.address}"
                root is ConnectException || all.contains("refused", true) ->
                    if (hide) "رفض السيرفر الاتصال." else "رفض السيرفر الاتصال على المنفذ ${server.port}."
                root is SocketTimeoutException || all.contains("timeout", true) || all.contains("timed out", true) ->
                    "انتهت مهلة الاتصال بسيرفر SSH. تحقق من العنوان والمنفذ والبايلود."
                root is SSLException ->
                    if (hide) "فشل TLS: تعذر إكمال المصافحة." else "فشل TLS: ${root.message ?: "تعذر إكمال المصافحة"}"
                all.contains("Algorithm negotiation fail", true) ->
                    "فشل التفاوض على خوارزميات SSH (السيرفر غير متوافق)."
                all.contains("invalid server's version string", true) || all.contains("Connection reset", true) ||
                    all.contains("End of IO Stream", true) ->
                    "السيرفر لم يرد ببروتوكول SSH. تأكد من النمط (TCP/WS) والبايلود والمنفذ."
                hide -> "فشل اتصال SSH (${root.javaClass.simpleName})."
                else -> e.message ?: e.javaClass.simpleName
            }
        }
    }
}

private class TunnelException(message: String) : IOException(message)

@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
private object TrustAll : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * يبني الوصلة الخام التي يمر عليها SSH:
 * TCP → (TLS + SNI اختياري) → (بايلود HTTP/WebSocket اختياري) → بايتات SSH الخام.
 */
private class EndpointProxy(private val s: Server) : com.jcraft.jsch.Proxy {
    private var socket: Socket? = null
    private var inp: InputStream? = null
    private var out: OutputStream? = null

    override fun connect(
        socketFactory: com.jcraft.jsch.SocketFactory?,
        host: String?,
        port: Int,
        timeout: Int
    ) {
        val t = if (timeout > 0) timeout else 15_000
        val raw = Socket()
        socket = raw
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(s.address, s.port), t)
        raw.soTimeout = t

        var sock: Socket = raw
        if (s.security == "tls") {
            sock = tls(raw)
            socket = sock
        }

        var input: InputStream = sock.getInputStream()
        val output: OutputStream = sock.getOutputStream()

        if (s.network == "ws") {
            val pin = PushbackInputStream(input, 8192)
            sendPayload(output)
            readHandshake(pin)
            input = pin
        }

        inp = input
        out = output
    }

    override fun getInputStream(): InputStream = inp ?: throw IOException("not connected")
    override fun getOutputStream(): OutputStream = out ?: throw IOException("not connected")
    override fun getSocket(): Socket? = socket
    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    // ---------------------------------------------------------------- TLS

    private fun sniHost(): String =
        s.sni.ifBlank { s.host.substringBefore(":") }.ifBlank { s.address }

    private fun isIp(h: String) = h.contains(':') || Regex("^[0-9.]+$").matches(h)

    private fun tls(raw: Socket): SSLSocket {
        val sni = sniHost()
        val ctx = SSLContext.getInstance("TLS")
        if (s.insecure) ctx.init(null, arrayOf<TrustManager>(TrustAll), SecureRandom()) else ctx.init(null, null, null)

        val ssl = ctx.socketFactory.createSocket(raw, sni, s.port, true) as SSLSocket
        val params = ssl.sslParameters
        if (!isIp(sni)) params.setServerNames(listOf<SNIServerName>(SNIHostName(sni)))
        ssl.sslParameters = params
        ssl.startHandshake()

        if (!s.insecure && !HttpsURLConnection.getDefaultHostnameVerifier().verify(sni, ssl.session)) {
            try { ssl.close() } catch (_: Exception) {}
            throw SSLPeerUnverifiedException("شهادة الخادم لا تطابق اسم SNI (فعّل السماح بالشهادات غير الموثوقة إن لزم).")
        }
        return ssl
    }

    // ---------------------------------------------------------------- payload

    private fun sendPayload(o: OutputStream) {
        val parts = Regex("\\[split\\]", RegexOption.IGNORE_CASE).split(buildPayload())
        parts.forEachIndexed { i, part ->
            if (part.isNotEmpty()) {
                o.write(part.toByteArray(Charsets.UTF_8))
                o.flush()
            }
            if (i < parts.lastIndex) Thread.sleep(200)
        }
    }

    private fun tag(text: String, name: String, value: String): String =
        text.replace(Regex(Regex.escape(name), RegexOption.IGNORE_CASE)) { value }

    private fun buildPayload(): String {
        val hostName = s.host.ifBlank { s.address }
        val hostPort = if (hostName.contains(":")) hostName else "$hostName:${s.port}"

        var p = s.payload.ifBlank { DEFAULT_PAYLOAD }
        // أسطر حقيقية → CRLF، ثم تسلسلات \r\n المكتوبة نصاً.
        p = p.replace("\r\n", "\n").replace("\n", "\r\n")
        p = p.replace("\\r\\n", "\r\n").replace("\\r", "\r").replace("\\n", "\n")

        p = tag(p, "[crlf]", "\r\n")
        p = tag(p, "[cr]", "\r")
        p = tag(p, "[lf]", "\n")
        p = tag(p, "[host_port]", hostPort)
        p = tag(p, "[host]", hostName)
        p = tag(p, "[port]", s.port.toString())
        p = tag(p, "[path]", s.path.ifBlank { "/" })
        p = tag(p, "[sni]", sniHost())
        p = tag(p, "[protocol]", "HTTP/1.1")
        p = tag(p, "[ua]", USER_AGENT)
        p = tag(p, "[ws_key]", wsKey())
        return p
    }

    private fun wsKey(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    }

    /**
     * يقرأ ترويسة رد HTTP بايتاً بايتاً (دون قراءة زائدة من بايتات SSH) ويقبل 1xx/2xx.
     * الرد المكرر (200 ثم 101) يتخطاه JSch لأنه يتجاهل أي أسطر قبل "SSH-".
     */
    private fun readHandshake(pin: PushbackInputStream) {
        val sb = StringBuilder()
        while (sb.length < 16_384) {
            val b = pin.read()
            if (b < 0) throw TunnelException("أغلق السيرفر الاتصال بعد إرسال البايلود.")
            sb.append(b.toChar())

            // السيرفر يرد مباشرة ببروتوكول SSH بلا HTTP: أعد البايتات ودع JSch يكمل.
            if (sb.length == 4 && sb.toString() == "SSH-") {
                pin.unread(sb.toString().toByteArray(Charsets.ISO_8859_1))
                return
            }

            val n = sb.length
            val endCrlf = n >= 4 && sb[n - 4] == '\r' && sb[n - 3] == '\n' && sb[n - 2] == '\r' && sb[n - 1] == '\n'
            val endLf = n >= 2 && sb[n - 2] == '\n' && sb[n - 1] == '\n'
            if (endCrlf || endLf) break
        }

        val first = sb.lineSequence().firstOrNull().orEmpty().trim()
        val code = Regex("^HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})").find(first)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw TunnelException("رد غير متوقع من السيرفر على البايلود: ${first.take(80)}")
        if (code !in 100..299) {
            throw TunnelException("رفض السيرفر البايلود: ${first.take(80)}")
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private const val DEFAULT_PAYLOAD =
            "GET [path] [protocol][crlf]Host: [host][crlf]Upgrade: websocket[crlf]" +
            "Connection: Upgrade[crlf]Sec-WebSocket-Key: [ws_key][crlf]Sec-WebSocket-Version: 13[crlf][crlf]"
    }
}
