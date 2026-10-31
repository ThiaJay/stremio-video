package org.thiajay.stremiotvcompanion

import java.security.SecureRandom

/**
 * Owner-thread source for a new opaque installation-local preference identity.
 *
 * The digest is generated from 32 fresh random bytes. It is never derived from
 * an account, package, device identifier, locale or preference value. This
 * source has no storage, Android Context, network, player or UI access and is
 * deliberately not wired into either synthetic Activity.
 */
class SecureRandomSubtitlePreferenceDigestSource internal constructor(
    private val entropy: EntropyPort
) : NativeSubtitlePreferenceStore.DigestSource {
    fun interface EntropyPort {
        /** Fill the supplied fixed-size buffer and return true only on success. */
        fun fill(target: ByteArray): Boolean
    }

    constructor() : this(SystemEntropy)

    private val ownerThread = Thread.currentThread()
    private var closed = false

    override fun nextInstallationDigest(): String? {
        if (!onOwnerThread() || closed) return null
        val bytes = ByteArray(DIGEST_BYTES)
        return try {
            if (!entropy.fill(bytes) || bytes.all { it == 0.toByte() }) return null
            val encoded = CharArray(DIGEST_BYTES * 2)
            for (index in bytes.indices) {
                val value = bytes[index].toInt() and 0xff
                encoded[index * 2] = HEX[value ushr 4]
                encoded[index * 2 + 1] = HEX[value and 0x0f]
            }
            String(encoded)
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        return true
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    private object SystemEntropy : EntropyPort {
        override fun fill(target: ByteArray): Boolean {
            SecureRandom().nextBytes(target)
            return true
        }
    }

    private companion object {
        const val DIGEST_BYTES = 32
        const val HEX = "0123456789abcdef"
    }
}
