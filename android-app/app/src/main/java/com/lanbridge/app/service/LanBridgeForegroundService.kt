package com.lanbridge.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.lanbridge.app.LanBridgeApp
import com.lanbridge.app.R
import com.lanbridge.app.data.model.AuthFailMessage
import com.lanbridge.app.data.model.AuthOkMessage
import com.lanbridge.app.data.model.ClipboardImageMessage
import com.lanbridge.app.data.model.ClipboardTextMessage
import com.lanbridge.app.data.model.ConnectionState
import com.lanbridge.app.data.model.FileAcceptMessage
import com.lanbridge.app.data.model.FileCompleteMessage
import com.lanbridge.app.data.model.FileOfferMessage
import com.lanbridge.app.data.model.FileProgressMessage
import com.lanbridge.app.data.model.FileRejectMessage
import com.lanbridge.app.data.model.HistoryItem
import com.lanbridge.app.data.model.HistoryKind
import com.lanbridge.app.data.model.HistorySyncMessage
import com.lanbridge.app.data.model.PairedDevice
import com.lanbridge.app.data.model.PairingUriData
import com.lanbridge.app.network.EchoLoopGuard
import com.lanbridge.app.network.FileTransferManager
import com.lanbridge.app.network.LanBridgeMessageListener
import com.lanbridge.app.network.LanBridgeWebSocketClient
import com.lanbridge.app.network.UdpDiscoveryListener
import com.lanbridge.app.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class LanBridgeForegroundService : LifecycleService(), LanBridgeMessageListener {

    companion object {
        private const val TAG = "LanBridgeFgService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_TRANSFER_ID = 1002

        const val ACTION_START = "com.lanbridge.app.action.START"
        const val ACTION_STOP = "com.lanbridge.app.action.STOP"
        const val ACTION_DISCONNECT = "com.lanbridge.app.action.DISCONNECT"
        const val ACTION_RECONNECT = "com.lanbridge.app.action.RECONNECT"
        const val ACTION_PAIR = "com.lanbridge.app.action.PAIR"
        const val ACTION_SEND_TEXT = "com.lanbridge.app.action.SEND_TEXT"
        const val ACTION_SEND_FILE = "com.lanbridge.app.action.SEND_FILE"

        const val EXTRA_PAIR_URI = "extra_pair_uri"
        const val EXTRA_CLIPBOARD_TEXT = "extra_clipboard_text"
        const val EXTRA_FILE_URI = "extra_file_uri"
        const val EXTRA_FILE_MIME = "extra_file_mime"

        private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        private val _activeDevice = MutableStateFlow<PairedDevice?>(null)
        val activeDevice: StateFlow<PairedDevice?> = _activeDevice.asStateFlow()

        private val _pingMs = MutableStateFlow(0L)
        val pingMs: StateFlow<Long> = _pingMs.asStateFlow()

        var currentServiceInstance: LanBridgeForegroundService? = null
            private set
    }

    private lateinit var clipboardManager: ClipboardManager
    private lateinit var echoLoopGuard: EchoLoopGuard
    private lateinit var fileTransferManager: FileTransferManager
    private lateinit var udpDiscovery: UdpDiscoveryListener
    private var webSocketClient: LanBridgeWebSocketClient? = null

    override fun onCreate() {
        super.onCreate()
        currentServiceInstance = this
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        echoLoopGuard = EchoLoopGuard()
        fileTransferManager = FileTransferManager(this)

        val app = LanBridgeApp.instance
        _activeDevice.value = app.preferencesManager.getPairedDevice()

        udpDiscovery = UdpDiscoveryListener(this) { beacon, sourceIp ->
            val paired = app.preferencesManager.getPairedDevice()
            if (paired != null && paired.deviceId == beacon.device_id) {
                if (paired.lastIp != sourceIp) {
                    Log.d(TAG, "Device IP changed from ${paired.lastIp} to $sourceIp")
                    val updated = paired.copy(lastIp = sourceIp, wsPort = beacon.ws_port)
                    app.preferencesManager.savePairedDevice(updated)
                    _activeDevice.value = updated
                }
                connectToPairedDevice()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundWithTypes(getString(R.string.notification_discovering))

        when (intent?.action) {
            ACTION_STOP -> {
                webSocketClient?.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                webSocketClient?.disconnect()
                _connectionState.value = ConnectionState.Disconnected
                updateNotification(getString(R.string.notification_disconnected), false)
            }
            ACTION_RECONNECT -> {
                connectToPairedDevice()
            }
            ACTION_PAIR -> {
                val uriString = intent.getStringExtra(EXTRA_PAIR_URI)
                if (!uriString.isNullOrEmpty()) {
                    handlePairRequest(uriString)
                }
            }
            ACTION_SEND_TEXT -> {
                val text = intent.getStringExtra(EXTRA_CLIPBOARD_TEXT)
                if (!text.isNullOrEmpty()) {
                    sendClipboardTextToPc(text)
                }
            }
            ACTION_SEND_FILE -> {
                val uri = intent.getParcelableExtra<Uri>(EXTRA_FILE_URI)
                val mime = intent.getStringExtra(EXTRA_FILE_MIME) ?: "application/octet-stream"
                if (uri != null) {
                    uploadFileToPc(uri, mime)
                }
            }
            else -> {
                connectToPairedDevice()
            }
        }

        udpDiscovery.start(lifecycleScope)
        return START_STICKY
    }

    private fun startForegroundWithTypes(text: String) {
        val notification = buildForegroundNotification(text, false)
        var serviceTypes = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceTypes)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error", e)
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(statusText: String, isConnected: Boolean): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingLaunch = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, LanBridgeApp.CHANNEL_SERVICE_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingLaunch)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isConnected) {
            val disconnectIntent = Intent(this, LanBridgeForegroundService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            val pendingDisconnect = PendingIntent.getService(
                this, 1, disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notification_action_disconnect), pendingDisconnect)
        } else {
            val reconnectIntent = Intent(this, LanBridgeForegroundService::class.java).apply {
                action = ACTION_RECONNECT
            }
            val pendingReconnect = PendingIntent.getService(
                this, 2, reconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.notification_action_reconnect), pendingReconnect)
        }

        return builder.build()
    }

    private fun updateNotification(statusText: String, isConnected: Boolean) {
        val notification = buildForegroundNotification(statusText, isConnected)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun connectToPairedDevice() {
        val app = LanBridgeApp.instance
        val paired = app.preferencesManager.getPairedDevice() ?: return

        if (webSocketClient == null) {
            webSocketClient = LanBridgeWebSocketClient(
                scope = lifecycleScope,
                clientId = app.preferencesManager.getClientId(),
                clientName = app.preferencesManager.getClientName(),
                listener = this
            )
        }

        webSocketClient?.connect(paired)
    }

    private fun handlePairRequest(uriString: String) {
        val pairData = PairingUriData.parse(uriString)
        if (pairData == null) {
            Toast.makeText(this, "Неверный формат QR-кода LanBridge", Toast.LENGTH_SHORT).show()
            return
        }

        val app = LanBridgeApp.instance
        val device = PairedDevice(
            deviceId = pairData.deviceId,
            deviceName = pairData.deviceName,
            certFingerprint = pairData.certFingerprint,
            sessionToken = pairData.oneTimeToken,
            lastIp = pairData.ip,
            wsPort = pairData.port
        )

        app.preferencesManager.savePairedDevice(device)
        _activeDevice.value = device

        if (webSocketClient == null) {
            webSocketClient = LanBridgeWebSocketClient(
                scope = lifecycleScope,
                clientId = app.preferencesManager.getClientId(),
                clientName = app.preferencesManager.getClientName(),
                listener = this
            )
        }

        webSocketClient?.connect(device)
    }

    fun sendClipboardTextToPc(text: String) {
        val sha256 = EchoLoopGuard.computeSha256(text)
        if (echoLoopGuard.isRemoteEcho(sha256)) return

        lifecycleScope.launch {
            webSocketClient?.sendClipboardText(content = text, sha256 = sha256)
            LanBridgeApp.instance.historyStorage.addItem(
                HistoryItem(
                    id = UUID.randomUUID().toString(),
                    kind = HistoryKind.TEXT,
                    content = text,
                    sha256 = sha256,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun uploadFileToPc(fileUri: Uri, mimeType: String) {
        val device = _activeDevice.value ?: return
        lifecycleScope.launch {
            val fileId = UUID.randomUUID().toString().replace("-", "")
            val contentResolver = contentResolver
            val fileName = contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            } ?: "shared_file"

            val fileSize = contentResolver.openFileDescriptor(fileUri, "r")?.use { it.statSize } ?: 0L

            webSocketClient?.sendFileOffer(
                fileId = fileId,
                fileName = fileName,
                fileSize = fileSize,
                sha256 = "",
                mimeType = mimeType
            )

            val result = fileTransferManager.uploadFile(
                fileUri = fileUri,
                fileId = fileId,
                sessionToken = device.sessionToken,
                serverIp = device.lastIp,
                serverPort = device.wsPort,
                certFingerprint = device.certFingerprint,
                mimeType = mimeType
            ) { sent, total ->
                val percent = if (total > 0) (sent * 100 / total).toInt() else 0
                webSocketClient?.sendFileProgress(fileId, sent, total)
            }

            if (result.isSuccess) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LanBridgeForegroundService, "Файл успешно отправлен на ПК", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // --- LanBridgeMessageListener Callbacks ---

    override fun onStateChanged(state: ConnectionState) {
        _connectionState.value = state
        when (state) {
            is ConnectionState.Connected -> {
                updateNotification("Подключено к ${state.deviceName} (${state.ip})", true)
            }
            is ConnectionState.Connecting -> {
                updateNotification("Подключение к ${state.deviceName}…", false)
            }
            is ConnectionState.Discovering -> {
                updateNotification(getString(R.string.notification_discovering), false)
            }
            is ConnectionState.Disconnected -> {
                updateNotification(getString(R.string.notification_disconnected), false)
            }
            is ConnectionState.AuthFailed -> {
                updateNotification("Ошибка авторизации: ${state.reason}", false)
            }
        }
    }

    override fun onAuthSuccess(sessionToken: String, serverId: String, serverName: String) {
        val app = LanBridgeApp.instance
        val current = _activeDevice.value ?: return
        val updated = current.copy(
            deviceId = serverId,
            deviceName = serverName,
            sessionToken = sessionToken
        )
        app.preferencesManager.savePairedDevice(updated)
        _activeDevice.value = updated
    }

    override fun onAuthFailed(reason: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(this@LanBridgeForegroundService, "Ошибка привязки: $reason", Toast.LENGTH_LONG).show()
        }
    }

    override fun onClipboardTextReceived(msg: ClipboardTextMessage) {
        if (echoLoopGuard.isRemoteEcho(msg.sha256)) return
        echoLoopGuard.recordRemote(msg.sha256)

        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val clip = ClipData.newPlainText("LanBridge", msg.content)
                clipboardManager.setPrimaryClip(clip)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to clipboard", e)
            }
        }

        LanBridgeApp.instance.historyStorage.addItem(
            HistoryItem(
                id = msg.id,
                kind = HistoryKind.TEXT,
                content = msg.content,
                sha256 = msg.sha256,
                timestamp = msg.timestamp * 1000
            )
        )
    }

    override fun onClipboardImageReceived(msg: ClipboardImageMessage) {
        if (echoLoopGuard.isRemoteEcho(msg.sha256)) return
        echoLoopGuard.recordRemote(msg.sha256)

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val imageBytes = Base64.decode(msg.data_base64, Base64.DEFAULT)
                val imagesDir = File(cacheDir, "clipboard_images").apply { mkdirs() }
                val imageFile = File(imagesDir, "clip_${msg.id}.png")
                FileOutputStream(imageFile).use { it.write(imageBytes) }

                LanBridgeApp.instance.historyStorage.addItem(
                    HistoryItem(
                        id = msg.id,
                        kind = HistoryKind.IMAGE,
                        content = imageFile.absolutePath,
                        sha256 = msg.sha256,
                        timestamp = msg.timestamp * 1000
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save received image", e)
            }
        }
    }

    override fun onFileOfferReceived(msg: FileOfferMessage) {
        val device = _activeDevice.value ?: return
        lifecycleScope.launch {
            webSocketClient?.sendFileAccept(msg.file_id)

            val downloadResult = fileTransferManager.downloadFile(
                fileName = msg.file_name,
                fileId = msg.file_id,
                expectedSha256 = msg.sha256,
                expectedSize = msg.file_size,
                sessionToken = device.sessionToken,
                serverIp = device.lastIp,
                serverPort = device.wsPort,
                certFingerprint = device.certFingerprint
            ) { bytesReceived, totalBytes ->
                webSocketClient?.sendFileProgress(msg.file_id, bytesReceived, totalBytes)
            }

            if (downloadResult.isSuccess) {
                val file = downloadResult.getOrNull()
                if (file != null) {
                    webSocketClient?.sendFileComplete(msg.file_id, msg.sha256)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@LanBridgeForegroundService,
                            "Файл сохранен в Загрузки: ${file.name}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    LanBridgeApp.instance.historyStorage.addItem(
                        HistoryItem(
                            id = msg.file_id,
                            kind = HistoryKind.FILE,
                            content = file.absolutePath,
                            fileName = file.name,
                            fileSize = file.length(),
                            sha256 = msg.sha256,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    override fun onFileAcceptReceived(msg: FileAcceptMessage) {}
    override fun onFileRejectReceived(msg: FileRejectMessage) {}
    override fun onFileProgressReceived(msg: FileProgressMessage) {}
    override fun onFileCompleteReceived(msg: FileCompleteMessage) {}
    override fun onHistorySyncReceived(msg: HistorySyncMessage) {}
    override fun onPingRttUpdated(rttMs: Long) {
        _pingMs.value = rttMs
    }

    override fun onDestroy() {
        udpDiscovery.stop()
        webSocketClient?.disconnect()
        currentServiceInstance = null
        super.onDestroy()
    }
}
