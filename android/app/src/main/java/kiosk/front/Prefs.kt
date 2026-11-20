package kiosk.front

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 기기에만 저장되는 설정. 위치·PIN·토큰 같은 개인 값은 리포가 아니라 여기에 둔다.
 * 설정 방법: adb shell am start -n kiosk.front/.MainActivity --es place 우리동네 --es lat 37.5 --es lon 127.0
 *          또는 PUT /api/config
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(c: Context) {
        sp = c.getSharedPreferences("kiosk", Context.MODE_PRIVATE)
        if (token.isEmpty()) token = randomToken()
    }

    private fun str(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    var token: String get() = str("token", ""); set(v) = put("token", v)
    var pin: String get() = str("pin", "1234"); set(v) = put("pin", v)
    /** 비어 있으면 앱에 묶인 web/index.html 을 띄운다. 개발 중엔 PC의 주소를 넣어 바로 확인할 수 있다. */
    var url: String get() = str("url", ""); set(v) = put("url", v)
    /** 알림을 읽어 대시보드로 넘길 앱 패키지 (쉼표 구분) */
    var watchRaw: String get() = str("watch", "com.sampleapp"); set(v) = put("watch", v)
    val watch: Set<String> get() = watchRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    /** 읽은 원본 알림을 시스템에서 지울지 */
    var consume: Boolean get() = sp.getBoolean("consume", true); set(v) = sp.edit().putBoolean("consume", v).apply()
    var port: Int get() = sp.getInt("port", 8080); set(v) = sp.edit().putInt("port", v).apply()

    // 날씨 위치 (웹 CONFIG 를 덮어씀)
    var place: String get() = str("place", ""); set(v) = put("place", v)
    var lat: String get() = str("lat", ""); set(v) = put("lat", v)
    var lon: String get() = str("lon", ""); set(v) = put("lon", v)

    /** 웹 쪽 CONFIG 에 합쳐질 값 */
    fun webConfig(): String = JSONObject().apply {
        if (place.isNotBlank()) put("place", place)
        lat.toDoubleOrNull()?.let { put("lat", it) }
        lon.toDoubleOrNull()?.let { put("lon", it) }
    }.toString()

    /** 키-값을 받아 반영. 알 수 없는 키는 무시. 바뀐 키 목록을 돌려준다. */
    fun apply(kv: Map<String, String>): List<String> {
        val changed = mutableListOf<String>()
        for ((k, v) in kv) {
            when (k) {
                "pin" -> if (v.length >= 4) pin = v else continue
                "token" -> if (v.length >= 16) token = v else continue
                "url" -> url = v
                "watch" -> watchRaw = v
                "consume" -> consume = v == "true" || v == "1"
                "place" -> place = v
                "lat" -> lat = v
                "lon" -> lon = v
                else -> continue
            }
            changed += k
        }
        return changed
    }

    private fun randomToken(): String {
        val b = ByteArray(18); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}
