package kiosk.front

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 원격 관리용 adb 접근 확보. 앱은 adb 를 5555 로 열 수 없다 (시스템 속성이라 adb 자체나 루트만 바꿈).
 * 대신 디바이스 오너로 무선 디버깅을 켜고, 그 포트(매번 바뀜)를 찾아 API 로 알려준다.
 * 그 포트로 adb connect 한 뒤 `adb tcpip 5555` 를 보내면 5555 가 다시 열린다 (재부팅 전까지).
 */
object AdbAccess {
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled" // Settings.Global.ADB_WIFI_ENABLED (API 30)

    fun adbEnabled(c: Context) = Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    fun wifiEnabled(c: Context) = Settings.Global.getInt(c.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    /** 무선 디버깅 켜기/끄기. 실패하면 이유를 돌려준다 */
    fun setWifi(c: Context, on: Boolean): String? {
        val dpm = c.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(c.packageName)) return "not device owner"
        val adm = ComponentName(c, AdminReceiver::class.java)
        return try {
            if (on) dpm.setGlobalSetting(adm, Settings.Global.ADB_ENABLED, "1")
            dpm.setGlobalSetting(adm, ADB_WIFI_ENABLED, if (on) "1" else "0")
            null
        } catch (e: Exception) {
            Log.w(TAG, "adb wifi", e); e.message ?: e.javaClass.simpleName
        }
    }

    fun isOpen(port: Int, timeoutMs: Int = 300): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs); true }
    } catch (_: Exception) { false }

    /** adb 서버인지: CNXN 을 보내면 adbd 는 STLS/AUTH/CNXN 으로 답한다 */
    private fun isAdb(port: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 200)
            s.soTimeout = 800
            val payload = "host::\u0000".toByteArray()
            val cmd = 0x4e584e43 // CNXN
            val h = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            h.putInt(cmd).putInt(0x01000001).putInt(256 * 1024).putInt(payload.size)
                .putInt(payload.sumOf { it.toInt() and 0xff }).putInt(cmd.inv()).put(payload)
            s.getOutputStream().apply { write(h.array()); flush() }
            val r = ByteArray(24); DataInputStream(s.getInputStream()).readFully(r)
            val got = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN).int
            got == 0x534c5453 || got == 0x48545541 || got == cmd // STLS, AUTH, CNXN
        }
    } catch (_: Exception) { false }

    /** 무선 디버깅 포트 찾기: 임시 포트 범위에서 열린 포트를 찾고 adb 응답으로 확인 (몇 초 걸림) */
    fun findWifiPort(): Int? {
        val found = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(48)
        try {
            for (p in 32768..60999) pool.execute {
                if (found.get() == 0 && isOpen(p, 150) && isAdb(p)) found.compareAndSet(0, p)
            }
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
        return found.get().takeIf { it != 0 }
    }

    /** 앱 시작 시: 5555 가 닫혀 있으면 무선 디버깅을 켜 둔다 (adb 복구 통로) */
    // Wi-Fi 연결 전에 켜면 시스템이 무시한다. 연결을 기다렸다가 켜고, 꺼지면 10분간 다시 시도
    fun ensureOnStart(c: Context) = Thread {
        repeat(20) {
            if (isOpen(5555) || (wifiEnabled(c) && onWifi(c))) return@Thread
            if (onWifi(c)) {
                val err = setWifi(c, true)
                Log.i(TAG, "adb 5555 closed; wireless debugging on: ${err ?: "ok"}")
                if (err != null) return@Thread
            }
            Thread.sleep(30_000)
        }
    }.start()

    private fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun status(c: Context, scan: Boolean): org.json.JSONObject = org.json.JSONObject()
        .put("adb", adbEnabled(c)).put("tcp5555", isOpen(5555)).put("wifi", wifiEnabled(c))
        .put("wifiPort", if (scan && wifiEnabled(c)) findWifiPort() ?: org.json.JSONObject.NULL else org.json.JSONObject.NULL)
}
