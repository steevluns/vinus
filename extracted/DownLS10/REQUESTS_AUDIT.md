# DownLS10 — مراجعة الطلبات

هذه القائمة تربط أهم طلبات التعديل بالملفات التي تنفذها في المشروع.

- إصدار التطبيق 3.0: `build.gradle` (versionCode 300 / versionName 3.0).
- مشروع GitHub Actions لبناء APK: `.github/workflows/build-apk.yml`.
- التنزيلات وإعادة المحاولة والاستئناف ودعم التحويلات وترويسات User-Agent/Referer: `DownloadManagerEngine.kt` و`DownloadsRepository.kt`.
- روابط مشاركة/تنزيل مباشرة ومن ضمنها SourceForge وGoogle Drive وDropbox وOneDrive وGitHub وMediaFire وPixeldrain وبعض الخدمات الأخرى: `LinkResolver.kt`، مع العلم أن المواقع التي تتطلب تسجيل الدخول أو تمنع التنزيل المباشر لا يمكن ضمان تنزيلها.
- تنزيل `data:` و`blob:` من داخل الصفحة: `DownloadsRepository.kt` و`BlobDownloadBridge.kt`.
- سجل التنزيلات وحماية قراءة البيانات التالفة: `DownloadPersistence.kt` و`DownloadHistory.kt`.
- حذف التنزيلات المكتملة أو جميع التنزيلات مع التأكيد: `MainActivity.kt` و`activity_main.xml`.
- أيقونة مدير التنزيلات بجانب النجمة وقبل زر التقدم: `activity_browser.xml` و`BrowserActivity.kt`.
- اقتراحات Google، إدراج الاقتراح بالسهم وتنفيذ البحث عند اختيار النص: `BrowserActivity.kt`.
- خيارات البحث الظاهرة محصورة في SearXNG وGoogle وبحث الفيديو: `BrowserActivity.kt`.
- إضافة صفحة جديدة وإغلاق جميع الصفحات في أعلى نافذة الصفحات: `BrowserActivity.kt`.
- حذف جميع العلامات المرجعية مع تأكيد، وتحديد عرض العنوان بثلاثة أسطر: `BrowserActivity.kt` و`BrowserStorage.kt`.
- مفتاح عام لمانع الإعلانات، مع تجاوز الحجب عند إيقافه: `AdBlocker.kt` و`BrowserActivity.kt`.
- إزالة القوائم التي طلب المستخدم حذفها من واجهة القوائم، وتنظيف بياناتها القديمة: `AdBlocker.kt`.
- إخفاء لوحة المفاتيح عند بدء المتصفح وفتحها عند لمس حقل العنوان: `BrowserActivity.kt` و`AndroidManifest.xml`.
- فحص معلومات إصدار WebView من صفحة الأذونات/الخدمات: `BrowserActivity.kt`.

## حدود التحقق

تم فحص بنية الأرشيف وملفات XML ساكنًا. لم يكن Gradle متاحًا في بيئة التحرير، لذلك لم يتم تأكيد نجاح التجميع أو تشغيل التطبيق على جهاز فعلي. بعض مزودي التنزيل قد يفرضون تسجيل الدخول أو قيودًا من جانب الخادم، ولا يمكن لأي عميل ضمان تنزيل كل رابط في جميع الظروف.
