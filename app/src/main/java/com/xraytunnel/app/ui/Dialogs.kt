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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
        icon = { Icon(Icons.AutoMirrored.Filled.Send, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(stringResource(R.string.telegram_title), textAlign = TextAlign.Center) },
        text = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.telegram_message), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(
                    Defaults.TELEGRAM_URL,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Defaults.TELEGRAM_URL))) }
                onClose()
            }) { Text(stringResource(R.string.open_channel)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.later)) } }
    )
}

/** تعديل سيرفر مستورد من الحافظة. العنوان (host) والمنفذ (port) لا يُعرضان ولا يتغيران. */
@Composable
fun EditServerDialog(server: Server, onSave: (Server) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(server.name) }
    var secret by remember { mutableStateOf(server.secret) }
    var network by remember { mutableStateOf(server.network) }
    var security by remember { mutableStateOf(server.security) }
    var sni by remember { mutableStateOf(server.sni) }
    var path by remember { mutableStateOf(server.path) }
    var fp by remember { mutableStateOf(server.fingerprint) }
    var flow by remember { mutableStateOf(server.flow) }

    val streamBased = server.protocol in listOf("vmess", "vless", "trojan")
    val secretLabel = when (server.protocol) {
        "vmess", "vless" -> R.string.field_uuid
        "wireguard" -> R.string.field_key
        else -> R.string.field_password
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.edit_server)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(secretLabel)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (streamBased) {
                    ChipRow(stringResource(R.string.field_network),
                        listOf("tcp", "ws", "grpc", "h2", "kcp", "httpupgrade", "xhttp"), network) { network = it }
                    ChipRow(stringResource(R.string.field_security),
                        listOf("none", "tls", "reality"), security) { security = it }
                    OutlinedTextField(sni, { sni = it }, label = { Text("SNI") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(path, { path = it }, label = { Text(stringResource(R.string.field_path)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(fp, { fp = it }, label = { Text("Fingerprint") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (server.protocol == "vless")
                        OutlinedTextField(flow, { flow = it }, label = { Text("Flow") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                } else if (server.protocol == "hysteria2") {
                    OutlinedTextField(sni, { sni = it }, label = { Text("SNI") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(server.copy(
                    name = name.trim().ifBlank { server.name },
                    secret = secret.trim(), network = network, security = security,
                    sni = sni.trim(), path = path.trim(), fingerprint = fp.trim(), flow = flow.trim()
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
