package com.lanbridge.app.network

import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicReference

class EchoLoopGuard {

    private val lastRemoteSha256 = AtomicReference<String?>(null)
    private val maxRecentHashes = 100

    private val recentRemoteHashes: MutableSet<String> =
        Collections.synchronizedSet(object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                if (size >= maxRecentHashes) {
                    val iterator = iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
                return super.add(element)
            }
        })

    /**
     * Records a SHA-256 hash received from remote peer before writing it to local clipboard.
     */
    fun recordRemote(sha256: String) {
        val cleanHash = sha256.lowercase().trim()
        lastRemoteSha256.set(cleanHash)
        recentRemoteHashes.add(cleanHash)
    }

    /**
     * Checks if the local clipboard hash matches any recent remote hash (preventing echo bounce loop).
     */
    fun isRemoteEcho(localSha256: String): Boolean {
        val cleanHash = localSha256.lowercase().trim()
        if (cleanHash == lastRemoteSha256.get()) {
            return true
        }
        return recentRemoteHashes.contains(cleanHash)
    }

    /**
     * Clears tracked hashes (e.g. on disconnect or unpair).
     */
    fun reset() {
        lastRemoteSha256.set(null)
        recentRemoteHashes.clear()
    }

    companion object {
        fun computeSha256(text: String): String {
            return computeSha256(text.toByteArray(Charsets.UTF_8))
        }

        fun computeSha256(data: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(data)
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
}
