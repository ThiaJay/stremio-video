package org.thiajay.stremiotvcompanion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections
import java.util.Locale

/**
 * Canonical, bounded representation of one explicit local subtitle preference
 * receipt. The format is deliberately private to this source contract: it is
 * not account data, a package identity or proof that an Android client adopted
 * the preference.
 */
object NativeSubtitlePreferenceReceiptCodec {
    private const val PREFIX = "nsp1:"
    private const val VERSION = 1
    private const val MAX_TEXT_BYTES = 256
    private const val MAX_RAW_BYTES = 823
    private const val MAX_ENCODED_LENGTH = 1103
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private val MAGIC = byteArrayOf(0x4e, 0x53, 0x50, 0x52)
    private val DIGEST = Regex("^[0-9a-f]{64}$")
    private val TOP_LEVEL_KEYS = setOf(
        "schemaVersion",
        "kind",
        "installationDigest",
        "preferenceRevision",
        "surface",
        "choice",
        "binding"
    )
    private val BINDING_KEYS = setOf(
        "sessionEnabled",
        "sessionLanguage",
        "globalLanguage",
        "sessionSource"
    )

    fun encode(receipt: Map<*, *>?): String? {
        return try {
            val exact = snapshotExact(receipt, TOP_LEVEL_KEYS) ?: return null
            if (wholeNumber(exact["schemaVersion"]) != 1L ||
                exact["kind"] != "explicit-local-choice" ||
                exact["choice"] != "enabled"
            ) return null

            val digest = exact["installationDigest"] as? String ?: return null
            if (!DIGEST.matches(digest)) return null
            val revision = wholeNumber(exact["preferenceRevision"])
                ?.takeIf { it in 1L..MAX_SAFE_INTEGER } ?: return null
            val surface = when (exact["surface"]) {
                "first-run" -> 1
                "subtitle-settings" -> 2
                else -> return null
            }
            val binding = snapshotExact(exact["binding"] as? Map<*, *>, BINDING_KEYS)
                ?: return null
            if (binding["sessionEnabled"] != true) return null
            val sessionLanguage = canonicalLanguage(binding["sessionLanguage"]) ?: run {
                if (binding["sessionLanguage"] == null) null else return null
            }
            val globalLanguage = canonicalLanguage(binding["globalLanguage"]) ?: run {
                if (binding["globalLanguage"] == null) null else return null
            }
            val sessionSource = when (val value = binding["sessionSource"]) {
                null -> null
                "embedded", "external" -> value as String
                else -> return null
            }

            val output = ByteArrayOutputStream(MAX_RAW_BYTES)
            DataOutputStream(output).use { data ->
                data.write(MAGIC)
                data.writeByte(VERSION)
                data.write(hexToBytes(digest) ?: return null)
                data.writeLong(revision)
                data.writeByte(surface)
                writeNullableText(data, sessionLanguage)
                writeNullableText(data, globalLanguage)
                writeNullableText(data, sessionSource)
            }
            val raw = output.toByteArray()
            if (raw.size > MAX_RAW_BYTES) return null
            PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    fun decode(encoded: String?): Map<String, Any?>? {
        return try {
            val candidate = encoded ?: return null
            if (!candidate.startsWith(PREFIX) || candidate.length > MAX_ENCODED_LENGTH) {
                return null
            }
            val payload = candidate.substring(PREFIX.length)
            if (payload.isEmpty() || '=' in payload) return null
            val raw = Base64.getUrlDecoder().decode(payload)
            if (raw.isEmpty() || raw.size > MAX_RAW_BYTES) return null

            val input = DataInputStream(ByteArrayInputStream(raw))
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC) || input.readUnsignedByte() != VERSION) return null
            val digestBytes = ByteArray(32)
            input.readFully(digestBytes)
            val revision = input.readLong()
            if (revision !in 1L..MAX_SAFE_INTEGER) return null
            val surface = when (input.readUnsignedByte()) {
                1 -> "first-run"
                2 -> "subtitle-settings"
                else -> return null
            }
            val sessionLanguageRead = readNullableText(input)
            val globalLanguageRead = readNullableText(input)
            val sessionSourceRead = readNullableText(input)
            if (!sessionLanguageRead.valid || !globalLanguageRead.valid ||
                !sessionSourceRead.valid
            ) return null
            val sessionLanguage = sessionLanguageRead.value
            val globalLanguage = globalLanguageRead.value
            val sessionSource = sessionSourceRead.value
            if (sessionLanguage != canonicalLanguage(sessionLanguage) ||
                globalLanguage != canonicalLanguage(globalLanguage) ||
                sessionSource !in setOf(null, "embedded", "external") ||
                input.available() != 0
            ) return null

            val binding = immutableMap(
                linkedMapOf(
                    "sessionEnabled" to true,
                    "sessionLanguage" to sessionLanguage,
                    "globalLanguage" to globalLanguage,
                    "sessionSource" to sessionSource
                )
            )
            val receipt = immutableMap(
                linkedMapOf(
                    "schemaVersion" to 1L,
                    "kind" to "explicit-local-choice",
                    "installationDigest" to bytesToHex(digestBytes),
                    "preferenceRevision" to revision,
                    "surface" to surface,
                    "choice" to "enabled",
                    "binding" to binding
                )
            )
            receipt.takeIf { encode(it) == candidate }
        } catch (_: CharacterCodingException) {
            null
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private data class TextRead(val valid: Boolean, val value: String?)

    private fun writeNullableText(output: DataOutputStream, value: String?) {
        if (value == null) {
            output.writeByte(0)
            return
        }
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_TEXT_BYTES)
        output.writeByte(1)
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private fun readNullableText(input: DataInputStream): TextRead {
        return when (input.readUnsignedByte()) {
            0 -> TextRead(valid = true, value = null)
            1 -> {
                val size = input.readUnsignedShort()
                if (size !in 1..MAX_TEXT_BYTES) return TextRead(false, null)
                val bytes = ByteArray(size)
                input.readFully(bytes)
                val text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                TextRead(valid = true, value = text)
            }
            else -> TextRead(valid = false, value = null)
        }
    }

    private fun canonicalLanguage(value: Any?): String? {
        val text = value as? String ?: return null
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_TEXT_BYTES) return null
        return text.takeIf {
            it == it.trim().lowercase(Locale.ROOT).replace('_', '-')
        }
    }

    private fun snapshotExact(
        source: Map<*, *>?,
        expectedKeys: Set<String>
    ): Map<String, Any?>? {
        return try {
            val value = source ?: return null
            val expectedSize = value.size
            if (expectedSize != expectedKeys.size) return null
            val copy = LinkedHashMap<String, Any?>(expectedSize)
            val iterator = value.entries.iterator()
            var count = 0
            while (iterator.hasNext()) {
                if (count >= expectedSize) return null
                val entry = iterator.next()
                val key = entry.key as? String ?: return null
                if (key !in expectedKeys || copy.containsKey(key)) return null
                copy[key] = entry.value
                count++
            }
            if (count != expectedSize || copy.keys != expectedKeys) return null
            Collections.unmodifiableMap(copy)
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun wholeNumber(value: Any?): Long? = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun hexToBytes(value: String): ByteArray? {
        if (!DIGEST.matches(value)) return null
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(value: ByteArray): String =
        value.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun immutableMap(value: LinkedHashMap<String, Any?>): Map<String, Any?> =
        Collections.unmodifiableMap(LinkedHashMap(value))
}
