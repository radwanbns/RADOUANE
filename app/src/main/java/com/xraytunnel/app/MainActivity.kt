package com.xraytunnel.app

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xraytunnel.app.data.Server
import com.xraytunnel.app.ui.*
import com.xraytunnel.app.vpn.VpnState
import com.xraytunnel.app.vpn.XrayVpnService

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { XrayTheme { MainScreen(vm) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val servers by vm.servers.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val state by vm.vpnState.collectAsStateWithLifecycle()
    val updating by vm.updating.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val error by XrayVpnService.lastError.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Server?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var showTelegram by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.consumeMessage() } }
    LaunchedEffect(error) { error?.let { snack.showSnackbar(it) } }

    // نافذة التلغرام: تظهر مرة واحدة عند أول تشغيل، ويمكن فتحها لاحقاً من أيقونة الأعلى
    LaunchedEffect(Unit) {
        if (!vm.telegramSeen()) { showTelegram = true; vm.markTelegramSeen() }
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun startVpn() = ContextCompat.startForegroundService(ctx, Intent(ctx, XrayVpnService::class.java))
    fun stopVpn() = ctx.startService(Intent(ctx, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))

    val vpnPerm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) startVpn()
    }
    fun toggle() {
        if (state != VpnState.Disconnected) { stopVpn(); return }
        if (servers.isEmpty()) { vm.toast(R.string.no_servers); return }
        val intent = VpnService.prepare(ctx)
        if (intent != null) vpnPerm.launch(intent) else startVpn()
    }
    fun importClipboard() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        vm.importText(cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty())
    }

    val selected = servers.firstOrNull { it.id == selectedId }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = { showTelegram = true }) {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.telegram_title),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.import_clipboard)) },
                            onClick = { menu = false; importClipboard() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete_imported)) },
                            onClick = { menu = false; confirmClear = true })
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Hero(state, selected?.name, updating, ::toggle, vm::updateAll)
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${stringResource(R.string.servers)} (${servers.size})",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = ::importClipboard) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.paste))
                    }
                }
            }
            if (servers.isEmpty()) {
                item { EmptyState() }
            } else {
                items(servers, key = { it.id }) { s ->
                    ServerRow(s, s.id == selectedId,
                        onClick = { vm.select(s.id) },
                        onEdit = { editing = s },
                        onDelete = { vm.delete(s.id) })
                }
            }
        }
    }

    if (showTelegram) TelegramDialog { showTelegram = false }

    editing?.let { s -> EditServerDialog(s, onSave = vm::save, onClose = { editing = null }) }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.delete_imported)) },
        text = { Text(stringResource(R.string.delete_imported_confirm)) },
        confirmButton = {
            TextButton(onClick = { vm.clearImported(); confirmClear = false }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } }
    )
}

// ---------------------------------------------------------------- Hero

@Composable
private fun Hero(state: VpnState, serverName: String?, updating: Boolean, onToggle: () -> Unit, onUpdate: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val statusColor by animateColorAsState(
        when (state) {
            VpnState.Connected -> ConnectedGreen
            VpnState.Connecting -> ConnectingAmber
            else -> cs.onSurfaceVariant
        }, label = "status"
    )
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(statusColor.copy(alpha = 0.18f), cs.surfaceVariant)))
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ConnectButton(state, onToggle)
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(when (state) {
                    VpnState.Connected -> R.string.connected
                    VpnState.Connecting -> R.string.connecting
                    else -> R.string.disconnected
                }),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = statusColor
            )
            Spacer(Modifier.height(4.dp))
            Text(
                serverName ?: stringResource(R.string.no_server_selected),
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            FilledTonalButton(
                onClick = onUpdate, enabled = !updating,
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)
            ) {
                if (updating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.update_servers), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ConnectButton(state: VpnState, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val color by animateColorAsState(
        when (state) {
            VpnState.Connected -> ConnectedGreen
            VpnState.Connecting -> ConnectingAmber
            else -> cs.primary
        }, label = "btn"
    )
    val pulse = if (state == VpnState.Connecting) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "p").value
    } else 0f

    Box(
        Modifier.size(176.dp).drawBehind {
            val base = 64.dp.toPx()
            when (state) {
                VpnState.Connecting -> drawCircle(color.copy(alpha = 0.35f * (1f - pulse)), radius = base + 24.dp.toPx() * pulse)
                VpnState.Connected -> {
                    drawCircle(color.copy(alpha = 0.18f), radius = base + 22.dp.toPx())
                    drawCircle(color.copy(alpha = 0.12f), radius = base + 12.dp.toPx())
                }
                else -> drawCircle(color.copy(alpha = 0.12f), radius = base + 12.dp.toPx())
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(128.dp).clip(CircleShape).background(color).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            // أيقونة الطاقة (مرسومة يدوياً حتى لا نحتاج مكتبة أيقونات إضافية)
            Canvas(Modifier.size(52.dp)) {
                val sw = 6.dp.toPx()
                val stroke = Stroke(width = sw, cap = StrokeCap.Round)
                drawArc(
                    OnStatus, startAngle = -60f, sweepAngle = 300f, useCenter = false, style = stroke,
                    topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw)
                )
                drawLine(OnStatus, Offset(size.width / 2, sw / 2), Offset(size.width / 2, size.height * 0.5f),
                    strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}

// ---------------------------------------------------------------- list

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Info, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.no_servers), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ServerRow(s: Server, selected: Boolean, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val accent = protocolColor(s.protocol)
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) cs.primaryContainer else cs.surfaceVariant),
        border = if (selected) BorderStroke(1.5.dp, cs.primary) else BorderStroke(1.dp, cs.outline.copy(alpha = 0.45f))
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) { Text(protocolLabel(s.protocol), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                // لا نعرض العنوان (host) ولا المنفذ (port) إطلاقاً
                Text(s.networkInfo(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Default.CheckCircle, null, tint = cs.primary, modifier = Modifier.padding(horizontal = 4.dp))
            if (s.isEditable) {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.edit_server), tint = cs.onSurfaceVariant) }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = cs.error) }
            } else {
                // سيرفر قادم من التحديث: مقفل (لا تعديل ولا حذف)
                Icon(Icons.Default.Lock, stringResource(R.string.locked_server),
                    Modifier.padding(horizontal = 12.dp).size(18.dp), tint = cs.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

private fun Server.networkInfo(): String = buildString {
    append(network.uppercase())
    if (security != "none") append(" · ").append(security.uppercase())
}

private fun protocolLabel(p: String) = when (p) {
    "shadowsocks" -> "SS"
    "hysteria2" -> "HY2"
    "wireguard" -> "WG"
    else -> p.uppercase().take(6)
}

private fun protocolColor(p: String) = when (p) {
    "vless" -> Color(0xFF38BDF8)
    "vmess" -> Color(0xFFA78BFA)
    "trojan" -> Color(0xFFF472B6)
    "shadowsocks" -> Color(0xFF34D399)
    "hysteria2" -> Color(0xFFFBBF24)
    "wireguard" -> Color(0xFFFB7185)
    else -> Color(0xFF94A3B8)
}
