package kiosk.front

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import java.net.Inet4Address

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val assets by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyExtras(intent)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WebView.setWebContentsDebuggingEnabled(true)

        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#080b12"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // LAN http 호출 허용
            addJavascriptInterface(Bridge(), "KioskNative")
            webViewClient = Client()
        }
        setContentView(web)
        hideSystemBars()
        Hub.attach(web)
        web.loadUrl(Hub.startUrl())
        Kiosk.applyPolicies(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (applyExtras(intent)) web.loadUrl(Hub.startUrl())
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        Kiosk.lock(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** 뒤로 가기: 앱을 나가지 않고 열린 화면(모달)만 닫는다 */
    @Deprecated("kiosk")
    override fun onBackPressed() {
        Hub.js("document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape'}))")
    }

    /** adb 로 설정: am start -n kiosk.front/.MainActivity --es pin 4821 --es place 우리동네 --es lat .. --es lon .. */
    private fun applyExtras(i: Intent?): Boolean {
        val ex = i?.extras ?: return false
        val kv = ex.keySet().mapNotNull { k -> ex.get(k)?.let { k to it.toString() } }.toMap()
        val changed = Prefs.apply(kv)
        if (changed.isNotEmpty()) Log.i(TAG, "config changed: $changed")
        return changed.any { it in setOf("place", "lat", "lon", "url") }
    }

    private fun hideSystemBars() {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------- 관리자 탈출구: 왼쪽 위 모서리를 3초 안에 5번 탭 → PIN ----------
    private var cornerTaps = 0
    private var firstTapAt = 0L

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val zone = 80 * resources.displayMetrics.density
            if (ev.x < zone && ev.y < zone) {
                val now = System.currentTimeMillis()
                if (now - firstTapAt > 3000) { firstTapAt = now; cornerTaps = 0 }
                if (++cornerTaps >= 5) { cornerTaps = 0; askPin() }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)

    private fun askPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        dialog().setTitle("관리자").setView(input)
            .setPositiveButton("확인") { _, _ ->
                if (input.text.toString() == Prefs.pin) showAdmin()
                else Toast.makeText(this, "PIN이 맞지 않아요", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAdmin() {
        val items = arrayOf(
            "Wi-Fi 설정 열기", "설정 열기", "화면 새로고침", "API 주소·토큰 보기",
            "알림 접근 권한 설정", "키오스크 잠금 다시 걸기", "디바이스 오너 해제",
        )
        dialog().setTitle("관리자 메뉴 (홈으로 돌아오면 다시 잠김)").setItems(items) { _, i ->
            when (i) {
                0 -> { Kiosk.unlock(this); startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                1 -> { Kiosk.unlock(this); startActivity(Intent(Settings.ACTION_SETTINGS)) }
                2 -> web.reload()
                3 -> dialog().setTitle("API").setMessage(
                    "주소: http://${ipv4() ?: "?"}:${Prefs.port}\n토큰: ${Prefs.token}\n" +
                        "디바이스 오너: ${Kiosk.isOwner(this)}\nTTS: ${Hub.ttsReady}"
                ).setPositiveButton("닫기", null).show()
                4 -> { Kiosk.unlock(this); startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                5 -> Kiosk.lock(this)
                6 -> dialog().setTitle("디바이스 오너를 해제할까요?")
                    .setMessage("키오스크 잠금이 풀리고, 다시 지정하려면 계정이 없는 상태에서 adb로 해야 합니다.")
                    .setPositiveButton("해제") { _, _ -> Kiosk.releaseOwner(this) }
                    .setNegativeButton("취소", null).show()
            }
        }.show()
    }

    private fun ipv4(): String? {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
    }

    // ---------- WebView ----------
    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assets.shouldInterceptRequest(request.url)

        /** 메인 화면이 다른 사이트로 넘어가지 않게 (iframe 은 허용) */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val start = Uri.parse(Hub.startUrl())
            return request.url.host != start.host
        }

        override fun onPageFinished(view: WebView, url: String) { Hub.pageReady() }

        /** WebView 렌더러가 죽으면 화면을 다시 만든다 */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e(TAG, "renderer gone, crashed=${detail.didCrash()}")
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            recreate()
            return true
        }
    }

    /** JS 에서 window.KioskNative 로 접근 */
    private inner class Bridge {
        @JavascriptInterface fun speak(text: String) = Hub.speak(text)
        @JavascriptInterface fun config(): String = Prefs.webConfig()
        @JavascriptInterface fun action(name: String, args: String) {
            Log.i(TAG, "action $name $args")
            when (name) {
                "reload" -> Hub.reload()
            }
        }
    }
}
