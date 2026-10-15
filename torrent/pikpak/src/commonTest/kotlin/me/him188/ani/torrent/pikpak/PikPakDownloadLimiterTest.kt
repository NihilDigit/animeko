package me.him188.ani.torrent.pikpak

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PikPakDownloadLimiterTest {
    @Test
    fun `raising the limit admits waiting downloads and lowering waits for active ones`() = runTest {
        val limit = MutableStateFlow(1)
        val limiter = PikPakDownloadLimiter(limit, backgroundScope)
        val releases = List(4) { CompletableDeferred<Unit>() }
        var started = 0
        val jobs = releases.map { release ->
            launch { limiter.withSlot { started++; release.await() } }
        }
        runCurrent()
        assertEquals(1, started)
        limit.value = 3
        runCurrent()
        assertEquals(3, started)
        limit.value = 1
        runCurrent()
        releases[0].complete(Unit)
        releases[1].complete(Unit)
        runCurrent()
        assertEquals(3, started)
        releases[2].complete(Unit)
        runCurrent()
        assertEquals(4, started)
        releases[3].complete(Unit)
        jobs.forEach { it.join() }
    }

    @Test
    fun `cancelled waiters and failed downloads do not leak slots`() = runTest {
        val limiter = PikPakDownloadLimiter(MutableStateFlow(1), backgroundScope)
        val release = CompletableDeferred<Unit>()
        val active = launch { limiter.withSlot { release.await() } }
        runCurrent()
        val waiting = launch { limiter.withSlot { error("cancelled waiter started") } }
        runCurrent()
        waiting.cancelAndJoin()
        active.cancelAndJoin()
        assertFailsWith<IllegalStateException> { limiter.withSlot { error("download failed") } }
        assertEquals(42, limiter.withSlot { 42 })
    }
}
