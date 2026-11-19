package com.lanbridge.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class HistoryKind {
    TEXT,
    IMAGE,
    FILE
}

@Serializable
data class HistoryItem(
    val id: String,
    val kind: HistoryKind,
    val content: String, // Text snippet, base64 thumbnail preview or local file path
    val sha256: String,
    val timestamp: Long,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val localUri: String? = null
)
