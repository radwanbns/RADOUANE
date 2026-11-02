# Xray Tunnel

عميل VPN لأندرويد (Kotlin + Jetpack Compose) مبني على نواة **Xray** عبر `libv2ray.aar`.

**البروتوكولات:** SSH (TCP / TLS / WebSocket + Payload) · VMess · VLESS (TLS / REALITY / XTLS-Vision) · Trojan · Shadowsocks · SOCKS · Hysteria2 · WireGuard
**النقل:** TCP · WS · gRPC · HTTP/2 · mKCP · HTTPUpgrade · XHTTP

## الروابط (خارج التطبيق فقط)
كل الروابط تُضبط في `app/src/main/java/com/xraytunnel/app/data/Defaults.kt` ولا تظهر في واجهة التطبيق:
- `SUBSCRIPTION_URL`: رابط ملف السيرفرات (مثلاً `https://raw.githubusercontent.com/USER/REPO/main/servers.txt`) — روابط `vless://` `vmess://` ... سطر لكل رابط، أو نفس المحتوى Base64.
- `TELEGRAM_URL`: رابط قناة التلغرام الذي يظهر في النافذة المنبثقة.

## سلوك السيرفرات
- **سيرفرات التحديث** (زر *تحديث السيرفرات*): مقفلة — لا تعديل ولا حذف، وتُستبدل بالكامل عند كل تحديث.
- **سيرفرات الحافظة** (زر *لصق* أو القائمة ⋮): قابلة للتعديل والحذف.
- يمكن تعديل Address (عنوان سيرفر V2Ray الفعلي) وHost (قيمة WS/HTTP) كلٌّ في خانة مستقلة، إضافة إلى Port للسيرفرات المحلية القابلة للتعديل.
- بجانب كل سيرفر يوجد خياران للمشاركة: JSON أو URI (عندما يدعم البروتوكول صيغة URI).
- سيرفرات التحديث من رابط GitHub تبقى مقفلة ضد التعديل والحذف، والرابط محفوظ مشفراً داخل التطبيق.
- نافذة تلغرام تظهر عند كل تشغيل، ويمكن فتحها لاحقاً من أيقونة الإرسال في أعلى الشاشة.

## البناء
- **GitHub Actions:** ارفع المشروع، يعمل `.github/workflows/build.yml` تلقائياً (يحمّل `libv2ray.aar` ويبني APK ثم يرفعه Artifact).
- **Android Studio:** ضع `libv2ray.aar` في `app/libs/` (من https://github.com/2dust/AndroidLibXrayLite/releases) ثم Build.

> واجهة النواة معزولة في ملف واحد: `core/XrayCore.kt`.

## Google AdMob ads and connection-time credit
- Google Mobile Ads SDK 23.6.0 is integrated.
- App Open test ad is requested on a fresh app launch; if unavailable, startup continues without waiting.
- An Interstitial test ad is shown when the user starts a VPN connection; the VPN starts after the ad closes, and starts immediately if the ad cannot load or show.
- Rewarded video grants 15 additional minutes after the reward callback.
- The project currently uses Google's official sample/test App ID and test ad unit IDs. Replace them with the AdMob App ID and the matching App Open, Interstitial, Rewarded, and Banner unit IDs from your AdMob account before publishing.
- Automatic GitHub server refresh runs on app launch only when at least 30 minutes have passed since the last successful refresh. The manual Update button always forces a fresh request.
- The service deducts one second only while its VPN state is `Connected`; stopping or failing to connect does not consume credit. At zero credit the service rejects a new connection.
- Credit is stored locally in SharedPreferences. It is not server-authoritative and should not be treated as tamper-proof subscription billing.
- Network Guard checks for a physical Wi-Fi/mobile transport and does not require Android's `NET_CAPABILITY_VALIDATED` flag before attempting the tunnel.


## Server update behavior
- Full automatic subscription refresh runs at most once every 30 minutes.
- While the app is in the foreground, a conditional HTTP check runs every 30 seconds using ETag/Last-Modified; the server list is downloaded and synchronized immediately when GitHub reports a change.
- The manual Update button remains available and forces a refresh.


## SSH (TCP / TLS / WebSocket + Payload)
- يتصل التطبيق بسيرفر SSH ثم يفتح SOCKS محلي (127.0.0.1 بكلمة سر عشوائية) يحوّل كل اتصال إلى قناة `direct-tcpip`، ونواة Xray تستعمله كمخرج. لا يُنقل UDP عبر SSH (يُمرَّر DNS عبر DoH ويُحجب باقي UDP).
- الأنماط: `tcp` مباشر، أو `ws` (بايلود HTTP/WebSocket)، وكلٌّ منهما اختياريًا مع `tls` وSNI. أي منفذ مقبول.
- متغيرات البايلود: `[crlf] [cr] [lf] [host] [port] [host_port] [path] [sni] [protocol] [ua] [ws_key] [split]`.
- زر **SSH** بجانب أزرار الملفات يضيف سيرفر SSH يدويًا.

## التحديث من GitHub (V2Ray + SSH)
نفس ملف `servers.txt` يقبل روابط V2Ray وروابط `ssh://` معًا (وكذلك JSON أو Base64). أمثلة جاهزة داخل `servers.txt`. سيرفرات التحديث تبقى مقفلة ومخفية التفاصيل (بما فيها رسائل الأخطاء).

## ملفات المشاركة `.xtun` (مقفل / غير مقفل)
مشفّرة دائمًا وتعمل مع V2Ray وSSH. **المقفل:** يُستورد مخفيًا، لا تعديل ولا إعادة تصدير، الحذف فقط. **غير المقفل:** يُستورد كسيرفر عادي قابل للتعديل.
