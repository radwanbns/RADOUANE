package com.xraytunnel.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.xraytunnel.app.MainActivity
import com.xraytunnel.app.R
import com.xraytunnel.app.core.ConfigBuilder
import com.xraytunnel.app.core.ConnLog
import com.xraytunnel.app.core.IcsOpenVpn
import com.xraytunnel.app.core.OvpnTunnel
import com.xraytunnel.app.core.SshTunnel
import com.xraytunnel.app.core.XrayCore
import com.xraytunnel.app.data.Repo
import com.xraytunnel.app.data.TimeCredit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VpnState { Disconnected, Connecting, Connected }

class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.xraytunnel.app.STOP"
        const val ACTION_SWITCH_SERVER = "com.xraytunnel.app.SWITCH_SERVER"
        const val EXTRA_SERVER_ID = "server_id"
        private const val CHANNEL = "xray_tunnel"
        val state = MutableStateFlow(VpnState.Disconnected)
        val lastError = MutableStateFlow<String?>(null)
    }

    private var pfd: ParcelFileDescriptor? = null
    private var sshTunnel: SshTunnel? = null
    private var ovpnTunnel: OvpnTunnel? = null
    /** true أثناء تشغيل OpenVPN الحقيقي عبر ics-openvpn (هو من ينشئ واجهة tun وليس هذه الخدمة). */
    @Volatile private var icsActive = false
    /** آخر حالة نصية من OpenVPN (WAIT, AUTH, ...)؛ تُعرض مرة واحدة في رسالة المراقب فقط. */
    @Volatile private var icsDetail = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var creditTicker: Job? = null
    // Serialize start/stop/switch so a server switch cannot race an in-progress connection.
    private val vpnMutex = Mutex()
    private lateinit var connectivityManager: ConnectivityManager

    /** Watch physical Wi-Fi/mobile networks; validation is not required to attempt a VPN connection. */
    private val physicalNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = enforceInternetAvailability()
        override fun onLost(network: Network) = enforceInternetAvailability()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            enforceInternetAvailability()
    }

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, physicalNetworkCallback)
        } catch (e: Exception) {
            setError("تعذر مراقبة اتصال الإنترنت: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun hasPhysicalNetwork(): Boolean {
        return try {
            connectivityManager.allNetworks.any { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                caps != null &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun enforceInternetAvailability() {
        scope.launch {
            vpnMutex.withLock {
                if (!icsActive && state.value != VpnState.Disconnected && !hasPhysicalNetwork()) {
                    setError("انقطع اتصال الإنترنت؛ تم إيقاف نفق VPN.")
                    stopVpn()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { vpnMutex.withLock { stopVpn() } }
                return START_NOT_STICKY
            }
            ACTION_SWITCH_SERVER -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                if (serverId != null) {
                    startForeground(1, notification())
                    scope.launch {
                        vpnMutex.withLock {
                            stopTunnelOnly()
                            Repo.get(this@XrayVpnService).select(serverId)
                            startVpnInternal()
                        }
                    }
                }
                return START_NOT_STICKY
            }
            else -> {
                startForeground(1, notification())
                scope.launch { vpnMutex.withLock { startVpnInternal() } }
            }
        }
        return START_NOT_STICKY
    }

    private fun startVpnInternal() {
        if (state.value != VpnState.Disconnected) return
        ConnLog.clear()
        if (TimeCredit.remaining(this) <= 0L) {
            setError("انتهى الوقت المتاح. شاهد إعلاناً مكافأة لإضافة ساعة كاملة.")
            stopVpn()
            return
        }
        if (!hasPhysicalNetwork()) {
            setError("لا توجد شبكة Wi-Fi أو بيانات هاتف متاحة.")
            stopVpn()
            return
        }
        state.value = VpnState.Connecting
        lastError.value = null
        val server = Repo.get(this).selectedServer()
        if (server == null) { stopVpn(); return }
        ConnLog.add(ConnLog.Level.INFO, "بدء الاتصال: ${server.name} (${server.protocol.uppercase()})")
        try {
            if (server.isRealOvpn) {
                startRealOvpn(server)
                return
            }
            // SSH أو OVPN: نتصل أولاً (قبل إنشاء واجهة VPN) لتظهر أخطاء البايلود/TLS فوراً.
            val config = when (server.protocol) {
                "ssh" -> {
                    ConnLog.add(ConnLog.Level.INFO, "تشغيل نفق SSH…")
                    val tunnel = SshTunnel(
                        server,
                        onFatal = { msg ->
                            scope.launch {
                                vpnMutex.withLock {
                                    if (state.value != VpnState.Disconnected) {
                                        setError(msg)
                                        stopVpn()
                                    }
                                }
                            }
                        },
                        onWarning = { msg -> setError(msg) }
                    )
                    val port = tunnel.start()
                    sshTunnel = tunnel
                    ConfigBuilder.build(server, port, tunnel.socksUser, tunnel.socksPass)
                }
                "ovpn" -> {
                    ConnLog.add(ConnLog.Level.INFO, "تشغيل نفق Payload (وضع قديم، SOCKS)…")
                    val tunnel = OvpnTunnel(
                        server,
                        onFatal = { msg ->
                            scope.launch {
                                vpnMutex.withLock {
                                    if (state.value != VpnState.Disconnected) {
                                        setError(msg)
                                        stopVpn()
                                    }
                                }
                            }
                        },
                        onWarning = { msg -> setError(msg) }
                    )
                    val port = tunnel.start()
                    ovpnTunnel = tunnel
                    ConfigBuilder.build(server, port, tunnel.socksUser, tunnel.socksPass)
                }
                else -> ConfigBuilder.build(server)
            }
            ConnLog.add(ConnLog.Level.INFO, "إنشاء واجهة VPN (tun)")
            val p = Builder()
                .setSession("Xray Tunnel")
                .setMtu(1500)
                .addAddress("10.10.14.1", 30)
                .addAddress("fd00:10:14::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName) // حتى لا يمر اتصال التطبيق نفسه عبر النفق
                .establish() ?: error("VPN permission not granted")
            pfd = p
            ConnLog.add(ConnLog.Level.INFO, "تشغيل نواة Xray")
            XrayCore.start(this, config, p.fd)
            state.value = VpnState.Connected
            ConnLog.add(ConnLog.Level.OK, "تم الاتصال بنجاح")
            startCreditTicker()
        } catch (e: Throwable) {
            setError(e.message ?: e.javaClass.simpleName)
            stopVpn()
        }
    }


    /** يسجّل الخطأ في سجل الاتصال ويعرضه للمستخدم. */
    private fun setError(msg: String) {
        ConnLog.add(ConnLog.Level.ERROR, msg)
        lastError.value = msg
    }

    private fun friendlyState(s: String): String = when (s.uppercase()) {
        "NOPROCESS" -> "تهيئة عملية OpenVPN"
        "CONNECTING" -> "بدء الاتصال"
        "RESOLVE" -> "حل اسم السيرفر"
        "TCP_CONNECT" -> "الاتصال بالسيرفر (TCP)"
        "WAIT" -> "انتظار رد السيرفر"
        "AUTH" -> "المصادقة وتبادل مفاتيح TLS"
        "GET_CONFIG" -> "استلام إعدادات الشبكة من السيرفر"
        "ASSIGN_IP" -> "تعيين عنوان IP للنفق"
        "ADD_ROUTES" -> "إضافة المسارات"
        "CONNECTED" -> "تم الاتصال بالسيرفر"
        "RECONNECTING" -> "إعادة الاتصال"
        "CONNECTRETRY" -> "إعادة المحاولة بعد قليل"
        "EXITING" -> "إنهاء الاتصال"
        else -> s
    }

    private fun stateLevel(s: String): ConnLog.Level = when (s.uppercase()) {
        "CONNECTED" -> ConnLog.Level.OK
        "CONNECTRETRY", "RECONNECTING" -> ConnLog.Level.WARN
        else -> ConnLog.Level.INFO
    }

    /** يصنّف سطراً من سجل ics-openvpn ويضيفه (يتجاهل تكرار سطور الحالة). */
    private fun addIcsLine(line: String) {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith("MANAGEMENT: >STATE")) return
        val level = when {
            l.startsWith("MANAGEMENT:") -> ConnLog.Level.DEBUG
            Regex("error|fatal", RegexOption.IGNORE_CASE).containsMatchIn(l) -> ConnLog.Level.ERROR
            Regex("warning|fail|reset|timeout|bad ", RegexOption.IGNORE_CASE).containsMatchIn(l) -> ConnLog.Level.WARN
            l.contains("Initialization Sequence Completed") || l.contains("Peer Connection Initiated") -> ConnLog.Level.OK
            else -> ConnLog.Level.INFO
        }
        ConnLog.add(level, l)
    }

    /** يخصم ثانية واحدة فقط أثناء حالة Connected. */
    private fun startCreditTicker() {
        creditTicker?.cancel()
        creditTicker = scope.launch {
            while (state.value == VpnState.Connected) {
                kotlinx.coroutines.delay(1000L)
                if (state.value != VpnState.Connected) break
                if (TimeCredit.consumeSecond(this@XrayVpnService) <= 0L) {
                    setError("انتهى الوقت المتاح؛ تم فصل VPN.")
                    vpnMutex.withLock { stopVpn() }
                    break
                }
            }
        }
    }

    /**
     * OpenVPN حقيقي عبر ics-openvpn: لا نستدعي Builder().establish() ولا نشغّل Xray؛
     * المكتبة تنشئ tun وتدير المصافحة وإعادة الاتصال، ونحن نعكس حالتها على [state].
     */
    private fun startRealOvpn(server: com.xraytunnel.app.data.Server) {
        icsActive = true
        icsDetail = ""
        ConnLog.add(
            ConnLog.Level.INFO,
            "الوضع: OpenVPN حقيقي (ics-openvpn)" +
                if (server.usesPayloadForward) " عبر ممرّر Payload/Proxy" else " (اتصال مباشر)"
        )
        var lastShown = ""
        IcsOpenVpn.observe(onDetail = { d ->
            icsDetail = d
            if (d != lastShown) {
                lastShown = d
                ConnLog.add(stateLevel(d), friendlyState(d))
            }
        }) { st ->
            scope.launch {
                vpnMutex.withLock {
                    if (!icsActive) return@withLock
                    when (st) {
                        IcsOpenVpn.Status.Connected -> if (state.value != VpnState.Connected) {
                            state.value = VpnState.Connected
                            ConnLog.add(ConnLog.Level.OK, "تم الاتصال بنجاح — النفق يعمل")
                            startCreditTicker()
                        }
                        IcsOpenVpn.Status.Connecting -> if (state.value == VpnState.Disconnected) {
                            state.value = VpnState.Connecting
                        }
                        // انقطاع شبكة مؤقت: نبقى متصلين ظاهرياً؛ OpenVPN يعيد الاتصال وحده
                        IcsOpenVpn.Status.Waiting ->
                            ConnLog.add(ConnLog.Level.WARN, "انقطاع شبكة مؤقت — OpenVPN ينتظر إعادة الاتصال")
                        is IcsOpenVpn.Status.Failed -> {
                            lastError.value = st.message
                            ConnLog.add(ConnLog.Level.ERROR, st.message)
                            stopVpn()
                        }
                        IcsOpenVpn.Status.Stopped -> if (state.value != VpnState.Disconnected) stopVpn()
                    }
                }
            }
        }
        var forwardPort = 0
        if (server.usesPayloadForward) {
            ConnLog.add(
                ConnLog.Level.INFO,
                "تشغيل الممرّر المحلي" +
                    (if (server.proxyHost.isNotBlank()) " — بروكسي ${server.proxyHost}:${server.proxyPort}" else "") +
                    (if (server.security == "tls") " — TLS" else "") +
                    (if (server.network == "ws" || server.payload.isNotBlank()) " — Payload/WS" else "")
            )
            val fwd = OvpnTunnel(
                server,
                onFatal = { msg ->
                    scope.launch {
                        vpnMutex.withLock {
                            if (state.value != VpnState.Disconnected) {
                                setError(msg)
                                stopVpn()
                            }
                        }
                    }
                },
                onWarning = { msg -> setError(msg) }
            )
            forwardPort = fwd.startRawForward() // يرمي خطأ مفهوماً إن فشل البايلود/البروكسي/TLS
            ovpnTunnel = fwd
        }
        ConnLog.add(ConnLog.Level.INFO, "تحليل ملف .ovpn وتشغيل ics-openvpn")
        IcsOpenVpn.markLogCursor()
        IcsOpenVpn.start(this, server, forwardPort)

        // يضيف سطور سجل ics-openvpn الجديدة إلى سجل الواجهة
        scope.launch {
            while (icsActive) {
                IcsOpenVpn.newLogLines(this@XrayVpnService).forEach { addIcsLine(it) }
                kotlinx.coroutines.delay(700L)
            }
        }

        // مراقب: إن بقي الاتصال معلّقاً نكتب التشخيص في السجل
        scope.launch {
            kotlinx.coroutines.delay(40_000L)
            if (icsActive && state.value == VpnState.Connecting) {
                ConnLog.add(ConnLog.Level.WARN, "لم يكتمل الاتصال بعد 40 ثانية (حالة OpenVPN: $icsDetail)")
                ovpnTunnel?.let { ConnLog.add(ConnLog.Level.DEBUG, it.diagnostics()) }
                lastError.value = "لم يكتمل الاتصال بعد 40 ثانية — راجع سجل الاتصال."
            }
        }
    }

    /** Stop only the tunnel; keep this foreground service alive for an immediate reconnect. */
    private fun stopTunnelOnly() {
        creditTicker?.cancel()
        creditTicker = null
        if (icsActive) {
            icsActive = false
            IcsOpenVpn.stop(this)
        }
        XrayCore.stop()
        try { sshTunnel?.close() } catch (_: Exception) {}
        sshTunnel = null
        try { ovpnTunnel?.close() } catch (_: Exception) {}
        ovpnTunnel = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        state.value = VpnState.Disconnected
    }

    private fun stopVpn() {
        stopTunnelOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { stopTunnelOnly(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() {
        try { connectivityManager.unregisterNetworkCallback(physicalNetworkCallback) } catch (_: Exception) {}
        stopTunnelOnly()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.connected))
            .setContentIntent(open)
            .addAction(0, getString(R.string.disconnect), stop)
            .setOngoing(true)
            .build()
    }
}
