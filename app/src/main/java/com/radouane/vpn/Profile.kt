package com.radouane.vpn

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** A saved connection profile. `raw` keeps the original share link or full Xray JSON. */
data class ServerProfile(
    val name: String,
    val protocol: String,
    val address: String,
    val port: Int,
    val raw: String
)

/**
 * Import layer for common proxy share links and full Xray JSON.
 * Parsing a profile is not the same as running it: the Xray engine/TUN bridge
 * must be integrated in RadouaneVpnService before any profile can connect.
 */
object ProfileParser {
    private val schemes = mapOf(
        "vless" to "VLESS",
        "vmess" to "VMess",
        "trojan" to "Trojan",
        "ss" to "Shadowsocks",
        "socks" to "SOCKS",
        "socks5" to "SOCKS5",
        "http" to "HTTP",
        "https" to "HTTP over TLS",
        "hysteria" to "Hysteria",
        "hy2" to "Hysteria2",
        "hysteria2" to "Hysteria2",
        "masque" to "MASQUE"
    )

    fun parse(input: String): ServerProfile {
        val value = input.trim()
        require(value.isNotEmpty()) { "الرابط فارغ" }
        if (value.startsWith("{")) return parseXrayJson(value)
        if (value.contains("[Interface]", ignoreCase = true) && value.contains("[Peer]", ignoreCase = true)) return parseWireGuard(value)
        val scheme = value.substringBefore("://", "").lowercase()
        return when (scheme) {
            "vmess" -> parseVmess(value)
            "ss" -> parseShadowsocks(value)
            "vless", "trojan", "socks", "socks5", "http", "https",
            "hysteria", "hy2", "hysteria2", "masque" -> parseUri(value, schemes.getValue(scheme))
            else -> throw IllegalArgumentException(
                "الصيغة غير معروفة. استعمل VLESS أو VMess أو Trojan أو SS أو SOCKS أو HTTP أو Hysteria أو Xray JSON"
            )
        }
    }

    private fun parseUri(raw: String, protocol: String): ServerProfile {
        val uri = URI(raw)
        val host = uri.host ?: throw IllegalArgumentException("عنوان السيرفر غير صالح")
        val port = uri.port.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("منفذ السيرفر غير صالح")
        val name = uri.fragment?.let { URLDecoder.decode(it, "UTF-8") }
            ?.takeIf { it.isNotBlank() } ?: "$protocol • $host"
        return ServerProfile(name, protocol, host, port, raw)
    }

    private fun parseVmess(raw: String): ServerProfile {
        val encoded = raw.substringAfter("vmess://").substringBefore('#').substringBefore('?')
        val json = String(Base64.decode(padBase64(encoded), Base64.DEFAULT), Charsets.UTF_8)
        val obj = JSONObject(json)
        val host = obj.optString("add")
        val port = obj.optString("port").toIntOrNull() ?: obj.optInt("port")
        require(host.isNotBlank() && port in 1..65535) { "بيانات VMess غير مكتملة" }
        return ServerProfile(obj.optString("ps").ifBlank { "VMess • $host" }, "VMess", host, port, raw)
    }

    private fun parseShadowsocks(raw: String): ServerProfile {
        val body = raw.substringAfter("ss://").substringBefore('#').substringBefore('?')
        val decoded = try {
            String(Base64.decode(padBase64(body), Base64.URL_SAFE or Base64.NO_WRAP or Base64.DEFAULT))
        } catch (_: Exception) { body }
        val endpoint = decoded.substringAfterLast('@')
        val host: String
        val port: Int
        if (endpoint.startsWith("[")) {
            host = endpoint.substringAfter('[').substringBefore(']')
            port = endpoint.substringAfterLast(':').toIntOrNull() ?: -1
        } else {
            host = endpoint.substringBeforeLast(':', "")
            port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: -1
        }
        require(host.isNotBlank() && port in 1..65535) { "رابط Shadowsocks غير صالح أو غير مدعوم" }
        return ServerProfile("Shadowsocks • $host", "Shadowsocks", host, port, raw)
    }

    private fun parseWireGuard(raw: String): ServerProfile {
        val endpointLine = raw.lineSequence().firstOrNull { it.trim().startsWith("Endpoint", ignoreCase = true) }
            ?: throw IllegalArgumentException("ملف WireGuard لا يحتوي على Endpoint")
        val endpoint = endpointLine.substringAfter('=', "").trim()
        val host: String
        val port: Int
        if (endpoint.startsWith("[")) {
            host = endpoint.substringAfter('[').substringBefore(']')
            port = endpoint.substringAfterLast(':').toIntOrNull() ?: -1
        } else {
            host = endpoint.substringBeforeLast(':', "")
            port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: -1
        }
        require(host.isNotBlank() && port in 1..65535) { "Endpoint في ملف WireGuard غير صالح" }
        return ServerProfile("WireGuard • $host", "WIREGUARD", host, port, raw)
    }

    private fun parseXrayJson(raw: String): ServerProfile {
        val root = JSONObject(raw)
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("ملف Xray JSON لا يحتوي على outbounds")
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            val protocol = outbound.optString("protocol")
            if (protocol.isBlank() || protocol == "freedom" || protocol == "blackhole" || protocol == "dns") continue
            val settings = outbound.optJSONObject("settings") ?: JSONObject()
            val servers = settings.optJSONArray("vnext") ?: settings.optJSONArray("servers")
            val first = servers?.optJSONObject(0)
            val host = first?.optString("address", "")?.takeIf { it.isNotBlank() }
                ?: settings.optString("address", "")
            val port = first?.optInt("port", 0) ?: settings.optInt("port", 0)
            val label = outbound.optString("tag").ifBlank { protocol.uppercase() }
            return ServerProfile(label, protocol.uppercase(), host, port, raw)
        }
        throw IllegalArgumentException("لم يتم العثور على Proxy outbound صالح في ملف Xray JSON")
    }

    private fun padBase64(value: String): String = value + "=".repeat((4 - value.length % 4) % 4)
}
