package kiosk.front

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class KioskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        NotifLog.init(this)
        Hub.init(this)
        ApiServer.startDefault()
        CalendarSync.start()
        GasSync.start()
        AdbAccess.ensureOnStart(this)
        Log.i(TAG, "API port ${Prefs.port}, token ${Prefs.token}")
    }
}

class AdminReceiver : android.app.admin.DeviceAdminReceiver()

/** 홈 앱으로 지정돼 있으면 필요 없지만, 지정 전 상태를 위한 보조 경로 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w(TAG, "boot start failed", e)
        }
    }
}
