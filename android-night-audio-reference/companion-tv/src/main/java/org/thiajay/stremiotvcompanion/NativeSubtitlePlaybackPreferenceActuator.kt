package org.thiajay.stremiotvcompanion

/**
 * Source-only handoff from an admitted installation-local subtitle preference
 * to a future client-owned player. It never replaces a manual track choice.
 *
 * The host supplies a complete immutable player snapshot and the exact
 * installation-local preference admission. Preparation issues at most one
 * lease. Execution consumes that exact object before stable final readback and
 * before the injected port is called. A changed video, source, load or player
 * generation, transition state, preference or receipt refuses the call.
 *
 * This class has no account, network, provider or production Stremio binding.
 * The disclosed TV lab may compose it through a narrow Media3 adapter against
 * synthetic local media only; the mobile lab and production clients do not.
 */
internal class NativeSubtitlePlaybackPreferenceActuator(
    private val snapshotPort: SnapshotPort,
    private val preferencePort: PreferencePort,
    private val actionPort: ActionPort
) {
    enum class Trigger {
        PREFERENCE_CHANGE,
        INITIAL_LOAD,
        EPISODE_TRANSITION,
        RECOVERY
    }

    enum class SelectionOrigin {
        NONE,
        AUTOMATIC,
        MANUAL
    }

    enum class Action {
        APPLY_AUTOMATIC,
        CLEAR_AUTOMATIC
    }

    data class Preference(
        val enabled: Boolean,
        val sameInstallationReceipt: Boolean
    )

    data class Snapshot(
        val playerGeneration: Long,
        val loadGeneration: Long,
        val videoId: String,
        val sourceId: String,
        val ready: Boolean,
        val released: Boolean,
        val transitioning: Boolean,
        val nextEpisodeRequested: Boolean,
        val selectionOrigin: SelectionOrigin
    )

    @ConsistentCopyVisibility
    data class Lease internal constructor(
        val trigger: Trigger,
        val action: Action,
        val preference: Preference,
        val snapshot: Snapshot
    )

    fun interface SnapshotPort {
        fun readSnapshot(): Snapshot?
    }

    fun interface PreferencePort {
        fun readPreference(): Preference?
    }

    fun interface ActionPort {
        fun apply(action: Action, trigger: Trigger, snapshot: Snapshot): Boolean
    }

    private val ownerThread = Thread.currentThread()
    private var issued: Lease? = null
    private var closed = false

    fun prepare(trigger: Trigger): Lease? {
        if (!usable() || issued != null) return null
        val preference = readStablePreference() ?: return null
        val snapshot = readStableSnapshot() ?: return null
        if (!valid(snapshot)) return null
        val action = selectAction(preference, snapshot.selectionOrigin) ?: return null
        return Lease(trigger, action, preference, snapshot).also { issued = it }
    }

    fun execute(lease: Lease): Boolean {
        if (!usable()) return false
        val accepted = issued ?: return false
        issued = null
        if (lease !== accepted) return false
        val finalPreference = readStablePreference() ?: return false
        val finalSnapshot = readStableSnapshot() ?: return false
        if (finalPreference != lease.preference || finalSnapshot != lease.snapshot) return false
        if (!valid(finalSnapshot)) return false
        if (selectAction(finalPreference, finalSnapshot.selectionOrigin) != lease.action) return false
        return try {
            actionPort.apply(lease.action, lease.trigger, lease.snapshot)
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        issued = null
        closed = true
        return true
    }

    private fun selectAction(
        preference: Preference,
        origin: SelectionOrigin
    ): Action? {
        if (origin == SelectionOrigin.MANUAL) return null
        val admittedEnabled = preference.enabled && preference.sameInstallationReceipt
        return when {
            admittedEnabled && origin == SelectionOrigin.NONE -> Action.APPLY_AUTOMATIC
            !admittedEnabled && origin == SelectionOrigin.AUTOMATIC -> Action.CLEAR_AUTOMATIC
            else -> null
        }
    }

    private fun valid(snapshot: Snapshot): Boolean =
        snapshot.playerGeneration > 0L &&
            snapshot.loadGeneration > 0L &&
            validIdentity(snapshot.videoId) &&
            validIdentity(snapshot.sourceId) &&
            snapshot.ready &&
            !snapshot.released &&
            !snapshot.transitioning &&
            !snapshot.nextEpisodeRequested

    private fun validIdentity(value: String): Boolean =
        value.isNotBlank() && value.length <= 256 && value.none { Character.isISOControl(it) }

    private fun readStableSnapshot(): Snapshot? = try {
        val first = snapshotPort.readSnapshot()
        val second = snapshotPort.readSnapshot()
        first?.takeIf { it == second }
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun readStablePreference(): Preference? = try {
        val first = preferencePort.readPreference()
        val second = preferencePort.readPreference()
        first?.takeIf { it == second }
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun usable(): Boolean = onOwnerThread() && !closed

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}
