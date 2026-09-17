/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.CdnConnectionStats
import io.github.nihildigit.pikpak.HttpRetryStats
import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.RangeAttempt
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.absoluteValue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What every CDN request on one file cost, and how far apart those costs are.
 *
 * Aggregate throughput cannot say whether eight connections are eight even ones
 * or seven fast ones and one crawling, and an in-order consumer waits for the
 * slowest of the eight — so the number that predicts a playback stall is the
 * high percentile of the per-request clock, not the mean. Nothing recorded that
 * until now: [ThroughputLog] sums, and the slow-range warning in [CloudFile]
 * only fires past a fixed five seconds, which on a long route is both too late
 * and too rare.
 *
 * The summary is built to settle, from one capture, which lever is worth
 * pulling. Each column answers one question that has an opposite answer:
 *
 *  - `headers` against `ttfb`: the two were measured identical to the
 *    millisecond, so the CDN never holds a response open and every bit of the
 *    wait happens before it answers at all
 *  - `conn` (connections built versus taken): what is left of that wait is
 *    either a handshake or the server being slow, and a handshake is three to
 *    four round trips where a pooled connection is one
 *  - by request size: the per-request overhead does not scale with size, so the
 *    duty cycle of a large request is the ceiling a larger block would buy
 *  - by priority: the gate hands out slots by priority, so if blocking reads
 *    are no faster than read-ahead the queue is below the gate and reordering
 *    there cannot help
 *  - `hidden retries`: a retry inside the HTTP layer lands in the same
 *    milliseconds as a slow route and calls for the opposite fix
 *
 * The outlier rule is deliberately the same shape as the abort rule it exists
 * to calibrate: a request is an outlier when it takes a multiple of what the
 * rest of this file is currently taking.
 */
internal class RangeAttemptLog(
    private val name: String,
    private val interval: Duration = REPORT_INTERVAL,
    /**
     * The client's running retry tally, or null before one exists.
     *
     * Reported as a delta per window, because a retry the HTTP layer performs
     * on its own lands in the same place a slow route does: inside one
     * request's time to headers.
     */
    private val httpRetries: () -> HttpRetryStats? = { null },
    /** Connection reuse as it stands now; also a delta per window. Global to the process. */
    private val connections: () -> CdnConnectionStats = { CdnConnectionStats.snapshot() },
    private val report: (String) -> Unit,
) {
    private val lock = SynchronizedObject()

    // A ring rather than everything seen: an hour of playback is tens of
    // thousands of requests, and a threshold is set from what the link is doing
    // now, not from what it did when the episode started. Parallel arrays
    // because every percentile below is taken over a filtered subset, and one
    // array of objects would allocate a window's worth on every report.
    private val totalMillis = LongArray(WINDOW)
    private val headerMillis = LongArray(WINDOW)
    private val bodyRates = LongArray(WINDOW)
    private val sizes = LongArray(WINDOW)
    private val priorities = IntArray(WINDOW)
    private var cursor = 0
    private var filled = 0

    // Completion order, not issue order: an attempt is reported when it ends,
    // and eight of them are in flight at once. The offsets are the point --
    // whether the reads walk the file or jump around it -- and those are
    // unaffected; only the sequence is approximate.
    private val trace = StringBuilder()
    private var traceLabel = OPENING_LABEL
    private var traceRequests = 0
    private var traceBytes = 0L
    private var traceMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var traceReported = false

    /** The furthest a read that follows playback has started, or -1 before there was one. See [armTraceOnJumpLocked]. */
    private var furthestPlaybackStart = -1L

    private val outcomes = HashMap<RangeAttempt.Outcome, Int>()
    private var mark: TimeSource.Monotonic.ValueTimeMark? = null
    private var sinceReport = 0
    private var retriesAtLastReport = HttpRetryStats()
    private var connectionsAtLastReport = CdnConnectionStats()

    fun record(attempt: RangeAttempt) {
        // Both lines are built under the lock and reported outside it: report
        // reaches a logger, and holding the lock across that would serialise
        // every connection on this file behind whatever the logger does.
        val lines = synchronized(lock) {
            outcomes[attempt.outcome] = (outcomes[attempt.outcome] ?: 0) + 1
            // A jump cuts the running trace short, so whatever it holds is
            // reported before the new one overwrites it.
            val cutShort = armTraceOnJumpLocked(attempt)
            traceLocked(attempt)

            // Only a request that delivered bytes has a rate; one that did not
            // is an outcome, and counting it as zero would drag every low
            // percentile down to zero and hide the distribution being measured.
            val rate = attempt.bytesPerSecondAfterFirstByte
            if (rate > 0) {
                totalMillis[cursor] = attempt.duration.inWholeMilliseconds
                headerMillis[cursor] = attempt.timeToHeaders?.inWholeMilliseconds ?: 0
                bodyRates[cursor] = rate
                sizes[cursor] = attempt.delivered
                priorities[cursor] = attempt.priority
                cursor = (cursor + 1) % WINDOW
                if (filled < WINDOW) filled++
            }
            sinceReport++

            val elapsed = (mark ?: TimeSource.Monotonic.markNow().also { mark = it }).elapsedNow()
            val outlier = if (isOutlier(attempt)) "[pikpak] $name attempt $attempt" else null
            // Either bound alone leaves a hole. Reads come in bursts with idle
            // between them, so a burst that starts and ends inside one interval
            // reports nothing at all — which is how the first capture came back
            // with outlier lines and no distribution. A count bound closes that;
            // the time bound still covers a slow trickle that never reaches it.
            val due = elapsed >= interval || sinceReport >= REPORT_EVERY
            val summary = if (due) renderSummary(elapsed).also { resetLocked() } else null
            // A trace is worth nothing once it is stale, so a summary falling
            // due flushes whatever of it has accumulated.
            listOfNotNull(cutShort, takeTraceLocked(due), outlier, summary)
        }
        lines.forEach(report)
    }

    /**
     * Starts a fresh trace when the blocking read position jumps, i.e. a seek.
     *
     * Measured: a seek costs about twenty seconds where a cold open costs
     * seven, and nothing in the distribution accounts for it -- eight-way
     * concurrency at a 1.6 s median cannot sum to twenty. So the question is
     * the same one the opening trace answered, asked at a seek: how many
     * requests and how many bytes the player takes before it resumes.
     *
     * A seek is inferred rather than signalled because the reader is handed a
     * position, not an event.
     *
     * Read-ahead counts, not just the blocking read. The first capture with
     * this trace in it came back with no seek at all because after the opening
     * there are almost no blocking reads: the priority is fixed when a fetch is
     * issued, and read-ahead runs far enough ahead that by the time playback
     * reaches a block it was already claimed at the lower priority. Read-ahead
     * follows the read position, so its own offsets jump at a seek too, and it
     * is the stream that is always there.
     *
     * The cache fetcher is excluded because it walks the file on its own
     * schedule and would fire this constantly. Nothing fires while a trace is
     * still running, because the opening itself jumps -- tail cues, then
     * header, then the resume point.
     *
     * Returns the previous trace if this jump cut it short. Must be called
     * under [lock].
     */
    private fun armTraceOnJumpLocked(attempt: RangeAttempt): String? {
        if (attempt.priority < PikPakStreamReader.READ_AHEAD_PRIORITY) return null
        // Against the furthest offset reached, not against the previous one.
        // Requests complete out of order across a read-ahead window, so
        // consecutive offsets differ by up to a whole window even when nothing
        // moved; distance from the furthest is bounded by the window.
        val furthest = furthestPlaybackStart
        if (furthest < 0 || (attempt.start - furthest).absoluteValue < JUMP_BYTES) {
            if (attempt.start > furthest) furthestPlaybackStart = attempt.start
            return null
        }
        furthestPlaybackStart = attempt.start

        // Only a trace still running has anything to flush. Re-rendering one that was
        // already printed is how the same opening came out twice, a second apart and
        // word for word but for the clock -- which then read as two readers on one file
        // and cost an afternoon.
        val cutShort = if (traceReported) null else renderTraceLocked()
        trace.setLength(0)
        traceLabel = "${attempt.start / 1024 / 1024} MiB"
        traceRequests = 0
        traceBytes = 0
        traceMark = null
        traceReported = false
        return cutShort
    }

    /**
     * Records the offset and size of one request of the running trace.
     *
     * The doubt this exists to settle at a cold open: several megabytes were
     * measured read before the first frame, and a container header does not
     * need several megabytes. If that is the demuxer probing, it is a cost no
     * amount of connection or block tuning touches, and the cheapest way to
     * tell a probe from a sequential header read is the offsets themselves.
     *
     * Must be called under [lock].
     */
    private fun traceLocked(attempt: RangeAttempt) {
        if (traceReported || traceRequests >= TRACE_REQUESTS) return
        if (traceMark == null) traceMark = TimeSource.Monotonic.markNow()
        traceRequests++
        traceBytes += attempt.delivered
        trace.append(' ')
            .append(attempt.start / 1024)
            .append('+')
            .append((attempt.requested ?: attempt.delivered) / 1024)
        if (attempt.priority >= PikPakStreamReader.BLOCKING_PRIORITY) trace.append('!')
    }

    /** Must be called under [lock]. */
    private fun takeTraceLocked(summaryDue: Boolean): String? {
        if (traceReported || traceRequests == 0) return null
        if (!summaryDue && traceRequests < TRACE_REQUESTS) return null
        traceReported = true
        return renderTraceLocked()
    }

    /** Must be called under [lock]. */
    private fun renderTraceLocked(): String? {
        if (traceRequests == 0) return null
        // KiB, and a `!` on the ones playback was blocked in. Absolute offsets
        // rather than deltas: a seek table sends the demuxer to the end of the
        // file, and a delta of most of the file is harder to recognise than the
        // offset itself. The byte total and the clock are what a seek is
        // judged on -- twenty seconds for a megabyte and twenty seconds for
        // twenty megabytes call for opposite fixes.
        val took = traceMark?.elapsedNow() ?: Duration.ZERO
        return "[pikpak] $name $traceLabel: $traceRequests requests, " +
                "${traceBytes / 1024} KiB in $took, KiB offset+size:$trace"
    }

    /**
     * Whether this request took long enough beside its peers to be worth a line.
     *
     * Wall clock, not throughput. The first capture settled which axis this is:
     * a request that looks slow is one that waited, not one that transferred
     * slowly. A rule on the body rate flags the requests whose bytes came
     * slightly slower and misses every one that sat for seconds and then
     * transferred at full speed, which is the shape being hunted.
     *
     * Needs [MIN_SAMPLES] before it says anything, or a cold open would report
     * each of its own first requests against the two before them. A request
     * that delivered nothing is always worth a line.
     *
     * Must be called under [lock].
     */
    private fun isOutlier(attempt: RangeAttempt): Boolean {
        if (attempt.delivered == 0L) return true
        if (filled < MIN_SAMPLES) return false
        return attempt.duration.inWholeMilliseconds > percentileLocked(totalMillis, 50) * OUTLIER_FACTOR
    }

    /** Must be called under [lock]. */
    private fun renderSummary(elapsed: Duration): String = buildString {
        append("[pikpak] $name $filled requests over $elapsed")
        if (filled == 0) {
            append(", none delivered bytes; ${outcomes.entries.joinToString { "${it.key}=${it.value}" }}")
            return@buildString
        }
        append(", total ms")
        listOf(50, 75, 95, 99).forEach { p -> append(" p$p=${percentileLocked(totalMillis, p)}") }
        // Headers and first byte were measured equal to the millisecond, so
        // only one of them is worth a column; the split lives in `conn`.
        append("; headers ms")
        listOf(50, 95, 99).forEach { p -> append(" p$p=${percentileLocked(headerMillis, p)}") }
        append("; body kB/s")
        listOf(5, 50, 95).forEach { p -> append(" p$p=${percentileLocked(bodyRates, p) / 1024}") }

        // The two numbers a threshold gets read off. An in-order consumer waits
        // for the slowest request in flight, so p95/p50 is what the tail costs
        // it; the header share says whether shortening that tail means moving
        // bytes faster or not waiting to start.
        val median = percentileLocked(totalMillis, 50).coerceAtLeast(1)
        val tail = percentileLocked(totalMillis, 95).toDouble() / median
        val waiting = percentileLocked(headerMillis, 50).toDouble() / median
        append("; tail p95/p50=${oneDecimal(tail)}")
        append("; header share=${(waiting * 100).toLong()}%")

        appendSplit("size") { i -> "${sizes[i] / 1024}K" }
        appendSplit("prio") { i -> priorities[i].toString() }

        val conn = connections()
        (conn - connectionsAtLastReport).let { d ->
            append("; conn $d")
            if (d.opened > 0) append(" avg=${d.handshake / d.opened.toInt()}")
        }
        connectionsAtLastReport = conn

        httpRetries()?.let { now ->
            val d = now - retriesAtLastReport
            append(
                "; hidden retries 5xx=${d.serverErrors} 429=${d.rateLimited}" +
                        " transport=${d.transport} waited=${d.waited}",
            )
            retriesAtLastReport = now
        }
        append("; ${outcomes.entries.joinToString { "${it.key}=${it.value}" }}")
    }

    /**
     * Median request time and duty cycle per class of request, e.g. per size.
     *
     * The duty cycle — bytes moving over wall clock — is the whole point of the
     * size split: per-request overhead is the same for a small request and a
     * large one, so reading it off the sizes already in flight says what a
     * larger block would buy without having to ship one and measure again.
     *
     * A class with fewer than [MIN_CLASS_SAMPLES] is dropped rather than
     * printed: a handful of requests gives a median that moves with any one of
     * them, and an unstable number here would be read as a real difference.
     *
     * Must be called under [lock].
     */
    private inline fun StringBuilder.appendSplit(label: String, key: (Int) -> String) {
        val classes = (0 until filled).groupBy(key)
        if (classes.size < 2) return
        append("; by $label")
        classes.entries.sortedBy { it.key }.forEach { (cls, indices) ->
            if (indices.size < MIN_CLASS_SAMPLES) return@forEach
            val median = medianOf(indices.map { totalMillis[it] })
            val bodyMs = medianOf(indices.map { totalMillis[it] - headerMillis[it] })
            append(" $cls:n=${indices.size},p50=${median}ms,duty=${(bodyMs * 100 / median.coerceAtLeast(1))}%")
        }
    }

    private fun medianOf(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[(sorted.size - 1) / 2]
    }

    private fun oneDecimal(value: Double): Double = (value * 10).toLong() / 10.0

    /**
     * Nearest-rank percentile over the [filled] live entries of a ring.
     *
     * Sorts a copy on every call. At a few hundred entries and one call per
     * percentile per report interval that is far cheaper than keeping an
     * ordered structure correct under a ring's overwrites.
     *
     * Must be called under [lock].
     */
    private fun percentileLocked(ring: LongArray, percentile: Int): Long {
        if (filled == 0) return 0
        val sorted = ring.copyOfRange(0, filled).also { it.sort() }
        val rank = (percentile * sorted.size + 99) / 100 - 1
        return sorted[rank.coerceIn(0, sorted.size - 1)]
    }

    /**
     * Clears the counters but keeps the ring.
     *
     * The outcome tally belongs to the interval that just ended; the rate
     * window is what the outlier rule compares against and must survive the
     * report, or every interval would begin with [MIN_SAMPLES] requests that
     * cannot be judged.
     *
     * Must be called under [lock].
     */
    private fun resetLocked() {
        outcomes.clear()
        mark = null
        sinceReport = 0
    }

    private companion object {
        val REPORT_INTERVAL = 10.seconds

        /**
         * Requests after which a distribution is printed regardless of the
         * clock. Eight connections at a quarter megabyte make this about eight
         * megabytes of playback, so a stall gets several lines around it.
         */
        const val REPORT_EVERY = 32

        /** Requests kept for the distribution. About a minute of one file's reads. */
        const val WINDOW = 256

        /** What a trace that was not armed by a jump is called. */
        const val OPENING_LABEL = "opened"

        /**
         * Requests traced individually from a file's start or from a seek.
         *
         * Past the first few megabytes the read pattern is the scheduler's and
         * is already known; what is unknown is what the demuxer asks for before
         * it will report a duration.
         */
        const val TRACE_REQUESTS = 40

        /**
         * How far a read must land from the furthest one to count as a seek.
         *
         * Twice the reader's 32 MiB read-ahead window, which is the widest two
         * offsets can be apart without anything having moved. A seek shorter
         * than this reads as ordinary progress and is missed on purpose: it
         * lands inside the window already being fetched, which is the case
         * that costs nothing.
         */
        const val JUMP_BYTES = 2 * PikPakStreamReader.DEFAULT_READ_AHEAD_BYTES

        /** Below this the window says nothing about a spread; see [isOutlier]. */
        const val MIN_SAMPLES = 16

        /** Below this a per-class median is noise; see [appendSplit]. */
        const val MIN_CLASS_SAMPLES = 5

        /**
         * How many times the median request a request may take before it earns
         * a line.
         *
         * Two, measured: on a 200 ms route p50 is about a second and p95 about
         * 2.7 s, so a factor of three prints only what is already past p99 and
         * leaves the whole 1.5-3 s band — the requests an in-order reader
         * actually waits on — with no line to read.
         */
        const val OUTLIER_FACTOR = 2
    }
}
