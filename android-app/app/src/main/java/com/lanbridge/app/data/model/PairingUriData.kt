package com.lanbridge.app.data.model

import android.net.Uri

data class PairingUriData(
    val deviceId: String,
    val deviceName: String,
    val ip: String,
    val port: Int,
    val certFingerprint: String,
    val oneTimeToken: String
) {
    companion object {
        fun parse(uriString: String): PairingUriData? {
            return try {
                val uri = Uri.parse(uriString.trim())
                if (uri.scheme != "lanbridge" || uri.host != "pair") {
                    return null
                }
                val deviceId = uri.getQueryParameter("id") ?: return null
                val deviceName = uri.getQueryParameter("name") ?: "Windows PC"
                val ip = uri.getQueryParameter("ip") ?: return null
                val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8443
                val fp = uri.getQueryParameter("fp") ?: return null
                val token = uri.getQueryParameter("token") ?: return null

                PairingUriData(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    ip = ip,
                    port = port,
                    certFingerprint = fp,
                    oneTimeToken = token
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
