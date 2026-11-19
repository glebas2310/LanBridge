package com.lanbridge.app.data.model

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Discovering : ConnectionState()
    data class Connecting(val deviceName: String, val ip: String) : ConnectionState()
    data class Connected(
        val deviceName: String,
        val ip: String,
        val port: Int,
        val pingMs: Long = 0L
    ) : ConnectionState()
    data class AuthFailed(val reason: String) : ConnectionState()
}
