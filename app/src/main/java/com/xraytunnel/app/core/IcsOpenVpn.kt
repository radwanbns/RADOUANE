package com.xraytunnel.app.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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

    /** يحلّل ملف .ovpn ويبدأ الاتصال. يرمي استثناءً برسالة مفهومة عند خطأ في الملف. */
    fun start(ctx: Context, server: Server, forwardPort: Int = 0) {
        val parser = ConfigParser()
        try {
            parser.parseConfig(StringReader(server.ovpnConfig))
        } catch (e: Exception) {
            throw IllegalStateException("ملف .ovpn غير صالح: ${e.message ?: e.javaClass.simpleName}")
        }
        val profile = try {
            parser.convertProfile()
        } catch (e: Exception) {
            throw IllegalStateException(
                "تعذّر تحويل ملف .ovpn: ${e.message ?: e.javaClass.simpleName}. " +
                    "تأكد أن الشهادات والمفاتيح مضمّنة داخل الملف (<ca> <cert> <key> <tls-auth>) وليست ملفات خارجية."
            )
        }

        profile.mName = server.name.ifBlank { "OpenVPN" }

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
                        Status.Failed("فشلت المصادقة: تحقق من اسم المستخدم وكلمة المرور.")
                    ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT ->
                        Status.Failed("هذا الملف يحتاج بيانات دخول أو كلمة سر مفتاح: أدخلها في حقلي المستخدم/كلمة المرور.")
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
