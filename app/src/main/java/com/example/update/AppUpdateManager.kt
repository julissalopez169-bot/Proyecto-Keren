package com.example.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.example.BuildConfig
import com.example.data.supabase.SupabaseManager
import com.example.notification.LuminaNotificationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val isMandatory: Boolean = false,
    val publishedAt: String = ""
)

class AppUpdateManager(
    private val context: Context,
    private val supabaseManager: SupabaseManager,
    private val notificationManager: LuminaNotificationManager
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("lumina_update_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val KEY_LAST_SNOOZED_TIME = "last_snoozed_timestamp"
        private const val KEY_SNOOZED_VERSION = "snoozed_version_code"
        private const val SNOOZE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours

        @Volatile
        private var INSTANCE: AppUpdateManager? = null

        fun getInstance(
            context: Context,
            supabaseManager: SupabaseManager,
            notificationManager: LuminaNotificationManager
        ): AppUpdateManager {
            return INSTANCE ?: synchronized(this) {
                val instance = AppUpdateManager(context.applicationContext, supabaseManager, notificationManager)
                INSTANCE = instance
                instance
            }
        }
    }

    val currentVersionCode: Int = BuildConfig.VERSION_CODE
    val currentVersionName: String = BuildConfig.VERSION_NAME

    /**
     * Checks if a new update is available.
     * @param forceCheck If true, ignores the 24-hour snooze reminder.
     */
    suspend fun checkForUpdates(forceCheck: Boolean = false): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            val urlStr = supabaseManager.supabaseUrl
            val keyStr = supabaseManager.supabaseAnonKey

            // If Supabase is configured, check remote table `app_updates`
            if (urlStr.isNotBlank() && keyStr.isNotBlank()) {
                val endpoint = "$urlStr/rest/v1/app_updates?select=*&order=version_code.desc&limit=1"
                val url = URL(endpoint)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("apikey", keyStr)
                    setRequestProperty("Authorization", "Bearer $keyStr")
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()

                    val jsonArray = JSONArray(body)
                    if (jsonArray.length() > 0) {
                        val obj = jsonArray.getJSONObject(0)
                        val remoteCode = obj.optInt("version_code", 1)
                        val remoteName = obj.optString("version_name", "1.1.0")
                        val notes = obj.optString("release_notes", "Mejoras de rendimiento y corrección de errores.")
                        val download = obj.optString("download_url", "https://github.com")
                        val mandatory = obj.optBoolean("is_mandatory", false)
                        val published = obj.optString("published_at", "")

                        val updateInfo = AppUpdateInfo(
                            versionCode = remoteCode,
                            versionName = remoteName,
                            releaseNotes = notes,
                            downloadUrl = download,
                            isMandatory = mandatory,
                            publishedAt = published
                        )

                        if (remoteCode > currentVersionCode) {
                            if (!forceCheck && isSnoozed(remoteCode) && !mandatory) {
                                Log.d(TAG, "Update $remoteCode available but snoozed by user.")
                                return@withContext Result.success(null)
                            }
                            return@withContext Result.success(updateInfo)
                        } else {
                            return@withContext Result.success(null)
                        }
                    }
                } else {
                    conn.disconnect()
                }
            }

            // If no remote table found or Supabase table not created yet, return no update
            Result.success(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Snooze reminders for this version for 24 hours.
     */
    fun snoozeUpdate(versionCode: Int) {
        prefs.edit()
            .putLong(KEY_LAST_SNOOZED_TIME, System.currentTimeMillis())
            .putInt(KEY_SNOOZED_VERSION, versionCode)
            .apply()
    }

    /**
     * Clear snooze to allow re-checking immediately.
     */
    fun clearSnooze() {
        prefs.edit()
            .remove(KEY_LAST_SNOOZED_TIME)
            .remove(KEY_SNOOZED_VERSION)
            .apply()
    }

    /**
     * Checks if notification was snoozed in the last 24 hours for this version.
     */
    private fun isSnoozed(versionCode: Int): Boolean {
        val lastSnooze = prefs.getLong(KEY_LAST_SNOOZED_TIME, 0L)
        val snoozedVer = prefs.getInt(KEY_SNOOZED_VERSION, -1)
        val timeSinceSnooze = System.currentTimeMillis() - lastSnooze

        return snoozedVer == versionCode && timeSinceSnooze < SNOOZE_DURATION_MS
    }

    /**
     * Simulates an update so the developer/user can test notifications and dialogs.
     */
    fun simulateUpdate(
        versionName: String = "1.1.0",
        versionCode: Int = 2,
        releaseNotes: String = "• Mejoras de rendimiento en transferencias instantáneas\n• Sincronización en tiempo real con Supabase PostgreSQL\n• Nuevo sistema de notificaciones de actualización OTA\n• Mayor protección en autenticación biométrica",
        downloadUrl: String = "https://github.com"
    ): AppUpdateInfo {
        clearSnooze()
        val update = AppUpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            releaseNotes = releaseNotes,
            downloadUrl = downloadUrl,
            isMandatory = false,
            publishedAt = "Hoy"
        )

        // Send Android push notification
        notificationManager.notifyUpdateAvailable(
            versionName = versionName,
            releaseNotes = "Nueva versión de Lumina Bank lista para instalar. Toca para actualizar o ver cambios.",
            downloadUrl = downloadUrl
        )

        return update
    }

    /**
     * Open browser or installer for downloading the new APK.
     */
    fun launchUpdateDownload(context: Context, downloadUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot launch download URL: ${e.message}")
        }
    }
}
