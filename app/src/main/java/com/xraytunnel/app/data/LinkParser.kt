package com.xraytunnel.app.data

import android.net.Uri
import android.util.Base64
import org.json.JSONObject

/** يحلل روابط المشاركة لكل البروتوكولات المدعومة. */
object LinkParser {

    fun parse(raw: String): Server? {
        val link = raw.trim()
        return try {
            when {
                link.startsWith("vmess://") -> vmess(link)
                link.startsWith("vless://") -> uriBased(link, "vless")
                link.startsWith("trojan://") -> uriBased(link, "trojan")
                link.startsWith("ss://") -> shadowsocks(link)
                link.startsWith("socks://") || link.startsWith("socks5://") -> socks(link)
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> uriBased(link, "hysteria2")
                link.startsWith("ssh://") -> ssh(link)
                link.startsWith("wireguard://") || link.startsWith("wg://") -> uriBased(link, "wireguard")
                else -> null
            }
        } catch (e: Exception) { null }
    }

    fun b64(s: String): String? = try {
        val clean = s.trim().replace("\n", "").replace("\r", "")
        String(Base64.decode(clean, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_PADDING), Charsets.UTF_8)
    } catch (e: Exception) { null }

    private fun vmess(link: String): Server? {
        val j = JSONObject(b64(link.removePrefix("vmess://")) ?: return null)
        val net = j.optString("net", "tcp").ifEmpty { "tcp" }
        val tls = j.optString("tls")
        return Server(
            name = j.optString("ps").ifEmpty { "VMess" },
            protocol = "vmess",
            address = j.optString("add"),
            port = j.optString("port").toIntOrNull() ?: 443,
            secret = j.optString("id"),
            alterId = j.optString("aid").toIntOrNull() ?: 0,
            method = j.optString("scy").ifEmpty { "auto" },
            network = net,
            headerType = j.optString("type").ifEmpty { "none" },
            host = j.optString("host"),
            path = j.optString("path"),
            security = if (tls == "tls") "tls" else "none",
            sni = j.optString("sni"),
            alpn = j.optString("alpn"),
            fingerprint = j.optString("fp"),
            insecure = j.optBoolean("allowInsecure", j.optBoolean("insecure", false))
        )
    }

    private fun uriBased(link: String, protocol: String): Server? {
        val u = Uri.parse(link)
        val host = u.host ?: return null
        val port = if (u.port > 0) u.port else 443
        fun q(k: String) = u.getQueryParameter(k) ?: ""
        val defaultSecurity = if (protocol == "trojan" || protocol == "hysteria2") "tls" else "none"
        val net = q("type").ifEmpty { "tcp" }
        val path = when (net) {
            "grpc" -> q("serviceName")
            "kcp", "mkcp" -> q("seed")
            else -> q("path")
        }
        return Server(
            name = u.fragment?.takeIf { it.isNotBlank() } ?: protocol.uppercase(),
            protocol = protocol,
            address = host,
            port = port,
            secret = Uri.decode(u.userInfo ?: ""),
            method = q("encryption"),
            flow = q("flow"),
            network = net,
            headerType = q("headerType").ifEmpty { "none" },
            host = q("host"),
            path = path,
            security = q("security").ifEmpty { defaultSecurity },
            sni = q("sni").ifEmpty { q("peer") },
            fingerprint = q("fp"),
            alpn = q("alpn"),
            insecure = q("allowInsecure") == "1" || q("insecure") == "1",
            publicKey = q("pbk").ifEmpty { q("publickey") },
            shortId = q("sid"),
            spiderX = q("spx"),
            obfsPassword = q("obfs-password"),
            localAddress = q("address"),
            reserved = q("reserved"),
            mtu = q("mtu").toIntOrNull() ?: 1280
        )
    }

    /**
     * ssh://user:pass@host:port?type=ws|tcp&security=tls|none&sni=..&host=..&path=..&payload=..&allowInsecure=1#Name
     * أي منفذ مقبول (1..65535). type=tcp: SSH مباشر، type=ws: WebSocket + بايلود.
     */
    private fun ssh(link: String): Server? {
        val u = Uri.parse(link)
        val host = u.host ?: return null
        val port = if (u.port in 1..65535) u.port else 22
        val ui = u.encodedUserInfo ?: ""
        val user = Uri.decode(ui.substringBefore(":"))
        val pass = Uri.decode(ui.substringAfter(":", ""))
        if (user.isBlank()) return null
        fun q(k: String) = u.getQueryParameter(k) ?: ""
        val net = when (q("type").lowercase()) { "ws", "websocket" -> "ws"; else -> "tcp" }
        val tls = q("security").lowercase() == "tls" || q("tls") == "1"
        return Server(
            name = u.fragment?.takeIf { it.isNotBlank() } ?: "SSH",
            protocol = "ssh",
            address = host,
            port = port,
            username = user,
            secret = pass,
            network = net,
            security = if (tls) "tls" else "none",
            sni = q("sni"),
            host = q("host"),
            path = q("path"),
            payload = q("payload"),
            insecure = q("allowInsecure") == "1" || q("insecure") == "1"
        )
    }

    private fun shadowsocks(link: String): Server? {
        val body = link.removePrefix("ss://")
        val name = Uri.decode(body.substringAfter("#", ""))
        val main = body.substringBefore("#").substringBefore("?")
        val userInfo: String
        val hostPort: String
        if (main.contains("@")) {
            val ui = main.substringBeforeLast("@")
            userInfo = if (ui.contains(":")) Uri.decode(ui) else (b64(ui) ?: return null)
            hostPort = main.substringAfterLast("@")
        } else {
            val dec = b64(main) ?: return null
            userInfo = dec.substringBeforeLast("@")
            hostPort = dec.substringAfterLast("@")
        }
        val host = hostPort.substringBeforeLast(":").trim('[', ']')
        val port = hostPort.substringAfterLast(":").toIntOrNull() ?: return null
        return Server(
            name = name.ifEmpty { "Shadowsocks" },
            protocol = "shadowsocks",
            address = host, port = port,
            method = userInfo.substringBefore(":"),
            secret = userInfo.substringAfter(":")
        )
    }

    private fun socks(link: String): Server? {
        val u = Uri.parse(link.replace("socks5://", "socks://"))
        val host = u.host ?: return null
        val ui = u.userInfo ?: ""
        val cred = if (ui.isEmpty()) "" else if (ui.contains(":")) Uri.decode(ui) else (b64(ui) ?: ui)
        return Server(
            name = u.fragment?.takeIf { it.isNotBlank() } ?: "SOCKS",
            protocol = "socks", address = host,
            port = if (u.port > 0) u.port else 1080, secret = cred
        )
    }
}
