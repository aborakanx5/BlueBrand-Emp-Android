// التطبيق يفتح النسخة المنشورة على Firebase Hosting مباشرة (server.url في capacitor.config.json)
// فأي تحديث للبرنامج بـ firebase deploy يوصل للتطبيق فوراً بدون بناء APK جديد.
// هنا نجهّز فقط صفحة «بدون إنترنت» (errorPath) وصفحة بداية احتياطية.
// الاستخدام: node ../scripts/build-www.mjs admin|portal   (من مجلد mobile/<app>)
import { rmSync, mkdirSync, writeFileSync, readFileSync, copyFileSync, existsSync } from 'node:fs';
import { resolve } from 'node:path';
const app = process.argv[2]; if (!['admin', 'portal'].includes(app)) { console.error('usage: build-www.mjs admin|portal'); process.exit(1); }
const dir = process.cwd(), cfg = JSON.parse(readFileSync(resolve(dir, 'capacitor.config.json'), 'utf8')), url = cfg.server.url, out = resolve(dir, 'www');
const name = app === 'admin' ? 'بلوبراند — المحاسبي' : 'بلوبراند — بوابة الموظفين';
rmSync(out, { recursive: true, force: true }); mkdirSync(out, { recursive: true });
if (existsSync(resolve(dir, 'assets/icon-only.png'))) copyFileSync(resolve(dir, 'assets/icon-only.png'), resolve(out, 'icon.png'));
const page = (title, msg, btn) => `<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><title>${name}</title>
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#1E2ABE;color:#fff;font-family:system-ui,sans-serif;text-align:center;padding:24px;box-sizing:border-box}
img{width:84px;height:84px;border-radius:22px;background:#fff;margin-bottom:18px}h1{font-size:20px;margin:0 0 8px}p{opacity:.85;font-size:14.5px;line-height:1.7;margin:0 0 22px}
button{font:inherit;font-size:16px;font-weight:600;border:0;border-radius:14px;padding:14px 34px;background:#fff;color:#1E2ABE}</style></head>
<body><div><img src="icon.png" alt="" onerror="this.remove()"><h1>${title}</h1><p>${msg}</p><button onclick="location.replace('${url}/')">${btn}</button></div>
<script>window.addEventListener('online',()=>location.replace('${url}/'));</script></body></html>`;
writeFileSync(resolve(out, 'offline.html'), page('ما فيه اتصال بالإنترنت', 'تأكد من الإنترنت (واي فاي أو بيانات الجوال) ثم اضغط «إعادة المحاولة».<br>بيرجع التطبيق تلقائياً أول ما يرجع الاتصال.', 'إعادة المحاولة'));
writeFileSync(resolve(out, 'index.html'), page(name, 'جاري الفتح…', 'فتح').replace('<script>', `<script>location.replace('${url}/');`));
console.log('www ready →', url);
