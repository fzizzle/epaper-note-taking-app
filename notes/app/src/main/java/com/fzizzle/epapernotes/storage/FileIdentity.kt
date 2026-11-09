package com.fzizzle.epapernotes.storage

import java.io.File
import java.security.MessageDigest

/**
 * What a file looked like at one moment: its size, its modified time and a fingerprint of its
 * bytes. Comparing two of these tells whether something else (a laptop, a sync app) changed
 * the file in between.
 *
 * The fingerprint is needed because size and time alone can miss an edit: a sync app may
 * write the same number of bytes and restore the old time.
 */
data class FileIdentity(val size: Long, val modifiedMs: Long, val sha256: String) {

    fun encode(): String = "$size $modifiedMs $sha256"

    companion object {
        fun of(file: File): FileIdentity {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            return FileIdentity(file.length(), file.lastModified(), hex)
        }

        fun decode(text: String): FileIdentity? {
            val parts = text.trim().split(' ')
            if (parts.size != 3) return null
            val size = parts[0].toLongOrNull() ?: return null
            val modified = parts[1].toLongOrNull() ?: return null
            if (!parts[2].matches(Regex("[0-9a-f]{64}"))) return null
            return FileIdentity(size, modified, parts[2])
        }
    }
}
