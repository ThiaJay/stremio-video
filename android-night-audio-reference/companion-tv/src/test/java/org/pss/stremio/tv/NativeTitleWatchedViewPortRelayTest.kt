package org.pss.stremio.tv

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTitleWatchedViewPortRelayTest {
    private class MobilePort(
        private val replaceResult: Boolean = true,
        private val clearResult: Boolean = true,
        private val throwOnReplace: Boolean = false
    ) : NativeMobileTitleWatchedViewPort {
        var replaces = 0
        var clears = 0

        override fun replaceTitleActions(
            actions: List<NativeMobileTitleWatchedViewAction>
        ): Boolean {
            replaces += 1
            if (throwOnReplace) error("synthetic mobile port failure")
            return replaceResult
        }

        override fun clearTitleActions(): Boolean {
            clears += 1
            return clearResult
        }
    }

    private class TvPort(
        private val replaceResult: Boolean = true,
        private val clearResult: Boolean = true,
        private val throwOnReplace: Boolean = false
    ) : NativeTvTitleWatchedViewPort {
        var replaces = 0
        var clears = 0

        override fun replaceTitleActions(
            actions: List<NativeTvTitleWatchedViewAction>
        ): Boolean {
            replaces += 1
            if (throwOnReplace) error("synthetic TV port failure")
            return replaceResult
        }

        override fun clearTitleActions(): Boolean {
            clears += 1
            return clearResult
        }
    }

    @Test fun mobileRelayRefusesBeforeBindingAndDelegatesAfterBinding() {
        val relay = NativeMobileTitleWatchedViewPortRelay()
        val port = MobilePort()

        assertFalse(relay.replaceTitleActions(emptyList()))
        assertTrue(relay.bind(port))
        assertTrue(relay.replaceTitleActions(emptyList()))
        assertTrue(port.replaces == 1)
    }

    @Test fun mobileRelayCloseClearsAndPermanentlyRevokes() {
        val relay = NativeMobileTitleWatchedViewPortRelay()
        val port = MobilePort()
        assertTrue(relay.bind(port))

        assertTrue(relay.close())
        assertTrue(port.clears == 1)
        assertFalse(relay.clearTitleActions())
        assertFalse(relay.bind(MobilePort()))
    }

    @Test fun mobileRelayRejectingPortPermanentlyPoisons() {
        val relay = NativeMobileTitleWatchedViewPortRelay()
        val port = MobilePort(replaceResult = false)
        assertTrue(relay.bind(port))

        assertFalse(relay.replaceTitleActions(emptyList()))
        assertTrue(port.clears == 1)
        assertFalse(relay.bind(MobilePort()))
        assertFalse(relay.close())
    }

    @Test fun mobileRelayThrowingPortPermanentlyPoisons() {
        val relay = NativeMobileTitleWatchedViewPortRelay()
        val port = MobilePort(throwOnReplace = true)
        assertTrue(relay.bind(port))

        assertFalse(relay.replaceTitleActions(emptyList()))
        assertTrue(port.clears == 1)
        assertFalse(relay.replaceTitleActions(emptyList()))
    }

    @Test fun tvRelayRefusesBeforeBindingAndDelegatesAfterBinding() {
        val relay = NativeTvTitleWatchedViewPortRelay()
        val port = TvPort()

        assertFalse(relay.replaceTitleActions(emptyList()))
        assertTrue(relay.bind(port))
        assertTrue(relay.replaceTitleActions(emptyList()))
        assertTrue(port.replaces == 1)
    }

    @Test fun tvRelayCloseClearsAndPermanentlyRevokes() {
        val relay = NativeTvTitleWatchedViewPortRelay()
        val port = TvPort()
        assertTrue(relay.bind(port))

        assertTrue(relay.close())
        assertTrue(port.clears == 1)
        assertFalse(relay.clearTitleActions())
        assertFalse(relay.bind(TvPort()))
    }

    @Test fun tvRelayRejectingPortPermanentlyPoisons() {
        val relay = NativeTvTitleWatchedViewPortRelay()
        val port = TvPort(replaceResult = false)
        assertTrue(relay.bind(port))

        assertFalse(relay.replaceTitleActions(emptyList()))
        assertTrue(port.clears == 1)
        assertFalse(relay.bind(TvPort()))
        assertFalse(relay.close())
    }

    @Test fun tvRelayThrowingPortPermanentlyPoisons() {
        val relay = NativeTvTitleWatchedViewPortRelay()
        val port = TvPort(throwOnReplace = true)
        assertTrue(relay.bind(port))

        assertFalse(relay.replaceTitleActions(emptyList()))
        assertTrue(port.clears == 1)
        assertFalse(relay.replaceTitleActions(emptyList()))
    }

    @Test fun foreignThreadCannotTakeOverOrCloseEitherRelay() {
        val mobile = NativeMobileTitleWatchedViewPortRelay()
        val tv = NativeTvTitleWatchedViewPortRelay()
        val mobileForeign = AtomicBoolean(true)
        val tvForeign = AtomicBoolean(true)
        val thread = Thread {
            mobileForeign.set(mobile.bind(MobilePort()) || mobile.close())
            tvForeign.set(tv.bind(TvPort()) || tv.close())
        }
        thread.start()
        thread.join()

        assertFalse(mobileForeign.get())
        assertFalse(tvForeign.get())
        assertTrue(mobile.bind(MobilePort()))
        assertTrue(tv.bind(TvPort()))
    }
}
