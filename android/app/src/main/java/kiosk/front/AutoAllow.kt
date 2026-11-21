package kiosk.front

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 무선 디버깅 "이 네트워크에서 허용할까요?" 창만 자동으로 허용한다.
 * 이 기기는 그 허용 기록을 저장하지 못해 재부팅마다 다시 묻는다 (adb_temp_keys.xml 읽기 실패).
 * 허용해도 접속은 이미 승인된 컴퓨터 키만 가능하다. 컴퓨터 키를 승인하는 USB 디버깅 창은 절대 누르지 않는다.
 * 켜기 (한 번, adb): settings put secure enabled_accessibility_services kiosk.front/kiosk.front.AutoAllow
 */
class AutoAllow : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (e.packageName != "com.android.systemui" || e.className != DIALOG) return
        Log.i(TAG, "wireless debugging prompt: auto-allow")
        for (delay in longArrayOf(300, 1000, 2500)) main.postDelayed({ allow() }, delay)
    }

    private fun allow() {
        val root = rootInActiveWindow ?: return
        if (root.packageName != "com.android.systemui") return
        root.findAccessibilityNodeInfosByViewId("android:id/alwaysUse").firstOrNull()?.let {
            if (!it.isChecked) it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } ?: return // 체크박스가 없으면 그 창이 아니다
        root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun onInterrupt() {}

    companion object { const val DIALOG = "com.android.systemui.wifi.WifiDebuggingActivity" }
}
