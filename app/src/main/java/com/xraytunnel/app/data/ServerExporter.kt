package com.xraytunnel.app.data

import android.net.Uri
import android.util.Base64
import org.json.JSONObject

/** Export a server as portable JSON or a protocol URI. */
object ServerExporter {
    fun toJson(server: Server): String = server.toJson().apply {
        remove("id")
        remove("subId")
        remove("locked")
    }.toString(2)

    fun toUri(s: Server): String? {
        val name = Uri.encode(s.name)
        val host = if (s.address.contains(':') && !s.address.startsWith("[")) "[${s.address}]" else s.address
        val authority = "${Uri.encode(s.secret)}@$host:${s.port}"
        fun query(vararg pairs: Pair<String, String>): String = pairs
            .filter { it.second.isNotEmpty() }
            .joinToString("&") { "${Uri.encode(it.first)}=${Uri.encode(it.second)}" }
        return when (s.protocol.lowercase()) {
            "vless", "trojan", "hysteria2", "socks" -> {
                val scheme = if (s.protocol == "socks") "socks5" else s.protocol
                val q = query(
                    "encryption" to s.method, "flow" to s.flow, "type" to s.network,
                    "security" to s.security, "sni" to s.sni, "fp" to s.fingerprint,
                    "alpn" to s.alpn, "host" to s.host, "path" to s.path,
                    "serviceName" to if (s.network == "grpc") s.path else "",
                    "pbk" to s.publicKey, "sid" to s.shortId, "spx" to s.spiderX,
                    "allowInsecure" to if (s.insecure) "1" else ""
                )
                "$scheme://$authority${if (q.isNotEmpty()) "?$q" else ""}#$name"
            }
            "vmess" -> {
                val j = JSONObject().apply {
                    put("v", "2"); put("ps", s.name); put("add", s.address); put("port", s.port.toString())
                    put("id", s.secret); put("aid", s.alterId.toString()); put("scy", s.method.ifBlank { "auto" })
                    put("net", s.network); put("type", s.headerType); put("host", s.host); put("path", s.path)
                    put("tls", if (s.security == "tls") "tls" else ""); put("sni", s.sni)
                    put("alpn", s.alpn); put("fp", s.fingerprint); put("allowInsecure", s.insecure)
                }
                "vmess://" + Base64.encodeToString(j.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }
            "ssh" -> {
                val q = query(
                    "type" to s.network, "security" to s.security, "sni" to s.sni,
                    "host" to s.host, "path" to s.path, "payload" to s.payload,
                    "proxy" to if (s.proxyHost.isNotBlank()) "${s.proxyHost}:${s.proxyPort}" else "",
                    "allowInsecure" to if (s.insecure) "1" else "",
                    "ns" to if (s.isSlowDns) s.dnsNs else "",
                    "key" to if (s.isSlowDns) s.dnsKey else "",
                    "resolver" to if (s.isSlowDns) s.dnsResolver else "",
                    "udpgw" to if (s.udpgw) "1" else "",
                    "udpgwPort" to if (s.udpgw && s.udpgwPort != 7300) s.udpgwPort.toString() else ""
                )
                "ssh://${Uri.encode(s.username)}:${Uri.encode(s.secret)}@$host:${s.port}" +
                    "${if (q.isNotEmpty()) "?$q" else ""}#$name"
            }
            "ovpn", "openvpn" -> {
                // Real .ovpn configs are embedded as base64 in the "config" query param.
                val configB64 = if (s.ovpnConfig.isNotBlank()) {
                    Base64.encodeToString(
                        s.ovpnConfig.toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP or Base64.URL_SAFE
                    )
                } else ""
                val q = query(
                    "type" to s.network, "security" to s.security, "sni" to s.sni,
                    "host" to s.host, "path" to s.path, "payload" to s.payload,
                    "proto" to s.method.ifBlank { "tcp" },
                    "proxy" to if (s.proxyHost.isNotBlank()) "${s.proxyHost}:${s.proxyPort}" else "",
                    "allowInsecure" to if (s.insecure) "1" else "",
                    "config" to configB64
                )
                val userInfo = when {
                    s.username.isNotBlank() && s.secret.isNotBlank() ->
                        "${Uri.encode(s.username)}:${Uri.encode(s.secret)}"
                    s.username.isNotBlank() -> Uri.encode(s.username)
                    s.secret.isNotBlank() -> Uri.encode(s.secret)
                    else -> ""
                }
                val effectiveHost = if (s.address.isNotBlank()) host else "openvpn"
                val effectivePort = if (s.port in 1..65535) s.port else 1194
                val authority = if (userInfo.isNotEmpty()) "$userInfo@$effectiveHost:$effectivePort"
                    else "$effectiveHost:$effectivePort"
                "ovpn://$authority${if (q.isNotEmpty()) "?$q" else ""}#$name"
            }
            "shadowsocks" -> {
                val user = Base64.encodeToString("${s.method}:${s.secret}".toByteArray(), Base64.NO_WRAP)
                "ss://$user@$host:${s.port}#$name"
            }
            else -> null
        }
    }
}
