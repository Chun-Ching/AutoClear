package tw.thinkingsoftware.powerclear

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import android.os.Bundle // 確保導入 Bundle
import android.content.SharedPreferences // 確保導入 SharedPreferences

data class DeviceData(val deviceId: String, val deviceOwnerName: String?)

class ChargingBroadcastReceiver : BroadcastReceiver() {

    private val TAG = "ChargingReceiver"

    private val KNOX_BASE_URL = "https://ap02.manage.samsungknox.com/emm"
    private val KNOX_AUTH_URL = "$KNOX_BASE_URL/oauth/token"
    // KNOX_SELECT_DEVICE_URL 重新啟用
    private val KNOX_SELECT_DEVICE_URL = "$KNOX_BASE_URL/oapi/device/selectDeviceInfoBySerialNumber"
    private val KNOX_CLEAR_DATA_URL = "$KNOX_BASE_URL/oapi/mdm/commonOTCServiceWrapper/sendDeviceControlForDeleteAppData"
    private val KNOX_NOTIFICATION_URL = "$KNOX_BASE_URL/oapi/mdm/commonOTCServiceWrapper/sendDeviceControlForNotification"

    // AppConfig 鍵
    // *** 還原：使用 SERIAL 鍵 ***
    private val APP_CONFIG_KEY_SERIAL = "serial_number" // 從 AppConfig 讀取裝置序列號
    private val APP_CONFIG_KEY_KNOX_CLIENT_ID = "knox_client_id"
    private val APP_CONFIG_KEY_KNOX_CLIENT_SECRET = "knox_client_secret"
    private val APP_CONFIG_KEY_APPS_TO_CLEAR = "apps_to_clear_data" // 從 AppConfig 讀取
    private val APP_CONFIG_KEY_SCREEN_OFF_DELAY_MINUTES = "screen_off_delay_minutes" // 從 AppConfig 讀取
    private val APP_CONFIG_KEY_TRIGGER_MODE = "trigger_mode" // 從 AppConfig 讀取

    private var knoxClientId = ""
    private var knoxClientSecret = ""
    // 預設為空列表，表示如果 MDM 沒有設定，則不清除任何 App 資料
    private var APPS_TO_CLEAR_DATA: List<String> = emptyList()
    // 螢幕關閉延遲時間，現在沒有預設初始值，將在 onReceive 中從 AppConfig 讀取並設定
    private var SCREEN_OFF_DELAY_MS: Long = 0L

    private val handler = Handler(Looper.getMainLooper())

    // 儲存 Context 的弱引用，以避免記憶體洩漏，並在需要時使用
    @SuppressLint("StaticFieldLeak") // 這裡由於是 BroadcastReceiver 的生命週期，可以暫時忽略
    private var applicationContext: Context? = null // 使用 applicationContext 以避免 Activity 洩漏

    private val screenOffTask = Runnable {
        applicationContext?.let { context ->
            val appRestrictions = readAppRestrictions(context)
            // *** 還原：從 AppConfig 讀取 serial ***
            val serial = appRestrictions.getString(APP_CONFIG_KEY_SERIAL).orEmpty()
            Log.d(TAG, "螢幕已關閉 ${SCREEN_OFF_DELAY_MS / 1000 / 60} 分鐘，觸發 Knox API 呼叫。序號: $serial")
            // 傳遞 serial
            triggerKnoxApiCall(context, serial)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == null) return

        applicationContext = context.applicationContext // 確保使用 Application Context

        val appRestrictions = readAppRestrictions(context)
        knoxClientId = appRestrictions.getString(APP_CONFIG_KEY_KNOX_CLIENT_ID).orEmpty()
        knoxClientSecret = appRestrictions.getString(APP_CONFIG_KEY_KNOX_CLIENT_SECRET).orEmpty()
        // *** 還原：從 AppConfig 讀取 serial ***
        val serial = appRestrictions.getString(APP_CONFIG_KEY_SERIAL).orEmpty()
        Log.d(TAG, "從 AppConfig 讀取到裝置序號: $serial") // 日誌也還原


        // 讀取 APPS_TO_CLEAR_DATA
        val appsToClearString = appRestrictions.getString(APP_CONFIG_KEY_APPS_TO_CLEAR)
        APPS_TO_CLEAR_DATA = if (!appsToClearString.isNullOrBlank()) {
            // 如果 MDM 提供了值，則解析並使用它
            appsToClearString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            // 如果 MDM 沒有提供值或值為空，則使用空的列表
            emptyList()
        }
        Log.d(TAG, "從 AppConfig 讀到要清除的 App 列表: $APPS_TO_CLEAR_DATA")

        // 讀取 SCREEN_OFF_DELAY_MINUTES，使用新的 safeGetLong 擴充函數
        // 如果 AppConfig 中沒有設定或設定為無效值，則預設為 5 分鐘
        var delayMinutes = appRestrictions.safeGetLong(APP_CONFIG_KEY_SCREEN_OFF_DELAY_MINUTES, 5L)
        if (delayMinutes <= 0) { // 確保延遲時間不為零或負數
            delayMinutes = 5L
            Log.w(TAG, "AppConfig 中 '$APP_CONFIG_KEY_SCREEN_OFF_DELAY_MINUTES' 設定為無效值或零，已回退到預設值 5 分鐘。")
        }
        SCREEN_OFF_DELAY_MS = delayMinutes * 60 * 1000L
        Log.d(TAG, "螢幕關閉延遲設定為: ${SCREEN_OFF_DELAY_MS / 1000 / 60} 分鐘")


        // *** 還原：檢查 serial 是否為空 ***
        if (serial.isBlank()) {
            Log.e(TAG, "AppConfig 未設定 serial_number！無法進行任何 Knox API 呼叫。")
            // 由於沒有有效的 serial，通知也無法發送給特定裝置，這裡傳遞一個通用訊息
            CoroutineScope(Dispatchers.IO).launch {
                sendKnoxNotification("N/A", "配置錯誤", "AppConfig 未設定 serial_number，無法呼叫 Knox API。", "")
            }
            return
        }

        if (knoxClientId.isEmpty() || knoxClientSecret.isEmpty()) {
            Log.e(TAG, "Knox Client ID 或 Client Secret 缺失，無法獲取 Token。")
            CoroutineScope(Dispatchers.IO).launch {
                sendKnoxNotification(serial, "配置錯誤", "Client ID/Secret 未設定，無法獲取 Token。", "")
            }
            return
        }

        // 處理觸發模式
        val triggerMode = appRestrictions.getString(APP_CONFIG_KEY_TRIGGER_MODE) ?: "charging" // 從 AppConfig 讀取觸發模式

        when (triggerMode) {
            "charging" -> {
                handler.removeCallbacks(screenOffTask) // 確保取消螢幕關閉計時器
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED, Intent.ACTION_BATTERY_CHANGED -> {
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                        if (isCharging) {
                            Log.d(TAG, "裝置正在充電或電量變化，觸發 Knox API 呼叫 (充電模式)。")
                            // *** 還原：傳遞 serial ***
                            triggerKnoxApiCall(context, serial)
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        Log.d(TAG, "裝置已斷開電源。")
                    }
                }
            }
            "screen_off" -> {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "螢幕已關閉。啟動螢幕關閉計時器。")
                        handler.postDelayed(screenOffTask, SCREEN_OFF_DELAY_MS)
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        Log.d(TAG, "螢幕已開啟或用戶已解鎖。取消螢幕關閉計時器。")
                        handler.removeCallbacks(screenOffTask)
                    }
                    else -> {
                        Log.d(TAG, "在螢幕關閉模式下，忽略充電相關事件: ${intent.action}")
                    }
                }
            }
            else -> {
                Log.w(TAG, "AppConfig 中設定了未知的觸發模式: $triggerMode。預設為充電模式。")
                // 回退到充電模式的邏輯
                handler.removeCallbacks(screenOffTask)
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED, Intent.ACTION_BATTERY_CHANGED -> {
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

                        if (isCharging) {
                            Log.d(TAG, "裝置正在充電或電量變化，觸發 Knox API 呼叫 (預設充電模式)。")
                            // *** 還原：傳遞 serial ***
                            triggerKnoxApiCall(context, serial)
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        Log.d(TAG, "裝置已斷開電源。")
                    }
                }
            }
        }
    }

    private fun readAppRestrictions(context: Context) =
        (context.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager)
            .getApplicationRestrictions()

    private fun Bundle.safeGetLong(key: String, defaultValue: Long): Long {
        return try {
            if (this.containsKey(key)) {
                val value = this.get(key) // 獲取原始物件
                when (value) {
                    is Long -> value // 如果本身就是 Long
                    is Int -> value.toLong() // 如果是 Int，轉換為 Long
                    is String -> value.toLongOrNull() ?: defaultValue // 如果是 String，嘗試解析，失敗則回傳預設值
                    else -> {
                        Log.w(TAG, "AppConfig 中 '$key' 的值類型為 ${value?.javaClass?.name}，非預期類型。")
                        defaultValue
                    }
                }
            } else {
                defaultValue // 鍵不存在
            }
        } catch (e: Exception) {
            Log.e(TAG, "安全獲取鍵 '$key' 的 Long 值時發生錯誤：${e.message}", e)
            defaultValue
        }
    }

    // *** 還原：triggerKnoxApiCall 現在接受 serial，並在內部呼叫 fetchKnoxDeviceId ***
    private fun triggerKnoxApiCall(context: Context, serial: String) {
        CoroutineScope(Dispatchers.IO).launch {
            if (!isNetworkAvailable(context)) {
                sendKnoxNotification(serial, "Knox 調用失敗", "無網路連接。", "")
                return@launch
            }

            // 獲取用於所有 Knox API 呼叫的 Token
            val knoxAccessToken = getKnoxAccessToken() ?: run {
                sendKnoxNotification(serial, "Knox 存取失敗", "無法取得 accessToken。", "")
                return@launch
            }
            Log.i(TAG, "成功取得 Knox 存取令牌。")

            // *** 還原：呼叫 selectDeviceInfoBySerialNumber API 獲取真正的 Knox Device ID ***
            Log.d(TAG, "正在使用序號 '$serial' 查詢 Knox Device ID...")
            val deviceData = fetchKnoxDeviceId(serial, knoxAccessToken)

            val knoxDeviceId: String
            val knoxDeviceOwnerName: String
            if (deviceData != null) {
                knoxDeviceId = deviceData.deviceId
                knoxDeviceOwnerName = deviceData.deviceOwnerName ?: "N/A"
                Log.i(TAG, "成功從 Knox API 獲取 DeviceID: $knoxDeviceId, DeviceOwnerName: $knoxDeviceOwnerName")
            } else {
                Log.e(TAG, "未能從 Knox API 獲取 DeviceID 或設備資訊。")
                knoxDeviceId = "N/A" // 如果無法獲取，則使用 N/A
                knoxDeviceOwnerName = "N/A (API Fail)"
                // 如果沒有有效的 Knox Device ID，則不執行清除 App 資料的命令
                sendKnoxNotification(serial, "Knox API 呼叫失敗", "未能從 Knox API 獲取有效的 Device ID。", knoxAccessToken)
                return@launch
            }

            // 只有當成功獲取到 Knox Device ID 且有效，並且有要清除的 App 列表時才執行清除 App 資料的命令
            if (knoxDeviceId != "N/A" && APPS_TO_CLEAR_DATA.isNotEmpty()) {
                clearAppDataOnDevice(knoxDeviceId, knoxAccessToken, APPS_TO_CLEAR_DATA)
            } else if (APPS_TO_CLEAR_DATA.isEmpty()) {
                Log.i(TAG, "AppConfig 中未設定要清除的 App 列表，不執行清除操作。")
                sendKnoxNotification(knoxDeviceId, "App 資料清除", "AppConfig 中未設定要清除的 App 列表，不執行清除操作。", knoxAccessToken)
            } else {
                Log.e(TAG, "無法執行清除 App 資料，因為 Knox Device ID 無效。")
            }
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) // 也考慮乙太網路
    }

    private suspend fun getKnoxAccessToken(): String? {
        return try {
            val conn = URL(KNOX_AUTH_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = listOf(
                "grant_type=client_credentials",
                "client_id=${URLEncoder.encode(knoxClientId, "UTF-8")}",
                "client_secret=${URLEncoder.encode(knoxClientSecret, "UTF-8")}"
            ).joinToString("&")

            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val responseCode = conn.responseCode
            // 根據 responseCode 選擇正確的流
            val stream = if (responseCode == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val json = JSONObject(resp)
                val token = json.optString("access_token", null)
                Log.i(TAG, "取得 Token 成功。")
                token
            } else {
                Log.e(TAG, "取得 Token 失敗：HTTP $responseCode - $resp")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "取得 Token 異常：${e.message}", e)
            null
        }
    }

    // *** 還原：fetchKnoxDeviceId 函數 ***
    private suspend fun fetchKnoxDeviceId(serial: String, token: String): DeviceData? {
        return try {
            val conn = URL(KNOX_SELECT_DEVICE_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Authorization", "Bearer $token") // 使用傳入的統一 Token

            val body = "serialNumber=" + URLEncoder.encode(serial, "UTF-8")
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val responseCode = conn.responseCode
            // 根據 responseCode 選擇正確的流
            val stream = if (responseCode == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val root = JSONObject(resp)
                val resultCode = root.optString("resultCode") // 獲取 "resultCode" 欄位的值 (例如 "0")
                val resultMessage = root.optString("resultMessage") // 獲取 "resultMessage" 欄位的值 (例如 "No Error")
                val resultValue = root.optJSONObject("resultValue") // 獲取 "resultValue" 物件，其中包含所有裝置詳細資訊

                // 檢查 resultCode 是否為 "0" (代表成功) 且 resultValue 物件存在
                if (resultCode == "0" && resultValue != null) {
                    // 從 resultValue 物件中提取 deviceId
                    val deviceId = resultValue.optString("deviceId", null) // <-- 這就是您要的 deviceId
                    // 嘗試提取 deviceOwnerName，如果不存在則使用 userId 作為備用
                    val deviceOwnerName = resultValue.optString("deviceOwnerName", resultValue.optString("userId", null))

                    if (!deviceId.isNullOrBlank()) {
                        Log.i(TAG, "selectDeviceInfo 回傳 deviceId=$deviceId, deviceOwnerName=$deviceOwnerName")
                        return DeviceData(deviceId, deviceOwnerName) // 回傳包含 deviceId 和 deviceOwnerName 的物件
                    } else {
                        Log.e(TAG, "selectDeviceInfo 回應成功但 deviceId 為空或不存在。")
                        return null
                    }
                } else {
                    Log.e(TAG, "selectDeviceInfo 回應失敗或缺少 'resultValue'。resultCode: $resultCode, Message: $resultMessage")
                    return null
                }
            } else {
                Log.e(TAG, "selectDeviceInfo 呼叫失敗：HTTP $responseCode - $resp")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchKnoxDeviceId 異常：${e.message}", e)
            return null
        }
    }


    private suspend fun clearAppDataOnDevice(deviceId: String, token: String, apps: List<String>) {
        // 在這裡再次檢查 apps 列表是否為空，儘管在呼叫此函數之前已經檢查過
        if (apps.isEmpty()) {
            Log.i(TAG, "要清除的 App 列表為空，不執行清除操作。")
            sendKnoxNotification(deviceId, "App 資料清除", "要清除的 App 列表為空，不執行清除操作。", token)
            return
        }

        try {
            val conn = URL(KNOX_CLEAR_DATA_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Authorization", "Bearer $token")

            // 構建請求體，將每個應用程式包名作為獨立的 'appPackages' 參數傳遞
            val params = mutableListOf<String>()
            params.add("deviceId=${URLEncoder.encode(deviceId, "UTF-8")}")

            // 對於每個要清除的 App，都添加一個 'appPackages' 參數
            apps.forEach { appPackage ->
                params.add("appPackages=${URLEncoder.encode(appPackage, "UTF-8")}")
            }
            params.add("deleteType=${URLEncoder.encode("ALL", "UTF-8")}") // 確保 deleteType 也被編碼

            val body = params.joinToString("&")

            Log.d(TAG, "發送給 Knox API 的 clearData 請求體: $body") // 新增日誌，用於確認請求體格式

            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val responseCode = conn.responseCode
            val stream = if (responseCode == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }

            Log.i(TAG, "clearData 回應: HTTP $responseCode - $resp")
            val message = if (responseCode == HttpURLConnection.HTTP_OK) {
                try {
                    val jsonResponse = JSONObject(resp)
                    // 檢查 resultCode 是否為 "0" (成功) 並且 areaResult 中的 result 也為 "0" (成功)
                    if (jsonResponse.optString("resultCode") == "0" &&
                        jsonResponse.optJSONObject("resultValue")?.optJSONArray("areaResult")?.optJSONObject(0)?.optString("result") == "0") {
                        "清除 App 資料命令已成功發送！"
                    } else {
                        "清除 App 資料命令已發送但回應異常: $resp"
                    }
                } catch (e: Exception) {
                    "清除 App 資料命令已發送但解析回應失敗: ${e.message}, 回應: $resp"
                }
            } else {
                "清除失敗，錯誤代碼: $responseCode, 回應: $resp"
            }
            sendKnoxNotification(deviceId, "App 資料清除結果", message, token)
        } catch (e: Exception) {
            Log.e(TAG, "clearData 失敗：${e.message}", e)
            sendKnoxNotification(deviceId, "App 資料清除失敗", e.message ?: "未知錯誤", token)
        }
    }

    private suspend fun sendKnoxNotification(deviceId: String, title: String, message: String, token: String) {
        try {
            // 這裡直接使用傳入的 token，因為它應該是最新且有效的
            val notifToken = token

            val conn = URL(KNOX_NOTIFICATION_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Authorization", "Bearer $notifToken")

            val body = listOf(
                "deviceId=${URLEncoder.encode(deviceId, "UTF-8")}",
                "Title=${URLEncoder.encode(title, "UTF-8")}",
                "Message=${URLEncoder.encode(message, "UTF-8")}",
                "SendType=Notification"
            ).joinToString("&")

            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val responseCode = conn.responseCode
            val stream = if (responseCode == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
            val resp = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }

            Log.i(TAG, "Notification API 回應碼: $responseCode - $resp")
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Knox 通知發送失敗，錯誤代碼: $responseCode, 回應: $resp")
            } else {
                Log.i(TAG, "Knox 通知命令已成功發送。")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendNotification 失敗：${e.message}", e)
        }
    }
}
