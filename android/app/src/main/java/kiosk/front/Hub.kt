package kiosk.front

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.webkit.WebView
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
    private var appCtx: Context? = null
    /** 원격 관리(API)에서 잠금 해제·앱 열기를 하려고 들고 있는 화면 */
    var activity: WeakReference<android.app.Activity>? = null

    fun context(): Context = appCtx!!

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
                applySavedVoice()
                Log.i(TAG, "TTS ready: engine=${tts?.defaultEngine} korean=$ttsKorean ($r) voice=${tts?.voice?.name}")
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

    /**
     * voice·rate·pitch 를 주면 이번 한 번만 그 음성으로 말한다 (들어보고 고를 때).
     * 요청마다 음성 설정이 함께 넘어가므로 말한 뒤 저장된 설정으로 되돌려도 앞의 문장에 영향이 없다.
     */
    fun speak(text: String, voice: String? = null, rate: Float? = null, pitch: Float? = null) { main.post {
        val t = tts
        if (!ttsOk || t == null) {
            Log.w(TAG, "TTS 준비 안 됨, 연결 후 재생")
            pendingSpeech.addLast(text); while (pendingSpeech.size > 3) pendingSpeech.removeFirst()
            ensureTts()
            return@post
        }
        val once = voice != null || rate != null || pitch != null
        if (once) applyVoice(voice ?: Prefs.ttsVoice, rate ?: Prefs.ttsRate, pitch ?: Prefs.ttsPitch)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "k${System.nanoTime()}")
        if (once) applySavedVoice()
    } }
    val ttsReady get() = ttsOk
    val ttsKoreanReady get() = ttsKorean
    fun ttsEngine(): String = tts?.defaultEngine ?: ""
    fun ttsVoiceName(): String = tts?.voice?.name ?: ""

    /** 한국어 음성 목록. network=true 는 인터넷(클라우드)으로 합성하는 음성 */
    fun koreanVoices(): List<android.speech.tts.Voice> =
        (tts?.voices ?: emptySet()).filter { it.locale.language == "ko" }.sortedBy { it.name }

    fun hasVoice(name: String) = name.isBlank() || koreanVoices().any { it.name == name }

    fun applySavedVoice() = applyVoice(Prefs.ttsVoice, Prefs.ttsRate, Prefs.ttsPitch)

    /** 이름이 비었거나 없는 음성이면 한국어 기본 음성 */
    private fun applyVoice(name: String, rate: Float, pitch: Float) {
        val t = tts ?: return
        val v = if (name.isBlank()) null else koreanVoices().firstOrNull { it.name == name }
        if (v != null) t.voice = v else t.setLanguage(Locale.KOREAN)
        t.setSpeechRate(rate)
        t.setPitch(pitch)
    }

    /** 설치된 앱의 실행 인텐트 (매니페스트 <queries> LAUNCHER 로 보이는 앱만) */
    fun launchIntent(pkg: String): android.content.Intent? = appCtx?.packageManager?.getLaunchIntentForPackage(pkg)

    /** 메인 스레드에서 화면(Activity)으로 작업. 화면이 없으면 false */
    fun onActivity(block: (android.app.Activity) -> Unit): Boolean {
        val a = activity?.get() ?: return false
        main.post { block(a) }
        return true
    }

}
