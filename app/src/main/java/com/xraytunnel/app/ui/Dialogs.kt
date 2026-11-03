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
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_telegram_brand),
                contentDescription = stringResource(R.string.telegram_title),
                modifier = Modifier.size(40.dp),
                tint = Color.Unspecified
            )
        },
        title = { Text(stringResource(R.string.telegram_title), textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.telegram_message), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    Defaults.telegramUrl(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Defaults.telegramUrl()))) }
                onClose()
            }) { Text(stringResource(R.string.open_channel)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.later)) } }
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
    var invalid by remember { mutableStateOf(false) }
    // بروتوكول V2Ray قابل للتغيير داخل الحوار
    var protocol by remember { mutableStateOf(server.protocol) }
    // proto الأصلي لـ OpenVPN (udp/tcp) محفوظ في method
    var ovpnProto by remember { mutableStateOf(server.method.takeIf { it == "udp" || it == "tcp" } ?: "tcp") }

    val isSsh = protocol == "ssh"
    val isOvpn = protocol == "ovpn"
    val isPayloadProto = isSsh || isOvpn
    val streamBased = protocol in listOf("vmess", "vless", "trojan")
    val secretLabel = when (protocol) {
        "vmess", "vless" -> R.string.field_uuid
        "wireguard" -> R.string.field_key
        "ovpn" -> R.string.field_password   // اختياري لـ OVPN
        else -> R.string.field_password
    }
    val currentMode = SSH_MODES.firstOrNull { it.second == Triple(sshTls, sshProxy, sshPayload) }?.first
        ?: SSH_MODES.first().first

    AlertDialog(
        onDismissRequest = onClose,
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
                        ChipRow(stringResource(R.string.field_ssh_mode), SSH_MODES.map { it.first }, currentMode) { picked ->
                            SSH_MODES.firstOrNull { it.first == picked }?.second?.let { (t, pr, pl) ->
                                sshTls = t; sshProxy = pr; sshPayload = pl
                            }
                        }
                    } else {
                        // proto الحقيقي من ملف .ovpn (udp / tcp)
                        ChipRow(stringResource(R.string.field_proto), listOf("udp", "tcp"), ovpnProto) { ovpnProto = it }
                        // أوضاع OVPN المبسطة (نفق الـ payload)
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
                    if (sshProxy && isSsh) {
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
                    Text(stringResource(R.string.ssh_required), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val badProxy = isSsh && sshProxy &&
                    (proxyHost.isBlank() || proxyPort.toIntOrNull() !in 1..65535)
                if (address.isBlank() || port.toIntOrNull() !in 1..65535 ||
                    (isSsh && username.isBlank()) || badProxy
                ) {
                    invalid = true
                    return@Button
                }
                onSave(server.copy(
                    protocol = protocol,
                    username = username.trim(),
                    payload = payload,
                    name = name.trim().ifBlank { server.name },
                    address = address.trim(),
                    port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: server.port,
                    secret = if (isSsh) secret else secret.trim(),
                    network = if (isPayloadProto) (if (sshPayload) "ws" else "tcp") else network,
                    security = if (isPayloadProto) (if (sshTls) "tls" else "none") else security,
                    host = host.trim(),
                    sni = sni.trim(),
                    path = path.trim(),
                    fingerprint = fp.trim(),
                    flow = flow.trim(),
                    insecure = insecure,
                    method = if (isOvpn) ovpnProto else server.method,
                    proxyHost = if (isSsh && sshProxy) proxyHost.trim() else "",
                    proxyPort = proxyPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8080
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
