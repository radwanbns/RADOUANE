package com.xraytunnel.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
import com.xraytunnel.app.data.DnsPrefs
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
    @Volatile private var notifStatusLine = ""
    @Volatile private var notifDownText = "—"
    @Volatile private var notifUpText = "—"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var creditTicker: Job? = null
    @Volatile private var stopRequested = false
    // Serialize lifecycle cleanup; stop requests are signalled immediately, not queued behind a slow connect.
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
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, physicalNetworkCallback)
        } catch (e: Exception) {
            setError("Could not monitor internet connectivity: ${e.message ?: e.javaClass.simpleName}")
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
                    setError("Internet connection lost; VPN tunnel stopped.")
                    stopVpn()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Signal cancellation immediately: startVpnInternal may be waiting on a slow
                // SSH/TLS/OpenVPN operation while holding vpnMutex.
                stopRequested = true
                stopActiveResourcesImmediately()
                scope.launch { vpnMutex.withLock { stopVpn() } }
                return START_NOT_STICKY
            }
            ACTION_SWITCH_SERVER -> {
                val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
                if (serverId != null) {
                    startForeground(1, notification())
                    // Cancel an in-progress handshake immediately, then serialize cleanup
                    // and start the newly selected profile.
                    stopRequested = true
                    stopActiveResourcesImmediately()
                    scope.launch {
                        vpnMutex.withLock {
                            stopTunnelOnly()
                            Repo.get(this@XrayVpnService).select(serverId)
                            stopRequested = false
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
        stopRequested = false
        ConnLog.clear()
        if (TimeCredit.remaining(this) <= 0L) {
            setError("Time expired. Watch a rewarded ad to add a full hour.")
            stopVpn()
            return
        }
        if (!hasPhysicalNetwork()) {
            setError("No Wi-Fi or mobile data available.")
            stopVpn()
            return
        }
        state.value = VpnState.Connecting
        notifStatusLine = "Connecting..."
        notifDownText = "—"
        notifUpText = "—"
        updateNotification()
        lastError.value = null
        val server = Repo.get(this).selectedServer()
        if (server == null) { stopVpn(); return }
        ConnLog.add(ConnLog.Level.INFO, "Starting connection: ${server.name} (${server.protocol.uppercase()})")
        try {
            if (stopRequested) return
            if (server.isRealOvpn) {
                startRealOvpn(server)
                return
            }
            // SSH أو OVPN: نتصل أولاً (قبل إنشاء واجهة VPN) لتظهر أخطاء البايلود/TLS فوراً.
            val config = when (server.protocol) {
                "ssh" -> {
                    ConnLog.add(
                        ConnLog.Level.INFO,
                        "Starting SSH tunnel…" +
                            (if (server.isSlowDns) " [SlowDNS]" else "") +
                            (if (server.udpgw) " [UDP]" else "")
                    )
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
                        onWarning = { msg -> setError(msg) },
                        appContext = applicationContext
                    )
                    val port = tunnel.start()
                    sshTunnel = tunnel
                    if (stopRequested) {
                        tunnel.close()
                        sshTunnel = null
                        return
                    }
                    ConfigBuilder.build(server, port, tunnel.socksUser, tunnel.socksPass)
                }
                "ovpn" -> {
                    ConnLog.add(ConnLog.Level.INFO, "Starting Payload tunnel (legacy SOCKS mode)…")
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
                    if (stopRequested) {
                        tunnel.close()
                        ovpnTunnel = null
                        return
                    }
                    ConfigBuilder.build(server, port, tunnel.socksUser, tunnel.socksPass)
                }
                else -> ConfigBuilder.build(server)
            }
            if (stopRequested) return
            ConnLog.add(ConnLog.Level.INFO, "Creating VPN interface (tun)")
            val p = Builder()
                .setSession("Xray Tunnel")
                .setMtu(1500)
                .addAddress("10.10.14.1", 30)
                .addAddress("fd00:10:14::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(DnsPrefs.primary(this))
                .addDnsServer(DnsPrefs.secondary(this))
                .addDisallowedApplication(packageName) // حتى لا يمر اتصال التطبيق نفسه عبر النفق
                .establish() ?: error("VPN permission not granted")
            pfd = p
            if (stopRequested) {
                try { p.close() } catch (_: Exception) {}
                pfd = null
                return
            }
            ConnLog.add(ConnLog.Level.INFO, "Starting Xray core")
            XrayCore.start(this, config, p.fd)
            if (stopRequested) {
                XrayCore.stop()
                return
            }
            state.value = VpnState.Connected
            updateNotification()
            ConnLog.add(ConnLog.Level.OK, "Connected successfully")
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
        "NOPROCESS" -> "Initializing OpenVPN process"
        "CONNECTING" -> "Starting connection"
        "RESOLVE" -> "Resolving server name"
        "TCP_CONNECT" -> "Connecting to server (TCP)"
        "WAIT" -> "Waiting for server response"
        "AUTH" -> "Authenticating and exchanging TLS keys"
        "GET_CONFIG" -> "Receiving network config from server"
        "ASSIGN_IP" -> "Assigning tunnel IP address"
        "ADD_ROUTES" -> "Adding routes"
        "CONNECTED" -> "Connected to server"
        "RECONNECTING" -> "Reconnecting"
        "CONNECTRETRY" -> "Retrying shortly"
        "EXITING" -> "Closing connection"
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

    /** يخصم الوقت أثناء Connected ويبقي إشعار ics مخفياً قدر الإمكان. */
    private fun startCreditTicker() {
        creditTicker?.cancel()
        creditTicker = scope.launch {
            while (state.value == VpnState.Connected) {
                kotlinx.coroutines.delay(1000L)
                if (state.value != VpnState.Connected) break
                if (icsActive) IcsOpenVpn.suppressIcons(this@XrayVpnService)
                if (TimeCredit.consumeSecond(this@XrayVpnService) <= 0L) {
                    setError("Available time ran out; VPN disconnected.")
                    vpnMutex.withLock { stopVpn() }
                    break
                }
            }
        }
    }

    private fun formatNotifSpeed(bytesPerSec: Long): String {
        val b = if (bytesPerSec < 0L) 0L else bytesPerSec
        return when {
            b >= 1_000_000L -> String.format("%.1f MB/s", b / 1_000_000.0)
            b >= 1_000L -> String.format("%.1f KB/s", b / 1_000.0)
            else -> "$b B/s"
        }
    }

    /** Mark OpenVPN as fully connected in app state + notification. */
    private fun markOvpnConnected() {
        if (state.value == VpnState.Connected) {
            notifStatusLine = "Connected"
            return
        }
        state.value = VpnState.Connected
        notifStatusLine = "Connected"
        ConnLog.add(ConnLog.Level.OK, "Connected successfully — tunnel is up")
        startCreditTicker()
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
            "Mode: real OpenVPN (ics-openvpn)" +
                if (server.usesPayloadForward) " via Payload/Proxy relay" else " (direct connection)"
        )
        var lastShown = ""
        IcsOpenVpn.observe(onDetail = { d ->
            icsDetail = d
            val line = friendlyState(d)
            if (d != lastShown) {
                lastShown = d
                ConnLog.add(stateLevel(d), line)
            }
            // Some ics builds report CONNECTED in the state string before/without LEVEL_CONNECTED.
            if (d.equals("CONNECTED", ignoreCase = true)) {
                scope.launch {
                    vpnMutex.withLock {
                        if (!icsActive) return@withLock
                        markOvpnConnected()
                    }
                    updateNotification()
                }
            } else {
                notifStatusLine = line
                updateNotification()
            }
        }) { st ->
            scope.launch {
                vpnMutex.withLock {
                    if (!icsActive) return@withLock
                    when (st) {
                        IcsOpenVpn.Status.Connected -> markOvpnConnected()
                        IcsOpenVpn.Status.Connecting -> {
                            if (state.value == VpnState.Disconnected) {
                                state.value = VpnState.Connecting
                            }
                            // Do not overwrite Connected with Connecting (race).
                        }
                        IcsOpenVpn.Status.Waiting -> {
                            // Keep UI connected during brief drops; OpenVPN reconnects itself.
                            ConnLog.add(ConnLog.Level.WARN, "Temporary network drop — OpenVPN is waiting to reconnect")
                        }
                        is IcsOpenVpn.Status.Failed -> {
                            lastError.value = st.message
                            ConnLog.add(ConnLog.Level.ERROR, st.message)
                            stopVpn()
                        }
                        IcsOpenVpn.Status.Stopped -> if (state.value != VpnState.Disconnected) stopVpn()
                    }
                }
                updateNotification()
            }
        }
        var forwardPort = 0
        if (server.usesPayloadForward) {
            ConnLog.add(
                ConnLog.Level.INFO,
                "Starting local forwarder" +
                    (if (server.proxyHost.isNotBlank()) " — proxy ${server.proxyHost}:${server.proxyPort}" else "") +
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
        ConnLog.add(ConnLog.Level.INFO, "Parsing .ovpn file and starting ics-openvpn")
        IcsOpenVpn.markLogCursor()
        IcsOpenVpn.start(this, server, forwardPort)

        // يضيف سطور سجل ics-openvpn الجديدة إلى سجل الواجهة
        scope.launch {
            while (icsActive) {
                IcsOpenVpn.suppressIcons(this@XrayVpnService)
                IcsOpenVpn.newLogLines(this@XrayVpnService).forEach { addIcsLine(it) }
                kotlinx.coroutines.delay(700L)
            }
        }

        // مراقب: إن بقي الاتصال معلّقاً نكتب التشخيص في السجل
        scope.launch {
            kotlinx.coroutines.delay(40_000L)
            if (icsActive && state.value == VpnState.Connecting) {
                ConnLog.add(ConnLog.Level.WARN, "Connection not completed after 40s (OpenVPN state: $icsDetail)")
                ovpnTunnel?.let { ConnLog.add(ConnLog.Level.DEBUG, it.diagnostics()) }
                lastError.value = "Connection not completed after 40s — check the connection log."
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

    private fun stopActiveResourcesImmediately() {
        creditTicker?.cancel()
        try { IcsOpenVpn.stop(this) } catch (_: Exception) {}
        try { XrayCore.stop() } catch (_: Exception) {}
        try { sshTunnel?.close() } catch (_: Exception) {}
        try { ovpnTunnel?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
    }

    private fun stopVpn() {
        stopRequested = true
        stopTunnelOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopRequested = true
        stopActiveResourcesImmediately()
        stopTunnelOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        stopRequested = true
        try { connectivityManager.unregisterNetworkCallback(physicalNetworkCallback) } catch (_: Exception) {}
        stopTunnelOnly()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val statusText = when (state.value) {
            VpnState.Connected -> getString(R.string.notification_connected)
            VpnState.Connecting -> getString(R.string.notification_connecting)
            else -> getString(R.string.notification_disconnected)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setContentIntent(open)
            .addAction(0, getString(R.string.disconnect), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setGroup("xraytunnel_vpn")
            .setGroupSummary(false)
            .build()
    }

    /** Refresh the foreground notification to match the current VpnState. */
    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, notification())
            if (icsActive) IcsOpenVpn.suppressIcons(this)
        } catch (_: Exception) {
        }
    }
}

