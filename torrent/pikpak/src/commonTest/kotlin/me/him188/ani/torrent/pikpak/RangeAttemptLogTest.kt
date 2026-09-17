/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.HttpRetryStats
import io.github.nihildigit.pikpak.RangeAttempt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The percentiles this log prints are what a trickle-abort threshold will be
 * read off, and the capture that produces them costs a trip to the network the
 * fault only appears on. A rank that is one out, or a failed request counted as
 * zero bytes per second, would not look wrong in the log — it would just move
 * the threshold.
 */
class RangeAttemptLogTest {

    @Test
    fun `percentiles are nearest-rank over the delivering requests`() {
        val lines = mutableListOf<String>()
        // Reports on the first request, so each one below prints its own line
        // and the assertions can name which.
        val log = RangeAttemptLog("x.mkv", interval = Duration.ZERO) { lines += it }

        // 100 requests at 1..100 kB/s. Nearest-rank puts p50 at the 50th of
        // them, which is 50 kB/s; a rank computed with the usual off-by-one
        // lands on 49 or 51.
        (1..100).forEach { log.record(attempt(bytesPerSecond = it * 1024L)) }

        val last = lines.last()
        assertTrue("body kB/s p5=5 p50=50 p95=95;" in last, "expected p5/p50/p95 of 5/50/95 in: $last")
    }

    @Test
    fun `a request that delivered nothing does not drag the distribution to zero`() {
        val lines = mutableListOf<String>()
        val log = RangeAttemptLog("x.mkv", interval = Duration.ZERO) { lines += it }

        repeat(20) { log.record(attempt(bytesPerSecond = 100 * 1024L)) }
        log.record(attempt(bytesPerSecond = 0, delivered = 0, outcome = RangeAttempt.Outcome.Failed))

        val last = lines.last()
        assertTrue("body kB/s p5=100 " in last, "a failed request has no rate and must not count as one: $last")
        assertTrue("Failed=1" in last, "it is still an outcome and must be tallied: $last")
    }

    /**
     * Reads arrive in bursts with idle between them, so a burst that starts and
     * ends inside one report interval prints no distribution at all — which is
     * how the first live capture came back with outlier lines and nothing to
     * read a threshold off. The count bound is what closes that, and nothing
     * about a missing line says it is missing.
     */
    @Test
    fun `a burst shorter than the interval still reports a distribution`() {
        val lines = mutableListOf<String>()
        val log = RangeAttemptLog("x.mkv", interval = 1.seconds) { lines += it }

        repeat(40) { log.record(attempt(bytesPerSecond = 2048 * 1024L)) }

        assertEquals(1, lines.count { "requests over" in it }, "the count bound must fire on its own")
    }

    /**
     * The size split is what says whether a larger block is worth shipping, and
     * the duty cycle is the number it says it with: per-request overhead does
     * not scale with size, so a large request spends a larger share of its life
     * moving bytes. Getting that share backwards, or off by the header time,
     * would argue for the opposite change.
     */
    @Test
    fun `the size split reports each class duty cycle`() {
        val lines = mutableListOf<String>()
        val log = RangeAttemptLog("x.mkv", interval = 1.seconds) { lines += it }

        // Same 400 ms of overhead either way. 256 KiB at 512 kB/s spends 500 ms
        // on its body, so half its life; 512 KiB spends a full second, two
        // thirds. Sixteen of each: enough to clear MIN_CLASS_SAMPLES, and the
        // report the assertions read is the one REPORT_EVERY triggers.
        repeat(16) { log.record(attempt(bytesPerSecond = 512 * 1024L, delivered = 256L * 1024, ttfb = 400.milliseconds)) }
        repeat(16) { log.record(attempt(bytesPerSecond = 512 * 1024L, delivered = 512L * 1024, ttfb = 400.milliseconds)) }

        val summary = lines.last { "requests over" in it }
        assertTrue("256K:n=16,p50=900ms,duty=55%" in summary, "expected the small class in: $summary")
        assertTrue("512K:n=16,p50=1400ms,duty=71%" in summary, "expected the large class in: $summary")
    }

    /**
     * The retry tally is monotonic since the client was built, and what a
     * window needs is how many landed in it. Printing the running total instead
     * would rise forever and read as a worsening link.
     */
    @Test
    fun `hidden retries are reported per window, not cumulative`() {
        val lines = mutableListOf<String>()
        var retries = HttpRetryStats()
        val log = RangeAttemptLog("x.mkv", interval = Duration.ZERO, httpRetries = { retries }) { lines += it }

        retries = HttpRetryStats(serverErrors = 3)
        log.record(attempt(bytesPerSecond = 2048 * 1024L))
        assertTrue("hidden retries 5xx=3 " in lines.last(), "first window sees all three: ${lines.last()}")

        retries = HttpRetryStats(serverErrors = 5)
        log.record(attempt(bytesPerSecond = 2048 * 1024L))
        assertTrue("hidden retries 5xx=2 " in lines.last(), "second window sees only the new two: ${lines.last()}")
    }

    /**
     * The case the axis change was made for: a request that waits four seconds
     * and then transfers at full speed. Judged on its body rate it is the
     * healthiest request in the window; judged on the clock it is the one
     * holding playback up.
     */
    @Test
    fun `a request that waited is an outlier even though its body was fast`() {
        val lines = mutableListOf<String>()
        // Long interval and a count bound well above what this test records, so
        // only outlier lines land in the list.
        val log = RangeAttemptLog("x.mkv", interval = 1.seconds) { lines += it }

        // Below MIN_SAMPLES the window cannot judge a spread, and a cold open
        // would otherwise report each of its own first requests.
        repeat(8) { log.record(attempt(bytesPerSecond = 2048 * 1024L, ttfb = 100.milliseconds)) }
        assertEquals(0, lines.size, "too few samples to call anything an outlier")

        repeat(24) { log.record(attempt(bytesPerSecond = 2048 * 1024L, ttfb = 100.milliseconds)) }
        val settled = lines.size

        log.record(attempt(bytesPerSecond = 2048 * 1024L, ttfb = 100.milliseconds))
        assertEquals(settled, lines.size, "a request at the median is not an outlier")

        log.record(attempt(bytesPerSecond = 2048 * 1024L, ttfb = 4.seconds))
        assertEquals(settled + 1, lines.size, "four seconds of waiting is, whatever the body did")
    }

    /**
     * A file that never reaches the traced request count still has to print
     * what it did read.
     *
     * The trace exists to settle whether the megabytes a cold open spends are a
     * sequential header read or the demuxer probing, and a short open — the
     * case where the answer matters most — is exactly the one that never fills
     * the buffer. Waiting for a full trace would print nothing at all.
     */
    @Test
    fun `an opening shorter than the trace is still printed`() {
        val lines = mutableListOf<String>()
        val log = RangeAttemptLog("x.mkv", interval = 1.seconds) { lines += it }

        // Two blocking reads at the head and one read-ahead past them, then
        // enough filler to make the count bound fire the first summary.
        log.record(attempt(bytesPerSecond = 2048 * 1024L, start = 0))
        log.record(attempt(bytesPerSecond = 2048 * 1024L, start = 256L * 1024))
        log.record(attempt(bytesPerSecond = 2048 * 1024L, start = 4L * 1024 * 1024, priority = 10))
        repeat(29) { log.record(attempt(bytesPerSecond = 2048 * 1024L, start = 8L * 1024 * 1024)) }

        val opening = lines.single { "opened:" in it }
        // single() above is half the assertion: the trace is printed once and
        // never again, or a long session repeats its own opening forever.
        assertTrue(" 0+256! 256+256! 4096+256 " in opening, "offsets, sizes and blocking marks: $opening")
    }

    /**
     * A seek is inferred from the offsets alone, so what must not read as one
     * is here beside what must: requests complete out of order across a 32 MiB
     * read-ahead window, and judging each against the one before it would call
     * every reordered pair a seek.
     */
    @Test
    fun `a jump away from the furthest read opens a new trace`() {
        val lines = mutableListOf<String>()
        val log = RangeAttemptLog("x.mkv", interval = 1.seconds) { lines += it }

        // Thirty-two requests to get the opening out of the way, ending with a
        // read-ahead pair reordered by most of the window.
        repeat(30) { log.record(attempt(bytesPerSecond = 2048 * 1024L, start = it * 1024L * 1024, priority = 10)) }
        log.record(attempt(bytesPerSecond = 2048 * 1024L, start = 60L * 1024 * 1024, priority = 10))
        log.record(attempt(bytesPerSecond = 2048 * 1024L, start = 31L * 1024 * 1024, priority = 10))
        assertEquals(1, lines.count { "opened:" in it }, "the opening reported once: $lines")
        assertEquals(0, lines.count { "MiB:" in it }, "reordering inside the window is not a seek: $lines")

        // Then the seek itself, and the reads that follow it.
        log.record(attempt(bytesPerSecond = 2048 * 1024L, start = 600L * 1024 * 1024, priority = 10))
        log.record(attempt(bytesPerSecond = 2048 * 1024L, start = 600L * 1024 * 1024 + 256 * 1024, priority = 10))
        repeat(30) { log.record(attempt(bytesPerSecond = 2048 * 1024L, start = 604L * 1024 * 1024, priority = 10)) }

        val seek = lines.single { "600 MiB:" in it }
        assertTrue(" 614400+256 614656+256 " in seek, "offsets from the seek onwards: $seek")
        assertTrue("KiB in" in seek, "bytes and clock since the seek: $seek")
    }

    /**
     * A trace is printed once, whatever happens after it.
     *
     * The defect this exists for: a jump arriving after the opening had already
     * been reported re-rendered it and printed it again, a second apart and
     * word for word but for the clock. Two identical openings for one file read
     * as two readers sharing it, which is a conclusion about the transport
     * drawn entirely from a defect in the log.
     */
    @Test
    fun `an opening is printed once even if a seek follows it`() {
        val lines = mutableListOf<String>()
        val log = RangeAttemptLog("x.mkv", interval = 1.seconds) { lines += it }

        repeat(32) { log.record(attempt(bytesPerSecond = 2048 * 1024L, start = it * 256L * 1024, priority = 10)) }
        assertEquals(1, lines.count { "opened:" in it }, "the opening reported once: $lines")

        // The jump that used to reprint it.
        log.record(attempt(bytesPerSecond = 2048 * 1024L, start = 900L * 1024 * 1024, priority = 10))

        assertEquals(1, lines.count { "opened:" in it }, "the opening must not be printed again: $lines")
    }

    /**
     * An attempt whose body ran at [bytesPerSecond] once the first byte
     * arrived. The time to first byte is fixed and excluded from the rate, so
     * a test's numbers are the ones the log will read.
     */
    private fun attempt(
        bytesPerSecond: Long,
        delivered: Long = 256L * 1024,
        outcome: RangeAttempt.Outcome = RangeAttempt.Outcome.Complete,
        ttfb: Duration = 200.milliseconds,
        start: Long = 0,
        priority: Int = 100,
    ): RangeAttempt {
        val body = if (bytesPerSecond <= 0) Duration.ZERO else (delivered * 1000 / bytesPerSecond).milliseconds
        return RangeAttempt(
            start = start,
            requested = delivered,
            delivered = delivered,
            // Equal to the first byte, as every live capture measured them:
            // the CDN answers and sends in the same breath.
            timeToHeaders = if (delivered == 0L) null else ttfb,
            timeToFirstByte = if (delivered == 0L) null else ttfb,
            duration = ttfb + body,
            activeReads = 8,
            queuedReads = 0,
            priority = priority,
            outcome = outcome,
            bucketDuration = RangeAttempt.BUCKET,
            timeline = LongArray(RangeAttempt.BUCKETS),
        )
    }
}
