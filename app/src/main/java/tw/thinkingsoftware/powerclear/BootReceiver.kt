package tw.thinkingsoftware.powerclear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 監聽系統啟動完成、快速啟動和應用程式更新（替換）事件的廣播接收器。
 * 當接收到這些事件時，會啟動 ChargingForegroundService。
 */
class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == null) {
            Log.w(TAG, "BootReceiver: 接收到空動作的 Intent。")
            return
        }

        Log.d(TAG, "BootReceiver 接收到動作: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
                // 直接使用字串來引用 ACTION_QUICKBOOT_POWERON (某些裝置特有)
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> { // <-- 新增此行，處理應用程式更新事件
                Log.i(TAG, "符合自動啟動服務的動作: ${intent.action}。正在啟動前景服務。")
                val serviceIntent = Intent(context, ChargingForegroundService::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                    Log.d(TAG, "BootReceiver: 已呼叫 context.startForegroundService() (API >= O)")
                } else {
                    context.startService(serviceIntent)
                    Log.d(TAG, "BootReceiver: 已呼叫 context.startService() (API < O)")
                }
            }
            else -> {
                Log.d(TAG, "BootReceiver: 接收到其他不相關的動作: ${intent.action}。")
            }
        }
    }
}
