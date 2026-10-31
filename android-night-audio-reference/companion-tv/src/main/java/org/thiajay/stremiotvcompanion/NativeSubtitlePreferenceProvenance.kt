package org.thiajay.stremiotvcompanion

import java.util.Collections
import java.util.Locale

/**
 * Native Android translation of Foundation PR #70's exact installation-local
 * subtitle-preference provenance contract at head
 * 99ea3173184029bbd7ee5ae7dc33d31d553fc135 and runtime blob
 * e63e9a35ec9e0bdeab0de17debaf5a3ce929d8d4.
 *
 * This pure boundary performs no storage, account, player, network or UI work.
 * An enabled flag, account hydration or a preferred language is never consent.
 */
object NativeSubtitlePreferenceProvenance {
    data class SessionPreference(
        val enabled: Boolean? = null,
        val language: String? = null,
        val source: String? = null
    )

    private val digest = Regex("^[0-9a-f]{64}$")
    private val receiptKinds = setOf(
        "same-installation-setting",
        "explicit-local-choice"
    )
    private val choiceSurfaces = setOf("first-run", "subtitle-settings")
    private val bindingKeys = setOf(
        "sessionEnabled",
        "sessionLanguage",
        "globalLanguage",
        "sessionSource"
    )
    private val sameInstallationKeys = setOf(
        "schemaVersion",
        "kind",
        "installationDigest",
        "preferenceRevision",
        "binding"
    )
    private val explicitChoiceKeys = sameInstallationKeys + setOf("surface", "choice")
    private val choiceEventKeys = setOf(
        "installationDigest",
        "preferenceRevision",
        "surface",
        "choice"
    )
    private val storageReadbackKeys = setOf(
        "scope",
        "installationDigest",
        "preferenceRevision",
        "binding"
    )
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun preferenceBinding(
        sessionPreference: SessionPreference?,
        globalLanguage: String?
    ): Map<String, Any?> = immutableMap(
        linkedMapOf(
            "sessionEnabled" to sessionPreference?.enabled,
            "sessionLanguage" to normalisedText(sessionPreference?.language),
            "globalLanguage" to normalisedText(globalLanguage),
            "sessionSource" to normalisedSource(sessionPreference?.source)
        )
    )

    fun verifySubtitlePreferenceProvenance(
        sessionPreference: SessionPreference?,
        globalLanguage: String?,
        preferenceProvenance: Map<*, *>?,
        activeInstallationDigest: String?
    ): Boolean {
        val evidence = snapshotExactObject(preferenceProvenance, explicitChoiceKeys.size, true) ?: return false
        val kind = evidence["kind"] as? String ?: return false
        if (wholeNumber(evidence["schemaVersion"]) != 1L || kind !in receiptKinds) return false
        val expectedKeys = if (kind == "explicit-local-choice") {
            explicitChoiceKeys
        } else {
            sameInstallationKeys
        }
        if (!hasExactKeys(evidence, expectedKeys)) return false
        if (activeInstallationDigest == null ||
            !digest.matches(activeInstallationDigest) ||
            evidence["installationDigest"] != activeInstallationDigest
        ) return false
        val revision = positiveRevision(evidence["preferenceRevision"]) ?: return false
        if (revision < 1L) return false
        val expected = preferenceBinding(sessionPreference, globalLanguage)
        if (!sameBinding(evidence["binding"], expected)) return false

        if (kind == "explicit-local-choice") {
            if (evidence["surface"] !in choiceSurfaces ||
                evidence["choice"] != "enabled" ||
                sessionPreference?.enabled != true
            ) return false
        }
        return true
    }

    fun needsSubtitlePreferenceChoice(
        sessionPreference: SessionPreference?,
        globalLanguage: String?,
        preferenceProvenance: Map<*, *>?,
        activeInstallationDigest: String?
    ): Boolean {
        // Explicit Off is authoritative.
        if (sessionPreference?.enabled == false) return false
        // No enabled session and no global language means no automatic choice.
        if (sessionPreference?.enabled != true && globalLanguage == null) return false
        return !verifySubtitlePreferenceProvenance(
            sessionPreference,
            globalLanguage,
            preferenceProvenance,
            activeInstallationDigest
        )
    }

    /**
     * The caller must invoke this only from the real local first-run or subtitle
     * settings event. This function validates that event and cannot create it.
     */
    fun issueExplicitLocalChoiceReceipt(
        activeInstallationDigest: String?,
        preferenceRevision: Any?,
        sessionPreference: SessionPreference?,
        globalLanguage: String?,
        localChoiceEvent: Map<*, *>?
    ): Map<String, Any?>? {
        if (activeInstallationDigest == null || !digest.matches(activeInstallationDigest)) {
            return null
        }
        val revision = positiveRevision(preferenceRevision) ?: return null
        val event = snapshotExactObject(localChoiceEvent, choiceEventKeys.size, false) ?: return null
        if (!hasExactKeys(event, choiceEventKeys) ||
            event["installationDigest"] != activeInstallationDigest ||
            positiveRevision(event["preferenceRevision"]) != revision ||
            event["surface"] !in choiceSurfaces ||
            event["choice"] != "enabled" ||
            sessionPreference?.enabled != true
        ) return null

        return immutableMap(
            linkedMapOf(
                "schemaVersion" to 1L,
                "kind" to "explicit-local-choice",
                "installationDigest" to activeInstallationDigest,
                "preferenceRevision" to revision,
                "surface" to event["surface"],
                "choice" to "enabled",
                "binding" to preferenceBinding(sessionPreference, globalLanguage)
            )
        )
    }

    /**
     * Promotion proves exact installation-local storage readback only. It does
     * not prove UI, signed package, private-client or device acceptance.
     */
    fun promoteStoredPreferenceReceipt(
        activeInstallationDigest: String?,
        sessionPreference: SessionPreference?,
        globalLanguage: String?,
        storedReceipt: Map<*, *>?,
        storageReadback: Map<*, *>?
    ): Map<String, Any?>? {
        val stored = snapshotExactObject(storedReceipt, explicitChoiceKeys.size, true) ?: return null
        val kind = stored["kind"] as? String ?: return null
        val expectedStoredKeys = if (kind == "explicit-local-choice") {
            explicitChoiceKeys
        } else {
            sameInstallationKeys
        }
        if (!hasExactKeys(stored, expectedStoredKeys) ||
            !verifySubtitlePreferenceProvenance(
                sessionPreference,
                globalLanguage,
                stored,
                activeInstallationDigest
            )
        ) return null

        val readback = snapshotExactObject(storageReadback, storageReadbackKeys.size, true) ?: return null
        if (!hasExactKeys(readback, storageReadbackKeys) ||
            readback["scope"] != "installation-local" ||
            readback["installationDigest"] != activeInstallationDigest
        ) return null
        val revision = positiveRevision(stored["preferenceRevision"]) ?: return null
        if (positiveRevision(readback["preferenceRevision"]) != revision ||
            !sameBinding(readback["binding"], stored["binding"])
        ) return null

        return immutableMap(
            linkedMapOf(
                "schemaVersion" to 1L,
                "kind" to "same-installation-setting",
                "installationDigest" to activeInstallationDigest,
                "preferenceRevision" to revision,
                "binding" to immutableBinding(stored["binding"])!!
            )
        )
    }

    /**
     * Detach a caller-owned map exactly once before validation. Custom maps may
     * change cardinality or throw when reread; those inputs fail closed.
     */
    private fun snapshotExactObject(
        value: Map<*, *>?,
        maxEntries: Int,
        detachBinding: Boolean
    ): Map<String, Any?>? {
        try {
            val source = value ?: return null
            val expectedSize = source.size
            if (expectedSize !in 1..maxEntries) return null
            val copy = LinkedHashMap<String, Any?>(expectedSize)
            val iterator = source.entries.iterator()
            var count = 0
            while (iterator.hasNext()) {
                if (count >= expectedSize) return null
                val entry = iterator.next()
                val key = entry.key as? String ?: return null
                if (copy.containsKey(key)) return null
                val detached = if (detachBinding && key == "binding") {
                    snapshotExactObject(
                        entry.value as? Map<*, *>,
                        bindingKeys.size,
                        false
                    ) ?: return null
                } else {
                    entry.value
                }
                copy[key] = detached
                count++
            }
            if (count != expectedSize) return null
            return Collections.unmodifiableMap(copy)
        } catch (_: RuntimeException) {
            return null
        } catch (_: LinkageError) {
            return null
        }
    }

    private fun sameBinding(candidate: Any?, expected: Any?): Boolean {
        val left = candidate as? Map<*, *> ?: return false
        val right = expected as? Map<*, *> ?: return false
        if (!hasExactKeys(left, bindingKeys) || !hasExactKeys(right, bindingKeys)) return false
        return bindingKeys.all { left[it] == right[it] }
    }

    private fun immutableBinding(value: Any?): Map<String, Any?>? {
        val source = value as? Map<*, *> ?: return null
        if (!hasExactKeys(source, bindingKeys)) return null
        return immutableMap(
            linkedMapOf(
                "sessionEnabled" to source["sessionEnabled"],
                "sessionLanguage" to source["sessionLanguage"],
                "globalLanguage" to source["globalLanguage"],
                "sessionSource" to source["sessionSource"]
            )
        )
    }

    private fun hasExactKeys(value: Map<*, *>, expected: Set<String>): Boolean =
        value.size == expected.size && value.keys.all { it is String } &&
            value.keys.map { it as String }.toSet() == expected

    private fun positiveRevision(value: Any?): Long? =
        wholeNumber(value)?.takeIf { it in 1L..MAX_SAFE_INTEGER }

    private fun wholeNumber(value: Any?): Long? = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun normalisedText(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)
            ?.replace('_', '-')

    private fun normalisedSource(value: String?): String? =
        value?.takeIf { it == "embedded" || it == "external" }

    private fun immutableMap(value: LinkedHashMap<String, Any?>): Map<String, Any?> =
        Collections.unmodifiableMap(LinkedHashMap(value))
}
