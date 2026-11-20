package kiosk.front

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Google Apps Script 웹 앱(gas/Code.gs)과 통신.
 * - 2분마다 오늘·내일 일정을 받아 kiosk.loadEvents("gas", events) 로 넘긴다 (iCal 보다 반영이 빠름)
 * - 키오스크 화면에서 추가한 일정을 POST 로 보낸다
 * 웹 앱 주소·토큰은 비밀이므로 로그·API 응답에 남기지 않는다.
 */
object GasSync {
    private const val EVERY_SEC = 120L
    private val exec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var started = false
    @Volatile var status: JSONObject = JSONObject().put("ok", false).put("error", "not configured")
        private set

    fun start() {
        if (started) return
        started = true
        exec.scheduleWithFixedDelay({ sync() }, 5, EVERY_SEC, TimeUnit.SECONDS)
    }

    fun now() = exec.execute { sync() }

    private fun sync() {
        if (!Prefs.gasReady) return
        val st = JSONObject().put("at", System.currentTimeMillis())
        try {
            val url = "${Prefs.gasUrl}?days=2&token=${URLEncoder.encode(Prefs.gasToken, "UTF-8")}"
            val res = JSONObject(http("GET", url, null))
            if (!res.optBoolean("ok")) throw IllegalStateException(res.optString("error", "error"))
            val events = res.optJSONArray("events")
            Hub.js("kiosk.loadEvents('gas', $events)")
            st.put("ok", true).put("events", events?.length() ?: 0)
        } catch (e: Exception) {
            Log.w(TAG, "gas sync failed: ${e.javaClass.simpleName} ${safe(e)}")
            st.put("ok", false).put("error", safe(e))
        }
        status = st
    }

    /** 일정 추가. 결과는 cb(JSON) 으로 (백그라운드 스레드) */
    fun add(event: JSONObject, cb: (JSONObject) -> Unit) = exec.execute {
        val out = try {
            if (!Prefs.gasReady) throw IllegalStateException("캘린더 쓰기가 설정되지 않았어요")
            val body = JSONObject(event.toString()).put("token", Prefs.gasToken).put("action", "add")
            JSONObject(http("POST", Prefs.gasUrl, body.toString()))
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", safe(e))
        }
        cb(out)
        if (out.optBoolean("ok")) sync()
    }

    /** 오류 메시지에서 주소·토큰이 새지 않게 */
    private fun safe(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return if (m.contains("http", true) || m.contains(Prefs.gasToken)) e.javaClass.simpleName else m.take(200)
    }

    /** Apps Script 는 응답을 302 로 다른 주소에 넘겨준다 → 그 주소는 GET 으로 따라간다 */
    private fun http(method: String, start: String, body: String?): String {
        var url = URL(start)
        var m = method
        var payload = body
        repeat(6) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                requestMethod = m
                setRequestProperty("User-Agent", "FrontKiosk/1.0")
                if (payload != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(payload!!.toByteArray(Charsets.UTF_8)) }
                }
            }
            try {
                when (val code = c.responseCode) {
                    in 200..299 -> return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    in 300..399 -> {
                        url = URL(url, c.getHeaderField("Location") ?: throw IllegalStateException("redirect"))
                        m = "GET"; payload = null
                    }
                    else -> throw IllegalStateException("HTTP $code")
                }
            } finally {
                c.disconnect()
            }
        }
        throw IllegalStateException("too many redirects")
    }
}
