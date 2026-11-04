
package com.xraytunnel.app.data

/** App configuration. All public endpoints are stored encrypted in SecureLinks. */
object Defaults {
    fun subscriptionUrl(): String = SecureLinks.subscriptionUrl()
    fun telegramUrl(): String = SecureLinks.telegramUrl()
    fun telegramChannelUrl(): String = SecureLinks.telegramChannelUrl()
    fun youtubeUrl(): String = SecureLinks.youtubeUrl()
    const val UPDATE_SUB_ID = "update"
}
