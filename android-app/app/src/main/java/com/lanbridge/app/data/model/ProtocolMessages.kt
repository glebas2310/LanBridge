package com.lanbridge.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val jsonConfig = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    coerceInputValues = true
}

fun extractMessageType(rawJson: String): String? {
    return try {
        val root = jsonConfig.parseToJsonElement(rawJson).jsonObject
        root["type"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }
}

@Serializable
data class BeaconPacket(
    val protocol: String = "lanbridge",
    val version: String = "1.0",
    val device_id: String,
    val device_name: String,
    val ws_port: Int = 8443,
    val cert_fingerprint: String,
    val timestamp: Long
)

@Serializable
data class AuthMessage(
    val type: String = "AUTH",
    val client_id: String,
    val client_name: String,
    val token: String,
    val protocol_version: String = "1.0"
)

@Serializable
data class AuthOkMessage(
    val type: String = "AUTH_OK",
    val server_id: String,
    val server_name: String,
    val session_token: String
)

@Serializable
data class AuthFailMessage(
    val type: String = "AUTH_FAIL",
    val reason: String
)

@Serializable
data class ClipboardTextMessage(
    val type: String = "CLIPBOARD_TEXT",
    val id: String,
    val content: String,
    val sha256: String,
    val timestamp: Long
)

@Serializable
data class ClipboardImageMessage(
    val type: String = "CLIPBOARD_IMAGE",
    val id: String,
    val format: String = "png",
    val width: Int,
    val height: Int,
    val data_base64: String,
    val sha256: String,
    val timestamp: Long
)

@Serializable
data class FileOfferMessage(
    val type: String = "FILE_OFFER",
    val file_id: String,
    val file_name: String,
    val file_size: Long,
    val sha256: String,
    val mime_type: String
)

@Serializable
data class FileAcceptMessage(
    val type: String = "FILE_ACCEPT",
    val file_id: String,
    val offset: Long = 0L
)

@Serializable
data class FileRejectMessage(
    val type: String = "FILE_REJECT",
    val file_id: String,
    val reason: String
)

@Serializable
data class FileProgressMessage(
    val type: String = "FILE_PROGRESS",
    val file_id: String,
    val bytes_transferred: Long,
    val total_bytes: Long
)

@Serializable
data class FileCompleteMessage(
    val type: String = "FILE_COMPLETE",
    val file_id: String,
    val sha256: String
)

@Serializable
data class HistorySyncItem(
    val id: String,
    val kind: String, // "text" | "image" | "file"
    val content: String,
    val sha256: String,
    val timestamp: Long
)

@Serializable
data class HistorySyncMessage(
    val type: String = "HISTORY_SYNC",
    val items: List<HistorySyncItem> = emptyList()
)

@Serializable
data class PingMessage(
    val type: String = "PING",
    val timestamp: Long
)

@Serializable
data class PongMessage(
    val type: String = "PONG",
    val timestamp: Long
)
