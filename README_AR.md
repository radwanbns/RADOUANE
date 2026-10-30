# RADOUANE VPN — مشروع Android Kotlin

## نطاق دعم البروتوكولات في طبقة الاستيراد
يدعم محلل الملفات/الروابط في هذا الإصدار قراءة أو حفظ البيانات الأولية للأنواع التالية:

- VLESS وVMess وTrojan وShadowsocks.
- SOCKS / SOCKS5 وHTTP / HTTPS proxy.
- Hysteria وHysteria2 (روابط المشاركة).
- WireGuard (ملف إعداد يحتوي على `[Interface]` و`[Peer]`).
- Xray JSON (ملف إعداد كامل، مع التعرف على أول outbound proxy).

## وسائل النقل والأمان في Xray
تختلف التوافقية حسب البروتوكول والسيرفر. يتضمن Xray وسائل نقل مثل RAW/TCP وWebSocket وgRPC وXHTTP وmKCP وHTTPUpgrade، مع TLS أو REALITY حيث يسمح توافق البروتوكول. ليست كل تركيبة صالحة لكل بروتوكول؛ يجب أن تطابق إعدادات العميل إعدادات السيرفر. راجع جدول التوافق الرسمي: https://xtls.github.io/en/config/transport.html

## مهم جداً — حالة الاتصال الفعلي
هذا الإصدار ما زال **مشروع واجهة وطبقة استيراد أولية**. لم يتم دمج Xray-core runtime ولا جسر TUN، كما أن خدمة WireGuard الفعلية غير مدمجة. لذلك فإن ظهور نوع البروتوكول أو نجاح قراءة الرابط لا يعني أن الاتصال يعمل. زر الاتصال لا ينشئ نفقاً فعلياً بعد.

لتحويله إلى VPN حقيقي يجب دمج محرك مناسب لكل مجموعة بروتوكولات وربط دورة حياته بخدمة Android VpnService، مع اختبارات اتصال TCP/UDP وDNS وIPv4/IPv6. Xray لا يشغّل تلقائياً كل بروتوكول موجود في السوق؛ OpenVPN وSSH وTUIC وNaiveProxy مثلاً تحتاج محركات/تنفيذات مستقلة أو محركاً آخر متوافقاً.

مرجع Xray الرسمي: https://xtls.github.io/

## البناء عبر GitHub من الهاتف
1. أنشئ مستودعاً جديداً على GitHub.
2. ارفع جميع محتويات هذا المجلد إلى المستودع.
3. افتح تبويب Actions وشغّل Android APK أو ادفع commit إلى main.
4. افتح نتيجة التشغيل الناجحة ثم نزّل Artifact باسم RADOUANE-VPN-debug.

## المتطلبات
- Android Studio حديث أو GitHub Actions.
- JDK 17، Gradle 8.9، Android SDK 35.
