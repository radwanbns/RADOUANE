package com.xraytunnel.app.core

import android.content.Context
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * غلاف حول libv2ray.aar (AndroidLibXrayLite).
 * إذا تغيّرت واجهة المكتبة في إصدار جديد، عدّل هذا الملف فقط.
 */
object XrayCore {
    private var controller: CoreController? = null
    private var inited = false

    private val handler = object : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }

    @Synchronized
    fun start(ctx: Context, config: String, tunFd: Int) {
        if (!inited) {
            Libv2ray.initCoreEnv(ctx.filesDir.absolutePath, "")
            inited = true
        }
        val c = Libv2ray.newCoreController(handler)
        c.startLoop(config, tunFd)
        controller = c
    }

    @Synchronized
    fun stop() {
        try { controller?.stopLoop() } catch (_: Exception) {}
        controller = null
    }
}
