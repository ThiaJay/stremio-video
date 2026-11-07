package org.pss.stremio.tv

/**
 * One-time, owner-thread relay used to compose a title-action host binding
 * before its real Android view exists.
 *
 * A delegate may be attached exactly once. Rebinding, delegate refusal or a
 * delegate exception permanently poisons the relay so another view cannot take
 * over an already-issued host generation.
 */
class NativeMobileTitleWatchedViewPortRelay : NativeMobileTitleWatchedViewPort {
    private val ownerThread = Thread.currentThread()
    private var delegate: NativeMobileTitleWatchedViewPort? = null
    private var closed = false

    fun bind(view: NativeMobileTitleWatchedViewPort): Boolean {
        if (!onOwnerThread() || closed) return false
        if (delegate != null || view === this) return poison()
        delegate = view
        return true
    }

    override fun replaceTitleActions(
        actions: List<NativeMobileTitleWatchedViewAction>
    ): Boolean {
        if (!onOwnerThread() || closed) return false
        val target = delegate ?: return false
        val accepted = call { target.replaceTitleActions(actions) }
        if (!accepted) return poison()
        return true
    }

    override fun clearTitleActions(): Boolean {
        if (!onOwnerThread() || closed) return false
        val target = delegate ?: return false
        val accepted = call { target.clearTitleActions() }
        if (!accepted) return poison()
        return true
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        val target = delegate
        delegate = null
        return target == null || call { target.clearTitleActions() }
    }

    private fun poison(): Boolean {
        if (!closed) {
            closed = true
            val target = delegate
            delegate = null
            if (target != null) call { target.clearTitleActions() }
        }
        return false
    }

    private fun call(block: () -> Boolean): Boolean = try {
        block()
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}

/** Android TV counterpart with the same one-time, fail-closed semantics. */
class NativeTvTitleWatchedViewPortRelay : NativeTvTitleWatchedViewPort {
    private val ownerThread = Thread.currentThread()
    private var delegate: NativeTvTitleWatchedViewPort? = null
    private var closed = false

    fun bind(view: NativeTvTitleWatchedViewPort): Boolean {
        if (!onOwnerThread() || closed) return false
        if (delegate != null || view === this) return poison()
        delegate = view
        return true
    }

    override fun replaceTitleActions(
        actions: List<NativeTvTitleWatchedViewAction>
    ): Boolean {
        if (!onOwnerThread() || closed) return false
        val target = delegate ?: return false
        val accepted = call { target.replaceTitleActions(actions) }
        if (!accepted) return poison()
        return true
    }

    override fun clearTitleActions(): Boolean {
        if (!onOwnerThread() || closed) return false
        val target = delegate ?: return false
        val accepted = call { target.clearTitleActions() }
        if (!accepted) return poison()
        return true
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        val target = delegate
        delegate = null
        return target == null || call { target.clearTitleActions() }
    }

    private fun poison(): Boolean {
        if (!closed) {
            closed = true
            val target = delegate
            delegate = null
            if (target != null) call { target.clearTitleActions() }
        }
        return false
    }

    private fun call(block: () -> Boolean): Boolean = try {
        block()
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}
