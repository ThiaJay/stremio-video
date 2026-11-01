package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentSyntheticSubtitlePreferenceScreenOwnerTest {
    @Test fun identicalStateAndReceiptObjectIsRefusedBeforeLease() {
        val preferences = FakeSharedPreferences()

        assertNull(open(preferences, preferences, MOBILE, ScreenFixture()))
        val host = PersistentSyntheticSubtitlePreferenceHost.open(preferences, MOBILE)
        assertNotNull(host)
        assertTrue(host!!.close())
    }

    @Test fun explicitOffDoesNotReadOrManufactureReceiptAdmission() {
        val state = FakeSharedPreferences()
        val receipt = FakeSharedPreferences()
        val fixture = ScreenFixture(stored = admittedReceipt())
        val owner = open(state, receipt, MOBILE, fixture)!!

        assertEquals(FIRST_RUN, owner.initialState()!!.surface)
        assertEquals(EXPLICIT_OFF, owner.initialState()!!.admission)
        assertEquals(0, fixture.port!!.loadCalls)
        assertEquals(snapshot(false), owner.currentPreference())
        assertTrue(owner.close())
    }

    @Test fun enabledStateWithoutExactReceiptRemainsUnadmitted() {
        val state = FakeSharedPreferences().apply { seed(MOBILE_KEY, true) }
        val fixture = ScreenFixture(stored = null)
        val owner = open(state, FakeSharedPreferences(), MOBILE, fixture)!!

        assertEquals(SUBTITLE_SETTINGS, owner.initialState()!!.surface)
        assertEquals(ENABLED_UNADMITTED, owner.initialState()!!.admission)
        assertEquals(1, fixture.port!!.loadCalls)
        assertTrue(owner.close())
    }

    @Test fun enabledStateIsAdmittedOnlyThroughScreenReceiptValidator() {
        val state = FakeSharedPreferences().apply { seed(TV_KEY, true) }
        val fixture = ScreenFixture(stored = admittedReceipt())
        val owner = open(state, FakeSharedPreferences(), TV, fixture)!!

        assertEquals(SUBTITLE_SETTINGS, owner.initialState()!!.surface)
        assertEquals(ENABLED_SAME_INSTALLATION, owner.initialState()!!.admission)
        assertEquals(1, fixture.port!!.loadCalls)
        assertTrue(owner.close())
    }

    @Test fun explicitOffPlaybackPreferenceDoesNotReadReceipt() {
        val fixture = ScreenFixture(stored = admittedReceipt())
        val owner = open(
            FakeSharedPreferences(),
            FakeSharedPreferences(),
            TV,
            fixture
        )!!

        assertEquals(
            NativeSubtitlePlaybackPreferenceActuator.Preference(false, false),
            owner.currentPlaybackPreference()
        )
        assertEquals(0, fixture.port!!.loadCalls)
        assertTrue(owner.close())
    }

    @Test fun enabledPlaybackPreferenceRequiresExactStoredReceipt() {
        val state = FakeSharedPreferences().apply { seed(TV_KEY, true) }
        val admittedFixture = ScreenFixture(stored = admittedReceipt())
        val admitted = open(state, FakeSharedPreferences(), TV, admittedFixture)!!
        assertEquals(
            NativeSubtitlePlaybackPreferenceActuator.Preference(true, true),
            admitted.currentPlaybackPreference()
        )
        assertTrue(admitted.close())

        val unadmittedFixture = ScreenFixture(stored = mapOf("kind" to "account-hydration"))
        val unadmitted = open(state, FakeSharedPreferences(), TV, unadmittedFixture)!!
        assertEquals(
            NativeSubtitlePlaybackPreferenceActuator.Preference(true, false),
            unadmitted.currentPlaybackPreference()
        )
        assertTrue(unadmitted.close())
    }

    @Test fun compositeOwnsRenderOutcomeRerenderAndScreenFirstClose() {
        val state = FakeSharedPreferences()
        val receipt = FakeSharedPreferences()
        val screenClosedWhileStateLeased = AtomicBoolean(false)
        val fixture = ScreenFixture(onClose = {
            val competing = PersistentSyntheticSubtitlePreferenceHost.open(state, MOBILE)
            screenClosedWhileStateLeased.set(competing == null)
            competing?.close()
        })
        val owner = open(state, receipt, MOBILE, fixture)!!

        assertTrue(owner.renderInitial())
        fixture.port!!.emit(outcome())
        assertEquals(AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED,
            owner.consumeOutcome()!!.choice)
        assertTrue(owner.renderSettings())
        assertTrue(owner.close())
        assertTrue(screenClosedWhileStateLeased.get())
        val reopened = PersistentSyntheticSubtitlePreferenceHost.open(state, MOBILE)
        assertNotNull(reopened)
        assertTrue(reopened!!.close())
    }

    @Test fun failedRenderPoisonsBothScreenAndStateLease() {
        val state = FakeSharedPreferences()
        val fixture = ScreenFixture(renderResult = false)
        val owner = open(state, FakeSharedPreferences(), TV, fixture)!!

        assertFalse(owner.renderInitial())
        assertEquals(1, fixture.port!!.closeCalls)
        assertNull(owner.initialState())
        assertFalse(owner.close())
        val reopened = PersistentSyntheticSubtitlePreferenceHost.open(state, TV)
        assertNotNull(reopened)
        assertTrue(reopened!!.close())
    }

    @Test fun constructionFailureAndForeignThreadCannotLeakAuthority() {
        val state = FakeSharedPreferences()
        assertNull(open(state, FakeSharedPreferences(), MOBILE, ScreenFixture(throwOnOpen = true)))
        val owner = open(state, FakeSharedPreferences(), MOBILE, ScreenFixture())!!
        val foreignInitial = AtomicReference<Any?>()
        val foreignRender = AtomicBoolean(true)
        val foreignClose = AtomicBoolean(true)

        Thread {
            foreignInitial.set(owner.initialState())
            foreignRender.set(owner.renderInitial())
            foreignClose.set(owner.close())
        }.apply { start(); join() }

        assertNull(foreignInitial.get())
        assertFalse(foreignRender.get())
        assertFalse(foreignClose.get())
        assertTrue(owner.renderInitial())
        assertTrue(owner.close())
    }

    private fun open(
        state: FakeSharedPreferences,
        receipt: FakeSharedPreferences,
        namespace: PersistentSyntheticSubtitlePreferenceHost.Namespace,
        fixture: ScreenFixture
    ) = PersistentSyntheticSubtitlePreferenceScreenOwner.openForTest(
        state,
        receipt,
        namespace,
        PersistentSyntheticSubtitlePreferenceScreenOwner.ScreenFactory { host ->
            fixture.open(host)
        }
    )

    private fun snapshot(enabled: Boolean) =
        AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot(
            NativeSubtitlePreferenceProvenance.SessionPreference(
                enabled = enabled,
                language = "English",
                source = "embedded"
            ),
            "English"
        )

    private fun admittedReceipt() =
        mapOf<String, Any?>("kind" to "same-installation-setting")

    private fun outcome() = AndroidSubtitlePreferenceChoiceOutcome(
        FIRST_RUN,
        AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED,
        NativeSubtitlePreferenceStore.CommitResult(
            ok = true,
            provenance = admittedReceipt(),
            rollbackVerified = true
        )
    )

    private class ScreenFixture(
        private val stored: Map<String, Any?>? = null,
        private val renderResult: Boolean = true,
        private val throwOnOpen: Boolean = false,
        private val onClose: () -> Unit = {}
    ) {
        var port: FakeScreenPort? = null

        fun open(
            host: PersistentSyntheticSubtitlePreferenceHost
        ): AndroidSubtitlePreferenceChoiceScreenOwner? {
            if (throwOnOpen) throw IllegalStateException("construction refused")
            check(host.readPreferenceSnapshot() != null)
            return AndroidSubtitlePreferenceChoiceScreenOwner.openForTest { callback ->
                FakeScreenPort(callback, stored, renderResult, onClose).also { port = it }
            }
        }
    }

    private class FakeScreenPort(
        private val callback: (AndroidSubtitlePreferenceChoiceOutcome) -> Unit,
        private val stored: Map<String, Any?>?,
        private val renderResult: Boolean,
        private val onClose: () -> Unit
    ) : AndroidSubtitlePreferenceChoiceScreenPort {
        var loadCalls = 0
        var closeCalls = 0

        override fun loadStoredPreference(): Map<String, Any?>? {
            loadCalls += 1
            return stored
        }

        override fun render(
            surface: AndroidSubtitlePreferenceChoiceSession.Surface
        ): Boolean = renderResult

        override fun clear(): Boolean = true

        override fun close(): Boolean {
            closeCalls += 1
            if (closeCalls == 1) onClose()
            return closeCalls == 1
        }

        fun emit(outcome: AndroidSubtitlePreferenceChoiceOutcome) = callback(outcome)
    }

    private companion object {
        val MOBILE = PersistentSyntheticSubtitlePreferenceHost.Namespace.MOBILE
        val TV = PersistentSyntheticSubtitlePreferenceHost.Namespace.TV
        val FIRST_RUN = AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
        val SUBTITLE_SETTINGS = AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS
        val EXPLICIT_OFF =
            PersistentSyntheticSubtitlePreferenceScreenOwner.Admission.EXPLICIT_OFF
        val ENABLED_SAME_INSTALLATION =
            PersistentSyntheticSubtitlePreferenceScreenOwner.Admission.ENABLED_SAME_INSTALLATION
        val ENABLED_UNADMITTED =
            PersistentSyntheticSubtitlePreferenceScreenOwner.Admission.ENABLED_UNADMITTED
        const val MOBILE_KEY =
            "org.thiajay.stremiotvcompanion.synthetic.mobile.subtitle.enabled.v2"
        const val TV_KEY =
            "org.thiajay.stremiotvcompanion.synthetic.tv.subtitle.enabled.v2"
    }
}
