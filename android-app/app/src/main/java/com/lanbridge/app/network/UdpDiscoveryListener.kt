package com.lanbridge.app.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.lanbridge.app.data.model.BeaconPacket
import com.lanbridge.app.data.model.jsonConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class UdpDiscoveryListener(
    private val context: Context,
    private val onBeaconReceived: (beacon: BeaconPacket, sourceIp: String) -> Unit
) {

    companion object {
        private const val TAG = "UdpDiscoveryListener"
        const val UDP_PORT = 54321
        private const val BUFFER_SIZE = 4096
    }

    private var listenJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: DatagramSocket? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (listenJob?.isActive == true) {
            return
        }

        acquireMulticastLock()

        listenJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "Starting UDP discovery listener on port $UDP_PORT...")
            try {
                val newSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_PORT))
                }
                socket = newSocket

                val buffer = ByteArray(BUFFER_SIZE)

                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    newSocket.receive(packet)

                    val sourceIp = packet.address.hostAddress ?: continue
                    val rawJson = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()

                    try {
                        val beacon = jsonConfig.decodeFromString<BeaconPacket>(rawJson)
                        if (beacon.protocol == "lanbridge") {
                            onBeaconReceived(beacon, sourceIp)
                        }
                    } catch (e: Exception) {
                        // Ignore malformed packets from other local network services
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Error in UDP discovery loop: ${e.message}")
                }
            } finally {
                closeSocket()
                releaseMulticastLock()
                Log.d(TAG, "UDP discovery listener stopped")
            }
        }
    }

    @Synchronized
    fun stop() {
        listenJob?.cancel()
        listenJob = null
        closeSocket()
        releaseMulticastLock()
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("LanBridgeMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "MulticastLock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(TAG, "MulticastLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MulticastLock: ${e.message}")
        }
        multicastLock = null
    }
}
