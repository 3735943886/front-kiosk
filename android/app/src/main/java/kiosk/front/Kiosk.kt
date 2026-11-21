package kiosk.front

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.util.Log

/**
 * 디바이스 오너 정책. 오너 지정: adb shell dpm set-device-owner kiosk.front/.AdminReceiver
 * - 잠금 작업 모드: 홈/최근 앱/알림창/빠른 설정/전원 메뉴/알림 팝업 모두 차단 (LOCK_TASK_FEATURE_NONE)
 * - 상태 표시줄 비활성, 잠금 화면 비활성, 충전 중 화면 유지
 * - 이 앱을 고정 홈 앱으로 지정 → 부팅하면 바로 대시보드
 */
object Kiosk {
    private fun admin(c: Context) = ComponentName(c, AdminReceiver::class.java)
    private fun dpm(c: Context) = c.getSystemService(DevicePolicyManager::class.java)

    fun isOwner(c: Context) = dpm(c).isDeviceOwnerApp(c.packageName)

    fun applyPolicies(a: Activity) {
        if (!isOwner(a)) { Log.w(TAG, "디바이스 오너가 아니라 키오스크 잠금 없이 실행합니다"); return }
        val d = dpm(a); val adm = admin(a)
        try {
            // SystemUI: 무선 디버깅 허용 창이 잠금 중에도 떠야 AutoAllow 가 누를 수 있다
            d.setLockTaskPackages(adm, arrayOf(a.packageName, "com.android.systemui"))
            d.setLockTaskFeatures(adm, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            d.setKeyguardDisabled(adm, true)
            d.setGlobalSetting(adm, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7")
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
            }
            d.addPersistentPreferredActivity(adm, home, ComponentName(a, MainActivity::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "policy", e)
        }
    }

    fun isLocked(a: Activity) =
        a.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    fun lock(a: Activity) {
        if (!isOwner(a)) return
        try { dpm(a).setStatusBarDisabled(admin(a), true) } catch (e: Exception) { Log.w(TAG, "statusbar", e) }
        if (!isLocked(a)) try { a.startLockTask() } catch (e: Exception) { Log.w(TAG, "lockTask", e) }
    }

    /** 관리용 일시 해제. 이 앱(홈)으로 돌아오면 onResume 에서 다시 잠긴다. */
    fun unlock(a: Activity) {
        try { if (isLocked(a)) a.stopLockTask() } catch (e: Exception) { Log.w(TAG, "unlock", e) }
        if (isOwner(a)) try { dpm(a).setStatusBarDisabled(admin(a), false) } catch (_: Exception) {}
    }

    /** 원격 재부팅 (디바이스 오너) */
    fun reboot(c: Context): String? = try {
        if (!isOwner(c)) "not device owner" else { dpm(c).reboot(admin(c)); null }
    } catch (e: Exception) { e.message ?: e.javaClass.simpleName }

    /** 디바이스 오너 완전 해제 (앱 삭제 전, 또는 키오스크를 그만 쓸 때) */
    fun releaseOwner(a: Activity) {
        if (!isOwner(a)) return
        val d = dpm(a); val adm = admin(a)
        unlock(a)
        try {
            d.clearPackagePersistentPreferredActivities(adm, a.packageName)
            d.setKeyguardDisabled(adm, false)
            d.setLockTaskPackages(adm, arrayOf())
            d.clearDeviceOwnerApp(a.packageName)
        } catch (e: Exception) {
            Log.e(TAG, "release", e)
        }
    }
}
