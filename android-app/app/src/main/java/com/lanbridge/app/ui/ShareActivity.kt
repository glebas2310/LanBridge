package com.lanbridge.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.lanbridge.app.service.LanBridgeForegroundService

class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrEmpty()) {
                    val serviceIntent = Intent(this, LanBridgeForegroundService::class.java).apply {
                        action = LanBridgeForegroundService.ACTION_SEND_TEXT
                        putExtra(LanBridgeForegroundService.EXTRA_CLIPBOARD_TEXT, text)
                    }
                    startService(serviceIntent)
                    Toast.makeText(this, "Текст отправлен на ПК", Toast.LENGTH_SHORT).show()
                } else {
                    val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }

                    if (streamUri != null) {
                        val mime = intent.type ?: "application/octet-stream"
                        val serviceIntent = Intent(this, LanBridgeForegroundService::class.java).apply {
                            action = LanBridgeForegroundService.ACTION_SEND_FILE
                            putExtra(LanBridgeForegroundService.EXTRA_FILE_URI, streamUri)
                            putExtra(LanBridgeForegroundService.EXTRA_FILE_MIME, mime)
                        }
                        startService(serviceIntent)
                        Toast.makeText(this, "Отправка файла на ПК…", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }

                if (!streamUris.isNullOrEmpty()) {
                    val mime = intent.type ?: "application/octet-stream"
                    for (uri in streamUris) {
                        val serviceIntent = Intent(this, LanBridgeForegroundService::class.java).apply {
                            action = LanBridgeForegroundService.ACTION_SEND_FILE
                            putExtra(LanBridgeForegroundService.EXTRA_FILE_URI, uri)
                            putExtra(LanBridgeForegroundService.EXTRA_FILE_MIME, mime)
                        }
                        startService(serviceIntent)
                    }
                    Toast.makeText(this, "Отправка ${streamUris.size} файлов на ПК…", Toast.LENGTH_SHORT).show()
                }
            }
        }

        finish()
    }
}
