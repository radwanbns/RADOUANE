package com.xraytunnel.app.core

import com.xraytunnel.app.data.Server
import org.json.JSONArray
import org.json.JSONObject

/** يبني ملف إعدادات Xray JSON من السيرفر المختار. */
object ConfigBuilder {

    fun build(s: Server): String {
        val root = JSONObject()
        root.put("log", JSONObject().put("loglevel", "warning"))
        root.put("dns", JSONObject().put("servers", JSONArray(listOf("https+local://1.1.1.1/dns-query", "localhost"))))

        root.put("inbounds", JSONArray().put(
            JSONObject()
                .put("tag", "tun-in")
                .put("protocol", "tun")
                .put("settings", JSONObject().put("name", "xray0").put("MTU", 1500))
                .put("sniffing", JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray(listOf("http", "tls", "quic"))))
        ))

        root.put("outbounds", JSONArray()
            .put(proxyOutbound(s))
            .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            .put(JSONObject().put("tag", "block").put("protocol", "blackhole")))

        val privateIps = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8",
            "169.254.0.0/16", "fc00::/7", "fe80::/10")
        root.put("routing", JSONObject()
            .put("domainStrategy", "AsIs")
            .put("rules", JSONArray().put(
                JSONObject().put("type", "field").put("ip", JSONArray(privateIps)).put("outboundTag", "direct")
            )))
        return root.toString()
    }

    private fun proxyOutbound(s: Server): JSONObject {
        val o = JSONObject().put("tag", "proxy")
        when (s.protocol) {
            "vmess" -> o.put("protocol", "vmess").put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", s.address).put("port", s.port).put("users", JSONArray().put(
                    JSONObject().put("id", s.secret).put("alterId", s.alterId)
                        .put("security", s.method.ifEmpty { "auto" }))))))
                .put("streamSettings", stream(s))

            "vless" -> o.put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", s.address).put("port", s.port).put("users", JSONArray().put(
                    JSONObject().put("id", s.secret).put("encryption", "none")
                        .apply { if (s.flow.isNotEmpty()) put("flow", s.flow) })))))
                .put("streamSettings", stream(s))

            "trojan" -> o.put("protocol", "trojan").put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", s.address).put("port", s.port).put("password", s.secret))))
                .put("streamSettings", stream(s))

            "shadowsocks" -> o.put("protocol", "shadowsocks").put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", s.address).put("port", s.port)
                    .put("method", s.method).put("password", s.secret))))
                .put("streamSettings", stream(s))

            "socks" -> o.put("protocol", "socks").put("settings", JSONObject().put("servers", JSONArray().put(
                JSONObject().put("address", s.address).put("port", s.port).apply {
                    if (s.secret.contains(":")) put("users", JSONArray().put(
                        JSONObject().put("user", s.secret.substringBefore(":")).put("pass", s.secret.substringAfter(":"))))
                })))

            "hysteria2" -> {
                o.put("protocol", "hysteria")
                    .put("settings", JSONObject().put("version", 2).put("address", s.address).put("port", s.port))
                val tls = JSONObject()
                    .put("serverName", s.sni.ifEmpty { s.address })
                    .put("alpn", JSONArray(listOf("h3")))
                    .put("allowInsecure", s.insecure)
                val hy = JSONObject().put("version", 2).put("auth", s.secret)
                o.put("streamSettings", JSONObject()
                    .put("network", "hysteria").put("security", "tls")
                    .put("tlsSettings", tls).put("hysteriaSettings", hy))
            }

            "wireguard" -> {
                val peer = JSONObject().put("publicKey", s.publicKey).put("endpoint", "${s.address}:${s.port}")
                val settings = JSONObject()
                    .put("secretKey", s.secret)
                    .put("address", JSONArray((s.localAddress.ifEmpty { "172.16.0.2/32" }).split(",").map { it.trim() }))
                    .put("peers", JSONArray().put(peer))
                    .put("mtu", s.mtu)
                if (s.reserved.isNotEmpty())
                    settings.put("reserved", JSONArray(s.reserved.split(",").mapNotNull { it.trim().toIntOrNull() }))
                o.put("protocol", "wireguard").put("settings", settings)
            }
            else -> error("Unsupported protocol: ${s.protocol}")
        }
        return o
    }

    private fun stream(s: Server): JSONObject {
        val net = when (s.network) { "h2" -> "http"; "mkcp" -> "kcp"; "splithttp" -> "xhttp"; else -> s.network }
        val st = JSONObject().put("network", net)
        val hostObj = { if (s.host.isNotEmpty()) JSONObject().put("Host", s.host) else JSONObject() }
        when (net) {
            "tcp" -> if (s.headerType == "http") st.put("tcpSettings", JSONObject().put("header", JSONObject()
                .put("type", "http").put("request", JSONObject()
                    .put("path", JSONArray(listOf(s.path.ifEmpty { "/" })))
                    .put("headers", JSONObject().put("Host", JSONArray(s.host.split(",").filter { it.isNotBlank() }))))))
            "kcp" -> st.put("kcpSettings", JSONObject()
                .put("header", JSONObject().put("type", s.headerType.ifEmpty { "none" }))
                .apply { if (s.path.isNotEmpty()) put("seed", s.path) })
            "ws" -> st.put("wsSettings", JSONObject().put("path", s.path.ifEmpty { "/" }).put("headers", hostObj()))
            "http" -> st.put("httpSettings", JSONObject().put("path", s.path.ifEmpty { "/" })
                .put("host", JSONArray(s.host.split(",").filter { it.isNotBlank() })))
            "grpc" -> st.put("grpcSettings", JSONObject().put("serviceName", s.path))
            "httpupgrade" -> st.put("httpupgradeSettings", JSONObject().put("path", s.path.ifEmpty { "/" }).put("host", s.host))
            "xhttp" -> st.put("xhttpSettings", JSONObject().put("path", s.path.ifEmpty { "/" }).put("host", s.host))
        }
        val sni = s.sni.ifEmpty { s.host }.ifEmpty { s.address }
        when (s.security) {
            "tls" -> {
                st.put("security", "tls")
                val tls = JSONObject().put("serverName", sni).put("allowInsecure", s.insecure)
                if (s.fingerprint.isNotEmpty()) tls.put("fingerprint", s.fingerprint)
                if (s.alpn.isNotEmpty()) tls.put("alpn", JSONArray(s.alpn.split(",")))
                st.put("tlsSettings", tls)
            }
            "reality" -> {
                st.put("security", "reality")
                st.put("realitySettings", JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", s.fingerprint.ifEmpty { "chrome" })
                    .put("publicKey", s.publicKey)
                    .put("shortId", s.shortId)
                    .put("spiderX", s.spiderX))
            }
        }
        return st
    }
}
