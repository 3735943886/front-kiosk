package kiosk.front

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 다른 앱의 알림을 읽어 대시보드로 넘긴다.
 * - 감시 대상(Prefs.watch) 앱이면 kiosk.ingestAndroid(raw) 호출 → 웹 쪽 규칙이 카드로 바꾼다
 * - 원본 알림은 지워서 쌓이지 않게 한다 (잠금 작업 모드에서는 어차피 화면에 안 뜬다)
 * - 모든 알림을 최근 100개까지 기록 (GET /api/android-notifications) → 파서 만들 때 실제 문구 확인용
 */
class NotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "notification listener connected")
        try { activeNotifications?.forEach { handle(it) } } catch (e: Exception) { Log.w(TAG, "active", e) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn)

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val ex = n.extras
        fun cs(key: String) = ex.getCharSequence(key)?.toString() ?: ""
        val raw = JSONObject().apply {
            put("package", sbn.packageName)
            put("key", sbn.key)
            put("id", sbn.id)
            put("tag", sbn.tag ?: "")
            put("postTime", sbn.postTime)
            put("channel", n.channelId ?: "")
            put("category", n.category ?: "")
            put("ongoing", sbn.isOngoing)
            put("title", cs(Notification.EXTRA_TITLE))
            put("text", cs(Notification.EXTRA_TEXT))
            put("bigText", cs(Notification.EXTRA_BIG_TEXT))
            put("subText", cs(Notification.EXTRA_SUB_TEXT))
            put("progress", ex.getInt(Notification.EXTRA_PROGRESS, -1))
            put("progressMax", ex.getInt(Notification.EXTRA_PROGRESS_MAX, -1))
            ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { lines ->
                put("lines", JSONArray(lines.map { it.toString() }))
            }
        }
        Hub.record(raw)
        if (sbn.packageName !in Prefs.watch) return
        Log.i(TAG, "watched notification: $raw")
        Hub.js("window.kiosk && kiosk.ingestAndroid($raw)")
        if (Prefs.consume && !sbn.isOngoing) {
            try { cancelNotification(sbn.key) } catch (e: Exception) { Log.w(TAG, "cancel", e) }
        }
    }
}
