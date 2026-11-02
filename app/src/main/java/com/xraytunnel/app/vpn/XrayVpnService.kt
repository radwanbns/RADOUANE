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
            lastError.value = "تعذر مراقبة اتصال الإنترنت: ${e.message ?: e.javaClass.simpleName}"
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
                if (state.value != VpnState.Disconnected && !hasPhysicalNetwork()) {
                    lastError.value = "انقطع اتصال الإنترنت؛ تم إيقاف نفق VPN."
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
        if (TimeCredit.remaining(this) <= 0L) {
            lastError.value = "انتهى الوقت المتاح. شاهد إعلاناً مكافأة لإضافة ساعة كاملة."
            stopVpn()
            return
        }
        if (!hasPhysicalNetwork()) {
            lastError.value = "لا توجد شبكة Wi-Fi أو بيانات هاتف متاحة."
            stopVpn()
            return
        }
        state.value = VpnState.Connecting
        lastError.value = null
        val server = Repo.get(this).selectedServer()
        if (server == null) { stopVpn(); return }
        try {
            // SSH: نتصل أولاً (قبل إنشاء واجهة VPN) لتظهر أخطاء الدخول/البايلود/TLS فوراً.
            val config = if (server.protocol == "ssh") {
                val tunnel = SshTunnel(
                    server,
                    onFatal = { msg ->
                        scope.launch {
                            vpnMutex.withLock {
                                if (state.value != VpnState.Disconnected) {
                                    lastError.value = msg
                                    stopVpn()
                                }
                            }
                        }
                    },
                    onWarning = { msg -> lastError.value = msg }
                )
                val port = tunnel.start() // يرمي استثناء برسالة مفهومة عند الفشل
                sshTunnel = tunnel
                ConfigBuilder.build(server, port, tunnel.socksUser, tunnel.socksPass)
            } else {
                ConfigBuilder.build(server)
            }
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
            XrayCore.start(this, config, p.fd)
             state.value = VpnState.Connected
            creditTicker?.cancel()
            creditTicker = scope.launch {
                while (state.value == VpnState.Connected) {
                    kotlinx.coroutines.delay(1000L)
                    if (state.value != VpnState.Connected) break
                    if (TimeCredit.consumeSecond(this@XrayVpnService) <= 0L) {
                        lastError.value = "انتهى الوقت المتاح؛ تم فصل VPN."
                        vpnMutex.withLock { stopVpn() }
                        break
                    }
                }
            }
        } catch (e: Throwable) {
            lastError.value = e.message ?: e.javaClass.simpleName
            stopVpn()
        }
    }

    /** Stop only the tunnel; keep this foreground service alive for an immediate reconnect. */
    private fun stopTunnelOnly() {
        creditTicker?.cancel()
        creditTicker = null
        XrayCore.stop()
        try { sshTunnel?.close() } catch (_: Exception) {}
        sshTunnel = null
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
