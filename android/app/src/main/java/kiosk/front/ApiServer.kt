package kiosk.front

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * LAN용 HTTP API. 모든 요청(health 제외)은 Authorization: Bearer <token> 필요.
 *   POST   /api/notify              알림 추가/갱신 (body: 알림 JSON) → {"id": ...}
 *   DELETE /api/notify/{id}         알림 지우기
 *   DELETE /api/notify              알림 모두 지우기
 *   PUT    /api/agenda              일정 목록 통째로 교체 (body: JSON 배열)
 *   POST   /api/speak               {"text": "..."} 화면 표시 없이 음성만
 *   GET    /api/state               현재 알림·일정
 *   GET    /api/android-notifications  최근 받은 다른 앱 알림 원본
 *   PUT    /api/config              {"place","lat","lon","pin","watch","consume","url"}
 *   POST   /api/reload              화면 새로고침
 *   PUT    /api/calendars           구독 캘린더 목록 교체: 항목마다 name, url, color, remind (url 은 기기에만 저장)
 *   GET    /api/calendars           구독 캘린더 목록(url 대신 host)과 마지막 동기화 결과
 *   POST   /api/calendars/sync      지금 동기화
 *   PUT    /api/gas                 {"url","token"} Apps Script 웹 앱 (일정 읽기·쓰기) 등록
 *   GET    /api/gas                 설정 여부와 마지막 동기화 결과
 *   POST   /api/gas/sync            지금 동기화
 *   POST   /api/admin/unlock        키오스크 잠금 잠시 해제 (홈으로 돌아오면 다시 잠김)
 *   POST   /api/admin/lock          대시보드로 돌아가 다시 잠금
 *   POST   /api/admin/open          {"uri"} 또는 {"package"} 잠금 해제 후 열기 (예: market://details?id=…)
 */
class ApiServer(port: Int) : NanoHTTPD(port) {

    companion object {
        private var instance: ApiServer? = null
        fun startDefault() {
            if (instance != null) return
            try {
                instance = ApiServer(Prefs.port).also { it.start(SOCKET_READ_TIMEOUT, false) }
            } catch (e: Exception) {
                Log.e(TAG, "API server start failed", e)
            }
        }
    }

    override fun serve(s: IHTTPSession): Response = try {
        route(s)
    } catch (e: Exception) {
        Log.w(TAG, "api error", e)
        json(Response.Status.BAD_REQUEST, JSONObject().put("error", e.message ?: e.javaClass.simpleName))
    }

    private fun route(s: IHTTPSession): Response {
        val path = s.uri.trimEnd('/')
        val m = s.method
        if (m == Method.OPTIONS) return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        if (path == "/api/health") {
            return json(Response.Status.OK, JSONObject().put("ok", true)
                .put("tts", Hub.ttsReady).put("ttsKorean", Hub.ttsKoreanReady).put("ttsEngine", Hub.ttsEngine()))
        }
        if (s.headers["authorization"] != "Bearer ${Prefs.token}") {
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "unauthorized"))
        }
        val body = readBody(s)
        return when {
            m == Method.POST && path == "/api/notify" -> {
                val n = JSONObject(body)
                val r = Hub.jsBlocking("kiosk.notify($n)")
                ok().put("id", decode(r) ?: JSONObject.NULL).let { json(Response.Status.OK, it) }
            }
            m == Method.DELETE && path == "/api/notify" -> { Hub.js("kiosk.clear()"); json(Response.Status.OK, ok()) }
            m == Method.DELETE && path.startsWith("/api/notify/") -> {
                val id = java.net.URLDecoder.decode(path.removePrefix("/api/notify/"), "UTF-8")
                Hub.js("kiosk.dismiss(${JSONObject.quote(id)})")
                json(Response.Status.OK, ok())
            }
            m == Method.PUT && path == "/api/agenda" -> {
                val list = JSONArray(body)
                Hub.js("kiosk.setAgenda($list)")
                json(Response.Status.OK, ok().put("count", list.length()))
            }
            m == Method.POST && path == "/api/speak" -> {
                Hub.speak(JSONObject(body).getString("text"))
                json(Response.Status.OK, ok().put("tts", Hub.ttsReady))
            }
            m == Method.GET && path == "/api/state" -> {
                val r = decode(Hub.jsBlocking("JSON.stringify({notifs: kiosk.list(), agenda: kiosk.agenda()})")) as? String
                raw(Response.Status.OK, r ?: "{}")
            }
            m == Method.GET && path == "/api/android-notifications" -> raw(Response.Status.OK, Hub.recentJson())
            m == Method.PUT && path == "/api/config" -> {
                val o = JSONObject(body)
                val kv = o.keys().asSequence().associateWith { o.get(it).toString() }
                val changed = Prefs.apply(kv)
                if (changed.any { it in setOf("place", "lat", "lon", "url") }) Hub.load(Hub.startUrl())
                json(Response.Status.OK, ok().put("changed", JSONArray(changed)))
            }
            m == Method.POST && path == "/api/reload" -> { Hub.reload(); json(Response.Status.OK, ok()) }
            m == Method.PUT && path == "/api/calendars" -> {
                val list = JSONArray(body)
                for (i in 0 until list.length()) {
                    val u = list.getJSONObject(i).optString("url")
                    require(Regex("^(https?|webcal)://", RegexOption.IGNORE_CASE).containsMatchIn(u)) { "calendar ${i + 1}: url must be http(s) or webcal" }
                }
                Prefs.calendarsJson = list.toString()
                CalendarSync.now()
                json(Response.Status.OK, ok().put("count", list.length()))
            }
            m == Method.GET && path == "/api/calendars" ->
                json(Response.Status.OK, ok().put("calendars", CalendarSync.publicList()).put("status", CalendarSync.status))
            m == Method.POST && path == "/api/calendars/sync" -> { CalendarSync.now(); json(Response.Status.OK, ok()) }
            m == Method.PUT && path == "/api/gas" -> {
                val o = JSONObject(body)
                val u = o.optString("url").trim()
                val t = o.optString("token").trim()
                require(u.isEmpty() || u.startsWith("https://script.google.com/")) { "url must be a script.google.com web app URL" }
                Prefs.gasUrl = u; Prefs.gasToken = t
                GasSync.now()
                json(Response.Status.OK, ok().put("configured", Prefs.gasReady))
            }
            m == Method.GET && path == "/api/gas" ->
                json(Response.Status.OK, ok().put("configured", Prefs.gasReady).put("status", GasSync.status))
            m == Method.POST && path == "/api/gas/sync" -> { GasSync.now(); json(Response.Status.OK, ok()) }
            m == Method.POST && path == "/api/admin/unlock" ->
                json(Response.Status.OK, ok().put("done", Hub.onActivity { Kiosk.unlock(it) }))
            m == Method.POST && path == "/api/admin/lock" -> json(Response.Status.OK, ok().put("done", Hub.onActivity { a ->
                a.startActivity(android.content.Intent(a, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                Kiosk.lock(a)
            }))
            m == Method.POST && path == "/api/admin/open" -> {
                val o = JSONObject(body.ifBlank { "{}" })
                val uri = o.optString("uri"); val pkg = o.optString("package")
                require(uri.isNotBlank() || pkg.isNotBlank()) { "uri or package required" }
                json(Response.Status.OK, ok().put("done", Hub.onActivity { a ->
                    Kiosk.unlock(a)
                    val i = if (uri.isNotBlank()) android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    else a.packageManager.getLaunchIntentForPackage(pkg)
                    if (i == null) Log.w(TAG, "open: no launch intent for $pkg")
                    else try { a.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.w(TAG, "open failed", e) }
                }))
            }
            else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "not found: $m $path"))
        }
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun decode(r: String?): Any? = if (r == null || r == "null") null else JSONTokener(r).nextValue()

    private fun readBody(s: IHTTPSession): String {
        val len = s.headers["content-length"]?.toIntOrNull() ?: return ""
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = s.inputStream.read(buf, off, len - off)
            if (n <= 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun json(st: Response.Status, o: JSONObject) = raw(st, o.toString())
    private fun raw(st: Response.Status, s: String) =
        cors(newFixedLengthResponse(st, "application/json; charset=utf-8", s))

    private fun cors(r: Response) = r.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type")
        addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
    }
}
