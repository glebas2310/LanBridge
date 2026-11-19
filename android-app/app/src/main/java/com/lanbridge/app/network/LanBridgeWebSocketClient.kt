package com.lanbridge.app.network

import android.util.Log
import com.lanbridge.app.data.model.AuthFailMessage
import com.lanbridge.app.data.model.AuthMessage
import com.lanbridge.app.data.model.AuthOkMessage
import com.lanbridge.app.data.model.ClipboardImageMessage
import com.lanbridge.app.data.model.ClipboardTextMessage
import com.lanbridge.app.data.model.ConnectionState
import com.lanbridge.app.data.model.FileAcceptMessage
import com.lanbridge.app.data.model.FileCompleteMessage
import com.lanbridge.app.data.model.FileOfferMessage
import com.lanbridge.app.data.model.FileProgressMessage
import com.lanbridge.app.data.model.FileRejectMessage
import com.lanbridge.app.data.model.HistorySyncMessage
import com.lanbridge.app.data.model.PairedDevice
import com.lanbridge.app.data.model.PingMessage
import com.lanbridge.app.data.model.PongMessage
import com.lanbridge.app.data.model.extractMessageType
import com.lanbridge.app.data.model.jsonConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

interface LanBridgeMessageListener {
    fun onStateChanged(state: ConnectionState)
    fun onAuthSuccess(sessionToken: String, serverId: String, serverName: String)
    fun onAuthFailed(reason: String)
    fun onClipboardTextReceived(msg: ClipboardTextMessage)
    fun onClipboardImageReceived(msg: ClipboardImageMessage)
    fun onFileOfferReceived(msg: FileOfferMessage)
    fun onFileAcceptReceived(msg: FileAcceptMessage)
    fun onFileRejectReceived(msg: FileRejectMessage)
    fun onFileProgressReceived(msg: FileProgressMessage)
    fun onFileCompleteReceived(msg: FileCompleteMessage)
    fun onHistorySyncReceived(msg: HistorySyncMessage)
    fun onPingRttUpdated(rttMs: Long)
}

class LanBridgeWebSocketClient(
    private val scope: CoroutineScope,
    private val clientId: String,
    private val clientName: String,
    private val listener: LanBridgeMessageListener
) {

    companion object {
        private const val TAG = "LanBridgeWsClient"
        private const val PING_INTERVAL_MS = 5000L
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30000L
    }

    private var activeDevice: PairedDevice? = null
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null

    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private val isManuallyDisconnected = AtomicBoolean(false)
    private var currentBackoffMs = INITIAL_BACKOFF_MS

    fun connect(device: PairedDevice) {
        isManuallyDisconnected.set(false)
        activeDevice = device

        reconnectJob?.cancel()
        reconnectJob = null

        establishConnection()
    }

    private fun establishConnection() {
        val device = activeDevice ?: return
        if (isManuallyDisconnected.get()) return

        closeCurrentConnection()

        listener.onStateChanged(ConnectionState.Connecting(device.deviceName, device.lastIp))

        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Creating pinned OkHttpClient for fp=${device.certFingerprint}")
                val client = TlsCertificatePinner.createPinnedOkHttpClient(device.certFingerprint)
                okHttpClient = client

                val url = "wss://${device.lastIp}:${device.wsPort}/ws"
                Log.d(TAG, "Connecting to $url...")

                val request = Request.Builder()
                    .url(url)
                    .build()

                webSocket = client.newWebSocket(request, createWebSocketListener(device))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initiate WebSocket connection: ${e.message}", e)
                handleConnectionFailure()
            }
        }
    }

    private fun createWebSocketListener(device: PairedDevice): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened. Sending AUTH message...")
                currentBackoffMs = INITIAL_BACKOFF_MS

                // Send AUTH message
                val authMsg = AuthMessage(
                    client_id = clientId,
                    client_name = clientName,
                    token = device.sessionToken,
                    protocol_version = "1.0"
                )
                val json = jsonConfig.encodeToString(authMsg)
                ws.send(json)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: code=$code, reason=$reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: code=$code, reason=$reason")
                stopPingLoop()
                if (!isManuallyDisconnected.get()) {
                    handleConnectionFailure()
                } else {
                    listener.onStateChanged(ConnectionState.Disconnected)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                stopPingLoop()
                if (!isManuallyDisconnected.get()) {
                    handleConnectionFailure()
                } else {
                    listener.onStateChanged(ConnectionState.Disconnected)
                }
            }
        }
    }

    private fun handleIncomingMessage(rawJson: String) {
        val type = extractMessageType(rawJson) ?: return

        when (type) {
            "AUTH_OK" -> {
                try {
                    val msg = jsonConfig.decodeFromString<AuthOkMessage>(rawJson)
                    Log.d(TAG, "AUTH_OK received from server: ${msg.server_name}")
                    val device = activeDevice
                    if (device != null) {
                        listener.onAuthSuccess(msg.session_token, msg.server_id, msg.server_name)
                        listener.onStateChanged(
                            ConnectionState.Connected(
                                deviceName = msg.server_name,
                                ip = device.lastIp,
                                port = device.wsPort,
                                pingMs = 0L
                            )
                        )
                        startPingLoop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode AUTH_OK", e)
                }
            }

            "AUTH_FAIL" -> {
                try {
                    val msg = jsonConfig.decodeFromString<AuthFailMessage>(rawJson)
                    Log.e(TAG, "AUTH_FAIL: ${msg.reason}")
                    stopPingLoop()
                    listener.onAuthFailed(msg.reason)
                    listener.onStateChanged(ConnectionState.AuthFailed(msg.reason))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode AUTH_FAIL", e)
                }
            }

            "PONG" -> {
                try {
                    val msg = jsonConfig.decodeFromString<PongMessage>(rawJson)
                    val rtt = System.currentTimeMillis() - msg.timestamp
                    val device = activeDevice
                    if (device != null) {
                        listener.onPingRttUpdated(rtt)
                        listener.onStateChanged(
                            ConnectionState.Connected(
                                deviceName = device.deviceName,
                                ip = device.lastIp,
                                port = device.wsPort,
                                pingMs = rtt
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode PONG", e)
                }
            }

            "CLIPBOARD_TEXT" -> {
                try {
                    val msg = jsonConfig.decodeFromString<ClipboardTextMessage>(rawJson)
                    listener.onClipboardTextReceived(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode CLIPBOARD_TEXT", e)
                }
            }

            "CLIPBOARD_IMAGE" -> {
                try {
                    val msg = jsonConfig.decodeFromString<ClipboardImageMessage>(rawJson)
                    listener.onClipboardImageReceived(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode CLIPBOARD_IMAGE", e)
                }
            }

            "FILE_OFFER" -> {
                try {
                    val msg = jsonConfig.decodeFromString<FileOfferMessage>(rawJson)
                    listener.onFileOfferReceived(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode FILE_OFFER", e)
                }
            }

            "FILE_ACCEPT" -> {
                try {
                    val msg = jsonConfig.decodeFromString<FileAcceptMessage>(rawJson)
                    listener.onFileAcceptReceived(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode FILE_ACCEPT", e)
                }
            }

            "FILE_REJECT" -> {
                try {
                    val msg = jsonConfig.decodeFromString<FileRejectMessage>(rawJson)
                    listener.onFileRejectReceived(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode FILE_REJECT", e)
                }
            }

            "FILE_PROGRESS" -> {
                try {
                    val msg = jsonConfig.decodeFromString<FileProgressMessage>(rawJson)
                    listener.onFileProgressReceived(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode FILE_PROGRESS", e)
                }
            }

            "FILE_COMPLETE" -> {
                try {
                    val msg = jsonConfig.decodeFromString<FileCompleteMessage>(rawJson)
                    listener.onFileCompleteReceived(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode FILE_COMPLETE", e)
                }
            }

            "HISTORY_SYNC" -> {
                try {
                    val msg = jsonConfig.decodeFromString<HistorySyncMessage>(rawJson)
                    listener.onHistorySyncReceived(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode HISTORY_SYNC", e)
                }
            }
        }
    }

    private fun startPingLoop() {
        stopPingLoop()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                sendPing()
            }
        }
    }

    private fun stopPingLoop() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun sendPing() {
        val ping = PingMessage(timestamp = System.currentTimeMillis())
        val json = jsonConfig.encodeToString(ping)
        sendRaw(json)
    }

    private fun handleConnectionFailure() {
        if (isManuallyDisconnected.get()) return

        listener.onStateChanged(ConnectionState.Disconnected)

        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Scheduling reconnection in ${currentBackoffMs}ms...")
            delay(currentBackoffMs)
            currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            if (!isManuallyDisconnected.get() && activeDevice != null) {
                establishConnection()
            }
        }
    }

    /**
     * Fast reconnect triggered immediately when a UDP beacon is received from the paired PC.
     */
    fun fastReconnect(newIp: String) {
        val device = activeDevice ?: return
        if (isManuallyDisconnected.get()) return

        if (device.lastIp != newIp || reconnectJob?.isActive == true) {
            Log.d(TAG, "Fast reconnect triggered with IP: $newIp")
            activeDevice = device.copy(lastIp = newIp)
            currentBackoffMs = INITIAL_BACKOFF_MS
            reconnectJob?.cancel()
            establishConnection()
        }
    }

    fun sendClipboardText(content: String, sha256: String, id: String = "clip_" + UUID.randomUUID().toString().take(8)) {
        val msg = ClipboardTextMessage(
            id = id,
            content = content,
            sha256 = sha256,
            timestamp = System.currentTimeMillis() / 1000
        )
        sendRaw(jsonConfig.encodeToString(msg))
    }

    fun sendClipboardImage(
        id: String = "img_" + UUID.randomUUID().toString().take(8),
        format: String = "png",
        width: Int,
        height: Int,
        base64: String,
        sha256: String
    ) {
        val msg = ClipboardImageMessage(
            id = id,
            format = format,
            width = width,
            height = height,
            data_base64 = base64,
            sha256 = sha256,
            timestamp = System.currentTimeMillis() / 1000
        )
        sendRaw(jsonConfig.encodeToString(msg))
    }

    fun sendFileOffer(
        fileId: String,
        fileName: String,
        fileSize: Long,
        sha256: String,
        mimeType: String
    ) {
        val msg = FileOfferMessage(
            file_id = fileId,
            file_name = fileName,
            file_size = fileSize,
            sha256 = sha256,
            mime_type = mimeType
        )
        sendRaw(jsonConfig.encodeToString(msg))
    }

    fun sendFileAccept(fileId: String, offset: Long = 0L) {
        val msg = FileAcceptMessage(
            file_id = fileId,
            offset = offset
        )
        sendRaw(jsonConfig.encodeToString(msg))
    }

    fun sendFileReject(fileId: String, reason: String) {
        val msg = FileRejectMessage(
            file_id = fileId,
            reason = reason
        )
        sendRaw(jsonConfig.encodeToString(msg))
    }

    fun sendFileProgress(fileId: String, bytesTransferred: Long, totalBytes: Long) {
        val msg = FileProgressMessage(
            file_id = fileId,
            bytes_transferred = bytesTransferred,
            total_bytes = totalBytes
        )
        sendRaw(jsonConfig.encodeToString(msg))
    }

    fun sendFileComplete(fileId: String, sha256: String) {
        val msg = FileCompleteMessage(
            file_id = fileId,
            sha256 = sha256
        )
        sendRaw(jsonConfig.encodeToString(msg))
    }

    fun sendRaw(json: String): Boolean {
        val ws = webSocket
        if (ws != null) {
            return ws.send(json)
        }
        return false
    }

    fun disconnect() {
        isManuallyDisconnected.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        stopPingLoop()
        closeCurrentConnection()
        listener.onStateChanged(ConnectionState.Disconnected)
    }

    private fun closeCurrentConnection() {
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (e: Exception) {
            // Ignore
        }
        webSocket = null
    }
}
