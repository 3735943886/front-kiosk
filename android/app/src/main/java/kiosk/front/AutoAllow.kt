package kiosk.front

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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

    override fun onServiceConnected() { Log.i(TAG, "AutoAllow connected") }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (e.packageName?.toString() != SYSTEMUI) return
        Log.i(TAG, "AutoAllow: systemui window ${e.className}")
        for (delay in longArrayOf(400, 1200, 3000)) main.postDelayed({ allow() }, delay)
    }

    private fun allow() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != SYSTEMUI) return
        save(root)
        val text = texts(root).joinToString(" | ")
        // 무선 디버깅 창인지 문구로 확인. USB 디버깅(컴퓨터 키 승인) 창은 건드리지 않는다
        val wifi = Regex("무선 디버깅|wireless debugging", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (!wifi || text.contains("USB", ignoreCase = true) || text.contains("RSA")) {
            Log.i(TAG, "AutoAllow: not the wireless prompt: $text"); return
        }
        root.findAccessibilityNodeInfosByViewId("android:id/alwaysUse").firstOrNull()?.let {
            if (!it.isChecked && !it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(it)
        }
        val ok = root.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull()
        if (ok == null) { Log.w(TAG, "AutoAllow: no allow button: $text"); return }
        if (!ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)) tap(ok)
        Log.i(TAG, "AutoAllow: allowed wireless debugging")
    }

    /** 버튼 클릭이 안 먹으면 그 자리를 손가락처럼 탭 (input tap 과 같은 효과) */
    private fun tap(n: AccessibilityNodeInfo) {
        val r = Rect().also { n.getBoundsInScreen(it) }
        val p = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
    }

    /** 마지막으로 본 SystemUI 창: 노드 목록(JSON)과 스크린샷 → GET /api/admin/prompt, /api/admin/prompt.png */
    private fun save(root: AccessibilityNodeInfo) {
        val nodes = org.json.JSONArray()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val r = Rect().also { n.getBoundsInScreen(it) }
            nodes.put(org.json.JSONObject().put("text", n.text?.toString() ?: "").put("id", n.viewIdResourceName ?: "")
                .put("cls", n.className?.toString() ?: "").put("checkable", n.isCheckable).put("checked", n.isChecked)
                .put("clickable", n.isClickable).put("bounds", r.flattenToString()))
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        try {
            java.io.File(filesDir, PROMPT_JSON).writeText(org.json.JSONObject()
                .put("time", System.currentTimeMillis()).put("nodes", nodes).toString())
        } catch (e: Exception) { Log.w(TAG, "prompt save", e) }
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(s: ScreenshotResult) {
                val bmp = android.graphics.Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace) ?: return
                try {
                    java.io.File(filesDir, PROMPT_PNG).outputStream().use {
                        bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } catch (e: Exception) { Log.w(TAG, "prompt shot", e) } finally { s.hardwareBuffer.close() }
            }
            override fun onFailure(code: Int) { Log.w(TAG, "prompt shot failed $code") }
        })
    }

    private fun texts(n: AccessibilityNodeInfo?, out: MutableList<String> = mutableListOf()): List<String> {
        if (n == null) return out
        n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out += it }
        for (i in 0 until n.childCount) texts(n.getChild(i), out)
        return out
    }

    override fun onInterrupt() {}

    companion object {
        const val SYSTEMUI = "com.android.systemui"
        const val PROMPT_JSON = "last-prompt.json"
        const val PROMPT_PNG = "last-prompt.png"
    }
}
