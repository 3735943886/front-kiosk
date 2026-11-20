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
            return json(Response.Status.OK, JSONObject().put("ok", true).put("tts", Hub.ttsReady))
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
