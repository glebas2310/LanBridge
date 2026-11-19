package com.lanbridge.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.lanbridge.app.service.LanBridgeForegroundService

class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        if (!selectedText.isNullOrEmpty()) {
            val serviceIntent = Intent(this, LanBridgeForegroundService::class.java).apply {
                action = LanBridgeForegroundService.ACTION_SEND_TEXT
                putExtra(LanBridgeForegroundService.EXTRA_CLIPBOARD_TEXT, selectedText)
            }
            startService(serviceIntent)
            Toast.makeText(this, "Текст отправлен на ПК", Toast.LENGTH_SHORT).show()
        }

        finish()
    }
}
