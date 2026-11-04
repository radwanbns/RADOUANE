package com.xraytunnel.app.data

/** App configuration. Most endpoints encrypted in SecureLinks; YouTube is plain. */
object Defaults {
    fun subscriptionUrl(): String = SecureLinks.subscriptionUrl()
    fun telegramUrl(): String = SecureLinks.telegramUrl()
    fun telegramChannelUrl(): String = SecureLinks.telegramChannelUrl()

    /** Opens channel videos tab — more reliable than bare @handle on Android. */
    fun youtubeUrl(): String = "https://www.youtube.com/@radouane_tech"
    const val UPDATE_SUB_ID = "update"
}
