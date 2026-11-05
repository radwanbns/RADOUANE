package com.xraytunnel.app.core

import android.annotation.SuppressLint
import android.content.Context
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.xraytunnel.app.data.Server
import java.io.BufferedInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
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
 *
 * إضافات:
 *  - SlowDNS: يشغّل عميل dnstt الأصلي ويتصل SSH بمنفذه المحلي بدل السيرفر مباشرة (network = "slowdns").
 *  - UDP: عند server.udpgw يدعم SOCKS المحلي أمر UDP ASSOCIATE ويمرّر الحزم عبر UDPGW داخل SSH،
 *    وهو يعمل في كل الأنماط (TCP / TLS / Proxy / Payload / SlowDNS).
 */
class SshTunnel(
    private val server: Server,
    private val onFatal: (String) -> Unit,
    private val onWarning: (String) -> Unit = {},
    private val appContext: Context? = null
) {
    val socksUser: String = randomHex(6)
    val socksPass: String = randomHex(12)

    @Volatile private var session: Session? = null
    @Volatile private var closed = false
    @Volatile private var warned = false
    private var slowDns: SlowDnsClient? = null
    private var udpGw: UdpGwMux? = null
    private var listener: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ssh-tunnel").apply { isDaemon = true }
    }

    /** يتصل بـ SSH (متزامن) ثم يشغّل SOCKS المحلي. يرجع المنفذ المحلي. */
    fun start(): Int {
        try {
            if (server.username.isBlank()) throw TunnelException("SSH username is empty.")
            if (sshHost().isBlank()) throw TunnelException("SSH server address is empty.")
            if (server.port !in 1..65535) throw TunnelException("Invalid port.")
            if (server.isSlowDns) {
                val ctx = appContext ?: throw TunnelException("SlowDNS needs an application context.")
                ConnLog.add(ConnLog.Level.INFO, "Starting SlowDNS core (dnstt)…")
                val sd = SlowDnsClient(ctx, server)
                slowDns = sd
                try {
                    sd.start()
                } catch (e: IllegalStateException) {
                    throw TunnelException(e.message ?: "SlowDNS failed to start.")
                }
                ConnLog.add(ConnLog.Level.INFO, "SlowDNS core is running; opening SSH over DNS (this can take several seconds)…")
            }
            session = connectSession()
            if (server.udpgw) {
                udpGw = UdpGwMux({ h, p -> openDirect(h, p) }, server.udpgwPort.coerceIn(1, 65535), onWarning)
                ConnLog.add(ConnLog.Level.INFO, "UDP over SSH enabled (UDPGW 127.0.0.1:${server.udpgwPort.coerceIn(1, 65535)})")
            }
            val ss = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
            listener = ss
            pool.execute { acceptLoop(ss) }
            pool.execute { superviseLoop() }
            // تحقق مبكر: هل يعمل badvpn-udpgw على السيرفر؟ (تحذير فقط، لا يوقف الاتصال)
            udpGw?.let { gw ->
                pool.execute {
                    if (gw.probe()) ConnLog.add(ConnLog.Level.OK, "UDPGW reachable — UDP is available through the tunnel")
                }
            }
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
        try { udpGw?.close() } catch (_: Exception) {}
        udpGw = null
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        try { slowDns?.stop() } catch (_: Exception) {}
        slowDns = null
        pool.shutdownNow()
    }

    /** عنوان SSH للجلسة (في SlowDNS قد يكون العنوان فارغاً فنستعمل نطاق NS). */
    private fun sshHost(): String = server.address.ifBlank { server.dnsNs }

    /** يجهّز قناة direct-tcpip إلى (host, port) على السيرفر من الجلسة الحالية (لا يوصلها). */
    private fun openDirect(host: String, port: Int): ChannelDirectTCPIP {
        val s = session
        if (s == null || !s.isConnected) throw IOException("SSH session is not connected")
        val ch = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
        ch.setHost(host)
        ch.setPort(port)
        ch.setOrgIPAddress("127.0.0.1")
        ch.setOrgPort(0)
        return ch
    }

    // ---------------------------------------------------------------- SSH session

    private fun connectSession(): Session {
        val jsch = JSch()
        // SlowDNS: أعد تشغيل نواة dnstt إن كانت ماتت (منفذها المحلي يتغيّر عندئذ، ويقرؤه EndpointProxy عند كل اتصال).
        if (server.isSlowDns) slowDns?.ensureRunning()
        val s = jsch.getSession(server.username, sshHost(), server.port)
        s.setPassword(server.secret)
        s.setProxy(EndpointProxy(server) { slowDns?.localPort ?: 0 })
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        // الأقوى أولاً، ثم الخوارزميات القديمة لأن كثيراً من سيرفرات SSH المجانية قديمة.
        s.setConfig("kex", KEX)
        s.setConfig("server_host_key", HOST_KEYS)
        // نفق DNS بطيء وتأخّره عالٍ: مهل أطول حتى لا نقطع الجلسة ظلماً.
        s.setServerAliveInterval(if (server.isSlowDns) 30_000 else 15_000)
        s.setServerAliveCountMax(if (server.isSlowDns) 4 else 3)
        s.connect(if (server.isSlowDns) SLOWDNS_CONNECT_TIMEOUT_MS else CONNECT_TIMEOUT_MS)
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

                var lastError = "SSH connection dropped."
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
            if (cmd == 3) {
                // UDP ASSOCIATE: مدعوم فقط عند تفعيل UDPGW؛ غير ذلك نرفضه فيعود التطبيق إلى TCP.
                val gw = udpGw
                if (gw == null) { reply(cout, 7); return }
                handleUdpAssociate(c, cin, cout, gw)
                return
            }
            if (cmd != 1) { reply(cout, 7); return }

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
                    onWarning("SSH server disables port forwarding (AllowTcpForwarding); internet will not work through it.")
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

    /**
     * SOCKS5 UDP ASSOCIATE: نفتح DatagramSocket محلياً (127.0.0.1) ونعيد منفذه لـ Xray؛ كل حزمة ترد بترويسة
     * SOCKS5 UDP تُحوَّل إلى إطار UDPGW داخل SSH، والردود تعود بالعكس. ينتهي الارتباط بإغلاق اتصال TCP.
     */
    private fun handleUdpAssociate(c: Socket, cin: DataInputStream, cout: OutputStream, gw: UdpGwMux) {
        val sock = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val conIds = HashMap<String, Int>()         // "ip:port" الوجهة -> conid
        // أول مرسل (Xray) فقط يُقبل؛ AtomicReference لأن الردود تُكتب من خيط قارئ UDPGW.
        val client = java.util.concurrent.atomic.AtomicReference<InetSocketAddress?>(null)
        val listener = UdpGwMux.Listener { srcIp, srcPort, data ->
            val ca = client.get()
            if (ca != null && !sock.isClosed) {
                val v6 = srcIp.size == 16
                val out = ByteArray(4 + srcIp.size + 2 + data.size)
                out[3] = (if (v6) 4 else 1).toByte()               // RSV RSV FRAG=0 ATYP
                System.arraycopy(srcIp, 0, out, 4, srcIp.size)
                out[4 + srcIp.size] = (srcPort shr 8).toByte()
                out[5 + srcIp.size] = srcPort.toByte()
                System.arraycopy(data, 0, out, 6 + srcIp.size, data.size)
                try { sock.send(DatagramPacket(out, out.size, ca)) } catch (_: Exception) {}
            }
        }
        try {
            // رد النجاح مع BND.ADDR=127.0.0.1 و BND.PORT=منفذ UDP المحلي
            val bp = sock.localPort
            cout.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (bp shr 8).toByte(), bp.toByte()))
            cout.flush()
            c.soTimeout = 0

            // ينتهي الارتباط عندما يغلق Xray اتصال التحكم TCP.
            pool.execute {
                try { while (cin.read() >= 0) { /* تجاهل */ } } catch (_: Exception) {}
                try { sock.close() } catch (_: Exception) {}
            }

            val buf = ByteArray(65535)
            val pkt = DatagramPacket(buf, buf.size)
            var warnedDomain = false
            while (!closed && !sock.isClosed) {
                pkt.length = buf.size
                sock.receive(pkt)
                if (!pkt.address.isLoopbackAddress) continue
                val known = client.get()
                if (known == null) client.set(InetSocketAddress(pkt.address, pkt.port))
                else if (pkt.port != known.port) continue

                // ترويسة SOCKS5 UDP: RSV(2) FRAG(1) ATYP(1) ADDR PORT DATA
                val n = pkt.length
                if (n < 10 || buf[2].toInt() != 0) continue            // لا نجمّع الشظايا
                val ip: ByteArray
                val portIdx: Int
                when (buf[3].toInt()) {
                    1 -> { ip = buf.copyOfRange(4, 8); portIdx = 8 }
                    4 -> { if (n < 22) continue; ip = buf.copyOfRange(4, 20); portIdx = 20 }
                    3 -> {
                        // اسم نطاق: UDPGW يقبل عناوين IP فقط، ولا نحلّ الاسم محلياً كي لا يتسرّب DNS خارج النفق.
                        val dl = buf[4].toInt() and 0xFF
                        val lit = if (5 + dl + 2 <= n) String(buf, 5, dl, Charsets.ISO_8859_1) else ""
                        val parsed = literalIp(lit)
                        if (parsed == null) {
                            if (!warnedDomain) {
                                warnedDomain = true
                                ConnLog.add(ConnLog.Level.DEBUG, "UDP packet with a domain destination dropped (UDPGW needs an IP)")
                            }
                            continue
                        }
                        ip = parsed; portIdx = 5 + dl
                    }
                    else -> continue
                }
                if (portIdx + 2 > n) continue
                val dstPort = ((buf[portIdx].toInt() and 0xFF) shl 8) or (buf[portIdx + 1].toInt() and 0xFF)
                val dataOff = portIdx + 2

                val key = ip.joinToString(".") { (it.toInt() and 0xFF).toString() } + ":" + dstPort
                var id = conIds[key] ?: -1
                if (id < 0) {
                    id = gw.allocate(listener)
                    if (id < 0) continue
                    conIds[key] = id
                }
                gw.send(id, ip, dstPort, buf, dataOff, n - dataOff)
            }
        } catch (_: Throwable) {
        } finally {
            conIds.values.forEach { gw.release(it) }
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /** يحوّل نصاً مثل "1.2.3.4" أو "2001:db8::1" إلى بايتات؛ null إن لم يكن عنوان IP حرفياً (دون أي استعلام DNS). */
    private fun literalIp(t: String): ByteArray? {
        if (t.isEmpty()) return null
        val isV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(t)
        val isV6 = t.contains(':') && t.all { it.isLetterOrDigit() || it == ':' || it == '.' }
        if (!isV4 && !isV6) return null
        return try {
            InetAddress.getByName(t).address // لا يحلّ اسماً لأن الإدخال عنوان حرفي
        } catch (_: Exception) {
            null
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
        private const val SLOWDNS_CONNECT_TIMEOUT_MS = 90_000
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
                    "SSH login failed: incorrect username or password."
                server.isSlowDns && (root is SocketTimeoutException || all.contains("timeout", true) ||
                    all.contains("timed out", true) || all.contains("End of IO Stream", true) ||
                    all.contains("Connection reset", true) || all.contains("invalid server's version string", true) ||
                    all.contains("Socket closed", true) || root is ConnectException) ->
                    "SlowDNS: SSH did not answer through the DNS tunnel. Check the NS domain, public key, resolver, and that the dnstt server is running."
                root is UnknownHostException ->
                    if (hide) "Could not reach the server address." else "Could not reach the server address: ${server.address}"
                root is ConnectException || all.contains("refused", true) ->
                    if (server.proxyHost.isNotBlank()) "The proxy refused the connection. Check the proxy address and port." else if (hide) "The server refused the connection." else "The server refused the connection on port ${server.port}."
                root is SocketTimeoutException || all.contains("timeout", true) || all.contains("timed out", true) ->
                    "SSH server connection timed out. Check the address, port and payload."
                root is SSLException ->
                    if (hide) "TLS failed: could not complete the handshake." else "TLS failed: ${root.message ?: "could not complete the handshake"}"
                all.contains("Algorithm negotiation fail", true) ->
                    "SSH algorithm negotiation failed (incompatible server)."
                all.contains("invalid server's version string", true) || all.contains("Connection reset", true) ||
                    all.contains("End of IO Stream", true) ->
                    "The server did not respond with the SSH protocol. Check the mode (TCP/WS), payload and port."
                hide -> "SSH connection failed (${root.javaClass.simpleName})."
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
private class EndpointProxy(
    private val s: Server,
    /** منفذ dnstt المحلي الحالي (SlowDNS فقط). */
    private val slowDnsPort: () -> Int = { 0 }
) : com.jcraft.jsch.Proxy {
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

        // SlowDNS: SSH يمر عبر منفذ dnstt المحلي (127.0.0.1)؛ لا TLS ولا بايلود ولا بروكسي، فالنقل هو DNS نفسه.
        if (s.isSlowDns) {
            val port = slowDnsPort()
            if (port !in 1..65535) throw TunnelException("SlowDNS core is not running.")
            var raw: Socket
            var attempt = 0
            while (true) {
                // Socket جديد لكل محاولة: connect الفاشل يغلق الـ Socket.
                raw = Socket()
                raw.tcpNoDelay = true
                try {
                    raw.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                    break
                } catch (e: ConnectException) {
                    try { raw.close() } catch (_: Exception) {}
                    // المنفذ لم يبدأ الاستماع بعد (العملية تقلع): جرّب ثانية.
                    if (++attempt >= 15) throw e
                    Thread.sleep(300)
                }
            }
            socket = raw
            raw.soTimeout = t
            inp = raw.getInputStream()
            out = raw.getOutputStream()
            return
        }

        val useProxy = s.proxyHost.isNotBlank()
        val payloadOn = s.network == "ws"
        val tlsOn = s.security == "tls"

        val raw = Socket()
        socket = raw
        raw.tcpNoDelay = true
        raw.connect(
            if (useProxy) InetSocketAddress(s.proxyHost, s.proxyPort) else InetSocketAddress(s.address, s.port),
            t
        )
        raw.soTimeout = t

        var sock: Socket = raw
        var input: InputStream = raw.getInputStream()
        var output: OutputStream = raw.getOutputStream()

        if (useProxy) {
            // TCP → HTTP proxy → (بايلود | CONNECT) → [TLS] → SSH
            val pin = PushbackInputStream(raw.getInputStream(), 8192)
            sendPayload(output, forProxy = true)
            readHandshake(pin, allowSshBanner = !tlsOn)
            if (tlsOn) {
                sock = tls(raw)
                socket = sock
                input = sock.getInputStream()
                output = sock.getOutputStream()
            } else {
                input = pin
            }
        } else {
            // TCP → [TLS] → [بايلود] → SSH
            if (tlsOn) {
                sock = tls(raw)
                socket = sock
                output = sock.getOutputStream()
            }
            val pin = PushbackInputStream(sock.getInputStream(), 8192)
            if (payloadOn) {
                sendPayload(output, forProxy = false)
                readHandshake(pin, allowSshBanner = true)
            }
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
            throw SSLPeerUnverifiedException("Server certificate does not match the SNI name (enable untrusted certificates if needed).")
        }
        return ssl
    }

    // ---------------------------------------------------------------- payload

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

    private fun tag(text: String, name: String, value: String): String =
        text.replace(Regex(Regex.escape(name), RegexOption.IGNORE_CASE)) { value }

    private fun buildPayload(forProxy: Boolean): String {
        val hostName = s.host.ifBlank { s.address }
        // [host_port] = عنوان سيرفر SSH الحقيقي (يستعمل في CONNECT عبر البروكسي).
        val hostPort = if (s.address.contains(":")) "[${s.address}]:${s.port}" else "${s.address}:${s.port}"

        // بروكسي بلا بايلود → CONNECT قياسي؛ وإلا بايلود WebSocket القياسي.
        val custom = if (s.network == "ws") s.payload else ""
        var p = custom.ifBlank { if (forProxy) DEFAULT_CONNECT else DEFAULT_PAYLOAD }
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
    private fun readHandshake(pin: PushbackInputStream, allowSshBanner: Boolean) {
        val sb = StringBuilder()
        while (sb.length < 16_384) {
            val b = pin.read()
            if (b < 0) throw TunnelException("Server closed the connection after sending the payload.")
            sb.append(b.toChar())

            // السيرفر يرد مباشرة ببروتوكول SSH بلا HTTP: أعد البايتات ودع JSch يكمل.
            if (allowSshBanner && sb.length == 4 && sb.toString() == "SSH-") {
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
            ?: throw TunnelException("Unexpected server response to payload: ${first.take(80)}")
        if (code !in 100..299) {
            throw TunnelException("Server rejected the payload: ${first.take(80)}")
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        private const val DEFAULT_CONNECT =
            "CONNECT [host_port] [protocol][crlf]Host: [host_port][crlf]Proxy-Connection: Keep-Alive[crlf][crlf]"

        private const val DEFAULT_PAYLOAD =
            "GET [path] [protocol][crlf]Host: [host][crlf]Upgrade: websocket[crlf]" +
            "Connection: Upgrade[crlf]Sec-WebSocket-Key: [ws_key][crlf]Sec-WebSocket-Version: 13[crlf][crlf]"
    }
}
