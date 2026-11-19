package com.lanbridge.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PairedDevice(
    val deviceId: String,
    val deviceName: String,
    val certFingerprint: String,
    val sessionToken: String,
    val lastIp: String,
    val wsPort: Int = 8443,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)
