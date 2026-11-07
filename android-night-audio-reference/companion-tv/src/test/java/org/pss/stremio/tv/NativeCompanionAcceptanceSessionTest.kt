package org.pss.stremio.tv

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.AbstractMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCompanionAcceptanceSessionTest {
    private val source = "a".repeat(64)
    private val device = "c".repeat(64)
    private val openedAt = "2026-11-03T05:54:00Z"
    private val evaluatedAt = "2026-11-03T06:00:00Z"

    @Test fun exactReceiptCanBeEvaluatedOnce() {
        val session = session()
        assertNotNull(session)
        val decision = session!!.evaluate(wire(), evaluatedAt)
        assertTrue(decision.validation.accepted)
        assertEquals("ACCEPT_COMPANION_TEXT_ENTRY", decision.plan.action)
    }

    @Test fun duplicateReceiptReplayIsRefusedAfterAcceptance() {
        val session = session()!!
        assertTrue(session.evaluate(wire(), evaluatedAt).validation.accepted)
        val replay = session.evaluate(wire(), evaluatedAt)
        assertFalse(replay.validation.accepted)
        assertEquals("SESSION_ALREADY_CONSUMED", replay.validation.reason)
        assertEquals("PRESERVE_TYPED_SEARCH", replay.plan.action)
    }

    @Test fun rejectedAttemptConsumesSessionBeforeLaterValidReceipt() {
        val session = session()!!
        val rejected = session.evaluate(wire().apply { remove("route") }, evaluatedAt)
        assertEquals("RECEIPT_WIRE_WIRE_SCHEMA_MISMATCH", rejected.validation.reason)
        val later = session.evaluate(wire(), evaluatedAt)
        assertEquals("SESSION_ALREADY_CONSUMED", later.validation.reason)
        assertFalse(later.plan.acceptance)
    }

    @Test fun foreignThreadCannotEvaluateOrClose() {
        val session = session()!!
        val foreignDecision = AtomicReference<CompanionAcceptanceDecision?>()
        val foreignClose = AtomicBoolean(true)
        val thread = Thread {
            foreignDecision.set(session.evaluate(wire(), evaluatedAt))
            foreignClose.set(session.close())
        }
        thread.start()
        thread.join()
        assertEquals("SESSION_OWNER_THREAD_MISMATCH", foreignDecision.get()!!.validation.reason)
        assertFalse(foreignClose.get())
        assertTrue(session.evaluate(wire(), evaluatedAt).validation.accepted)
    }

    @Test fun closeRevokesUnusedSession() {
        val session = session()!!
        assertTrue(session.close())
        assertFalse(session.close())
        val decision = session.evaluate(wire(), evaluatedAt)
        assertEquals("SESSION_CLOSED", decision.validation.reason)
        assertEquals("PRESERVE_TYPED_SEARCH", decision.plan.action)
    }

    @Test fun nullExpectedCannotOpenSession() {
        assertNull(NativeCompanionAcceptanceSession.open(null, openedAt))
    }

    @Test fun sessionRequiresExpectedReviewedExecutorAuthority() {
        for (executor in listOf<String?>(null, "short", "B".repeat(64))) {
            assertNull(
                NativeCompanionAcceptanceSession.open(
                    expected(executorSourceSha256 = executor),
                    openedAt
                )
            )
        }
    }

    @Test fun sessionDiagnosticsExposeNoQueryOrDigest() {
        val expected = expected()
        val session = NativeCompanionAcceptanceSession.open(expected, openedAt)!!
        assertFalse(session.toString().contains("bounded query"))
        assertFalse(session.toString().contains(expected.expectedTextSha256!!))
        val fields = NativeCompanionAcceptanceSession::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it == "query" || it == "expectedText" })
    }

    @Test fun hostileMapConsumesSessionWithoutEscaping() {
        val session = session()!!
        val first = session.evaluate(ThrowingEntriesMap(), evaluatedAt)
        assertEquals("RECEIPT_WIRE_INVALID_OR_HOSTILE_WIRE_INPUT", first.validation.reason)
        assertEquals(
            "SESSION_ALREADY_CONSUMED",
            session.evaluate(wire(), evaluatedAt).validation.reason
        )
    }

    @Test fun earlierReceiptCannotBeReplayedInLaterSession() {
        val session = NativeCompanionAcceptanceSession.open(
            expected(),
            "2026-11-03T05:56:00Z"
        )!!
        val decision = session.evaluate(wire(), evaluatedAt)
        assertFalse(decision.validation.accepted)
        assertEquals("SESSION_OBSERVATION_PRECEDES_REQUEST", decision.validation.reason)
        assertEquals("PRESERVE_TYPED_SEARCH", decision.plan.action)
    }

    @Test fun evaluationClockRollbackIsRefusedAndConsumesSession() {
        val session = NativeCompanionAcceptanceSession.open(
            expected(),
            "2026-11-03T06:01:00Z"
        )!!
        val first = session.evaluate(wire(), evaluatedAt)
        assertEquals("SESSION_CLOCK_ROLLBACK", first.validation.reason)
        assertEquals(
            "SESSION_ALREADY_CONSUMED",
            session.evaluate(wire(), "2026-11-03T06:02:00Z").validation.reason
        )
    }

    @Test fun expiredSessionIsRefused() {
        val session = NativeCompanionAcceptanceSession.open(
            expected(),
            "2026-11-03T05:49:59Z"
        )!!
        val decision = session.evaluate(wire(), evaluatedAt)
        assertEquals("SESSION_EXPIRED", decision.validation.reason)
        assertFalse(decision.plan.acceptance)
    }

    @Test fun invalidSessionOpenTimeCannotOpen() {
        assertNull(NativeCompanionAcceptanceSession.open(expected(), null))
        assertNull(NativeCompanionAcceptanceSession.open(expected(), "not-an-instant"))
    }

    private fun session() =
        NativeCompanionAcceptanceSession.open(expected(), openedAt)

    private fun expected(
        executorSourceSha256: String? = "b".repeat(64)
    ) = ExpectedCompanionAcceptance(
        contractSourceSha256 = source,
        executorSourceSha256 = executorSourceSha256,
        deviceIdentityDigest = device,
        deviceModel = "AFTKRT",
        appPackage = "com.stremio.one",
        appVersion = "1.11.2",
        expectedTextSha256 = NativeCompanionAcceptanceReceipt.digestExpectedText("bounded query")
    )

    private fun wire() = linkedMapOf<String, Any?>(
        "schemaVersion" to 1L,
        "contractId" to NativeCompanionAcceptanceReceipt.CONTRACT_ID,
        "contractSourceSha256" to source,
        "executorSourceSha256" to "b".repeat(64),
        "executorReviewed" to true,
        "deviceIdentityDigest" to device,
        "deviceModel" to "AFTKRT",
        "deviceIdentityStatus" to NativeCompanionTextEntryPolicy.VERIFIED_CURRENT_DEVICE,
        "appPackage" to "com.stremio.one",
        "appVersion" to "1.11.2",
        "route" to NativeCompanionTextEntryPolicy.VERIFIED_OFFICIAL_FIRE_TV_APP,
        "observedAt" to "2026-11-03T05:55:00Z",
        "explicitCompanionInputGesture" to true,
        "searchSurfaceBefore" to "stremio",
        "typedSearchFocused" to true,
        "sensitiveInput" to false,
        "audioCaptured" to false,
        "accountMutation" to false,
        "settingsMutation" to false,
        "playbackStarted" to false,
        "expectedText" to "bounded query",
        "readBackText" to "bounded query",
        "searchSurfaceAfter" to "stremio",
        "resultsRemainInStremio" to true
    )

    private class ThrowingEntriesMap : AbstractMap<String, Any?>() {
        override val entries: Set<Map.Entry<String, Any?>>
            get() = throw IllegalStateException("hostile map")
    }
}
