package sa.bluebrand.emb.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/* زر «رفض» من الإشعار نفسه */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getStringExtra("id") ?: return
        CallActivity.reject(ctx, id, i.getStringExtra("rej"))
        CallActivity.endIf(id)
    }
}
