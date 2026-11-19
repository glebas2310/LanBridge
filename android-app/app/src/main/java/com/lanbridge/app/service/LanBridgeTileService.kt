package com.lanbridge.app.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.lanbridge.app.ui.TransparentClipboardActivity

@RequiresApi(Build.VERSION_CODES.N)
class LanBridgeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val isConnected = LanBridgeForegroundService.connectionState.value is com.lanbridge.app.data.model.ConnectionState.Connected
        tile.state = if (isConnected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Буфер на ПК"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()

        // Launch transparent activity to obtain window focus and read clipboard
        val intent = Intent(this, TransparentClipboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntentCompat(intent))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun PendingIntentCompat(intent: Intent): android.app.PendingIntent {
        return android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }
}
