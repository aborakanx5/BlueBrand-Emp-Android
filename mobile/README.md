# تطبيقات أندرويد — بلوبراند (المحاسبي + بوابة الموظفين)

## الفكرة
- التطبيق **يفتح النسخة المنشورة** على Firebase Hosting مباشرة:
  - المحاسبي ← https://bluebrand-cc.web.app
  - البوابة ← https://bluebrand-emp.web.app
- أي تحديث للبرنامج بـ `firebase deploy --only hosting` يوصل للتطبيق **فوراً** — بدون APK جديد.
- نبني APK جديد فقط لو تغيّرت إعدادات التطبيق نفسه (الأيقونة، الاسم، الصلاحيات، الإضافات).

## الخصائص المدعومة داخل التطبيق
- إشعارات حقيقية (FCM) حتى والتطبيق مقفل، والضغط على الإشعار يفتح الشاشة المعنية.
- الكاميرا ورفع الملفات (تصوير الإيصال، المرفقات، الملفات).
- الموقع الجغرافي (تسجيل الحضور في البوابة).
- تصدير Excel وPDF والطباعة ← تتحفظ كملف وتطلع قائمة «حفظ / مشاركة» (واتساب، Drive، الملفات…).
- روابط واتساب والاتصال والروابط الخارجية تفتح بالتطبيق المناسب.
- زر الرجوع في أندرويد: يقفل النوافذ ثم يرجع للرئيسية ثم يصغّر التطبيق.
- صفحة «ما فيه إنترنت» مع رجوع تلقائي.
- تنبيه «تحديث جديد متوفر» من ملف `/app/version.json` على الموقع.

## الإعداد (مرة وحدة)
1. **Firebase Console** ← إعدادات المشروع ← «إضافة تطبيق» ← Android:
   - `sa.bluebrand.cc` (المحاسبي) ثم `sa.bluebrand.portal` (البوابة).
   - بعد إضافة الاثنين نزّل **google-services.json** (ملف واحد فيه الاثنين).
2. **GitHub** ← المستودع ← Settings ← Secrets and variables ← Actions ← New repository secret:
   - `GOOGLE_SERVICES_JSON` = محتوى ملف google-services.json كامل.
3. Actions ← **«إنشاء مفتاح التوقيع»** ← Run workflow ← نزّل الملف `keystore` من نتيجة التشغيل،
   وأضف الأسرار الأربعة: `ANDROID_KEYSTORE_BASE64` `ANDROID_KEYSTORE_PASSWORD` `ANDROID_KEY_PASSWORD` `ANDROID_KEY_ALIAS`.
   احتفظ بالملف في مكان آمن — بدونه ما تقدر تحدّث التطبيق فوق النسخ المثبتة.

## البناء والتوزيع
1. Actions ← **Build Android APK** ← Run workflow (رقم الإصدار اختياري).
2. بعد ما يخلص (~8 دقائق) نزّل من أسفل صفحة التشغيل:
   - `bluebrand-admin-…` ← فيه `bluebrand-admin.apk` و `version.json`
   - `bluebrand-portal-…` ← فيه `bluebrand-portal.apk` و `version.json`
3. انسخها لمشروع Firebase:
   - المحاسبي ← `public/app/` (الملفين)
   - البوابة ← `portal/app/` (الملفين)
4. `firebase deploy --only hosting`
5. روابط التثبيت:
   - المحاسبي: https://bluebrand-cc.web.app/app
   - الموظفين: https://bluebrand-emp.web.app/app

> أول مرة بعد المفتاح الجديد: احذف أي نسخة قديمة (debug) من الجوال ثم ثبّت الجديدة — بعدها كل التحديثات تنثبت فوقها مباشرة.
