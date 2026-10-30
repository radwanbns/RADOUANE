# Xray Tunnel

عميل VPN لأندرويد (Kotlin + Jetpack Compose) مبني على نواة **Xray** عبر `libv2ray.aar`.

**البروتوكولات:** VMess · VLESS (TLS / REALITY / XTLS-Vision) · Trojan · Shadowsocks · SOCKS · Hysteria2 · WireGuard
**النقل:** TCP · WS · gRPC · HTTP/2 · mKCP · HTTPUpgrade · XHTTP

## الروابط (خارج التطبيق فقط)
كل الروابط تُضبط في `app/src/main/java/com/xraytunnel/app/data/Defaults.kt` ولا تظهر في واجهة التطبيق:
- `SUBSCRIPTION_URL`: رابط ملف السيرفرات (مثلاً `https://raw.githubusercontent.com/USER/REPO/main/servers.txt`) — روابط `vless://` `vmess://` ... سطر لكل رابط، أو نفس المحتوى Base64.
- `TELEGRAM_URL`: رابط قناة التلغرام الذي يظهر في النافذة المنبثقة.

## سلوك السيرفرات
- **سيرفرات التحديث** (زر *تحديث السيرفرات*): مقفلة — لا تعديل ولا حذف، وتُستبدل بالكامل عند كل تحديث.
- **سيرفرات الحافظة** (زر *لصق* أو القائمة ⋮): قابلة للتعديل والحذف.
- العنوان (host) والمنفذ (port) لا يُعرضان في أي مكان في التطبيق.
- نافذة تلغرام تظهر عند أول تشغيل، ويمكن فتحها لاحقاً من أيقونة الإرسال في أعلى الشاشة.

## البناء
- **GitHub Actions:** ارفع المشروع، يعمل `.github/workflows/build.yml` تلقائياً (يحمّل `libv2ray.aar` ويبني APK ثم يرفعه Artifact).
- **Android Studio:** ضع `libv2ray.aar` في `app/libs/` (من https://github.com/2dust/AndroidLibXrayLite/releases) ثم Build.

> واجهة النواة معزولة في ملف واحد: `core/XrayCore.kt`.
