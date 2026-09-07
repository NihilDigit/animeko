/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.openani.mediamp.io.SeekableInput
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Where [RangeStreamInput] and [SequentialDownloader] get their bytes.
 *
 * [VariantReader] is the production implementation; the indirection is what
 * lets the transport be tested without a PikPak account.
 */
internal interface RangeSource {
    suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (ByteReadChannel) -> T,
    ): T
}

/**
 * Serves one remote file to the player over HTTP range requests.
 *
 * This is a transport and nothing else. It has no download policy: it does not
 * decide what the file is worth keeping, it writes nothing to disk, and it
 * fetches only what the read position implies. PikPak is an online source, so
 * the player is the one that knows what it will read next, and every playback
 * fault this replaced came from an engine-side policy layer second-guessing it.
 *
 * Bytes live in an in-memory cache of fixed [unitSize] slots keyed by their
 * offset, capped at [memoryCapBytes] and evicted least-recently-used. A cache
 * rather than a contiguous window because mpv opens a Matroska file by reading
 * the head, seeking to the tail for the Cues and seeking back: a window would
 * throw away one end each time, and a small backward seek would refetch.
 *
 * [concurrency] fetches run at once, each over its own range request. One
 * fetch covers [unitSize] from a start or a seek and twice that once
 * [wideBlockThresholdBytes] ahead of the read position is in hand, so a cold
 * open spreads over all connections while a settled stream pays half the
 * per-request latency.
 */
internal class RangeStreamInput(
    private val source: RangeSource,
    override val size: Long,
    private val concurrency: Int,
    private val logTag: String,
    parentCoroutineContext: CoroutineContext,
    private val unitSize: Long = UNIT_SIZE,
    private val readAheadBytes: Long = READ_AHEAD_BYTES,
    private val memoryCapBytes: Long = MEMORY_CAP_BYTES,
    private val wideBlockThresholdBytes: Long = WIDE_BLOCK_THRESHOLD_BYTES,
) : SeekableInput {
    private val logger = logger<RangeStreamInput>()
    private val scope = CoroutineScope(parentCoroutineContext + SupervisorJob(parentCoroutineContext[Job]))

    private val lock = SynchronizedObject()

    /**
     * Cached slots, least recently used first. Insertion order is the LRU
     * order: a hit removes and reinserts.
     */
    private val cache = LinkedHashMap<Int, ByteArray>()
    private var cachedBytes = 0L

    /** Slot -> the fetch that will fill it. One fetch may own several slots. */
    private val inFlight = HashMap<Int, Fetch>()

    /**
     * Bumped whenever a worker or a reader could have new work to see: a
     * completed fetch, a seek, a close. Waiters park on it so they cannot miss
     * a wake-up landing between their check and their suspend.
     */
    private val revision = MutableStateFlow(0L)

    @Volatile
    private var readPosition = 0L

    @Volatile
    private var closed = false

    /** The failure that made this input unusable, if any. Reported to every reader. */
    @Volatile
    private var failure: Throwable? = null

    private val delivered = atomic(0L)

    /** Bytes the CDN has handed over, monotonic. The basis of the speed readout. */
    val deliveredBytes: Long get() = delivered.value

    // Set by seekTo, consumed by the read that first serves bytes at the new
    // position. The point of the log line is the interval a viewer feels, so it
    // ends at the byte and not at the fetch.
    private var seekTarget = -1L
    private var seekMark: TimeMark? = null

    override val position: Long get() = readPosition

    override val bytesRemaining: Long get() = (size - readPosition).coerceAtLeast(0)

    init {
        require(concurrency >= 1) { "concurrency must be >= 1, got $concurrency" }
        repeat(concurrency) { scope.launch { runWorker() } }
    }

    override fun seekTo(position: Long) {
        require(position >= 0) { "position must be >= 0, got $position" }
        checkOpen()
        if (position == readPosition) return
        readPosition = position
        val windowEnd = (position + readAheadBytes).coerceAtMost(size)
        // Fetches the new position does not want are cancelled rather than left
        // to finish. Their partial bytes are lost, which is the point: what the
        // reader needs back is the connection, and after a seek every one of
        // them is moving bytes nobody will look at.
        val victims = synchronized(lock) {
            seekTarget = position
            seekMark = TimeSource.Monotonic.markNow()
            inFlight.values.distinct()
                .filter { it.endOffset <= position || it.startOffset >= windowEnd }
                .onEach { it.cancelled = true }
        }
        for (fetch in victims) fetch.job?.cancel()
        if (victims.isNotEmpty()) {
            logger.debug { "[$logTag] seek to $position cancelled ${victims.size} in-flight block(s)" }
        }
        bump()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkOpen()
        val pos = readPosition
        if (pos >= size) return -1
        if (length == 0) return 0

        val slot = slotOf(pos)
        val data = awaitSlot(slot)
        val within = (pos - slot.toLong() * unitSize).toInt()
        val available = minOf(minOf(data.size - within, length).toLong(), size - pos).toInt()
        data.copyInto(buffer, offset, within, within + available)
        readPosition = pos + available
        // Crossing into the next block is what moves the read-ahead window and
        // makes the block behind it evictable, so it is also the only moment a
        // worker parked against the memory cap could have work again. Without
        // this the stream stops the first time the cache fills.
        if (slotOf(readPosition) != slot) bump()
        reportSeekLatency(pos)
        return available
    }

    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        synchronized(lock) {
            cache.clear()
            cachedBytes = 0
            inFlight.values.distinct().forEach { it.done.complete(Unit) }
            inFlight.clear()
        }
        bump()
    }

    ///////////////////////////////////////////////////////////////////////////
    // reading
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Blocks the calling thread until the slot is cached, then returns it.
     *
     * Blocking is what [SeekableInput] asks for: [read] is not a suspending
     * function, and the players call it from their own decode thread. The
     * loop re-checks rather than trusting one wake-up, because a fetch this
     * reader was waiting on can be cancelled by a seek and has to be reclaimed.
     */
    private fun awaitSlot(slot: Int): ByteArray {
        while (true) {
            failure?.let { throw IOException("[$logTag] stream failed", it) }
            checkOpen()
            var waiter: CompletableDeferred<Unit>? = null
            var seen = 0L
            synchronized(lock) {
                hit(slot)?.let { return it }
                seen = revision.value
                waiter = inFlight[slot]?.done
            }
            val pending = waiter
            runBlocking {
                // join rather than await: a fetch cancelled by a seek completes
                // its deferred exceptionally, and that is a reason to look
                // again, not to fail the read.
                if (pending != null) pending.join() else revision.first { it != seen }
            }
        }
    }

    private fun reportSeekLatency(servedAt: Long) {
        var mark: TimeMark? = null
        synchronized(lock) {
            if (seekTarget != servedAt) return
            mark = seekMark
            seekTarget = -1
            seekMark = null
        }
        val since = mark ?: return
        logger.info { "[$logTag] seek to $servedAt: first byte after ${since.elapsedNow()}" }
    }

    ///////////////////////////////////////////////////////////////////////////
    // fetching
    ///////////////////////////////////////////////////////////////////////////

    internal inner class Fetch(val firstSlot: Int, val slotCount: Int) {
        val done = CompletableDeferred<Unit>()

        @Volatile
        var job: Job? = null

        @Volatile
        var cancelled = false

        val startOffset: Long get() = firstSlot.toLong() * unitSize
        val endOffset: Long get() = (startOffset + slotCount * unitSize).coerceAtMost(size)
    }

    private suspend fun runWorker() {
        while (currentCoroutineContext().isActive) {
            val seen = revision.value
            val fetch = synchronized(lock) { claimNext() }
            if (fetch == null) {
                revision.first { it != seen }
                continue
            }
            // Its own child job so a seek can cancel this block without taking
            // the worker down with it. Started lazily and registered first: a
            // seek landing in between would otherwise find no job to cancel.
            val job = scope.launch(start = CoroutineStart.LAZY) { runFetch(fetch) }
            val startIt = synchronized(lock) {
                if (fetch.cancelled) false else { fetch.job = job; true }
            }
            if (!startIt) {
                abandon(fetch)
                continue
            }
            try {
                job.start()
                job.join()
            } finally {
                // Not inside runFetch: a seek landing between the registration
                // above and start() cancels the lazy job before its body ever
                // runs, so the body is not a place the cleanup can live. After
                // a fetch that did complete this is a no-op.
                abandon(fetch)
            }
        }
    }

    /**
     * Takes the first slot inside the read-ahead window that is neither cached
     * nor already being fetched, and claims it plus, when the stream has
     * settled, the slot after it.
     *
     * Must be called under [lock].
     */
    private fun claimNext(): Fetch? {
        if (closed || failure != null) return null
        val pos = readPosition
        if (pos >= size) return null
        val windowEnd = (pos + readAheadBytes).coerceAtMost(size)
        val firstSlot = slotOf(pos)
        val lastSlot = slotOf(windowEnd - 1)

        var target = -1
        for (slot in firstSlot..lastSlot) {
            if (slot in cache || slot in inFlight) continue
            target = slot
            break
        }
        if (target == -1) return null
        if (!makeRoom()) return null

        val slotCount = if (wide(pos) && target < lastSlot && (target + 1) !in cache && (target + 1) !in inFlight) 2 else 1
        val fetch = Fetch(target, slotCount)
        for (slot in target until target + slotCount) inFlight[slot] = fetch
        return fetch
    }

    /**
     * Whether the stream has enough ahead of the read position to be worth
     * asking for double-sized blocks. Everything between the read position and
     * [wideBlockThresholdBytes] past it must already be cached or on its way,
     * which a start and a seek both undo, so both fall back to single slots.
     *
     * Must be called under [lock].
     */
    private fun wide(pos: Long): Boolean {
        val end = (pos + wideBlockThresholdBytes).coerceAtMost(size)
        for (slot in slotOf(pos)..slotOf(end - 1)) {
            if (slot !in cache && slot !in inFlight) return false
        }
        return true
    }

    /**
     * Frees enough of the cache to stay under [memoryCapBytes], dropping the
     * least recently used slot that lies outside the current read-ahead window.
     *
     * Slots inside the window are exempt because evicting them is what the
     * fetch about to be claimed would have to undo. When nothing outside the
     * window is left, the window is already full and there is nothing worth
     * claiming, which is what the `false` return says.
     *
     * Must be called under [lock].
     */
    private fun makeRoom(): Boolean {
        val budget = memoryCapBytes
        val pos = readPosition
        val windowEnd = (pos + readAheadBytes).coerceAtMost(size)
        val firstSlot = slotOf(pos)
        val lastSlot = slotOf((windowEnd - 1).coerceAtLeast(pos))
        while (cachedBytes + inFlightBytes() + unitSize > budget) {
            val victim = cache.keys.firstOrNull { it < firstSlot || it > lastSlot } ?: return false
            cachedBytes -= (cache.remove(victim)?.size ?: 0).toLong()
        }
        return true
    }

    private fun inFlightBytes(): Long = inFlight.size.toLong() * unitSize

    private suspend fun runFetch(fetch: Fetch) {
        val offset = fetch.startOffset
        val length = fetch.endOffset - offset
        val startedAt = TimeSource.Monotonic.markNow()
        var attempt = 0
        while (true) {
            try {
                val bytes = readRange(offset, length, priorityFor(offset))
                complete(fetch, bytes)
                logger.debug {
                    "[$logTag] block $offset (+${bytes.size}) in ${startedAt.elapsedNow()}"
                }
                return
            } catch (e: CancellationException) {
                // Releasing the slots and waking the waiters is the worker's
                // job, in a finally around join(); see abandon.
                throw e
            } catch (e: Throwable) {
                attempt++
                if (attempt < MAX_ATTEMPTS) continue
                // The link refresh a 403 needs happens inside the SDK's
                // RangeReader, below this loop; what is left here is the case
                // where even that did not help.
                releaseSlots(fetch)
                failure = e
                fetch.done.completeExceptionally(e)
                bump()
                return
            }
        }
    }

    private suspend fun readRange(offset: Long, length: Long, priority: Int): ByteArray {
        val buffer = ByteArray(length.toInt())
        var filled = 0
        source.read(offset, length, priority) { channel ->
            while (filled < buffer.size) {
                // readAvailable hands over already-buffered bytes without
                // suspending, so without this a cancelled fetch could run to
                // the end of its block before reaching a cancellation point.
                currentCoroutineContext().ensureActive()
                val n = channel.readAvailable(buffer, filled, buffer.size - filled)
                if (n == -1) break
                filled += n
            }
        }
        if (filled != buffer.size) {
            // Not a transport hiccup: RangeReader resumes those itself. It
            // means the remote resource is shorter than the recorded length,
            // i.e. the wrong bytes.
            throw IOException("[$logTag] got $filled of ${buffer.size} bytes at $offset")
        }
        return buffer
    }

    /** Higher for the block the reader is blocked on, so it wins a contended connection slot. */
    private fun priorityFor(offset: Long): Int {
        val pos = readPosition
        return if (offset <= pos && offset + unitSize > pos) BLOCKING_PRIORITY else READ_AHEAD_PRIORITY
    }

    private fun complete(fetch: Fetch, bytes: ByteArray) {
        synchronized(lock) {
            for (i in 0 until fetch.slotCount) {
                val slot = fetch.firstSlot + i
                if (inFlight[slot] === fetch) inFlight.remove(slot)
                val from = i * unitSize.toInt()
                if (from >= bytes.size) continue
                val to = minOf(bytes.size, from + unitSize.toInt())
                put(slot, bytes.copyOfRange(from, to))
            }
        }
        delivered.addAndGet(bytes.size.toLong())
        fetch.done.complete(Unit)
        bump()
    }

    /**
     * Gives up a claimed fetch that will never deliver bytes.
     *
     * Idempotent, and safe to run after [complete] or after the failure path:
     * the slot removal only touches slots this fetch still owns, and
     * `completeExceptionally` returns false once the deferred is settled, so a
     * second pass changes nothing. That is what lets it run unconditionally on
     * every exit path of a claimed fetch, which is the only way to cover the
     * case where the fetch body never ran at all.
     */
    private fun abandon(fetch: Fetch) {
        releaseSlots(fetch)
        val abandoned = fetch.done.completeExceptionally(
            CancellationException("[$logTag] fetch at ${fetch.startOffset} was abandoned"),
        )
        // Both halves matter: the slots are free for claimNext again, and a
        // reader parked on this fetch's done has to be told to look again.
        if (abandoned) bump()
    }

    private fun releaseSlots(fetch: Fetch) = synchronized(lock) {
        for (i in 0 until fetch.slotCount) {
            val slot = fetch.firstSlot + i
            if (inFlight[slot] === fetch) inFlight.remove(slot)
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // cache
    ///////////////////////////////////////////////////////////////////////////

    private fun slotOf(offset: Long): Int = (offset / unitSize).toInt()

    /** Must be called under [lock]. Reinserting is what makes the map's order an LRU order. */
    private fun hit(slot: Int): ByteArray? {
        val data = cache.remove(slot) ?: return null
        cache[slot] = data
        return data
    }

    /** Must be called under [lock]. */
    private fun put(slot: Int, data: ByteArray) {
        val previous = cache.put(slot, data)
        cachedBytes += data.size - (previous?.size ?: 0)
    }

    private fun bump() {
        revision.update { it + 1 }
    }

    private fun checkOpen() {
        if (closed) throw IOException("[$logTag] input is closed")
    }

    /** Cached bytes held right now. For tests asserting the memory cap. */
    internal val cachedBytesForTest: Long get() = synchronized(lock) { cachedBytes }

    /** Slots currently claimed by a fetch. A leaked claim shows up here as a count that never falls. */
    internal val inFlightSlotsForTest: Int get() = synchronized(lock) { inFlight.size }

    // The window a seek has to hit to cancel a fetch before its body runs is a
    // few instructions wide, so the abandonment path is driven directly rather
    // than raced for.
    internal fun claimForTest(): Fetch? = synchronized(lock) { claimNext() }

    internal fun abandonForTest(fetch: Fetch) = abandon(fetch)

    internal companion object {
        /**
         * Cache and fetch granularity. A range request costs roughly 200 ms
         * before its first byte, which at the ~0.2 MB/s one PikPak connection
         * sustains is the time it takes to move 40 KiB, so a quarter of a
         * megabyte keeps the latency a rounding error while still splitting a
         * cold open across all eight connections.
         */
        const val UNIT_SIZE: Long = 256L * 1024

        /** How far past the read position the stream is kept filled. In memory only. */
        const val READ_AHEAD_BYTES: Long = 64L * 1024 * 1024

        const val MEMORY_CAP_BYTES: Long = 64L * 1024 * 1024

        /** Bytes ahead that must be in hand before blocks double in size. */
        const val WIDE_BLOCK_THRESHOLD_BYTES: Long = 32L * 1024 * 1024

        /** Attempts per block. RangeReader already retries transport failures and refreshes expired links. */
        const val MAX_ATTEMPTS = 3

        private const val BLOCKING_PRIORITY = 100
        private const val READ_AHEAD_PRIORITY = 10
    }
}
