package com.xraytunnel.app.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xraytunnel.app.R
import com.xraytunnel.app.data.Defaults
import com.xraytunnel.app.data.Repo
import com.xraytunnel.app.data.Server
import com.xraytunnel.app.data.ServerFile
import com.xraytunnel.app.data.SubscriptionUpdater
import com.xraytunnel.app.vpn.XrayVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val SUBSCRIPTION_URL: String
    get() = Defaults.subscriptionUrl()

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repo.get(app)

    val servers = repo.servers
    val selectedId = repo.selectedId
    val vpnState = XrayVpnService.state

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    private fun str(id: Int, vararg a: Any) =
        getApplication<Application>().getString(id, *a)

    fun toast(id: Int) {
        _message.value = str(id)
    }

    private val updatePrefs = app.getSharedPreferences(
        "subscription_update_state",
        Context.MODE_PRIVATE
    )

    private val autoUpdateIntervalMs = 30L * 60L * 1000L

    // Manual update: always fetch immediately.
    fun updateAll() {
        updateAllInternal(showErrors = true, respectInterval = false)
    }

    // Automatic update when opening the app.
    fun updateIfDue() {
        val last = updatePrefs.getLong("last_success", 0L)

        if (System.currentTimeMillis() - last >= autoUpdateIntervalMs) {
            updateAllInternal(
                showErrors = false,
                respectInterval = true
            )
        }
    }

    // Lightweight server check every 30 seconds.
    fun checkForNewServers() {
        if (_updating.value) return

        val lastSuccess = updatePrefs.getLong("last_success", 0L)

        if (System.currentTimeMillis() - lastSuccess >= autoUpdateIntervalMs) {
            updateAllInternal(
                showErrors = false,
                respectInterval = true
            )
            return
        }

        viewModelScope.launch {
            if (_updating.value) return@launch

            _updating.value = true

            try {
                val result = SubscriptionUpdater.fetchConditional(
                    SUBSCRIPTION_URL,
                    updatePrefs.getString("etag", null),
                    updatePrefs.getString("last_modified", null)
                )

                if (!result.notModified && result.servers != null) {
                    val changes = repo.syncUpdated(result.servers)

                    updatePrefs.edit()
                        .putLong("last_success", System.currentTimeMillis())
                        .putString("etag", result.etag)
                        .putString("last_modified", result.lastModified)
                        .apply()

                    if (changes > 0) {
                        _message.value = str(R.string.update_success)
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Automatic check remains silent.
            } finally {
                _updating.value = false
            }
        }
    }

    fun updateAllSilently() = updateIfDue()

    // Manual and scheduled full update.
    private fun updateAllInternal(
        showErrors: Boolean,
        respectInterval: Boolean
    ) {
        if (_updating.value) return

        if (respectInterval) {
            val last = updatePrefs.getLong("last_success", 0L)

            if (System.currentTimeMillis() - last < autoUpdateIntervalMs) {
                return
            }
        }

        viewModelScope.launch {
            if (_updating.value) return@launch

            _updating.value = true

            try {
                val result = SubscriptionUpdater.fetch(SUBSCRIPTION_URL)
                val changes = repo.syncUpdated(result.servers.orEmpty())

                updatePrefs.edit()
                    .putLong("last_success", System.currentTimeMillis())
                    .putString("etag", result.etag)
                    .putString("last_modified", result.lastModified)
                    .apply()

                if (changes > 0) {
                    _message.value = str(R.string.update_success)
                } else if (showErrors) {
                    _message.value = str(R.string.update_no_changes)
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (showErrors) {
                    _message.value = str(
                        R.string.update_failed,
                        e.message ?: e.javaClass.simpleName
                    )
                }
            } finally {
                _updating.value = false
            }
        }
    }

    // Import servers from clipboard.
    fun importText(text: String) {
        val found = SubscriptionUpdater.parseContent(text)

        if (found.isEmpty()) {
            _message.value = str(R.string.nothing_found)
        } else {
            repo.addServers(found)
            _message.value = str(R.string.imported_fmt, found.size)
        }
    }

    // Import servers from a .xtun file (locked or unlocked), falling back to plain link lists.
    fun importFileText(text: String) {
        val fromFile = ServerFile.parse(text)
        val found = fromFile ?: SubscriptionUpdater.parseContent(text)

        if (found.isEmpty()) {
            _message.value = str(R.string.nothing_found)
        } else {
            repo.addServers(found)
            _message.value = str(R.string.imported_fmt, found.size)
        }
    }

    // Add a server created manually (e.g. SSH form).
    fun addServer(server: Server) {
        repo.addServers(listOf(server))
        _message.value = str(R.string.imported_fmt, 1)
    }

    fun select(id: String) = repo.select(id)

    fun delete(id: String) = repo.delete(id)

    fun save(server: Server) = repo.updateServer(server)

    fun clearImported() = repo.clearImported()
}
