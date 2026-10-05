package sa.bluebrand.emb.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class BlueBrandMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("bluebrand", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            "call" -> { incomingCall(message.data); return }
            "call_end" -> { val id = message.data["id"] ?: return
                Handler(Looper.getMainLooper()).post { CallActivity.endIf(id) }
                getSystemService(NotificationManager::class.java).cancel(CallActivity.notifId(id)); return }
        }
        // FCM notification payloads are displayed by Android automatically in background.
        // This block also supports data-only messages and foreground delivery.
        val title = message.notification?.title ?: message.data["title"] ?: "BlueBrand Emp"
        val body = message.notification?.body ?: message.data["body"] ?: message.data["message"] ?: return
        val target = message.data["url"] ?: message.data["link"] ?: BuildConfig.HOME_URL

        val manager = getSystemService(NotificationManager::class.java)
        val channelId = "bb"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "إشعارات بلوبراند", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }
        val intent = Intent(this, MainActivity::class.java).putExtra("notification_url", target)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pending).build())
    }

    /* مكالمة واردة: شاشة اتصال كاملة + رنين متكرر لين يرد أو يرفض أو تنتهي (45 ثانية) */
    private fun incomingCall(d: Map<String, String>) {
        val id = d["id"] ?: return
        if (MainActivity.inFront) return /* التطبيق مفتوح قدامه ← شاشة الاتصال داخل التطبيق تكفي */
        val name = d["name"] ?: ""
        val video = d["video"] == "1"
        val link = d["link"] ?: d["open"] ?: return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = "bb_call"
        val ring = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(ch, "المكالمات", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(ring, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(true); vibrationPattern = longArrayOf(0, 900, 700, 900, 700)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC; setBypassDnd(true)
            })
        }
        val n = CallActivity.notifId(id)
        val full = Intent(this, CallActivity::class.java).apply {
            putExtra("id", id); putExtra("name", name); putExtra("video", d["video"] ?: "0"); putExtra("link", link); putExtra("rej", d["rej"])
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        }
        val fl = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val fullPi = PendingIntent.getActivity(this, n, full, fl)
        val accPi = PendingIntent.getActivity(this, n + 1, Intent(this, MainActivity::class.java).putExtra("notification_url", link).putExtra("cancel_notif", n)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), fl)
        val rejPi = PendingIntent.getBroadcast(this, n + 2, Intent(this, CallActionReceiver::class.java).putExtra("id", id).putExtra("rej", d["rej"]), fl)
        val notif = NotificationCompat.Builder(this, ch)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle((if (video) "🎥 مكالمة فيديو من " else "📞 مكالمة من ") + name)
            .setContentText("مكالمة واردة…")
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(ring, android.media.AudioManager.STREAM_RING).setVibrate(longArrayOf(0, 900, 700, 900, 700))
            .setOngoing(true).setAutoCancel(true).setTimeoutAfter(45000)
            .setContentIntent(fullPi).setFullScreenIntent(fullPi, true)
            .addAction(0, "✖ رفض", rejPi).addAction(0, if (video) "🎥 رد" else "📞 رد", accPi)
            .build()
        notif.flags = notif.flags or Notification.FLAG_INSISTENT
        nm.notify(n, notif)
    }
}
