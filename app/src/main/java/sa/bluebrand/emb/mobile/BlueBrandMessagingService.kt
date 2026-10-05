package sa.bluebrand.emb.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class BlueBrandMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        getSharedPreferences("bluebrand", MODE_PRIVATE).edit().putString("fcm_token", token).apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // FCM notification payloads are displayed by Android automatically in background.
        // This block also supports data-only messages and foreground delivery.
        val title = message.notification?.title ?: message.data["title"] ?: "BlueBrand Emp"
        val body = message.notification?.body ?: message.data["body"] ?: message.data["message"] ?: return
        val target = message.data["url"] ?: message.data["link"] ?: "https://bluebrand-emp.web.app"

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
}
