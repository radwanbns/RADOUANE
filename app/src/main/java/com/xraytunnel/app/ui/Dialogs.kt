package com.xraytunnel.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xraytunnel.app.R
import com.xraytunnel.app.data.Defaults
import com.xraytunnel.app.data.Server

/** نافذة قناة التلغرام. */
@Composable
fun TelegramDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_telegram_brand),
                contentDescription = stringResource(R.string.telegram_title),
                modifier = Modifier.size(40.dp),
                tint = Color.Unspecified
            )
        },
        title = {
            Text(
                stringResource(R.string.telegram_title),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.telegram_message),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            // Keep default Material button colors (no custom green)
            Button(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Defaults.telegramUrl()))) }
                onClose()
            }) { Text(stringResource(R.string.open_channel)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(R.string.later)) }
        }
    )
}

/** أنماط SSH: (TLS, Proxy, Payload). */
private val SSH_MODES = listOf(
    "SSH-Direct" to Triple(false, false, false),
    "SSH-Proxy" to Triple(false, true, false),
    "SSH-Payload" to Triple(false, false, true),
    "SSH-Proxy-Payload" to Triple(false, true, true),
    "SSH-TLS" to Triple(true, false, false),
    "SSH-TLS-Proxy" to Triple(true, true, false),
    "SSH-TLS-Payload" to Triple(true, false, true),
    "SSH-TLS-Proxy-Payload" to Triple(true, true, true)
)

/** نمط SSH فوق نفق DNS (dnstt / SlowDNS): لا يشارك TLS/Proxy/Payload لأن النقل هو DNS نفسه. */
private const val MODE_SLOWDNS = "SSH-SlowDNS"
private val DNS_KEY_RE = Regex("^[0-9a-fA-F]{64}$")

/** إضافة/تعديل سيرفر محلي (V2Ray أو SSH بكل أنماطه)، بما فيه عنوان الخادم والمنفذ. */
@Composable
fun EditServerDialog(server: Server, onSave: (Server) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(server.name) }
    var secret by remember { mutableStateOf(server.secret) }
    var address by remember { mutableStateOf(server.address) }
    var host by remember { mutableStateOf(server.host) }
    var port by remember { mutableStateOf(server.port.toString()) }
    var network by remember { mutableStateOf(server.network) }
    var security by remember { mutableStateOf(server.security) }
    var sni by remember { mutableStateOf(server.sni) }
    var path by remember { mutableStateOf(server.path) }
    var fp by remember { mutableStateOf(server.fingerprint) }
    var flow by remember { mutableStateOf(server.flow) }
    var insecure by remember { mutableStateOf(server.insecure) }
    var username by remember { mutableStateOf(server.username) }
    var payload by remember { mutableStateOf(server.payload) }
    var proxyHost by remember { mutableStateOf(server.proxyHost) }
    var proxyPort by remember { mutableStateOf(server.proxyPort.toString()) }
    var sshTls by remember { mutableStateOf(server.security == "tls") }
    var sshProxy by remember { mutableStateOf(server.proxyHost.isNotBlank()) }
    var sshPayload by remember { mutableStateOf(server.network == "ws") }
    var sshSlowDns by remember { mutableStateOf(server.protocol == "ssh" && server.network == "slowdns") }
    var dnsNs by remember { mutableStateOf(server.dnsNs) }
    var dnsKey by remember { mutableStateOf(server.dnsKey) }
    var dnsResolver by remember { mutableStateOf(server.dnsResolver) }
    var udpgw by remember { mutableStateOf(server.udpgw) }
    var udpgwPort by remember { mutableStateOf(server.udpgwPort.toString()) }
    var invalid by remember { mutableStateOf(false) }
    // بروتوكول V2Ray قابل للتغيير داخل الحوار
    var protocol by remember { mutableStateOf(server.protocol) }
    // proto الأصلي لـ OpenVPN (udp/tcp) محفوظ في method
    var ovpnConfig by remember { mutableStateOf(server.ovpnConfig) }
    var ovpnProto by remember { mutableStateOf(server.method.takeIf { it == "udp" || it == "tcp" } ?: "tcp") }

    val isSsh = protocol == "ssh"
    val isOvpn = protocol == "ovpn"
    val realOvpn = isOvpn && ovpnConfig.isNotBlank()
    val isPayloadProto = isSsh || isOvpn
    val streamBased = protocol in listOf("vmess", "vless", "trojan")
    val secretLabel = when (protocol) {
        "vmess", "vless" -> R.string.field_uuid
        "wireguard" -> R.string.field_key
        "ovpn" -> R.string.field_password   // اختياري لـ OVPN
        else -> R.string.field_password
    }
    val slowDnsOn = isSsh && sshSlowDns
    val currentMode = if (slowDnsOn) MODE_SLOWDNS else
        SSH_MODES.firstOrNull { it.second == Triple(sshTls, sshProxy, sshPayload) }?.first
            ?: SSH_MODES.first().first

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(R.string.edit_server)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it }, label = { Text(stringResource(R.string.field_address)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (streamBased) {
                    // اختيار نوع V2Ray: VLESS / VMess / Trojan
                    ChipRow(stringResource(R.string.field_protocol), listOf("vless", "vmess", "trojan"), protocol) { protocol = it }
                    OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.field_host_ws)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (isSsh || isOvpn) {
                    OutlinedTextField(username, { username = it }, label = {
                        Text(stringResource(if (isOvpn) R.string.field_username_optional else R.string.field_username))
                    }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.field_port)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(secretLabel)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (isPayloadProto) {
                    if (isSsh) {
                        ChipRow(stringResource(R.string.field_ssh_mode), SSH_MODES.map { it.first } + MODE_SLOWDNS, currentMode) { picked ->
                            if (picked == MODE_SLOWDNS) {
                                sshSlowDns = true; sshTls = false; sshProxy = false; sshPayload = false
                            } else {
                                SSH_MODES.firstOrNull { it.first == picked }?.second?.let { (t, pr, pl) ->
                                    sshSlowDns = false; sshTls = t; sshProxy = pr; sshPayload = pl
                                }
                            }
                        }
                        if (slowDnsOn) {
                            Text(stringResource(R.string.slowdns_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(dnsNs, { dnsNs = it }, label = { Text(stringResource(R.string.field_dns_ns)) },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(dnsKey, { dnsKey = it.trim() }, label = { Text(stringResource(R.string.field_dns_key)) },
                                supportingText = { Text(stringResource(R.string.dns_key_hint)) },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(dnsResolver, { dnsResolver = it.trim() },
                                label = { Text(stringResource(R.string.field_dns_resolver)) },
                                supportingText = { Text(stringResource(R.string.dns_resolver_hint)) },
                                singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        // UDP عبر UDPGW: متاح في كل أنماط SSH (بما فيها SlowDNS)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Checkbox(checked = udpgw, onCheckedChange = { udpgw = it })
                            Text(stringResource(R.string.field_udp_over_ssh))
                        }
                        if (udpgw) {
                            OutlinedTextField(udpgwPort, { udpgwPort = it.filter(Char::isDigit).take(5) },
                                label = { Text(stringResource(R.string.field_udpgw_port)) },
                                supportingText = { Text(stringResource(R.string.udpgw_hint)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth())
                        }
                    } else {
                        // OpenVPN الحقيقي (ics-openvpn): الصق محتوى ملف .ovpn كاملاً
                        OutlinedTextField(ovpnConfig, { ovpnConfig = it },
                            label = { Text(stringResource(R.string.field_ovpn_config)) },
                            supportingText = { Text(stringResource(
                                if (realOvpn) R.string.ovpn_real_hint else R.string.ovpn_legacy_hint)) },
                            minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth())
                        // proto الحقيقي من ملف .ovpn (udp / tcp) — أي منفذ 1–65535 مدعوم
                        ChipRow(stringResource(R.string.field_proto), listOf("udp", "tcp"), ovpnProto) { ovpnProto = it }
                        // أوضاع OVPN المبسطة (نفق الـ payload)
                        run {
                        val modeDirect = stringResource(R.string.mode_direct)
                        val modePayload = stringResource(R.string.mode_payload)
                        val modeTlsPayload = stringResource(R.string.mode_tls_payload)
                        ChipRow(stringResource(R.string.field_mode), listOf(modeDirect, modePayload, modeTlsPayload), when {
                            sshTls && sshPayload -> modeTlsPayload
                            sshPayload -> modePayload
                            else -> modeDirect
                        }) { picked ->
                            when (picked) {
                                modeDirect -> { sshTls = false; sshPayload = false }
                                modePayload -> { sshTls = false; sshPayload = true }
                                modeTlsPayload -> { sshTls = true; sshPayload = true }
                            }
                        }
                        }
                    }
                    // بروكسي HTTP (SSH عند تفعيل الوضع، و OVPN دائماً فوق البايلود)
                    if ((sshProxy && isSsh) || isOvpn) {
                        OutlinedTextField(proxyHost, { proxyHost = it }, label = { Text(stringResource(R.string.field_proxy_host)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(proxyPort, { proxyPort = it.filter(Char::isDigit).take(5) },
                            label = { Text(stringResource(R.string.field_proxy_port)) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth())
                    }
                    if (sshTls) {
                        OutlinedTextField(sni, { sni = it }, label = { Text(stringResource(R.string.field_sni)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (sshPayload || isOvpn) {
                        OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.field_host_tag)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.field_path_tag)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(payload, { payload = it },
                            label = { Text(stringResource(R.string.field_payload)) },
                            supportingText = { Text(stringResource(R.string.payload_hint)) },
                            minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                    }
                    if (sshTls) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Checkbox(checked = insecure, onCheckedChange = { insecure = it })
                            Text(stringResource(R.string.field_allow_insecure))
                        }
                    }
                } else if (streamBased) {
                    ChipRow(stringResource(R.string.field_network),
                        listOf("tcp", "ws", "grpc", "h2", "kcp", "httpupgrade", "xhttp"), network) { network = it }
                    ChipRow(stringResource(R.string.field_security),
                        listOf("none", "tls", "reality"), security) { security = it }
                    OutlinedTextField(sni, { sni = it }, label = { Text(stringResource(R.string.field_sni)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.field_path)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(fp, { fp = it }, label = { Text(stringResource(R.string.field_fingerprint)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (security == "tls") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Checkbox(checked = insecure, onCheckedChange = { insecure = it })
                            Text(stringResource(R.string.field_allow_insecure))
                        }
                    }
                    if (protocol == "vless")
                        OutlinedTextField(flow, { flow = it }, label = { Text(stringResource(R.string.field_flow)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                } else if (protocol == "hysteria2") {
                    OutlinedTextField(sni, { sni = it }, label = { Text(stringResource(R.string.field_sni)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Checkbox(checked = insecure, onCheckedChange = { insecure = it })
                        Text(stringResource(R.string.field_allow_insecure))
                    }
                }
                if (invalid) {
                    Text(stringResource(if (slowDnsOn) R.string.slowdns_required else R.string.ssh_required),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val badSshProxy = isSsh && sshProxy &&
                    (proxyHost.isBlank() || proxyPort.toIntOrNull() !in 1..65535)
                val badOvpnProxy = isOvpn && proxyHost.isNotBlank() &&
                    proxyPort.toIntOrNull() !in 1..65535
                val badSlowDns = slowDnsOn &&
                    (dnsNs.isBlank() || !DNS_KEY_RE.matches(dnsKey.trim()))
                val badUdpgw = isSsh && udpgw && udpgwPort.toIntOrNull() !in 1..65535
                // في SlowDNS لا يُستعمل العنوان للاتصال (يُستعمل نطاق NS) فيجوز تركه فارغاً.
                if ((address.isBlank() && !slowDnsOn) || port.toIntOrNull() !in 1..65535 ||
                    (isSsh && username.isBlank()) || badSshProxy || badOvpnProxy || badSlowDns || badUdpgw
                ) {
                    invalid = true
                    return@Button
                }
                onSave(server.copy(
                    protocol = protocol,
                    username = username.trim(),
                    payload = payload,
                    ovpnConfig = if (isOvpn) ovpnConfig.trim() else "",
                    name = name.trim().ifBlank { server.name },
                    address = address.trim().ifBlank { if (slowDnsOn) dnsNs.trim() else "" },
                    port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: server.port,
                    secret = if (isSsh) secret else secret.trim(),
                    network = if (isPayloadProto) (if (slowDnsOn) "slowdns" else if (sshPayload) "ws" else "tcp") else network,
                    security = if (isPayloadProto) (if (sshTls) "tls" else "none") else security,
                    host = host.trim(),
                    sni = sni.trim(),
                    path = path.trim(),
                    fingerprint = fp.trim(),
                    flow = flow.trim(),
                    insecure = insecure,
                    // Payload/WebSocket/Proxy/TLS يعني TCP دائماً
                    method = if (isOvpn) {
                        if (realOvpn && (sshPayload || sshTls || proxyHost.isNotBlank())) "tcp" else ovpnProto
                    } else server.method,
                    proxyHost = when {
                        isSsh && sshProxy -> proxyHost.trim()
                        isOvpn && proxyHost.isNotBlank() -> proxyHost.trim()
                        else -> ""
                    },
                    proxyPort = proxyPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080,
                    dnsNs = if (slowDnsOn) dnsNs.trim().trimEnd('.') else "",
                    dnsKey = if (slowDnsOn) dnsKey.trim().lowercase() else "",
                    dnsResolver = if (slowDnsOn) dnsResolver.trim() else "",
                    udpgw = isSsh && udpgw,
                    udpgwPort = udpgwPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 7300
                ))
                onClose()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } }
    )
}

@Composable
private fun ChipRow(label: String, options: List<String>, current: String, onPick: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (options + current).distinct().forEach { o ->
                FilterChip(selected = o == current, onClick = { onPick(o) }, label = { Text(o) })
            }
        }
    }
}
