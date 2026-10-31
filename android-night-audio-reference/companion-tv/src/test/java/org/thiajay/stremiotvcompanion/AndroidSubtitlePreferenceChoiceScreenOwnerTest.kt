package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSubtitlePreferenceChoiceScreenOwnerTest {
    @Test fun screenHoldsOneOutcomeUntilExplicitConsumption() {
        val fixture = Fixture()
        val owner = fixture.open()!!
        val outcome = outcome(ok = true)

        assertTrue(owner.render(AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN))
        fixture.port!!.emit(outcome)
        assertFalse(owner.render(AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS))
        assertFalse(owner.clear())
        assertSame(outcome, owner.consumeOutcome())
        assertNull(owner.consumeOutcome())
        assertTrue(owner.render(AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS))
        assertTrue(owner.close())
    }

    @Test fun refusedOutcomeIsHeldWithoutPromotionOrRerender() {
        val fixture = Fixture()
        val owner = fixture.open()!!
        val refused = outcome(ok = false)

        assertTrue(owner.render(AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN))
        fixture.port!!.emit(refused)
        assertFalse(owner.render(AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN))
        val consumed = owner.consumeOutcome()
        assertSame(refused, consumed)
        assertFalse(consumed!!.result.ok)
        assertTrue(owner.close())
    }

    @Test fun duplicateOutcomePermanentlyPoisonsAndClosesPort() {
        val fixture = Fixture()
        val owner = fixture.open()!!
        fixture.port!!.emit(outcome(ok = true))
        fixture.port!!.emit(outcome(ok = false))

        assertEquals(1, fixture.port!!.closeCalls)
        assertNull(owner.consumeOutcome())
        assertFalse(owner.render(AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN))
        assertFalse(owner.close())
    }

    @Test fun renderOrClearRefusalPermanentlyPoisons() {
        val renderFixture = Fixture(renderResult = false)
        val renderOwner = renderFixture.open()!!
        assertFalse(renderOwner.render(AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN))
        assertEquals(1, renderFixture.port!!.closeCalls)
        assertFalse(renderOwner.close())

        val clearFixture = Fixture(clearResult = false)
        val clearOwner = clearFixture.open()!!
        assertFalse(clearOwner.clear())
        assertEquals(1, clearFixture.port!!.closeCalls)
        assertFalse(clearOwner.close())
    }

    @Test fun throwingPortOperationsAreContainedAndRevokeAuthority() {
        val renderFixture = Fixture(throwOnRender = true)
        val renderOwner = renderFixture.open()!!
        assertFalse(renderOwner.render(AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN))
        assertEquals(1, renderFixture.port!!.closeCalls)

        val clearFixture = Fixture(throwOnClear = true)
        val clearOwner = clearFixture.open()!!
        assertFalse(clearOwner.clear())
        assertEquals(1, clearFixture.port!!.closeCalls)
    }

    @Test fun nullOrThrowingFactoryFailsClosed() {
        assertNull(AndroidSubtitlePreferenceChoiceScreenOwner.openForTest {
            null
        })
        assertNull(AndroidSubtitlePreferenceChoiceScreenOwner.openForTest {
            throw IllegalStateException("refuse construction")
        })
    }

    @Test fun ownerThreadAndCloseRevokePendingOutcomeAndPort() {
        val fixture = Fixture()
        val owner = fixture.open()!!
        assertTrue(owner.render(AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN))
        fixture.port!!.emit(outcome(ok = true))
        val foreignRender = AtomicBoolean(true)
        val foreignOutcome = AtomicReference<AndroidSubtitlePreferenceChoiceOutcome?>()
        val foreignClose = AtomicBoolean(true)

        Thread {
            foreignRender.set(owner.render(
                AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS
            ))
            foreignOutcome.set(owner.consumeOutcome())
            foreignClose.set(owner.close())
        }.apply { start(); join() }

        assertFalse(foreignRender.get())
        assertNull(foreignOutcome.get())
        assertFalse(foreignClose.get())
        assertNotNull(owner.consumeOutcome())
        assertTrue(owner.close())
        assertEquals(1, fixture.port!!.closeCalls)
        assertFalse(owner.render(AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN))
        assertNull(owner.consumeOutcome())
    }

    private fun outcome(ok: Boolean) = AndroidSubtitlePreferenceChoiceOutcome(
        AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
        AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED,
        NativeSubtitlePreferenceStore.CommitResult(
            ok = ok,
            provenance = if (ok) mapOf("kind" to "same-installation-setting") else null,
            rollbackVerified = true
        )
    )

    private class Fixture(
        private val renderResult: Boolean = true,
        private val clearResult: Boolean = true,
        private val throwOnRender: Boolean = false,
        private val throwOnClear: Boolean = false
    ) {
        var port: FakePort? = null

        fun open(): AndroidSubtitlePreferenceChoiceScreenOwner? =
            AndroidSubtitlePreferenceChoiceScreenOwner.openForTest { callback ->
                FakePort(
                    callback,
                    renderResult,
                    clearResult,
                    throwOnRender,
                    throwOnClear
                ).also { port = it }
            }
    }

    private class FakePort(
        private val callback: (AndroidSubtitlePreferenceChoiceOutcome) -> Unit,
        private val renderResult: Boolean,
        private val clearResult: Boolean,
        private val throwOnRender: Boolean,
        private val throwOnClear: Boolean
    ) : AndroidSubtitlePreferenceChoiceScreenPort {
        var closeCalls = 0

        override fun render(
            surface: AndroidSubtitlePreferenceChoiceSession.Surface
        ): Boolean {
            if (throwOnRender) throw IllegalStateException("render refused")
            return renderResult
        }

        override fun clear(): Boolean {
            if (throwOnClear) throw IllegalStateException("clear refused")
            return clearResult
        }

        override fun close(): Boolean {
            closeCalls += 1
            return closeCalls == 1
        }

        fun emit(outcome: AndroidSubtitlePreferenceChoiceOutcome) {
            callback(outcome)
        }
    }
}
