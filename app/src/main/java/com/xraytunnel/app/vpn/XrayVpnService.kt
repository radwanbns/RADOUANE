package com.xraytunnel.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.xraytunnel.app.MainActivity
import com.xraytunnel.app.R
import com.xraytunnel.app.core.ConfigBuilder
import com.xraytunnel.app.core.XrayCore
import com.xraytunnel.app.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class VpnState { Disconnected, Connecting, Connected }

class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_STOP = "com.xraytunnel.app.STOP"
        private const val CHANNEL = "xray_tunnel"
        val state = MutableStateFlow(VpnState.Disconnected)
        val lastError = MutableStateFlow<String?>(null)
    }

    private var pfd: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); return START_NOT_STICKY }
        startForeground(1, notification())
        scope.launch { startVpn() }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (state.value != VpnState.Disconnected) return
        state.value = VpnState.Connecting
        lastError.value = null
        val server = Repo.get(this).selectedServer()
        if (server == null) { stopVpn(); return }
        try {
            val config = ConfigBuilder.build(server)
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
        } catch (e: Throwable) {
            lastError.value = e.message ?: e.javaClass.simpleName
            stopVpn()
        }
    }

    private fun stopVpn() {
        XrayCore.stop()
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        state.value = VpnState.Disconnected
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { stopVpn() }
    override fun onDestroy() { XrayCore.stop(); state.value = VpnState.Disconnected; super.onDestroy() }

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
