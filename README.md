# Xray Tunnel

عميل VPN لأندرويد (Kotlin + Jetpack Compose) مبني على نواة **Xray** عبر `libv2ray.aar`.

**البروتوكولات:** SSH (TCP / TLS / WebSocket + Payload / **SlowDNS** + **UDP عبر UDPGW** في كل الأنماط) · VMess · VLESS (TLS / REALITY / XTLS-Vision) · Trojan · Shadowsocks · SOCKS · Hysteria2 · WireGuard
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

## SSH SlowDNS و UDP (UDPGW)

### SSH-SlowDNS (SSH فوق DNS)
- نمط جديد ضمن **SSH mode** باسم `SSH-SlowDNS`. الحقول: **NS domain** + **Public key** (64 hex) + **DNS resolver** اختياري (الافتراضي `8.8.8.8:53`؛ `https://…` = DoH، `tls://host` = DoT).
- يشغّل التطبيق عميل **dnstt** الأصلي (Go) كملف تنفيذي `libdnstt.so` ويستمع على `127.0.0.1:<منفذ عشوائي>`؛ ثم يتصل SSH بهذا المنفذ. اسم المستخدم وكلمة السر هما حساب SSH على السيرفر.
- حقلا Address وPort لا يُستعملان في الاتصال (يُستعمل NS والـ resolver). إن تُرك Address فارغاً يُملأ بنطاق NS.
- التضمين: `build.yml` يبني `dnstt-client` لكل ABI (arm64-v8a / armeabi-v7a / x86_64) إلى `app/src/main/jniLibs/<abi>/libdnstt.so`. عند البناء المحلي نفّذ الأمر نفسه (`GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -o app/src/main/jniLibs/arm64-v8a/libdnstt.so ./dnstt-client` من مستودع dnstt) وإلا ستظهر رسالة «SlowDNS core is not included».
- مع DoH/DoT استعمل عنوان **IP** في الرابط (مثل `https://1.1.1.1/dns-query`)، لأن ملف Go الثابت لا يقرأ إعدادات DNS الخاصة بأندرويد.
- السرعة محدودة بطبيعة DNS، والاتصال الأول قد يستغرق عدة ثوانٍ (مهلة SSH هنا 90 ثانية).

**إعداد السيرفر (مرة واحدة):**
```
# DNS: سجل NS للنطاق الفرعي يشير إلى سيرفرك
#   t.example.com.   NS   tns.example.com.      tns.example.com.  A  <IP السيرفر>
dnstt-server -gen-key -privkey-file server.key -pubkey-file server.pub   # محتوى server.pub = Public key
iptables -t nat -I PREROUTING -p udp --dport 53 -j REDIRECT --to-ports 5300
dnstt-server -udp :5300 -privkey-file server.key t.example.com 127.0.0.1:22
```

### UDP عبر UDPGW (في كل أنماط SSH)
- خانة **UDP over SSH (UDPGW)** تعمل مع: Direct / Proxy / Payload / TLS / كل التركيبات / SlowDNS.
- عند تفعيلها يدعم SOCKS المحلي أمر `UDP ASSOCIATE` ويضمّن حزم UDP داخل قناة `direct-tcpip` واحدة إلى `127.0.0.1:<منفذ UDPGW>` على السيرفر (بروتوكول badvpn-udpgw). عند تعطيلها يبقى السلوك القديم: DNS عبر DoH ويُحجب باقي UDP.
- يلزم على السيرفر: `badvpn-udpgw --listen-addr 127.0.0.1:7300 --max-clients 1000 --max-connections-for-client 10` وأن يسمح sshd بالتحويل (`AllowTcpForwarding yes` وأن لا يمنع `PermitOpen` المنفذ 7300). إن تعذّر الوصول يظهر تحذير في السجل ويستمر TCP بالعمل.
- لا تُحلّ أسماء النطاقات محلياً لوجهات UDP (حتى لا يتسرّب DNS خارج النفق)؛ لذلك لا يُستبدل عنوان QUIC باسم نطاق عند تفعيل UDP.
- في الروابط: `ssh://user:pass@host:22?type=slowdns&ns=t.example.com&key=<64hex>&resolver=8.8.8.8:53&udpgw=1&udpgwPort=7300#Name` (و`udpgw=1` يصلح مع أي `type`).

## التحديث من GitHub (V2Ray + SSH)
نفس ملف `servers.txt` يقبل روابط V2Ray وروابط `ssh://` معًا (وكذلك JSON أو Base64). أمثلة جاهزة داخل `servers.txt`. سيرفرات التحديث تبقى مقفلة ومخفية التفاصيل (بما فيها رسائل الأخطاء).

## ملفات المشاركة `.xtun` (مقفل / غير مقفل)
مشفّرة دائمًا وتعمل مع V2Ray وSSH. **المقفل:** يُستورد مخفيًا، لا تعديل ولا إعادة تصدير، الحذف فقط. **غير المقفل:** يُستورد كسيرفر عادي قابل للتعديل.


## OpenVPN الحقيقي (ics-openvpn)
- وضع OVPN القديم (Payload + SOCKS محلي) **ليس عميل OpenVPN**: لا مصافحة TLS ولا tun ولا إعادة اتصال، ولذلك كان غير مستقر ويعمل كـ SOCKS فقط. بقي كوضع احتياطي عند ترك خانة `.ovpn` فارغة.
- الآن: عند لصق/استيراد ملف `.ovpn` كاملاً يُشغَّل عبر مكتبة **ics-openvpn** (OpenVPN 2.x الحقيقي): واجهة tun حقيقية، كل الترافيك يمر بالنفق، وإعادة اتصال تلقائية عند تغيّر الشبكة.
- عند تشغيل OpenVPN الحقيقي لا تعمل نواة Xray (ics-openvpn ينشئ واجهة VPN بنفسه)؛ `XrayVpnService` تبقى للإشعار وعدّاد الوقت والحالة.
- الشهادات والمفاتيح يجب أن تكون **مضمّنة** داخل الملف (`<ca>` `<cert>` `<key>` `<tls-auth>`/`<tls-crypt>`). الملفات الخارجية (`ca ca.crt`) غير مدعومة.
- `http-proxy host port` داخل الملف مدعوم من ics-openvpn. حقن Payload ([crlf] ...) غير مدعوم مع OpenVPN الحقيقي.
- البناء: يبني `build.yml` مكتبة ics-openvpn كـ `app/libs/ics-openvpn.aar` (يحتاج NDK و submodules). محلياً: استنسخ `schwabe/ics-openvpn --recurse-submodules` ثم `./gradlew :main:assembleSkeletonOvpn23Release` وانسخ الـ AAR إلى `app/libs/ics-openvpn.aar`.
- **الترخيص:** ics-openvpn مرخّص GPLv2؛ تضمينه يستلزم نشر كود تطبيقك بنفس الترخيص (وقد يتعارض مع نشره على Google Play أو مع AdMob، راجع ذلك قانونياً).
