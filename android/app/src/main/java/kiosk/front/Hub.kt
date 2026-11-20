package kiosk.front

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

const val TAG = "Kiosk"
const val ASSET_URL = "https://appassets.androidplatform.net/assets/index.html"

/** WebView(대시보드)와 서비스·HTTP 서버 사이의 연결 지점. TTS도 여기서 관리. */
object Hub {
    private val main = Handler(Looper.getMainLooper())
    private var web: WeakReference<WebView>? = null
    @Volatile private var ready = false
    private val pending = ArrayDeque<String>()
    private var tts: TextToSpeech? = null
    @Volatile private var ttsOk = false
    @Volatile private var ttsKorean = false
    @Volatile private var ttsStarting = false
    private val pendingSpeech = ArrayDeque<String>()
    private val recent = ArrayDeque<JSONObject>()
    private var appCtx: Context? = null
    /** 원격 관리(API)에서 잠금 해제·앱 열기를 하려고 들고 있는 화면 */
    var activity: WeakReference<android.app.Activity>? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        ensureTts()
    }

    /** TTS 엔진이 나중에 설치돼도 다시 연결한다 (디바이스 오너 앱은 강제 종료가 안 돼 재시작으로 해결 불가) */
    fun ensureTts() = main.post {
        if (ttsOk || ttsStarting) return@post
        val ctx = appCtx ?: return@post
        ttsStarting = true
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = TextToSpeech(ctx) { status ->
            ttsStarting = false
            ttsOk = status == TextToSpeech.SUCCESS
            if (ttsOk) {
                val r = tts?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsKorean = r >= 0
                Log.i(TAG, "TTS ready: engine=${tts?.defaultEngine} korean=$ttsKorean ($r)")
                while (pendingSpeech.isNotEmpty()) speak(pendingSpeech.removeFirst())
            } else Log.w(TAG, "TTS 엔진을 찾지 못했습니다")
        }
    }

    fun attach(w: WebView) { web = WeakReference(w); ready = false }

    fun pageReady() = main.post {
        ready = true
        while (pending.isNotEmpty()) web?.get()?.evaluateJavascript(pending.removeFirst(), null)
    }

    /** 대시보드 JS 실행. 페이지가 아직 준비 전이면 콜백 없는 호출은 큐에 쌓았다가 실행. */
    fun js(code: String, cb: ((String?) -> Unit)? = null) = main.post {
        val w = web?.get()
        if (w == null || !ready) {
            if (cb == null) pending.addLast(code) else cb(null)
            return@post
        }
        w.evaluateJavascript(code) { cb?.invoke(it) }
    }

    /** 메인 스레드가 아닌 곳(HTTP 서버)에서 결과를 기다릴 때 */
    fun jsBlocking(code: String, timeoutMs: Long = 3000): String? {
        val latch = CountDownLatch(1)
        var out: String? = null
        js(code) { out = it; latch.countDown() }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }

    fun load(url: String) = main.post { web?.get()?.loadUrl(url) }
    fun reload() = main.post { web?.get()?.reload() }
    fun startUrl(): String = Prefs.url.ifBlank { ASSET_URL }

    fun speak(text: String) {
        if (!ttsOk) {
            Log.w(TAG, "TTS 준비 안 됨, 연결 후 재생")
            main.post { pendingSpeech.addLast(text); while (pendingSpeech.size > 3) pendingSpeech.removeFirst() }
            ensureTts()
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "k${System.nanoTime()}")
    }
    val ttsReady get() = ttsOk
    val ttsKoreanReady get() = ttsKorean
    fun ttsEngine(): String = tts?.defaultEngine ?: ""

    /** 메인 스레드에서 화면(Activity)으로 작업. 화면이 없으면 false */
    fun onActivity(block: (android.app.Activity) -> Unit): Boolean {
        val a = activity?.get() ?: return false
        main.post { block(a) }
        return true
    }

    /** 최근 받은 다른 앱 알림 (파서 만들 때 실제 문구 확인용) */
    fun record(o: JSONObject) = synchronized(recent) {
        recent.addLast(o)
        while (recent.size > 100) recent.removeFirst()
    }
    fun recentJson(): String = synchronized(recent) { JSONArray(recent.toList()).toString() }
}
