package org.thiajay.stremiotvcompanion

import android.os.Looper
import android.view.ViewGroup
import org.pss.stremio.tv.CanonicalWatchedAccountHostPort
import org.pss.stremio.tv.CanonicalWatchedScreenConstructionBoundary
import org.pss.stremio.tv.CanonicalWatchedScreenOwnedAccountLease
import org.pss.stremio.tv.CanonicalWatchedStatePort
import org.pss.stremio.tv.NativeMobileTitleWatchedHostBinding
import org.pss.stremio.tv.NativeMobileTitleWatchedOutcome
import org.pss.stremio.tv.NativeMobileTitleWatchedViewAction
import org.pss.stremio.tv.NativeMobileTitleWatchedViewPortRelay
import org.pss.stremio.tv.NativeTvTitleWatchedHostBinding
import org.pss.stremio.tv.NativeTvTitleWatchedOutcome
import org.pss.stremio.tv.NativeTvTitleWatchedViewAction
import org.pss.stremio.tv.NativeTvTitleWatchedViewPortRelay
import org.pss.stremio.tv.SessionBoundCanonicalWatchedAccountAdapter
import org.pss.stremio.tv.TvWatchedCoordinator

/**
 * Actual Android mobile composition root for the accepted host binding and
 * widget port. The isolated lab creates this owner only with a local in-memory
 * synthetic account host; production adoption still requires a validated
 * authenticated private-client host.
 *
 * Outcomes are held in a one-slot mailbox. A client must consume the result
 * before rendering a fresh account snapshot, preventing a refused or
 * unobserved activation from being silently replaced.
 */
class AndroidMobileTitleWatchedScreenOwner private constructor(
    container: ViewGroup,
    coordinator: TvWatchedCoordinator,
    account: CanonicalWatchedStatePort,
    private val ownedAccount: SessionBoundCanonicalWatchedAccountAdapter?
) {
    companion object {
        fun create(
            container: ViewGroup,
            coordinator: TvWatchedCoordinator,
            account: CanonicalWatchedStatePort
        ): AndroidMobileTitleWatchedScreenOwner? {
            if (Looper.myLooper() !== Looper.getMainLooper()) return null
            return CanonicalWatchedScreenConstructionBoundary.external {
                val owner = AndroidMobileTitleWatchedScreenOwner(
                    container, coordinator, account, ownedAccount = null
                )
                if (owner.ready) owner else null
            }
        }

        fun create(
            container: ViewGroup,
            coordinator: TvWatchedCoordinator,
            accountHost: CanonicalWatchedAccountHostPort
        ): AndroidMobileTitleWatchedScreenOwner? {
            if (Looper.myLooper() !== Looper.getMainLooper()) return null
            return CanonicalWatchedScreenConstructionBoundary.owned(accountHost) { account ->
                val owner = AndroidMobileTitleWatchedScreenOwner(
                    container, coordinator, account, ownedAccount = account
                )
                if (owner.ready) owner else null
            }
        }
    }

    private val ownerThread = Thread.currentThread()
    private val ownedAccountLease = CanonicalWatchedScreenOwnedAccountLease(ownedAccount)
    private val relay = NativeMobileTitleWatchedViewPortRelay()
    private val host = NativeMobileTitleWatchedHostBinding(coordinator, account, relay)
    private val port = AndroidMobileTitleWatchedWidgetPort(
        container = container,
        onActivation = ::onActivation
    )
    private var pendingOutcome: NativeMobileTitleWatchedOutcome? = null
    private var ready = relay.bind(port)
    private var closed = false

    init {
        if (!ready) {
            host.close()
            relay.close()
            ownedAccountLease.requestTermination()
            closed = true
        }
    }

    fun render(seasonNumber: Int?): Boolean {
        if (!onMainOwnerThread() || closed || !ready || pendingOutcome != null) return false
        if (ownedAccountLease.isTerminationRequested()) {
            poison()
            return false
        }
        val rendered = host.render(seasonNumber)
        if (!rendered && !host.isOperational()) poison()
        return rendered
    }

    fun consumeOutcome(): NativeMobileTitleWatchedOutcome? {
        if (!onMainOwnerThread() || closed || !ready) return null
        val outcome = pendingOutcome
        if (ownedAccountLease.isTerminationRequested() || !host.isOperational()) {
            pendingOutcome = null
            poison()
            return outcome
        }
        outcome ?: return null
        pendingOutcome = null
        return outcome
    }

    fun close(): Boolean {
        if (!onMainOwnerThread()) {
            ownedAccountLease.requestTermination()
            return false
        }
        if (closed) return false
        closed = true
        ready = false
        pendingOutcome = null
        val hostClosed = host.close()
        val relayClosed = relay.close()
        val accountClosed = ownedAccountLease.requestTermination()
        return hostClosed && relayClosed && accountClosed
    }

    private fun onActivation(
        action: NativeMobileTitleWatchedViewAction,
        selectedActionId: String,
        semanticEvent: String,
        unwatchAcknowledged: Boolean
    ) {
        if (!onMainOwnerThread()) {
            ownedAccountLease.requestTermination()
            return
        }
        if (ownedAccountLease.isTerminationRequested() ||
            closed || !ready || pendingOutcome != null
        ) {
            poison()
            return
        }
        val outcome = host.activate(
            action = action,
            selectedActionId = selectedActionId,
            semanticEvent = semanticEvent,
            unwatchAcknowledged = unwatchAcknowledged
        )
        pendingOutcome = outcome
        if (!host.isOperational()) {
            ownedAccountLease.requestTermination()
        }
    }

    private fun poison() {
        if (closed) return
        closed = true
        ready = false
        pendingOutcome = null
        host.close()
        relay.close()
        ownedAccountLease.requestTermination()
    }

    private fun onMainOwnerThread(): Boolean =
        Thread.currentThread() === ownerThread &&
            Looper.myLooper() === Looper.getMainLooper()
}

/** Android TV composition root with the same one-outcome lifecycle contract. */
class AndroidTvTitleWatchedScreenOwner private constructor(
    container: ViewGroup,
    coordinator: TvWatchedCoordinator,
    account: CanonicalWatchedStatePort,
    private val ownedAccount: SessionBoundCanonicalWatchedAccountAdapter?
) {
    companion object {
        fun create(
            container: ViewGroup,
            coordinator: TvWatchedCoordinator,
            account: CanonicalWatchedStatePort
        ): AndroidTvTitleWatchedScreenOwner? {
            if (Looper.myLooper() !== Looper.getMainLooper()) return null
            return CanonicalWatchedScreenConstructionBoundary.external {
                val owner = AndroidTvTitleWatchedScreenOwner(
                    container, coordinator, account, ownedAccount = null
                )
                if (owner.ready) owner else null
            }
        }

        fun create(
            container: ViewGroup,
            coordinator: TvWatchedCoordinator,
            accountHost: CanonicalWatchedAccountHostPort
        ): AndroidTvTitleWatchedScreenOwner? {
            if (Looper.myLooper() !== Looper.getMainLooper()) return null
            return CanonicalWatchedScreenConstructionBoundary.owned(accountHost) { account ->
                val owner = AndroidTvTitleWatchedScreenOwner(
                    container, coordinator, account, ownedAccount = account
                )
                if (owner.ready) owner else null
            }
        }
    }

    private val ownerThread = Thread.currentThread()
    private val ownedAccountLease = CanonicalWatchedScreenOwnedAccountLease(ownedAccount)
    private val relay = NativeTvTitleWatchedViewPortRelay()
    private val host = NativeTvTitleWatchedHostBinding(coordinator, account, relay)
    private val port = AndroidTvTitleWatchedWidgetPort(
        container = container,
        onActivation = ::onActivation
    )
    private var pendingOutcome: NativeTvTitleWatchedOutcome? = null
    private var ready = relay.bind(port)
    private var closed = false

    init {
        if (!ready) {
            host.close()
            relay.close()
            ownedAccountLease.requestTermination()
            closed = true
        }
    }

    fun render(seasonNumber: Int?): Boolean {
        if (!onMainOwnerThread() || closed || !ready || pendingOutcome != null) return false
        if (ownedAccountLease.isTerminationRequested()) {
            poison()
            return false
        }
        val rendered = host.render(seasonNumber)
        if (!rendered && !host.isOperational()) poison()
        return rendered
    }

    fun consumeOutcome(): NativeTvTitleWatchedOutcome? {
        if (!onMainOwnerThread() || closed || !ready) return null
        val outcome = pendingOutcome
        if (ownedAccountLease.isTerminationRequested() || !host.isOperational()) {
            pendingOutcome = null
            poison()
            return outcome
        }
        outcome ?: return null
        pendingOutcome = null
        return outcome
    }

    fun close(): Boolean {
        if (!onMainOwnerThread()) {
            ownedAccountLease.requestTermination()
            return false
        }
        if (closed) return false
        closed = true
        ready = false
        pendingOutcome = null
        val hostClosed = host.close()
        val relayClosed = relay.close()
        val accountClosed = ownedAccountLease.requestTermination()
        return hostClosed && relayClosed && accountClosed
    }

    private fun onActivation(
        action: NativeTvTitleWatchedViewAction,
        focusedActionId: String,
        remoteKey: String,
        unwatchAcknowledged: Boolean
    ) {
        if (!onMainOwnerThread()) {
            ownedAccountLease.requestTermination()
            return
        }
        if (ownedAccountLease.isTerminationRequested() ||
            closed || !ready || pendingOutcome != null
        ) {
            poison()
            return
        }
        val outcome = host.activate(
            action = action,
            focusedActionId = focusedActionId,
            remoteKey = remoteKey,
            unwatchAcknowledged = unwatchAcknowledged
        )
        pendingOutcome = outcome
        if (!host.isOperational()) {
            ownedAccountLease.requestTermination()
        }
    }

    private fun poison() {
        if (closed) return
        closed = true
        ready = false
        pendingOutcome = null
        host.close()
        relay.close()
        ownedAccountLease.requestTermination()
    }

    private fun onMainOwnerThread(): Boolean =
        Thread.currentThread() === ownerThread &&
            Looper.myLooper() === Looper.getMainLooper()
}
