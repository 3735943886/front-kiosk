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
    private val recent = ArrayDeque<JSONObject>()

    fun init(ctx: Context) {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ttsOk = status == TextToSpeech.SUCCESS
            if (ttsOk) {
                val r = tts?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (r < 0) Log.w(TAG, "TTS: 한국어 음성이 없습니다 (Google 음성 엔진 설치 필요)")
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
        if (!ttsOk) { Log.w(TAG, "TTS 준비 안 됨: $text"); return }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "k${System.nanoTime()}")
    }
    val ttsReady get() = ttsOk

    /** 최근 받은 다른 앱 알림 (파서 만들 때 실제 문구 확인용) */
    fun record(o: JSONObject) = synchronized(recent) {
        recent.addLast(o)
        while (recent.size > 100) recent.removeFirst()
    }
    fun recentJson(): String = synchronized(recent) { JSONArray(recent.toList()).toString() }
}
