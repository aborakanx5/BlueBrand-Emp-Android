package sa.bluebrand.emb.mobile

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/* شاشة الاتصال الواردة — تطلع فوق كل شي حتى لو الجوال مقفل (رد / رفض) */
class CallActivity : Activity() {
    companion object {
        var cur: CallActivity? = null
        fun endIf(id: String) { cur?.let { if (it.callId == id) it.finish() } }
        fun notifId(id: String) = 70000 + (id.hashCode() and 0xFFFF)
        fun reject(ctx: Context, id: String, url: String?) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId(id))
            if (url.isNullOrBlank()) return
            Thread { try { (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000; requestMethod = "GET" }.let { it.responseCode; it.disconnect() } } catch (_: Throwable) {} }.start()
        }
        fun accept(ctx: Context, id: String, link: String) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId(id))
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra("notification_url", link)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    var callId = ""
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show(intent)
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); if (intent != null) show(intent) }

    private fun show(i: Intent) {
        callId = i.getStringExtra("id") ?: ""
        val name = i.getStringExtra("name") ?: ""
        val video = i.getStringExtra("video") == "1"
        val link = i.getStringExtra("link") ?: ""
        val rej = i.getStringExtra("rej")
        cur = this
        val dp = resources.displayMetrics.density
        fun circle(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
        fun btn(icon: String, label: String, color: Int, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(context).apply { text = icon; textSize = 30f; gravity = Gravity.CENTER; background = circle(color); setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams((76 * dp).toInt(), (76 * dp).toInt()); setOnClickListener { onClick() } })
            addView(TextView(context).apply { text = label; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; setPadding(0, (8 * dp).toInt(), 0, 0) })
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0E1240")); setPadding((24 * dp).toInt(), (90 * dp).toInt(), (24 * dp).toInt(), (70 * dp).toInt())
            addView(TextView(context).apply { text = if (video) "🎥" else "📞"; textSize = 54f; gravity = Gravity.CENTER })
            addView(TextView(context).apply { text = name.ifBlank { "BlueBrand" }; setTextColor(Color.WHITE); textSize = 28f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setPadding(0, (18 * dp).toInt(), 0, 0) })
            addView(TextView(context).apply { text = if (video) "مكالمة فيديو واردة…" else "مكالمة صوتية واردة…"; setTextColor(Color.parseColor("#B8BEE8")); textSize = 16f; gravity = Gravity.CENTER; setPadding(0, (8 * dp).toInt(), 0, 0) })
            addView(android.view.View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
                addView(btn("✖", "رفض", Color.parseColor("#E5484D")) { reject(this@CallActivity, callId, rej); finish() })
                addView(btn(if (video) "🎥" else "📞", "رد", Color.parseColor("#22A55B")) { accept(this@CallActivity, callId, link); finish() })
            })
        }
        setContentView(root)
        h.removeCallbacksAndMessages(null)
        h.postDelayed({ (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId(callId)); finish() }, 45000)
    }

    override fun onDestroy() { h.removeCallbacksAndMessages(null); if (cur == this) cur = null; super.onDestroy() }
}
