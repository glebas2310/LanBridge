package com.lanbridge.app.data.storage

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lanbridge.app.data.model.PairedDevice
import java.util.UUID

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences

    companion object {
        private const val TAG = "PreferencesManager"
        private const val PREFS_FILE = "lanbridge_secure_prefs"
        private const val FALLBACK_PREFS_FILE = "lanbridge_fallback_prefs"

        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_NAME = "client_name"

        private const val KEY_PAIRED_DEVICE_ID = "paired_device_id"
        private const val KEY_PAIRED_DEVICE_NAME = "paired_device_name"
        private const val KEY_PAIRED_CERT_FP = "paired_cert_fingerprint"
        private const val KEY_PAIRED_SESSION_TOKEN = "paired_session_token"
        private const val KEY_PAIRED_LAST_IP = "paired_last_ip"
        private const val KEY_PAIRED_WS_PORT = "paired_ws_port"
        private const val KEY_PAIRED_LAST_SEEN = "paired_last_seen"
    }

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, using standard fallback", e)
            context.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    fun getClientId(): String {
        var id = prefs.getString(KEY_CLIENT_ID, null)
        if (id == null) {
            id = "and_" + UUID.randomUUID().toString().replace("-", "").take(8)
            prefs.edit().putString(KEY_CLIENT_ID, id).apply()
        }
        return id
    }

    fun getClientName(): String {
        return prefs.getString(KEY_CLIENT_NAME, null) ?: Build.MODEL ?: "Android Device"
    }

    fun setClientName(name: String) {
        prefs.edit().putString(KEY_CLIENT_NAME, name).apply()
    }

    fun getPairedDevice(): PairedDevice? {
        val deviceId = prefs.getString(KEY_PAIRED_DEVICE_ID, null) ?: return null
        val deviceName = prefs.getString(KEY_PAIRED_DEVICE_NAME, "Windows PC") ?: "Windows PC"
        val certFingerprint = prefs.getString(KEY_PAIRED_CERT_FP, null) ?: return null
        val sessionToken = prefs.getString(KEY_PAIRED_SESSION_TOKEN, null) ?: return null
        val lastIp = prefs.getString(KEY_PAIRED_LAST_IP, "") ?: ""
        val wsPort = prefs.getInt(KEY_PAIRED_WS_PORT, 8443)
        val lastSeen = prefs.getLong(KEY_PAIRED_LAST_SEEN, 0L)

        return PairedDevice(
            deviceId = deviceId,
            deviceName = deviceName,
            certFingerprint = certFingerprint,
            sessionToken = sessionToken,
            lastIp = lastIp,
            wsPort = wsPort,
            lastSeenTimestamp = lastSeen
        )
    }

    fun savePairedDevice(device: PairedDevice) {
        prefs.edit()
            .putString(KEY_PAIRED_DEVICE_ID, device.deviceId)
            .putString(KEY_PAIRED_DEVICE_NAME, device.deviceName)
            .putString(KEY_PAIRED_CERT_FP, device.certFingerprint)
            .putString(KEY_PAIRED_SESSION_TOKEN, device.sessionToken)
            .putString(KEY_PAIRED_LAST_IP, device.lastIp)
            .putInt(KEY_PAIRED_WS_PORT, device.wsPort)
            .putLong(KEY_PAIRED_LAST_SEEN, System.currentTimeMillis())
            .apply()
    }

    fun updateLastKnownIp(ip: String) {
        prefs.edit()
            .putString(KEY_PAIRED_LAST_IP, ip)
            .putLong(KEY_PAIRED_LAST_SEEN, System.currentTimeMillis())
            .apply()
    }

    fun updateSessionToken(newToken: String) {
        prefs.edit()
            .putString(KEY_PAIRED_SESSION_TOKEN, newToken)
            .apply()
    }

    fun clearPairedDevice() {
        prefs.edit()
            .remove(KEY_PAIRED_DEVICE_ID)
            .remove(KEY_PAIRED_DEVICE_NAME)
            .remove(KEY_PAIRED_CERT_FP)
            .remove(KEY_PAIRED_SESSION_TOKEN)
            .remove(KEY_PAIRED_LAST_IP)
            .remove(KEY_PAIRED_WS_PORT)
            .remove(KEY_PAIRED_LAST_SEEN)
            .apply()
    }
}
