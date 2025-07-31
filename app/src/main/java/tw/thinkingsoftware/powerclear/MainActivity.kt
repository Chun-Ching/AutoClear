package tw.thinkingsoftware.powerclear

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * 應用程式的「跳板」活動。
 * 它不顯示任何 UI，只負責在啟動時啟動 ChargingForegroundService，然後立即結束。
 * 這使得應用程式可以在沒有用戶界面互動的情況下，在背景啟動其核心服務。
 *
 * 已移除裝置管理員啟用邏輯。
 */
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // 權限請求啟動器，用於處理 POST_NOTIFICATIONS 權限
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "通知權限已授予。服務將嘗試顯示前景通知。")
            } else {
                Log.w(TAG, "通知權限被拒絕。服務將繼續運行，但可能無法顯示前景通知。")
            }
            // 無論通知權限是否授予，都繼續啟動服務並結束 Activity
            startServiceAndFinish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate: 正在檢查通知權限並準備啟動服務。")

        // 首先請求通知權限 (Android 13+)，然後在回調中啟動服務並結束 Activity
        requestNotificationPermission()

        // 注意：這裡沒有 setContent，因此不會顯示任何 UI
    }

    /**
     * 請求 POST_NOTIFICATIONS 權限 (Android 13+)。
     * 權限請求完成後，會呼叫 startServiceAndFinish()。
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33 (Android 13)
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "通知權限已授予 (在啟動時檢查)。直接啟動服務。")
                startServiceAndFinish() // 權限已授予，直接啟動服務並結束
            } else {
                Log.d(TAG, "Android 13+ 裝置，請求 POST_NOTIFICATIONS 權限。")
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                // 權限請求結果會在 requestPermissionLauncher 的回調中處理
            }
        } else {
            Log.d(TAG, "Android 版本低於 13，無需請求 POST_NOTIFICATIONS 權限。直接啟動服務。")
            startServiceAndFinish() // 舊版本直接啟動服務並結束
        }
    }

    /**
     * 啟動 ChargingForegroundService 並結束當前的 Activity。
     */
    private fun startServiceAndFinish() {
        startChargingMonitorService() // 啟動服務
        finish() // 結束 Activity
        Log.d(TAG, "MainActivity 已結束。")
    }

    /**
     * 啟動 ChargingForegroundService。
     */
    private fun startChargingMonitorService() {
        val serviceIntent = Intent(this, ChargingForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26 (Android 8.0 Oreo)
            startForegroundService(serviceIntent)
            Log.d(TAG, "已呼叫 startForegroundService (Android 8.0+)。")
        } else {
            startService(serviceIntent)
            Log.d(TAG, "已呼叫 startService (Android 8.0-)。")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy: 活動已銷毀。")
        // 活動銷毀時，服務會繼續運行，無需在此停止服務或取消註冊 BroadcastReceiver
    }
}
