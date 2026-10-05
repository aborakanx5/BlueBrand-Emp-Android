package sa.bluebrand.emb.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File

class MainActivity : Activity() {
    companion object { @Volatile var inFront = false }
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private val homeUrl = BuildConfig.HOME_URL
    private val fileChooserRequest = 4101
    private val cameraPermissionRequest = 4102
    private val notificationPermissionRequest = 4103
    private val webPermissionRequest = 4104
    private val locationPermissionRequest = 4105
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()
        requestNotificationPermissionIfNeeded()

        webView = WebView(this)  /* سياق النشاط (مو التطبيق) — عشان نوافذ التأكيد تقدر تظهر */
        /* حاوية تاخذ مسافة شريط الحالة وأزرار الجوال (وكيبورد) — عشان البرنامج ما يتداخل مع البار العلوي */
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.parseColor("#1E2ABE")) }
        root.addView(webView, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        try { androidx.core.view.WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = false } catch (_: Throwable) {}

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            setGeolocationEnabled(true)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = "$userAgentString ${BuildConfig.UA_TAG}/${BuildConfig.VERSION_NAME}"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "http", "https" -> false
                    "bluebrand" -> {
                        if (uri.host == "print") printCurrentPage()
                        true
                    }
                    else -> {
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Throwable) {}
                        true
                    }
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            /* نوافذ alert / confirm / prompt من البرنامج — بتصميم عصري (بطاقة، أيقونة، أزرار بلون الهوية) */
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                if (isFinishing) { result.cancel(); return true }
                bbDialog(message ?: "", 0, null) { ok, _ -> if (ok) result.confirm() else result.cancel() }; return true
            }
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                if (isFinishing) { result.cancel(); return true }
                bbDialog(message ?: "", 1, null) { ok, _ -> if (ok) result.confirm() else result.cancel() }; return true
            }
            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean {
                if (isFinishing) { result.cancel(); return true }
                bbDialog(message ?: "", 2, defaultValue) { ok, v -> if (ok) result.confirm(v ?: "") else result.cancel() }; return true
            }
            override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean { result.confirm(); return true }
            /* الكاميرا والمايك من الصفحة (مكالمات الفيديو والصوت، الرسائل الصوتية، الباركود): نطلب صلاحية أندرويد ثم نعطيها للصفحة */
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val need = mutableListOf<String>()
                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
                    if (need.isEmpty()) { request.grant(request.resources); return@runOnUiThread }
                    pendingWebPermission?.deny()
                    pendingWebPermission = request
                    requestPermissions(need.toTypedArray(), webPermissionRequest)
                }
            }

            /* الموقع (تسجيل الحضور والانصراف من البوابة) */
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) { callback.invoke(origin, true, false); return }
                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), locationPermissionRequest)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback

                val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }

                val initialIntents = mutableListOf<Intent>()
                if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                    try {
                        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        val dir = File(cacheDir, "camera").apply { mkdirs() }
                        val photo = File(dir, "capture_${System.currentTimeMillis()}.jpg")
                        cameraUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", photo)
                        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                        cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        if (cameraIntent.resolveActivity(packageManager) != null) initialIntents.add(cameraIntent)
                    } catch (_: Throwable) {
                        cameraUri = null
                    }
                }

                val chooser = Intent.createChooser(contentIntent, "اختيار ملف")
                if (initialIntents.isNotEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
                return try {
                    startActivityForResult(chooser, fileChooserRequest)
                    true
                } catch (_: Throwable) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                    false
                }
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent ?: "")
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType))
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            } catch (_: Throwable) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Throwable) {}
            }
        }

        intent?.getIntExtra("cancel_notif", 0)?.takeIf { it != 0 }?.let { getSystemService(NotificationManager::class.java).cancel(it) }
        val initialUrl = (intent?.getStringExtra("notification_url") ?: intent?.getStringExtra("link"))?.takeIf { it.startsWith("https://") } ?: homeUrl
        webView.loadUrl(initialUrl)

        // Firebase is intentionally initialized after the UI so a messaging issue cannot block app startup.
        webView.postDelayed({ initializeFirebaseMessagingSafely() }, 1200)
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            webPermissionRequest -> {
                val req = pendingWebPermission ?: return
                pendingWebPermission = null
                val ok = mutableListOf<String>()
                if (req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) && has(Manifest.permission.CAMERA)) ok.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && has(Manifest.permission.RECORD_AUDIO)) ok.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                req.resources.filter { it != PermissionRequest.RESOURCE_VIDEO_CAPTURE && it != PermissionRequest.RESOURCE_AUDIO_CAPTURE }.forEach { ok.add(it) }
                if (ok.isEmpty()) req.deny() else req.grant(ok.toTypedArray())
            }
            locationPermissionRequest -> {
                val cb = pendingGeoCallback ?: return
                val granted = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
                cb.invoke(pendingGeoOrigin, granted, false)
                pendingGeoCallback = null
                pendingGeoOrigin = null
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != fileChooserRequest) return
        val callback = fileCallback ?: return
        val result: Array<Uri>? = when {
            resultCode != RESULT_OK -> null
            data?.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
            data?.data != null -> arrayOf(data.data!!)
            cameraUri != null -> arrayOf(cameraUri!!)
            else -> null
        }
        callback.onReceiveValue(result)
        fileCallback = null
        cameraUri = null
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getIntExtra("cancel_notif", 0)?.takeIf { it != 0 }?.let { getSystemService(NotificationManager::class.java).cancel(it) }
        val url = intent?.getStringExtra("notification_url") ?: intent?.getStringExtra("link")
        if (::webView.isInitialized && url != null && url.startsWith("https://")) webView.loadUrl(url)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun initializeFirebaseMessagingSafely() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                getSharedPreferences("bluebrand", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
                publishTokenToWeb(token)
            }
        } catch (_: Throwable) { }
    }

    private fun publishTokenToWeb(token: String) {
        if (!::webView.isInitialized) return
        val safe = token.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript(
            "try{localStorage.setItem('bluebrand_fcm_token','$safe');window.dispatchEvent(new CustomEvent('bluebrand-fcm-token',{detail:'$safe'}));}catch(e){}",
            null
        )
    }

    private fun printCurrentPage() {
        try {
            val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            manager.print("BlueBrand Emp", webView.createPrintDocumentAdapter("BlueBrand Emp"), PrintAttributes.Builder().build())
        } catch (_: Throwable) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("bb", "إشعارات بلوبراند", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationPermissionRequest)
        }
    }

    /* ===== إجبار حذف التطبيق القديم =====
       يدوّر على أي تطبيق بلوبراند قديم مثبت (غير هذا وغير تطبيق الإدارة) ويطلب حذفه — النافذة ما تنقفل لين ينحذف */
    private val oldPkgs = BuildConfig.OLD_PKGS.split(",").filter { it.isNotBlank() }.toSet()
    private val keepPkgs = BuildConfig.KEEP_PKGS.split(",").filter { it.isNotBlank() }.toSet()  /* التطبيق الثاني (الإدارة/الموظفين) — لا ينحذف أبداً */
    private var oldDlg: AlertDialog? = null

    private fun findOldApps(): List<Pair<String, String>> = try {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = if (Build.VERSION.SDK_INT >= 33) packageManager.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0))
                   else @Suppress("DEPRECATION") packageManager.queryIntentActivities(i, 0)
        val re = Regex("blue\\s*brand|بلو\\s*براند", RegexOption.IGNORE_CASE)
        list.mapNotNull { r ->
            val p = r.activityInfo.packageName
            val l = r.loadLabel(packageManager).toString()
            if (p == packageName || p in keepPkgs) null
            else if (p in oldPkgs || (BuildConfig.OLD_BY_NAME && (p.startsWith("sa.bluebrand.") || re.containsMatchIn(l)))) p to l else null
        }.distinctBy { it.first }
    } catch (_: Throwable) { emptyList() }

    private fun checkOldApps() {
        val old = findOldApps()
        oldDlg?.dismiss(); oldDlg = null
        if (old.isEmpty()) return
        val (pkg, label) = old.first()
        oldDlg = AlertDialog.Builder(this)
            .setTitle("احذف التطبيق القديم")
            .setMessage("لازم تحذف النسخة القديمة «$label» عشان يشتغل التطبيق الجديد صح (الإشعارات والبصمة والدردشة).\n\nاضغط «حذف القديم» ثم «موافق».")
            .setCancelable(false)
            .setPositiveButton("حذف القديم") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))) }
                catch (_: Throwable) { try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))) } catch (_: Throwable) {} }
            }
            .show()
    }

    /* ===== نافذة عصرية موحّدة: kind 0 = تنبيه · 1 = تأكيد · 2 = إدخال ===== */
    private fun bbDialog(msg: String, kind: Int, def: String?, cb: (Boolean, String?) -> Unit) {
        val d = resources.displayMetrics.density; fun dp(v: Int) = (v * d).toInt()
        val danger = kind == 1 && Regex("حذف|إلغاء|الغاء|مسح|خروج|إيقاف|رفض|نهائي").containsMatchIn(msg)
        val brand = android.graphics.Color.parseColor(if (danger) "#E5484D" else "#1E2ABE")
        val dlg = android.app.Dialog(this); var done = false
        fun close(ok: Boolean, v: String?) { if (done) return; done = true; dlg.dismiss(); cb(ok, v) }
        fun round(c: Int, r: Int, stroke: Int = 0) = android.graphics.drawable.GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke) }
        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
            background = round(android.graphics.Color.WHITE, 26); setPadding(dp(22), dp(22), dp(22), dp(18)); elevation = dp(12).toFloat()
        }
        val ic = android.widget.TextView(this).apply {
            text = when { kind == 0 -> "💬"; kind == 2 -> "✏️"; danger -> "⚠️"; else -> "❔" }; textSize = 26f; gravity = android.view.Gravity.CENTER
            background = round(android.graphics.Color.argb(28, android.graphics.Color.red(brand), android.graphics.Color.green(brand), android.graphics.Color.blue(brand)), 30)
        }
        card.addView(ic, android.widget.LinearLayout.LayoutParams(dp(60), dp(60)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        val tv = android.widget.TextView(this).apply {
            text = msg; textSize = 16.5f; setTextColor(android.graphics.Color.parseColor("#12142B")); gravity = android.view.Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val sc = android.widget.ScrollView(this).apply { addView(tv) }
        card.addView(sc, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        var input: android.widget.EditText? = null
        if (kind == 2) { input = android.widget.EditText(this).apply { setText(def ?: ""); setSelection(text.length); textSize = 16f; background = round(android.graphics.Color.parseColor("#F3F4FA"), 14, android.graphics.Color.parseColor("#D7DAE8")); setPadding(dp(14), dp(12), dp(14), dp(12)); textDirection = android.view.View.TEXT_DIRECTION_ANY_RTL }
            card.addView(input, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }) }
        val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL }
        fun btn(t: String, primary: Boolean, ok: Boolean) = android.widget.TextView(this).apply {
            text = t; textSize = 15.5f; gravity = android.view.Gravity.CENTER; setPadding(0, dp(13), 0, dp(13)); typeface = android.graphics.Typeface.DEFAULT_BOLD
            if (primary){ setTextColor(android.graphics.Color.WHITE); background = round(brand, 16) } else { setTextColor(android.graphics.Color.parseColor("#4A4E6A")); background = round(android.graphics.Color.parseColor("#EEF0F8"), 16) }
            isClickable = true; isFocusable = true; setOnClickListener { close(ok, input?.text?.toString()) }
        }
        val okLbl = when { kind == 0 -> "حسناً"; danger -> "نعم، تأكيد"; kind == 2 -> "حفظ"; else -> "موافق" }
        row.addView(btn(okLbl, true, true), android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        if (kind != 0) { row.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(dp(10), 1)); row.addView(btn("إلغاء", false, false), android.widget.LinearLayout.LayoutParams(0, -2, 1f)) }
        card.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2))
        val wrap = android.widget.FrameLayout(this).apply { setPadding(dp(22), 0, dp(22), 0); addView(card, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER)) }
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); dlg.setContentView(wrap)
        dlg.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)); setLayout(-1, -2); setDimAmount(0.55f); addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND); attributes = attributes.apply { windowAnimations = android.R.style.Animation_Dialog } }
        dlg.setOnCancelListener { close(false, null) }
        dlg.show()
        card.scaleX = 0.92f; card.scaleY = 0.92f; card.alpha = 0f; card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start()
        if (kind == 2) input?.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        checkOldApps()
        askFullScreenCalls()
    }

    override fun onPause() { inFront = false; super.onPause() }

    /* أندرويد 14+: إذن «شاشة كاملة» عشان شاشة الاتصال تطلع والجوال مقفل — نطلبه مرة وحدة */
    private fun askFullScreenCalls() {
        if (Build.VERSION.SDK_INT < 34 || oldDlg?.isShowing == true) return
        val nm = getSystemService(NotificationManager::class.java)
        val sp = getSharedPreferences("bluebrand", MODE_PRIVATE)
        if (nm.canUseFullScreenIntent() || sp.getBoolean("fsi_asked", false)) return
        sp.edit().putBoolean("fsi_asked", true).apply()
        AlertDialog.Builder(this).setTitle("📞 شاشة المكالمات")
            .setMessage("عشان تطلع لك شاشة الاتصال حتى لو الجوال مقفل، فعّل «السماح بالإشعارات بملء الشاشة» للتطبيق.")
            .setPositiveButton("تفعيل") { _, _ -> try { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) } catch (_: Throwable) {} }
            .setNegativeButton("لاحقاً", null).show()
    }
}
