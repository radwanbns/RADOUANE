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

    /**
     * Replace the GitHub-managed list with the latest valid subscription.
     * Clipboard servers are preserved exactly. Existing IDs are retained by
     * exact configuration first, then by protocol + server name when a config
     * was edited remotely, so the selected server does not unnecessarily jump.
     * Returns the number of added, removed, or changed managed servers.
     */
    fun syncUpdated(list: List<Server>): Int {
        if (list.isEmpty()) return 0 // Never interpret an empty/invalid fetch as mass deletion.

        val current = _servers.value
        val oldManaged = current.filter { it.isManaged }
        val clipboard = current.filterNot { it.isManaged }
        val incoming = list.distinctBy { it.identityKey() }

        val exactMatches = oldManaged.associateBy { it.identityKey() }
        val oldByLabel = oldManaged.groupBy { stableLabelKey(it) }
        val usedOldIds = mutableSetOf<String>()
        var changes = 0

        val latestManaged = incoming.map { server ->
            val exact = exactMatches[server.identityKey()]?.takeIf { it.id !in usedOldIds }
            val previous = exact ?: oldByLabel[stableLabelKey(server)]
                ?.firstOrNull { it.id !in usedOldIds }

            if (previous == null) {
                changes++ // Added
                server.copy(subId = Defaults.UPDATE_SUB_ID)
            } else {
                usedOldIds += previous.id
                val updated = server.copy(id = previous.id, subId = Defaults.UPDATE_SUB_ID)
                if (previous != updated) changes++ // Configuration or display name changed
                updated
            }
        }

        changes += oldManaged.count { it.id !in usedOldIds } // Removed from GitHub
        if (changes == 0) return 0

        // Keep clipboard servers untouched and replace only subscription-managed servers.
        saveServers(latestManaged + clipboard)
        return changes
    }

    private fun stableLabelKey(server: Server): String =
        server.protocol.trim().lowercase() + "\u001f" + server.name.trim().lowercase()

    /** تعديل سيرفر من الحافظة فقط. */
    fun updateServer(updated: Server) {
        val cur = _servers.value.firstOrNull { it.id == updated.id } ?: return
        if (!cur.isEditable) return
        saveServers(_servers.value.map { if (it.id == updated.id) updated.copy(subId = "", locked = false) else it })
    }

    /** حذف سيرفر من الحافظة فقط. */
    fun delete(id: String) = saveServers(_servers.value.filterNot { it.id == id && it.isDeletable })

    /** حذف كل سيرفرات الحافظة (سيرفرات التحديث تبقى). */
    fun clearImported() = saveServers(_servers.value.filterNot { it.isDeletable })

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
