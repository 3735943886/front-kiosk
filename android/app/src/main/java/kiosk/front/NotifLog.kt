package kiosk.front

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 받은 알림 원본을 기기 파일에 쌓는다 (앱 재시작·업데이트·재부팅 뒤에도 남음).
 * 어댑터(web/apps)를 만들거나 고칠 때 실제 문구 확인용: GET /api/android-notifications
 */
object NotifLog {
    private const val KEEP = 500
    private var file: File? = null
    private var count = -1

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "notif-log.jsonl")
    }

    @Synchronized
    fun add(o: JSONObject) {
        val f = file ?: return
        try {
            f.appendText(o.toString() + "\n")
            if (count < 0) count = f.readLines().size else count++
            if (count > KEEP + 100) {
                val keep = f.readLines().takeLast(KEEP)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
                count = keep.size
            }
        } catch (e: Exception) {
            Log.w(TAG, "notif log", e)
        }
    }

    /** 최근 것부터 [limit]개, [pkg] 가 있으면 그 앱만 */
    @Synchronized
    fun recent(limit: Int, pkg: String?): JSONArray {
        val out = JSONArray()
        val lines = try { file?.takeIf { it.exists() }?.readLines() ?: emptyList() } catch (e: Exception) { emptyList() }
        for (line in lines.asReversed()) {
            if (out.length() >= limit) break
            val o = try { JSONObject(line) } catch (e: Exception) { continue }
            if (pkg == null || o.optString("package") == pkg) out.put(o)
        }
        return out
    }
}
