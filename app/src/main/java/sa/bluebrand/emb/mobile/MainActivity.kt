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

        webView = WebView(applicationContext)
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
