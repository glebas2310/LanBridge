package com.lanbridge.app.service

import android.app.PendingIntent
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

        val intent = Intent(this, TransparentClipboardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Throwable) {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
