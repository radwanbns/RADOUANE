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
                link.startsWith("ovpn://") || link.startsWith("openvpn://") -> ovpnLink(link)
                link.startsWith("wireguard://") || link.startsWith("wg://") -> uriBased(link, "wireguard")
                // ملف .ovpn خام
                link.contains("remote ") && (link.contains("client") || link.contains("dev tun") || link.contains("proto ")) -> ovpnConfig(link)
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
     * ssh://user:pass@host:port?type=ws|tcp&proxy=host:port&security=tls|none&sni=..&host=..&path=..&payload=..&allowInsecure=1#Name
     * أي منفذ مقبول (1..65535). type=tcp: SSH مباشر، type=ws: WebSocket + بايلود.
     * SlowDNS: type=slowdns&ns=ns.example.com&key=<64 hex>&resolver=8.8.8.8:53
     * UDP عبر UDPGW (يعمل مع كل الأنماط): udpgw=1&udpgwPort=7300  (أو udpgw=7300)
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
        val slowDns = q("type").lowercase() in listOf("slowdns", "dns", "dnstt") || q("ns").isNotBlank()
        val net = when {
            slowDns -> "slowdns"
            q("type").lowercase() in listOf("ws", "websocket", "payload") -> "ws"
            q("payload").isNotBlank() -> "ws"
            else -> "tcp"
        }
        // udpgw=1 | udpgw=7300 | udpgw=true ، والمنفذ اختياري عبر udpgwPort
        val udpRaw = q("udpgw").ifBlank { q("udp") }.lowercase()
        val udpOn = udpRaw.isNotBlank() && udpRaw !in listOf("0", "false", "no", "off")
        val udpPort = (q("udpgwPort").toIntOrNull() ?: udpRaw.toIntOrNull()?.takeIf { it > 1 })
            ?.takeIf { it in 1..65535 } ?: 7300
        val dnsNs = q("ns").trim().ifBlank { if (slowDns) host else "" }
        val proxy = q("proxy").trim()
        val proxyHost = if (proxy.contains(":")) proxy.substringBeforeLast(":") else proxy
        val proxyPort = if (proxy.contains(":")) proxy.substringAfterLast(":").toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080 else 8080
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
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            insecure = q("allowInsecure") == "1" || q("insecure") == "1",
            dnsNs = if (slowDns) dnsNs else "",
            dnsKey = if (slowDns) q("key").ifBlank { q("pubkey") }.trim() else "",
            dnsResolver = if (slowDns) q("resolver").ifBlank { q("dns") }.trim() else "",
            udpgw = udpOn,
            udpgwPort = udpPort
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

    /**
     * ovpn://[user:pass@]host:port?type=ws|tcp&payload=...&security=tls&sni=...&host=...&path=...&proto=udp|tcp&allowInsecure=1#Name
     * أو openvpn://...
     */
    private fun ovpnLink(link: String): Server? {
        val u = Uri.parse(link)
        val host = u.host ?: return null
        val port = if (u.port in 1..65535) u.port else 1194
        fun q(k: String) = u.getQueryParameter(k) ?: ""
        val slowDns = q("type").lowercase() in listOf("slowdns", "dns", "dnstt") || q("ns").isNotBlank()
        val net = when {
            slowDns -> "slowdns"
            q("type").lowercase() in listOf("ws", "websocket", "payload") -> "ws"
            q("payload").isNotBlank() -> "ws"
            else -> "tcp"
        }
        // udpgw=1 | udpgw=7300 | udpgw=true ، والمنفذ اختياري عبر udpgwPort
        val udpRaw = q("udpgw").ifBlank { q("udp") }.lowercase()
        val udpOn = udpRaw.isNotBlank() && udpRaw !in listOf("0", "false", "no", "off")
        val udpPort = (q("udpgwPort").toIntOrNull() ?: udpRaw.toIntOrNull()?.takeIf { it > 1 })
            ?.takeIf { it in 1..65535 } ?: 7300
        val dnsNs = q("ns").trim().ifBlank { if (slowDns) host else "" }
        val tls = q("security").lowercase() == "tls" || q("tls") == "1"
        val ui = u.encodedUserInfo ?: ""
        val user = if (ui.contains(":")) Uri.decode(ui.substringBefore(":")) else ""
        val pass = if (ui.contains(":")) Uri.decode(ui.substringAfter(":")) else Uri.decode(ui)
        val proto = q("proto").lowercase().takeIf { it == "udp" || it == "tcp" }
            ?: q("protocol").lowercase().takeIf { it == "udp" || it == "tcp" }
            ?: "tcp"
        val proxy = q("proxy").trim()
        val proxyHost = if (proxy.contains(":")) proxy.substringBeforeLast(":") else proxy
        val proxyPort = if (proxy.contains(":"))
            proxy.substringAfterLast(":").toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080
        else 8080
        val configB64 = q("config").ifBlank { q("ovpn") }
        val ovpnCfg = if (configB64.isNotBlank()) {
            try {
                val flags = Base64.NO_WRAP or Base64.URL_SAFE
                String(Base64.decode(configB64, flags), Charsets.UTF_8).trim()
            } catch (_: Exception) {
                try {
                    String(Base64.decode(configB64, Base64.DEFAULT), Charsets.UTF_8).trim()
                } catch (_: Exception) {
                    ""
                }
            }
        } else ""
        return Server(
            name = u.fragment?.takeIf { it.isNotBlank() } ?: "OpenVPN",
            protocol = "ovpn",
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
            method = proto,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            insecure = q("allowInsecure") == "1" || q("insecure") == "1",
            ovpnConfig = ovpnCfg
        )
    }

    /**
     * تحليل محتوى ملف .ovpn خام.
     * يستخرج remote + port + proto ويعاملها كـ OVPN مع إمكانية إضافة Payload لاحقاً.
     * يدعم أي منفذ (1–65535) لـ tcp و udp.
     * صيغ مدعومة:
     *   remote host
     *   remote host port
     *   remote host port proto
     *   port N
     *   proto tcp|udp|tcp4|udp4|...
     */
    fun ovpnConfig(content: String): Server? {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
        var remoteHost = ""
        var remotePort = 0
        var proto = "udp"
        var name = "OpenVPN"
        var username = ""
        var password = ""
        var globalPort = 0

        fun parsePort(s: String): Int? = s.toIntOrNull()?.takeIf { it in 1..65535 }
        fun parseProto(s: String): String? {
            val p = s.lowercase()
            return when {
                p.startsWith("udp") -> "udp"
                p.startsWith("tcp") -> "tcp"
                else -> null
            }
        }

        for (line in lines) {
            val parts = line.split(Regex("\\s+"))
            when (parts[0].lowercase()) {
                "remote" -> {
                    // remote <host> [port] [proto]
                    if (parts.size >= 2 && remoteHost.isBlank()) {
                        remoteHost = parts[1].trim().trim('[', ']')
                    }
                    if (parts.size >= 3) parsePort(parts[2])?.let { if (remotePort == 0) remotePort = it }
                    if (parts.size >= 4) parseProto(parts[3])?.let { proto = it }
                }
                "port" -> if (parts.size >= 2) parsePort(parts[1])?.let { globalPort = it }
                "proto" -> if (parts.size >= 2) parseProto(parts[1])?.let { proto = it }
                "auth-user-pass" -> { /* manual */ }
                "dev" -> { /* ignore */ }
            }
        }
        if (remoteHost.isBlank()) return null

        // أولوية: منفذ remote ثم port العام ثم الافتراضي حسب proto
        val finalPort = when {
            remotePort in 1..65535 -> remotePort
            globalPort in 1..65535 -> globalPort
            proto == "tcp" -> 443
            else -> 1194
        }

        val commentName = content.lines().firstOrNull { it.trim().startsWith("#") }?.removePrefix("#")?.trim()
        if (!commentName.isNullOrBlank()) name = commentName

        return Server(
            name = name,
            protocol = "ovpn",
            address = remoteHost,
            port = finalPort,
            username = username,
            secret = password,
            // network = وضع النفق (tcp/ws للـ payload). proto الأصلي يُحفظ في method
            network = "tcp",
            security = "none",
            method = proto,
            payload = "",
            // النص الكامل (شهادات/مفاتيح/tls-auth...) يُمرَّر كما هو إلى ics-openvpn
            ovpnConfig = content.trim()
        )
    }
}
