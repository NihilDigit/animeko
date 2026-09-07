/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger

/**
 * Decides which [SequentialDownloader]s of one engine may fetch right now.
 *
 * The line, not the CDN, is the scarce resource: eight connections already
 * saturate it, and one downloader opens eight. Nothing below this class knows
 * that. The SDK prioritises reads within one file — [RangeStreamInput] asks at
 * priority 100 or 10 and a downloader at 1, and `RangeReader`'s gate hands a
 * contended slot to the highest bidder — but that gate is per file. Across
 * files the shared CDN client's dispatcher is FIFO, so eighteen cache records
 * turning NORMAL at once simply queue ahead of whatever the player asks for.
 * This is where that is arbitrated.
 *
 * Two rules:
 *
 *  1. At most [limit] downloaders fetch at a time, FIFO by the time each asked
 *     to start.
 *  2. While a stream is open on some entry, the only downloader allowed to run
 *     is that entry's own. Every other one pauses at its next round boundary.
 *
 * Rule 2 deliberately leaves the second slot idle during playback. A second
 * downloader would take roughly half the line from the player, and the file
 * being watched is also the one whose cache record is worth finishing first.
 * The playing entry's downloader jumps the queue and keeps the front of it
 * after the stream closes, so an episode being watched is downloaded to
 * completion rather than handing the line back to whoever was queued first.
 */
internal class DownloadScheduler(
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val logger = logger<DownloadScheduler>()
    private val lock = SynchronizedObject()

    /**
     * Downloaders that want to fetch, oldest request first. A slot is here for
     * the whole of a download run, whether it is fetching or waiting; rank in
     * this list, not a held permit, is what decides. Permits were the rejected
     * alternative: a downloader that already holds one cannot be made to give
     * way when a stream opens, and re-evaluating rank at every round boundary
     * gives preemption for free.
     */
    private val contenders = mutableListOf<Slot>()

    /** Entries with at least one open [RangeStreamInput]. */
    private val playing = mutableSetOf<Slot>()

    /** Bumped on every state change; waiters re-evaluate rather than being handed a slot. */
    private val version = MutableStateFlow(0L)

    fun newSlot(name: String): Slot = Slot(name)

    inner class Slot internal constructor(private val name: String) {
        private var streams = 0

        /**
         * Suspends until this downloader may fetch its next round.
         *
         * Called at each round boundary, so a downloader that loses its turn
         * finishes the blocks in flight and claims no new ones.
         */
        suspend fun awaitTurn() {
            var deferred = false
            while (true) {
                // Read before the check: a state change racing with it bumps
                // the version and the wait below returns at once.
                val seen = version.value
                val allowed = synchronized(lock) {
                    if (contenders.none { it === this }) contenders += this
                    isAllowed(this)
                }
                if (allowed) {
                    if (deferred) logger.info { "[$name] download resumed" }
                    return
                }
                if (!deferred) {
                    deferred = true
                    logger.info { "[$name] download deferred: another download or a playing stream has the line" }
                }
                version.first { it != seen }
            }
        }

        /** The download run ended: completed, stopped, failed or closed. */
        fun leave() {
            synchronized(lock) { contenders.removeAll { it === this } }
            bump()
        }

        /**
         * A [RangeStreamInput] opened on this entry. Counted, not a flag: mpv
         * runs a second instance for seek previews, so two streams on one entry
         * and briefly two entries across an episode switch are both normal.
         */
        fun openStream() {
            synchronized(lock) {
                streams++
                if (streams == 1) playing += this
            }
            bump()
        }

        fun closeStream() {
            synchronized(lock) {
                if (streams == 0) return@synchronized
                streams--
                if (streams > 0) return@synchronized
                playing -= this
                // Moving to the front here, not on open, is what "keeps its
                // slot after the stream closes" means: while playing it was
                // exempt from the rank check anyway.
                val at = contenders.indexOfFirst { it === this }
                if (at > 0) {
                    contenders.removeAt(at)
                    contenders.add(0, this)
                }
            }
            bump()
        }

        /**
         * The entry is gone. Its streams can no longer be closed one by one,
         * and a slot left in the playing set would hold every other download
         * of the engine paused for good.
         */
        fun release() {
            synchronized(lock) {
                streams = 0
                playing -= this
                contenders.removeAll { it === this }
            }
            bump()
        }

        override fun toString(): String = name
    }

    private fun isAllowed(slot: Slot): Boolean {
        // Under rule 2 the pool shrinks to the playing entries; a downloader of
        // any other entry is not in it and therefore ranks nowhere.
        val pool = if (playing.isEmpty()) contenders else contenders.filter { it in playing }
        val rank = pool.indexOfFirst { it === slot }
        return rank in 0 until limit
    }

    private fun bump() {
        version.update { it + 1 }
    }

    internal companion object {
        /**
         * The line carries about 6 to 7 MB/s and eight connections saturate it,
         * so a third concurrent downloader buys no throughput and only spreads
         * the same bandwidth over more unfinished files.
         */
        const val DEFAULT_LIMIT = 2
    }
}
