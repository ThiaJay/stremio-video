package org.thiajay.stremiotvcompanion

import android.content.SharedPreferences
import android.os.Looper
import android.view.ViewGroup

/**
 * One lifecycle authority for the disclosed persistent subtitle-choice labs.
 *
 * State and installation-receipt storage must be different SharedPreferences
 * objects. The owner opens the state lease first, derives the initial surface,
 * admits enabled state only through the exact receipt validator, and always
 * closes the Android screen before releasing the state lease. This prevents a
 * client host from accidentally assembling storage, admission and close order
 * as independent authorities.
 *
 * This class remains synthetic-lab-only. It has no account, provider, player,
 * network or official Stremio preference path.
 */
internal class PersistentSyntheticSubtitlePreferenceScreenOwner private constructor(
    private val host: PersistentSyntheticSubtitlePreferenceHost,
    private val screen: AndroidSubtitlePreferenceChoiceScreenOwner,
    private val initial: InitialState
) {
    enum class Admission {
        EXPLICIT_OFF,
        ENABLED_SAME_INSTALLATION,
        ENABLED_UNADMITTED
    }

    data class InitialState(
        val surface: AndroidSubtitlePreferenceChoiceSession.Surface,
        val admission: Admission
    )

    internal fun interface ScreenFactory {
        fun create(
            host: PersistentSyntheticSubtitlePreferenceHost
        ): AndroidSubtitlePreferenceChoiceScreenOwner?
    }

    private val ownerThread = Thread.currentThread()
    private var closed = false

    fun initialState(): InitialState? = initial.takeIf { usable() }

    fun renderInitial(): Boolean {
        if (!usable()) return false
        val rendered = screen.render(initial.surface)
        if (!rendered) poison()
        return rendered
    }

    fun consumeOutcome(): AndroidSubtitlePreferenceChoiceOutcome? {
        if (!usable()) return null
        return screen.consumeOutcome()
    }

    fun currentPreference():
        AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot? {
        if (!usable()) return null
        return host.readPreferenceSnapshot()
    }

    fun currentPlaybackPreference():
        NativeSubtitlePlaybackPreferenceActuator.Preference? {
        if (!usable()) return null
        val preference = host.readPreferenceSnapshot()
            ?.sessionPreference ?: return null
        val enabled = preference.enabled
        if (!enabled) {
            return NativeSubtitlePlaybackPreferenceActuator.Preference(
                enabled = false,
                sameInstallationReceipt = false
            )
        }
        val stored = screen.loadStoredPreference()
        return NativeSubtitlePlaybackPreferenceActuator.Preference(
            enabled = true,
            sameInstallationReceipt =
                stored?.get("kind") == "same-installation-setting"
        )
    }

    fun renderSettings(): Boolean {
        if (!usable()) return false
        val rendered = screen.render(
            AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS
        )
        if (!rendered) poison()
        return rendered
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        val screenClosed = screen.close()
        val hostClosed = host.close()
        return screenClosed && hostClosed
    }

    private fun poison() {
        if (!onOwnerThread() || closed) return
        closed = true
        screen.close()
        host.close()
    }

    private fun usable(): Boolean = onOwnerThread() && !closed

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    companion object {
        fun open(
            statePreferences: SharedPreferences,
            receiptPreferences: SharedPreferences,
            namespace: PersistentSyntheticSubtitlePreferenceHost.Namespace,
            container: ViewGroup
        ): PersistentSyntheticSubtitlePreferenceScreenOwner? {
            if (Looper.myLooper() !== Looper.getMainLooper()) return null
            return create(
                statePreferences,
                receiptPreferences,
                namespace,
                ScreenFactory { host ->
                    AndroidSubtitlePreferenceChoiceScreenOwner.open(
                        receiptPreferences,
                        host,
                        host,
                        container
                    )
                }
            )
        }

        internal fun openForTest(
            statePreferences: SharedPreferences,
            receiptPreferences: SharedPreferences,
            namespace: PersistentSyntheticSubtitlePreferenceHost.Namespace,
            factory: ScreenFactory
        ): PersistentSyntheticSubtitlePreferenceScreenOwner? = create(
            statePreferences,
            receiptPreferences,
            namespace,
            factory
        )

        private fun create(
            statePreferences: SharedPreferences,
            receiptPreferences: SharedPreferences,
            namespace: PersistentSyntheticSubtitlePreferenceHost.Namespace,
            factory: ScreenFactory
        ): PersistentSyntheticSubtitlePreferenceScreenOwner? {
            if (statePreferences === receiptPreferences) return null
            val host = PersistentSyntheticSubtitlePreferenceHost.open(
                statePreferences,
                namespace
            ) ?: return null
            val surface = host.presentationSurface()
            val preference = host.readPreferenceSnapshot()?.sessionPreference
            if (surface == null || preference == null) {
                host.close()
                return null
            }

            val screen = try {
                factory.create(host)
            } catch (_: RuntimeException) {
                null
            } catch (_: LinkageError) {
                null
            }
            if (screen == null) {
                host.close()
                return null
            }

            val admission = if (preference.enabled != true) {
                Admission.EXPLICIT_OFF
            } else {
                val stored = screen.loadStoredPreference()
                if (stored?.get("kind") == "same-installation-setting") {
                    Admission.ENABLED_SAME_INSTALLATION
                } else {
                    Admission.ENABLED_UNADMITTED
                }
            }
            return PersistentSyntheticSubtitlePreferenceScreenOwner(
                host,
                screen,
                InitialState(surface, admission)
            )
        }
    }
}
