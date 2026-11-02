package com.xraytunnel.app.data

import org.json.JSONObject
import java.util.UUID

/** سيرفر واحد، يغطي: vmess, vless, trojan, shadowsocks, socks, hysteria2, wireguard */
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val subId: String = "",
    val name: String = "",
    val protocol: String = "vless",
    val address: String = "",
    val port: Int = 443,
    val secret: String = "",       // uuid / password / "user:pass" (socks) / WG private key
    val method: String = "",       // cipher (ss) أو security (vmess)
    val flow: String = "",
    val alterId: Int = 0,
    val network: String = "tcp",   // tcp, ws, grpc, h2, kcp, httpupgrade, xhttp
    val headerType: String = "none",
    val host: String = "",
    val path: String = "",         // path أو serviceName (grpc) أو seed (kcp)
    val security: String = "none", // none, tls, reality
    val sni: String = "",
    val fingerprint: String = "",
    val alpn: String = "",
    val insecure: Boolean = false,
    val publicKey: String = "",    // reality pbk / WG peer public key
    val shortId: String = "",
    val spiderX: String = "",
    val obfsPassword: String = "",
    val localAddress: String = "", // WireGuard
    val reserved: String = "",     // WireGuard "1,2,3"
    val mtu: Int = 1280,
    val username: String = "",     // SSH: اسم المستخدم (كلمة المرور في secret)
    val payload: String = "",      // SSH WS: بايلود HTTP مع [crlf] [host] [split] ...
    val locked: Boolean = false    // سيرفر مستورد من ملف مقفل: مخفي، لا تعديل ولا مشاركة
) {
    /** سيرفرات الحافظة فقط (subId فارغ) قابلة للتعديل والحذف؛ سيرفرات التحديث مقفلة. */
    val isEditable: Boolean get() = subId.isBlank() && !locked

    /** سيرفرات التحديث (GitHub) فقط. */
    val isManaged: Boolean get() = subId.isNotBlank()

    /** يمكن حذفه: سيرفرات الحافظة والملفات (المقفلة وغير المقفلة). */
    val isDeletable: Boolean get() = subId.isBlank()

    /** Stable identity for duplicate detection across repeated subscription checks. */
    fun identityKey(): String = listOf(
        protocol, address, port.toString(), secret, method, flow, alterId.toString(),
        network, headerType, host, path, security, sni, fingerprint, alpn,
        insecure.toString(), publicKey, shortId, spiderX, obfsPassword,
        localAddress, reserved, mtu.toString(), username, payload
    ).joinToString("\u001f")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("subId", subId); put("name", name); put("protocol", protocol)
        put("address", address); put("port", port); put("secret", secret); put("method", method)
        put("flow", flow); put("alterId", alterId); put("network", network)
        put("headerType", headerType); put("host", host); put("path", path)
        put("security", security); put("sni", sni); put("fingerprint", fingerprint)
        put("alpn", alpn); put("insecure", insecure); put("publicKey", publicKey)
        put("shortId", shortId); put("spiderX", spiderX); put("obfsPassword", obfsPassword)
        put("localAddress", localAddress); put("reserved", reserved); put("mtu", mtu)
        put("username", username); put("payload", payload)
        put("locked", locked)
    }

    companion object {
        fun fromJson(o: JSONObject) = Server(
            id = o.optString("id"), subId = o.optString("subId"), name = o.optString("name"),
            protocol = o.optString("protocol", "vless"), address = o.optString("address"),
            port = o.optInt("port", 443), secret = o.optString("secret"),
            method = o.optString("method"), flow = o.optString("flow"),
            alterId = o.optInt("alterId"), network = o.optString("network", "tcp"),
            headerType = o.optString("headerType", "none"), host = o.optString("host"),
            path = o.optString("path"), security = o.optString("security", "none"),
            sni = o.optString("sni"), fingerprint = o.optString("fingerprint"),
            alpn = o.optString("alpn"), insecure = o.optBoolean("insecure"),
            publicKey = o.optString("publicKey"), shortId = o.optString("shortId"),
            spiderX = o.optString("spiderX"), obfsPassword = o.optString("obfsPassword"),
            localAddress = o.optString("localAddress"), reserved = o.optString("reserved"),
            mtu = o.optInt("mtu", 1280),
            username = o.optString("username"), payload = o.optString("payload"), locked = o.optBoolean("locked", false)
        )
    }
}
