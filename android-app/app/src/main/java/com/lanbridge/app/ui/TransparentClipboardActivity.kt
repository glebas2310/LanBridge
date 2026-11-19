package com.lanbridge.app.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.lanbridge.app.service.LanBridgeForegroundService

class TransparentClipboardActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Read system clipboard while window has focus
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip

        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this)?.toString()
            if (!text.isNullOrEmpty()) {
                val serviceIntent = Intent(this, LanBridgeForegroundService::class.java).apply {
                    action = LanBridgeForegroundService.ACTION_SEND_TEXT
                    putExtra(LanBridgeForegroundService.EXTRA_CLIPBOARD_TEXT, text)
                }
                startService(serviceIntent)
                Toast.makeText(this, "Буфер отправлен на ПК", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show()
        }

        finish()
    }
}
