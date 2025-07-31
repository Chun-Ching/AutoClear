package tw.thinkingsoftware.powerclear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.util.Log

/**
 * 前景服務，用於在應用程式背景運行時，持續監測充電狀態和螢幕事件。
 * 它會動態註冊 ChargingBroadcastReceiver 並顯示一個持續的通知。
 */
class ChargingForegroundService : Service() {

    private val TAG = "ChargingForegroundService"
    private val NOTIFICATION_CHANNEL_ID = "charging_service_channel"
    private val NOTIFICATION_ID = 101

    // 實例化您的 BroadcastReceiver
    private val chargingReceiver = ChargingBroadcastReceiver()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ChargingForegroundService onCreate")
        createNotificationChannel()

        // 創建前景服務通知
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("背景監控服務")
            // 建議使用更具體的通知文字
            .setContentText("正在背景監控裝置充電與螢幕狀態")
            // 建議使用您自己的應用程式圖標，例如 R.drawable.ic_launcher_foreground 或 R.mipmap.ic_launcher
            .setSmallIcon(android.R.drawable.ic_lock_power_off) // 替換為您自己的應用程式圖標
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // 確保在 Android 13 (API 33) 及更高版本上，前景服務行為設置正確
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
        // 啟動前景服務，並綁定通知
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "前景服務已啟動並顯示通知")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ChargingForegroundService onStartCommand")

        // 建立 IntentFilter，包含您想要監聽的所有事件
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // 動態註冊 BroadcastReceiver
        registerReceiver(chargingReceiver, filter)
        Log.d(TAG, "ChargingBroadcastReceiver 已在服務中動態註冊，包含充電和螢幕事件")

        return START_STICKY // 服務被殺死後，系統會嘗試重新創建它
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ChargingForegroundService onDestroy")
        // 在服務銷毀時，務必取消註冊 BroadcastReceiver，以避免記憶體洩漏
        unregisterReceiver(chargingReceiver)
        Log.d(TAG, "ChargingBroadcastReceiver 已在服務中取消註冊")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // 此服務不需要綁定
    }

    /**
     * 創建通知通道，這是 Android 8.0 (API 26) 及更高版本顯示通知的必要條件。
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "充電監控服務",
                NotificationManager.IMPORTANCE_LOW // 重要性設置為低，減少打擾
            ).apply {
                description = "用於在背景監控裝置充電狀態和螢幕事件。"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "通知通道已創建")
        }
    }
}
