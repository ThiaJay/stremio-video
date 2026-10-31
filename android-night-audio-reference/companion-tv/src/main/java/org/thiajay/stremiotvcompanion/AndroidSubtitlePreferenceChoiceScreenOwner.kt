package org.thiajay.stremiotvcompanion

import android.content.SharedPreferences
import android.os.Looper
import android.view.ViewGroup

interface AndroidSubtitlePreferenceChoiceScreenPort {
    fun render(surface: AndroidSubtitlePreferenceChoiceSession.Surface): Boolean
    fun clear(): Boolean
    fun close(): Boolean
}

/**
 * Lifecycle owner for one real Android subtitle-choice screen.
 *
 * Exactly one typed outcome is held until the client explicitly consumes it.
 * Rendering or clearing over an unobserved outcome is refused. The owner is
 * composed only by the disclosed synthetic mobile lab. Its production
 * constructor requires an injected client-owned preference-application port
 * and grants no account, player, provider, network or independent preference
 * authority.
 */
class AndroidSubtitlePreferenceChoiceScreenOwner private constructor() {
    internal fun interface PortFactory {
        fun create(
            onOutcome: (AndroidSubtitlePreferenceChoiceOutcome) -> Unit
        ): AndroidSubtitlePreferenceChoiceScreenPort?
    }

    private val ownerThread = Thread.currentThread()
    private var port: AndroidSubtitlePreferenceChoiceScreenPort? = null
    private var pendingOutcome: AndroidSubtitlePreferenceChoiceOutcome? = null
    private var ready = false
    private var closed = false

    fun render(surface: AndroidSubtitlePreferenceChoiceSession.Surface): Boolean {
        if (!usable() || pendingOutcome != null) return false
        val rendered = try {
            port?.render(surface) == true
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (!rendered) poison()
        return rendered
    }

    fun consumeOutcome(): AndroidSubtitlePreferenceChoiceOutcome? {
        if (!usable()) return null
        val outcome = pendingOutcome ?: return null
        pendingOutcome = null
        return outcome
    }

    fun clear(): Boolean {
        if (!usable() || pendingOutcome != null) return false
        val cleared = try {
            port?.clear() == true
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (!cleared) poison()
        return cleared
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        ready = false
        pendingOutcome = null
        val active = port
        port = null
        return try {
            active?.close() == true
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun attach(candidate: AndroidSubtitlePreferenceChoiceScreenPort): Boolean {
        if (!onOwnerThread() || closed || ready || port != null) return false
        port = candidate
        ready = true
        return true
    }

    private fun receiveOutcome(outcome: AndroidSubtitlePreferenceChoiceOutcome) {
        if (!usable() || pendingOutcome != null) {
            poison()
            return
        }
        pendingOutcome = outcome
    }

    private fun poison() {
        if (closed) return
        closed = true
        ready = false
        pendingOutcome = null
        val active = port
        port = null
        try {
            active?.close()
        } catch (_: RuntimeException) {
            // Authority is already revoked.
        } catch (_: LinkageError) {
            // Authority is already revoked.
        }
    }

    private fun usable(): Boolean = onOwnerThread() && ready && !closed

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    companion object {
        fun open(
            preferences: SharedPreferences,
            snapshotPort: AndroidSubtitlePreferenceChoiceSession.SnapshotPort,
            applicationPort: AndroidSubtitlePreferenceChoiceTransaction.ApplicationPort,
            container: ViewGroup
        ): AndroidSubtitlePreferenceChoiceScreenOwner? {
            if (Looper.myLooper() !== Looper.getMainLooper()) return null
            return create(PortFactory { onOutcome ->
                AndroidSubtitlePreferenceChoiceWidgetPort.open(
                    preferences,
                    snapshotPort,
                    applicationPort,
                    container,
                    onOutcome
                )
            })
        }

        internal fun openForTest(
            factory: PortFactory
        ): AndroidSubtitlePreferenceChoiceScreenOwner? = create(factory)

        private fun create(
            factory: PortFactory
        ): AndroidSubtitlePreferenceChoiceScreenOwner? {
            val owner = AndroidSubtitlePreferenceChoiceScreenOwner()
            val candidate = try {
                factory.create(owner::receiveOutcome)
            } catch (_: RuntimeException) {
                null
            } catch (_: LinkageError) {
                null
            } ?: return null

            if (owner.attach(candidate)) return owner
            try {
                candidate.close()
            } catch (_: RuntimeException) {
                // Failed construction has no surviving authority.
            } catch (_: LinkageError) {
                // Failed construction has no surviving authority.
            }
            return null
        }
    }
}
