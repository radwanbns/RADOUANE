package com.xraytunnel.app.data

/**
 * كل الروابط هنا فقط (خارج واجهة التطبيق) — المستخدم لا يستطيع رؤيتها أو تعديلها من التطبيق.
 */
object Defaults {
    /**
     * رابط ملف السيرفرات الذي يقرأه زر "تحديث السيرفرات".
     * ضع هنا رابط raw من GitHub (أو أي رابط اشتراك). الملف: روابط (vless://, vmess://...) سطراً لكل رابط،
     * أو نفس المحتوى مشفراً Base64.
     */
    const val SUBSCRIPTION_URL = "https://raw.githubusercontent.com/radwanbns/Redo-VPN/refs/heads/main/servers.txt"

    /** رابط قناة التلغرام الذي يظهر في النافذة المنبثقة. */
    const val TELEGRAM_URL = "https://t.me/radouane_tech"

    /** علامة تميّز السيرفرات القادمة من التحديث (مقفلة: لا تعديل ولا حذف). */
    const val UPDATE_SUB_ID = "update"
}
