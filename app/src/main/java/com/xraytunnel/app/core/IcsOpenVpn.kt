package com.xraytunnel.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.xraytunnel.app.data.Server
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.Connection
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader

/**
 * غلاف حول مكتبة ics-openvpn (OpenVPN for Android) — عميل OpenVPN حقيقي (مصافحة TLS،
 * إنشاء واجهة tun، تمرير كل الترافيك، إعادة اتصال تلقائي عند تغيّر الشبكة).
 *
 * كل استدعاءات المكتبة محصورة هنا؛ إذا تغيّرت واجهة إصدار جديد عدّل هذا الملف فقط
 * (مثل XrayCore.kt مع libv2ray).
 *
 * مهم: ics-openvpn يملك خدمة VpnService خاصة به (OpenVPNService) وينشئ واجهة tun بنفسه،
 * لذلك لا يُشغَّل معه نواة Xray؛ XrayVpnService تبقى فقط كمتحكّم (إشعار + عدّاد الوقت + الحالة).
 */
object IcsOpenVpn {

    sealed class Status {
        object Connecting : Status()
        object Connected : Status()
        /** انقطاع شبكة مؤقت: OpenVPN ينتظر ويعيد الاتصال تلقائياً. */
        object Waiting : Status()
        data class Failed(val message: String) : Status()
        object Stopped : Status()
    }

    private var listener: VpnStatus.StateListener? = null
    private var sawActive = false

    /**
     * إشعار ics-openvpn إلزامي (خدمة مقدمة) ولا يمكن حذفه، لكن نخفضه لأدنى أهمية: بلا أيقونة في شريط
     * الحالة وفي قسم الإشعارات الصامتة، فيبقى إشعار التطبيق "متصل" هو الظاهر.
     * يجب استدعاؤه قبل بدء ics (يُخفَّض مستوى قناة موجودة فقط ما لم يغيّرها المستخدم يدوياً).
     */
    private fun quietNotifications(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Hide OpenVPN-ics status icons as much as the system allows (FGS still requires a silent notif).
                val ids = listOf(
                    "openvpn_bg", "openvpn_newstat", "openvpn_status",
                    "vpn", "background", "openvpn"
                )
                for (id in ids) {
                    val ch = NotificationChannel(id, "Background", NotificationManager.IMPORTANCE_MIN).apply {
                        setShowBadge(false)
                        setSound(null, null)
                        enableVibration(false)
                        enableLights(false)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                    }
                    nm.createNotificationChannel(ch)
                }
            }
            // Cancel leftover OpenVPN UI notifications so only XrayTunnel icon remains visible.
            suppressOpenVpnIcons(nm)
        } catch (_: Throwable) {}
    }

    private fun suppressOpenVpnIcons(nm: NotificationManager) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                nm.activeNotifications.forEach { sbn ->
                    // Keep only XrayTunnel VPN notification (id = 1).
                    if (sbn.id == 1) return@forEach
                    val title = sbn.notification.extras
                        ?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
                    val text = sbn.notification.extras
                        ?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
                    val channel = if (Build.VERSION.SDK_INT >= 26) sbn.notification.channelId.orEmpty() else ""
                    val looksVpnRelated =
                        title.contains("OpenVPN", true) ||
                            text.contains("OpenVPN", true) ||
                            title.contains("OVPN", true) ||
                            channel.contains("openvpn", true) ||
                            sbn.tag?.contains("openvpn", true) == true ||
                            text.contains("kbit/s", true) ||
                            text.contains("Mbit/s", true) ||
                            text.contains("bit/s", true) ||
                            title.contains("VPN", true) && !title.contains("Xray", true)
                    if (looksVpnRelated) {
                        try { nm.cancel(sbn.tag, sbn.id) } catch (_: Exception) {}
                        try { nm.cancel(sbn.id) } catch (_: Exception) {}
                    }
                }
            }
            // Common ics-openvpn notification ids used across versions
            for (id in intArrayOf(0, 1, 2, 3, 10, 100, 101, 102, 1001, 1002)) {
                if (id == 1) continue // XrayTunnel
                try { nm.cancel(id) } catch (_: Exception) {}
            }
        } catch (_: Throwable) {}
    }

    /** Call while connected to keep OpenVPN status icons from lingering. */
    fun suppressIcons(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            suppressOpenVpnIcons(nm)
            quietNotifications(ctx)
        } catch (_: Throwable) {}
    }

    /** يحلّل ملف .ovpn ويبدأ الاتصال. يرمي استثناءً برسالة مفهومة عند خطأ في الملف. */
    fun start(ctx: Context, server: Server, forwardPort: Int = 0) {
        val parser = ConfigParser()
        try {
            parser.parseConfig(StringReader(server.ovpnConfig))
        } catch (e: Exception) {
            throw IllegalStateException("Invalid .ovpn file: ${e.message ?: e.javaClass.simpleName}")
        }
        val profile = try {
            parser.convertProfile()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Failed to convert .ovpn file: ${e.message ?: e.javaClass.simpleName}. " +
                    "Make sure certificates and keys are embedded inside the file (<ca> <cert> <key> <tls-auth>) and not external files."
            )
        }

        profile.mName = server.name.ifBlank { "VPN" }

        // بيانات الدخول المحفوظة في السيرفر (إن وُجد auth-user-pass في الملف)
        if (server.username.isNotBlank()) profile.mUsername = server.username
        if (server.secret.isNotBlank()) profile.mPassword = server.secret

        // تعديلات المستخدم على العنوان/المنفذ/البروتوكول تُطبَّق على أول remote
        profile.mConnections.firstOrNull()?.let { c ->
            if (forwardPort > 0) {
                // Payload/WebSocket/Proxy: OpenVPN يتصل بالمُمرِّر المحلي (TCP) والمُمرِّر يتصل بالسيرفر
                c.mServerName = "127.0.0.1"
                c.mServerPort = forwardPort.toString()
                c.mUseUdp = false
                c.mProxyType = Connection.ProxyType.NONE
            } else {
                if (server.address.isNotBlank()) c.mServerName = server.address
                if (server.port in 1..65535) c.mServerPort = server.port.toString()
                when (server.method) {
                    "udp" -> c.mUseUdp = true
                    "tcp" -> c.mUseUdp = false
                }
            }
        }

        // استثناء التطبيق نفسه (الإعلانات/تحديث السيرفرات) من النفق، كما كان مع Xray
        profile.mAllowedAppsVpn.add(ctx.packageName)
        profile.mAllowedAppsVpnAreDisallowed = true

        quietNotifications(ctx)
        ProfileManager.setTemporaryProfile(ctx, profile)
        VPNLaunchHelper.startOpenVpn(profile, ctx, "XrayTunnel", true)
    }

    /** يسجّل مستمعاً لحالة الاتصال ويحوّلها إلى [Status]. */
    @Synchronized
    fun observe(onDetail: (String) -> Unit = {}, onStatus: (Status) -> Unit) {
        removeObserver()
        sawActive = false
        val l = object : VpnStatus.StateListener {
            override fun updateState(
                state: String?, logmessage: String?, localizedResId: Int,
                level: ConnectionStatus?, intent: Intent?
            ) {
                if (!state.isNullOrBlank()) onDetail(state)
                val status: Status? = when (level) {
                    ConnectionStatus.LEVEL_CONNECTED -> { sawActive = true; Status.Connected }
                    ConnectionStatus.LEVEL_START,
                    ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                    ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> { sawActive = true; Status.Connecting }
                    ConnectionStatus.LEVEL_NONETWORK,
                    ConnectionStatus.LEVEL_VPNPAUSED -> if (sawActive) Status.Waiting else null
                    ConnectionStatus.LEVEL_AUTH_FAILED ->
                        Status.Failed("Authentication failed: check your username and password.")
                    ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT ->
                        Status.Failed("This file requires credentials or a key passphrase: enter them in the username/password fields.")
                    // LEVEL_NOTCONNECTED يصل أيضاً قبل البدء؛ نعتمده فقط بعد أن رأينا حالة نشطة
                    ConnectionStatus.LEVEL_NOTCONNECTED -> if (sawActive) Status.Stopped else null
                    else -> null
                }
                if (status != null) onStatus(status)
            }

            override fun setConnectedVPN(uuid: String?) {}
        }
        listener = l
        VpnStatus.addStateListener(l)
    }

    @Volatile private var lastLogItem: Any? = null

    /** يضع مؤشر السجل عند آخر عنصر حالي حتى نقرأ أسطر الجلسة الجديدة فقط. */
    fun markLogCursor() {
        lastLogItem = try { VpnStatus.getlogbuffer().lastOrNull() } catch (_: Throwable) { null }
    }

    /** أسطر السجل الجديدة منذ آخر استدعاء. */
    fun newLogLines(ctx: Context): List<String> = try {
        val buf = VpnStatus.getlogbuffer()
        val last = lastLogItem
        val idx = if (last == null) -1 else buf.indexOfLast { it === last }
        val fresh = if (idx >= 0) buf.drop(idx + 1) else buf.takeLast(60)
        if (fresh.isNotEmpty()) lastLogItem = fresh.last()
        fresh.map { it.getString(ctx) }
    } catch (_: Throwable) { emptyList() }

    /** آخر أسطر من سجل ics-openvpn (لتشخيص التعليق). */
    fun recentLog(ctx: Context, n: Int = 8): String = try {
        VpnStatus.getlogbuffer().takeLast(n).joinToString("\n") { it.getString(ctx) }
    } catch (_: Throwable) { "" }

    @Synchronized
    fun removeObserver() {
        listener?.let { VpnStatus.removeStateListener(it) }
        listener = null
    }

    /** يوقف اتصال ics-openvpn (يربط الخدمة ثم يستدعي stopVPN). */
    fun stop(ctx: Context) {
        removeObserver()
        try { ProfileManager.setConnectedVpnProfileDisconnected(ctx) } catch (_: Throwable) {}
        val app = ctx.applicationContext
        val intent = Intent(app, OpenVPNService::class.java).setAction(OpenVPNService.START_SERVICE)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    // الواجهة الحالية في ics-openvpn عبر AIDL (كما في DisconnectVPN)
                    IOpenVPNServiceInternal.Stub.asInterface(binder)?.stopVPN(false)
                } catch (_: Throwable) {
                } finally {
                    try { app.unbindService(this) } catch (_: Throwable) {}
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        try {
            // بدون BIND_AUTO_CREATE: إن لم تكن الخدمة تعمل فلا شيء لإيقافه
            app.bindService(intent, conn, 0)
        } catch (_: Throwable) {}
    }
}
