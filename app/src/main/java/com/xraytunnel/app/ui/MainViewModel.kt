package com.xraytunnel.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xraytunnel.app.R
import com.xraytunnel.app.data.Defaults
import com.xraytunnel.app.data.Repo
import com.xraytunnel.app.data.Server
import com.xraytunnel.app.data.SubscriptionUpdater
import com.xraytunnel.app.vpn.XrayVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repo.get(app)
    val servers = repo.servers
    val selectedId = repo.selectedId
    val vpnState = XrayVpnService.state

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    private fun str(id: Int, vararg a: Any) = getApplication<Application>().getString(id, *a)

    fun toast(id: Int) { _message.value = str(id) }

    /** زر التحديث: يجلب السيرفرات من الرابط المخفي في Defaults ويستبدل سيرفرات التحديث فقط. */
    fun updateAll() {
        if (_updating.value) return
        viewModelScope.launch {
            _updating.value = true
            try {
                val list = SubscriptionUpdater.fetch(Defaults.SUBSCRIPTION_URL)
                if (list.isEmpty()) _message.value = str(R.string.nothing_found)
                else { repo.replaceUpdated(list); _message.value = str(R.string.updated_fmt, list.size) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = str(R.string.update_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                _updating.value = false
            }
        }
    }

    fun importText(text: String) {
        val found = SubscriptionUpdater.parseContent(text)
        if (found.isEmpty()) _message.value = str(R.string.nothing_found)
        else { repo.addServers(found); _message.value = str(R.string.imported_fmt, found.size) }
    }

    fun select(id: String) = repo.select(id)
    fun delete(id: String) = repo.delete(id)
    fun save(server: Server) = repo.updateServer(server)
    fun clearImported() = repo.clearImported()

    fun telegramSeen() = repo.telegramSeen()
    fun markTelegramSeen() = repo.markTelegramSeen()
}
