package org.thiajay.stremiotvcompanion

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thiajay.stremiotvcompanion.NativeSubtitlePreferenceProvenance.SessionPreference

class NativeSubtitlePreferenceReceiptCodecTest {
    private val digest = "0123456789abcdef".repeat(4)

    @Test fun canonicalReceiptRoundTripsExactly() {
        val receipt = receipt()
        val encoded = NativeSubtitlePreferenceReceiptCodec.encode(receipt)
        assertTrue(encoded?.startsWith("nsp1:") == true)
        assertEquals(receipt, NativeSubtitlePreferenceReceiptCodec.decode(encoded))
        assertEquals(encoded, NativeSubtitlePreferenceReceiptCodec.encode(receipt))
    }

    @Test fun mapOrderCannotChangeCanonicalEncoding() {
        val receipt = receipt()
        val reordered = linkedMapOf<String, Any?>(
            "binding" to receipt["binding"],
            "choice" to receipt["choice"],
            "surface" to receipt["surface"],
            "preferenceRevision" to receipt["preferenceRevision"],
            "installationDigest" to receipt["installationDigest"],
            "kind" to receipt["kind"],
            "schemaVersion" to receipt["schemaVersion"]
        )
        assertEquals(
            NativeSubtitlePreferenceReceiptCodec.encode(receipt),
            NativeSubtitlePreferenceReceiptCodec.encode(reordered)
        )
    }

    @Test fun reviewedSurfacesHaveDistinctCanonicalValues() {
        val first = NativeSubtitlePreferenceReceiptCodec.encode(receipt(surface = "first-run"))
        val settings = NativeSubtitlePreferenceReceiptCodec.encode(
            receipt(surface = "subtitle-settings")
        )
        assertNotEquals(first, settings)
        assertEquals("subtitle-settings", NativeSubtitlePreferenceReceiptCodec.decode(settings)?.get("surface"))
    }

    @Test fun malformedPrefixBase64AndTrailingBytesFailClosed() {
        val encoded = NativeSubtitlePreferenceReceiptCodec.encode(receipt())!!
        assertNull(NativeSubtitlePreferenceReceiptCodec.decode(encoded.removePrefix("nsp1:")))
        assertNull(NativeSubtitlePreferenceReceiptCodec.decode("nsp1:***"))
        val raw = raw(encoded) + byteArrayOf(0)
        assertNull(NativeSubtitlePreferenceReceiptCodec.decode(wrap(raw)))
    }

    @Test fun nonCanonicalPaddingAndVersionFailClosed() {
        val encoded = NativeSubtitlePreferenceReceiptCodec.encode(receipt())!!
        assertNull(NativeSubtitlePreferenceReceiptCodec.decode(encoded + "="))
        val raw = raw(encoded)
        raw[4] = 2
        assertNull(NativeSubtitlePreferenceReceiptCodec.decode(wrap(raw)))
    }

    @Test fun invalidDigestAndRevisionBoundsCannotEncode() {
        assertNull(NativeSubtitlePreferenceReceiptCodec.encode(receipt(digestValue = "A".repeat(64))))
        assertNull(NativeSubtitlePreferenceReceiptCodec.encode(receipt(revision = 0L)))
        assertNull(
            NativeSubtitlePreferenceReceiptCodec.encode(
                receipt(revision = 9_007_199_254_740_992L)
            )
        )
    }

    @Test fun bindingRequiresExactClosedSchemaAndEnabledChoice() {
        val receipt = LinkedHashMap(receipt())
        val binding = LinkedHashMap(receipt["binding"] as Map<String, Any?>)
        binding["accountHydration"] = true
        receipt["binding"] = binding
        assertNull(NativeSubtitlePreferenceReceiptCodec.encode(receipt))

        binding.remove("accountHydration")
        binding["sessionEnabled"] = false
        assertNull(NativeSubtitlePreferenceReceiptCodec.encode(receipt))
    }

    @Test fun nonCanonicalOrOversizedTextFailsClosed() {
        assertNull(
            NativeSubtitlePreferenceReceiptCodec.encode(
                receipt(language = " EN_GB ")
            )
        )
        assertNull(
            NativeSubtitlePreferenceReceiptCodec.encode(
                receipt(language = "x".repeat(257))
            )
        )
    }

    @Test fun malformedUtf8FailsClosed() {
        val encoded = NativeSubtitlePreferenceReceiptCodec.encode(receipt(language = "eng"))!!
        val raw = raw(encoded)
        val marker = byteArrayOf('e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte())
        val index = indexOf(raw, marker)
        assertTrue(index >= 0)
        raw[index] = 0xc3.toByte()
        raw[index + 1] = 0x28
        assertNull(NativeSubtitlePreferenceReceiptCodec.decode(wrap(raw)))
    }

    @Test fun decodedTopLevelAndBindingAreImmutable() {
        val decoded = NativeSubtitlePreferenceReceiptCodec.decode(
            NativeSubtitlePreferenceReceiptCodec.encode(receipt())
        )!!
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (decoded as MutableMap<String, Any?>)["kind"] = "forged"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            ((decoded["binding"] as Map<String, Any?>) as MutableMap<String, Any?>)["sessionSource"] = "external"
        }
    }

    private fun receipt(
        digestValue: String = digest,
        revision: Long = 7L,
        surface: String = "first-run",
        language: String = "eng"
    ): Map<String, Any?> =
        NativeSubtitlePreferenceProvenance.issueExplicitLocalChoiceReceipt(
            activeInstallationDigest = digestValue,
            preferenceRevision = revision,
            sessionPreference = SessionPreference(
                enabled = true,
                language = language,
                source = "embedded"
            ),
            globalLanguage = "en-gb",
            localChoiceEvent = linkedMapOf(
                "installationDigest" to digestValue,
                "preferenceRevision" to revision,
                "surface" to surface,
                "choice" to "enabled"
            )
        ) ?: linkedMapOf(
            "schemaVersion" to 1L,
            "kind" to "explicit-local-choice",
            "installationDigest" to digestValue,
            "preferenceRevision" to revision,
            "surface" to surface,
            "choice" to "enabled",
            "binding" to linkedMapOf(
                "sessionEnabled" to true,
                "sessionLanguage" to language,
                "globalLanguage" to "en-gb",
                "sessionSource" to "embedded"
            )
        )

    private fun raw(encoded: String): ByteArray =
        Base64.getUrlDecoder().decode(encoded.removePrefix("nsp1:"))

    private fun wrap(raw: ByteArray): String =
        "nsp1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

    private fun indexOf(source: ByteArray, target: ByteArray): Int {
        for (index in 0..(source.size - target.size)) {
            if (target.indices.all { source[index + it] == target[it] }) return index
        }
        return -1
    }
}
