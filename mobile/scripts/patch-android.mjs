// يُشغَّل بعد `npx cap add android`: صلاحيات، أيقونة الإشعار، قناة الإشعارات، التوقيع، رقم الإصدار
import { readFileSync, writeFileSync, existsSync, mkdirSync, copyFileSync } from 'node:fs';
import { resolve } from 'node:path';
const dir = process.cwd(), A = resolve(dir, 'android'), app = resolve(A, 'app');
const mf = resolve(app, 'src/main/AndroidManifest.xml'); let m = readFileSync(mf, 'utf8');
const perms = ['android.permission.INTERNET', 'android.permission.POST_NOTIFICATIONS', 'android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION', 'android.permission.CAMERA', 'android.permission.VIBRATE'];
for (const p of perms) if (!m.includes(`"${p}"`)) m = m.replace('</manifest>', `    <uses-permission android:name="${p}" />\n</manifest>`);
if (!m.includes('android.hardware.camera')) m = m.replace('</manifest>', '    <uses-feature android:name="android.hardware.camera" android:required="false" />\n    <uses-feature android:name="android.hardware.location.gps" android:required="false" />\n</manifest>');
const meta = `        <meta-data android:name="com.google.firebase.messaging.default_notification_icon" android:resource="@drawable/ic_stat_bb" />
        <meta-data android:name="com.google.firebase.messaging.default_notification_color" android:resource="@color/bb_brand" />
        <meta-data android:name="com.google.firebase.messaging.default_notification_channel_id" android:value="bb" />\n`;
if (!m.includes('default_notification_channel_id')) m = m.replace(/(<application[^>]*>)/, `$1\n${meta}`);
if (!/android:supportsRtl/.test(m)) m = m.replace('<application', '<application android:supportsRtl="true"'); else m = m.replace(/android:supportsRtl="false"/, 'android:supportsRtl="true"');
writeFileSync(mf, m);
// أيقونة الإشعار الأحادية + لون الهوية
const dr = resolve(app, 'src/main/res/drawable'); mkdirSync(dr, { recursive: true }); copyFileSync(resolve(dir, 'assets/notif/ic_stat_bb.png'), resolve(dr, 'ic_stat_bb.png'));
const vf = resolve(app, 'src/main/res/values/colors.xml'); let c = existsSync(vf) ? readFileSync(vf, 'utf8') : '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n';
if (!c.includes('bb_brand')) c = c.replace('</resources>', '    <color name="bb_brand">#1E2ABE</color>\n</resources>'); writeFileSync(vf, c);
// build.gradle: الإصدار + التوقيع من متغيرات البيئة (GitHub Secrets)
const gf = resolve(app, 'build.gradle'); let g = readFileSync(gf, 'utf8');
const vc = process.env.VERSION_CODE || '1', vn = process.env.APP_VERSION || '1.0.0';
g = g.replace(/versionCode\s+\d+/, `versionCode ${vc}`).replace(/versionName\s+"[^"]*"/, `versionName "${vn}"`);
if (!g.includes('bbRelease')) {
  g = g.replace(/android\s*\{/, `android {
    signingConfigs {
        bbRelease {
            if (System.getenv("KEYSTORE_PATH")) {
                storeFile file(System.getenv("KEYSTORE_PATH"))
                storePassword System.getenv("KEYSTORE_PASS")
                keyAlias System.getenv("KEY_ALIAS")
                keyPassword System.getenv("KEY_PASS")
            }
        }
    }`);
  g = g.replace(/buildTypes\s*\{\s*release\s*\{/, `buildTypes {
        release {
            if (System.getenv("KEYSTORE_PATH")) { signingConfig signingConfigs.bbRelease }`);
}
writeFileSync(gf, g);
console.log('android patched: version', vn, '(' + vc + ')', process.env.KEYSTORE_PATH ? 'signed' : 'unsigned');
