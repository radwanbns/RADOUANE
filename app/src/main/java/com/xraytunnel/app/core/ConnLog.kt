package com.xraytunnel.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل الاتصال المعروض في الواجهة (مشترك بين الخدمة والممرّر وأحداث ics-openvpn). */
object ConnLog {
    enum class Level { INFO, OK, WARN, ERROR, DEBUG }

    data class Line(val time: Long, val level: Level, val text: String)

    private const val MAX = 300

    val lines = MutableStateFlow<List<Line>>(emptyList())

    fun add(level: Level, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val line = Line(System.currentTimeMillis(), level, t)
        lines.update { (it + line).takeLast(MAX) }
    }

    fun clear() {
        lines.value = emptyList()
    }

    fun asText(): String {
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        return lines.value.joinToString("\n") { "${fmt.format(Date(it.time))} [${it.level.name}] ${it.text}" }
    }
}
