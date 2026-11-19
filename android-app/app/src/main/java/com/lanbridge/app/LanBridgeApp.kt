package com.lanbridge.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import com.lanbridge.app.data.storage.HistoryStorage
import com.lanbridge.app.data.storage.PreferencesManager
import com.lanbridge.app.service.LanBridgeForegroundService

class LanBridgeApp : Application() {

    companion object {
        const val CHANNEL_SERVICE_ID = "lanbridge_service_channel"
        const val CHANNEL_TRANSFERS_ID = "lanbridge_transfers_channel"

        lateinit var instance: LanBridgeApp
            private set
    }

    lateinit var preferencesManager: PreferencesManager
        private set

    lateinit var historyStorage: HistoryStorage
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferencesManager = PreferencesManager(this)
        historyStorage = HistoryStorage(this)

        createNotificationChannels()

        // If user already has a paired device, start the foreground service
        if (preferencesManager.getPairedDevice() != null) {
            startLanBridgeService(this)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Background Service Status Channel (Silent / Low importance)
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // File Transfers Channel (Default importance)
            val transferChannel = NotificationChannel(
                CHANNEL_TRANSFERS_ID,
                getString(R.string.notification_transfer_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notification_transfer_channel_desc)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(transferChannel)
        }
    }

    fun startLanBridgeService(context: Context) {
        val intent = Intent(context, LanBridgeForegroundService::class.java).apply {
            action = LanBridgeForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopLanBridgeService(context: Context) {
        val intent = Intent(context, LanBridgeForegroundService::class.java).apply {
            action = LanBridgeForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }
}
