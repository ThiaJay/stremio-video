package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureRandomSubtitlePreferenceDigestSourceTest {
    @Test fun deterministicEntropyProducesExactLowercaseDigest() {
        var requested = 0
        val source = SecureRandomSubtitlePreferenceDigestSource { target ->
            requested = target.size
            target.indices.forEach { target[it] = it.toByte() }
            true
        }

        assertEquals(
            "000102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d1e1f",
            source.nextInstallationDigest()
        )
        assertEquals(32, requested)
    }

    @Test fun eachRequestRequiresFreshEntropy() {
        var generation = 0
        val source = SecureRandomSubtitlePreferenceDigestSource { target ->
            generation += 1
            target.fill(generation.toByte())
            true
        }

        assertEquals("01".repeat(32), source.nextInstallationDigest())
        assertEquals("02".repeat(32), source.nextInstallationDigest())
        assertEquals(2, generation)
    }

    @Test fun entropyRefusalReturnsNoIdentity() {
        val source = SecureRandomSubtitlePreferenceDigestSource { target ->
            target.fill(1)
            false
        }

        assertNull(source.nextInstallationDigest())
    }

    @Test fun allZeroEntropyFailsClosed() {
        val source = SecureRandomSubtitlePreferenceDigestSource { true }

        assertNull(source.nextInstallationDigest())
    }

    @Test fun runtimeAndLinkageFailuresAreContained() {
        val runtime = SecureRandomSubtitlePreferenceDigestSource {
            throw IllegalStateException("entropy unavailable")
        }
        val linkage = SecureRandomSubtitlePreferenceDigestSource {
            throw NoClassDefFoundError("provider unavailable")
        }

        assertNull(runtime.nextInstallationDigest())
        assertNull(linkage.nextInstallationDigest())
    }

    @Test fun entropyBufferIsClearedAfterDigestCreation() {
        var retained: ByteArray? = null
        val source = SecureRandomSubtitlePreferenceDigestSource { target ->
            retained = target
            target.fill(0x5a)
            true
        }

        assertEquals("5a".repeat(32), source.nextInstallationDigest())
        assertTrue(retained!!.all { it == 0.toByte() })
    }

    @Test fun callerCannotMutateReturnedDigestThroughEntropyBuffer() {
        var retained: ByteArray? = null
        val source = SecureRandomSubtitlePreferenceDigestSource { target ->
            retained = target
            target.fill(0x2b)
            true
        }

        val digest = source.nextInstallationDigest()
        retained!!.fill(0x7c)
        assertEquals("2b".repeat(32), digest)
    }

    @Test fun foreignThreadAndClosedSourceFailClosed() {
        val source = SecureRandomSubtitlePreferenceDigestSource { target ->
            target.fill(1)
            true
        }
        val foreign = AtomicReference<String?>("unexpected")
        val foreignClose = AtomicReference<Boolean>(true)

        Thread {
            foreign.set(source.nextInstallationDigest())
            foreignClose.set(source.close())
        }.apply { start(); join() }

        assertNull(foreign.get())
        assertFalse(foreignClose.get())
        assertEquals("01".repeat(32), source.nextInstallationDigest())
        assertTrue(source.close())
        assertNull(source.nextInstallationDigest())
        assertFalse(source.close())
    }
}
