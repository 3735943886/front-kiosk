package kiosk.front

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 구독 캘린더(iCal 주소)를 주기적으로 받아 대시보드에 넘긴다.
 * 해석(반복 일정 펼치기, #알림/#멘트 태그)은 web/ics.js 가 한다.
 * iCal 주소는 비밀이므로 로그·API 응답에 절대 남기지 않는다 (호스트 이름만).
 */
object CalendarSync {
    private const val EVERY_MIN = 15L
    private val exec = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var started = false
    @Volatile var status: JSONArray = JSONArray()
        private set

    fun start() {
        if (started) return
        started = true
        exec.scheduleWithFixedDelay({ syncAll() }, 10, EVERY_MIN * 60, TimeUnit.SECONDS)
    }

    fun now() = exec.execute { syncAll() }

    private fun calendars(): JSONArray = try { JSONArray(Prefs.calendarsJson) } catch (_: Exception) { JSONArray() }

    /** API 응답용: url 을 빼고 호스트만 */
    fun publicList(): JSONArray {
        val src = calendars()
        return JSONArray().apply {
            for (i in 0 until src.length()) {
                val c = JSONObject(src.getJSONObject(i).toString())
                val host = try { URL(normalize(c.optString("url"))).host } catch (_: Exception) { "?" }
                c.remove("url"); c.put("host", host)
                put(c)
            }
        }
    }

    private fun syncAll() {
        val cals = calendars()
        val names = JSONArray()
        val st = JSONArray()
        for (i in 0 until cals.length()) {
            val c = cals.optJSONObject(i) ?: continue
            val name = c.optString("name").ifBlank { "캘린더${i + 1}" }
            names.put(name)
            val cfg = JSONObject(c.toString()).apply { remove("url"); put("name", name) }
            val res = JSONObject().put("name", name).put("at", System.currentTimeMillis())
            try {
                val text = fetch(c.optString("url"))
                Hub.js("kiosk.loadIcs(${JSONObject.quote(name)}, $cfg, ${JSONObject.quote(text)})")
                res.put("ok", true).put("bytes", text.length)
            } catch (e: Exception) {
                // 예외 메시지에 URL 이 섞일 수 있어 종류만 남긴다
                Log.w(TAG, "calendar '$name' sync failed: ${e.javaClass.simpleName}")
                res.put("ok", false).put("error", e.javaClass.simpleName)
            }
            st.put(res)
        }
        Hub.js("kiosk.setCalendars($names)")
        status = st
    }

    private fun normalize(u: String) = u.trim().replaceFirst(Regex("^webcal://", RegexOption.IGNORE_CASE), "https://")

    private fun fetch(raw: String): String {
        var url = URL(normalize(raw))
        repeat(5) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "FrontKiosk/1.0")
            }
            try {
                when (val code = conn.responseCode) {
                    in 200..299 -> return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    in 300..399 -> url = URL(url, conn.getHeaderField("Location") ?: throw IllegalStateException("redirect"))
                    else -> throw IllegalStateException("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("too many redirects")
    }
}
