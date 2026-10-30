package com.xraytunnel.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xraytunnel.app.R
import kotlinx.coroutines.delay

/** مدة ظهور لافتة Update Success (4 ثوانٍ). */
private const val SHOW_MILLIS = 4000L

/**
 * لافتة "Update Success": تظهر عند وجود سيرفرات جديدة وتختفي تلقائياً بعد 4 ثوانٍ.
 * ضعها داخل Box وأعطها Modifier.align(...) من المستدعي.
 */
@Composable
fun UpdateSuccessHost(event: UpdateSuccess?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(event) }
    SideEffect { if (event != null) last = event }
    val shown = event ?: last

    // يُعاد تشغيل المؤقت مع كل حدث جديد (id مختلف) ويُلغى الذي قبله تلقائياً
    LaunchedEffect(event?.id) {
        if (event != null) {
            delay(SHOW_MILLIS)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = event != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 }
    ) {
        val count = shown?.count ?: 0
        val res = LocalContext.current.resources
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = ConnectedGreen,
            contentColor = OnStatus,
            shadowElevation = 8.dp
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(stringResource(R.string.update_success), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(res.getQuantityString(R.plurals.new_servers_fmt, count, count), fontSize = 12.sp)
                }
            }
        }
    }
}
