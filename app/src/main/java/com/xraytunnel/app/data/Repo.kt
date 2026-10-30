package com.xraytunnel.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class Repo private constructor(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("xray_tunnel", Context.MODE_PRIVATE)

    private val _servers = MutableStateFlow(readServers())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _selected = MutableStateFlow(sp.getString("selected", null))
    val selectedId: StateFlow<String?> = _selected.asStateFlow()

    fun selectedServer(): Server? = _servers.value.firstOrNull { it.id == _selected.value }

    fun select(id: String) { _selected.value = id; sp.edit().putString("selected", id).apply() }

    // ---------- servers ----------

    /** إضافة سيرفرات من الحافظة (قابلة للتعديل والحذف). */
    fun addServers(list: List<Server>) = saveServers(_servers.value + list.map { it.copy(subId = "") })

    /** استبدال سيرفرات التحديث بالقائمة الجديدة، دون المساس بسيرفرات الحافظة. */
    fun replaceUpdated(list: List<Server>) {
        val oldSelected = selectedServer()?.takeIf { !it.isEditable }
        val fresh = list.map { it.copy(subId = Defaults.UPDATE_SUB_ID) }
        saveServers(fresh + _servers.value.filter { it.isEditable })
        // نحافظ على السيرفر المختار بعد التحديث (المعرّفات تتغير مع كل تحديث)
        if (oldSelected != null) {
            fresh.firstOrNull { it.name == oldSelected.name && it.address == oldSelected.address && it.port == oldSelected.port }
                ?.let { select(it.id) }
        }
    }

    /** تعديل سيرفر من الحافظة فقط. */
    fun updateServer(updated: Server) {
        val cur = _servers.value.firstOrNull { it.id == updated.id } ?: return
        if (!cur.isEditable) return
        saveServers(_servers.value.map { if (it.id == updated.id) updated.copy(subId = "") else it })
    }

    /** حذف سيرفر من الحافظة فقط. */
    fun delete(id: String) = saveServers(_servers.value.filterNot { it.id == id && it.isEditable })

    /** حذف كل سيرفرات الحافظة (سيرفرات التحديث تبقى). */
    fun clearImported() = saveServers(_servers.value.filterNot { it.isEditable })

    private fun saveServers(list: List<Server>) {
        _servers.value = list
        sp.edit().putString("servers", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
        if (list.none { it.id == _selected.value }) {
            list.firstOrNull()?.let { select(it.id) } ?: run { _selected.value = null; sp.edit().remove("selected").apply() }
        }
    }

    private fun readServers(): List<Server> = try {
        val a = JSONArray(sp.getString("servers", "[]"))
        (0 until a.length()).map { Server.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    // ---------- telegram dialog ----------
    fun telegramSeen(): Boolean = sp.getBoolean("tg_seen", false)
    fun markTelegramSeen() = sp.edit().putBoolean("tg_seen", true).apply()

    companion object {
        @Volatile private var inst: Repo? = null
        fun get(ctx: Context): Repo = inst ?: synchronized(this) { inst ?: Repo(ctx).also { inst = it } }
    }
}
