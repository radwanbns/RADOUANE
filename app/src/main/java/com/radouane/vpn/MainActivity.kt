package com.radouane.vpn

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy = Color(0xFF071426)
private val Panel = Color(0xFF102743)
private val Cyan = Color(0xFF00C2FF)
private val Muted = Color(0xFF9CB2CA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RadouaneApp() }
    }
}

@Composable
private fun RadouaneApp() {
    val profiles = remember { mutableStateListOf<ServerProfile>() }
    var selected by remember { mutableStateOf<ServerProfile?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("أضف رابط بروكسي أو ملف إعداد للبدء") }
    var connecting by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as Activity
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            message = "تم منح إذن VPN. يلزم دمج محرك Xray لإتمام الاتصال."
        } else message = "لم يتم منح إذن VPN"
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Navy, surface = Panel)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Navy) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("RADOUANE", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                        Text("VPN  •  XRAY CLIENT", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("● READY", color = Color(0xFF41D69A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(26.dp))
                Box(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(22.dp)).padding(20.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("حالة الاتصال", color = Muted)
                        Spacer(Modifier.height(8.dp))
                        Text("غير متصل", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(message, color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = {
                                val intent = VpnService.prepare(activity)
                                if (intent != null) vpnPermission.launch(intent)
                                else message = "إذن VPN متاح؛ محرك Xray غير مدمج بعد في هذه النسخة."
                            },
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("اتصال", color = Navy, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp) }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("السيرفرات", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${profiles.size} سيرفر", color = Muted, fontSize = 12.sp)
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, null, tint = Cyan) }
                }
                if (profiles.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Link, null, tint = Muted, modifier = Modifier.size(38.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("لا توجد سيرفرات بعد", color = Muted)
                            Text("اضغط + لإضافة رابط أو إعداد", color = Muted, fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                        items(profiles) { profile ->
                            Row(
                                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(15.dp)).clickable { selected = profile }.padding(15.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name, color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("${profile.protocol}  •  ${profile.address}:${profile.port}", color = Muted, fontSize = 12.sp)
                                }
                                if (selected == profile) Text("محدد", color = Cyan, fontSize = 12.sp)
                                IconButton(onClick = { profiles.remove(profile); if (selected == profile) selected = null }) {
                                    Icon(Icons.Default.Delete, null, tint = Color(0xFFFF7185))
                                }
                            }
                        }
                    }
                }
                Text("VLESS • VMess • Trojan • SS • SOCKS • HTTP • Hysteria • WireGuard", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("إضافة سيرفر") },
            text = {
                Column {
                    Text("الصق رابط بروكسي أو Xray JSON أو إعداد WireGuard")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = link, onValueChange = { link = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("vless://… أو Xray JSON أو [Interface]…") }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val parsed = ProfileParser.parse(link)
                        profiles.add(parsed); selected = parsed; link = ""; showAdd = false; message = "تمت إضافة السيرفر بنجاح"
                    } catch (e: Exception) { message = e.message ?: "تعذر قراءة الرابط" }
                }) { Text("إضافة") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("إلغاء") } }
        )
    }
}
