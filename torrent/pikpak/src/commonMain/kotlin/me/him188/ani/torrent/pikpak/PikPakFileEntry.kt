/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.PikPakStreamReader
import io.github.nihildigit.pikpak.RangeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.him188.ani.app.torrent.api.files.AbstractTorrentFileEntry
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import me.him188.ani.app.torrent.api.pieces.MutablePieceList
import me.him188.ani.app.torrent.api.pieces.PieceList
import me.him188.ani.app.torrent.api.pieces.TorrentDownloadController
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.warn
import org.openani.mediamp.io.SeekableInput
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * One file of a PikPak-backed torrent.
 *
 * A cache record writes the whole file: PikPak is treated as a swarm that
 * always holds every piece, so [TorrentDownloadController] decides what to
 * fetch exactly as it does for BT and [PieceFetcher] moves the bytes. Playback
 * writes nothing; it reads the pieces that are already on disk and streams the
 * rest, so caching an episode and watching it at once costs each byte once.
 *
 * The piece list is real: its states come from what is on disk, not from a
 * downloaded length, so the progress bar and the cache engine read the truth
 * even when the stream being read is a transcode of a different size.
 */
internal class PikPakFileEntry(
    index: Int,
    length: Long,
    private val saveDirectory: SystemPath,
    relativePath: String,
    torrentId: String,
    parentCoroutineContext: CoroutineContext,
    val meta: PikPakFileMeta,
    private val source: RangeSource,
    /** The variant's byte count as `meta.json` records it, or the torrent's own length when it does not. */
    private val initialVariantLength: Long,
    /** Which stream is actually being read, and how long it is. A transcode costs one probe, the original none. */
    private val streamVariant: suspend () -> StreamVariant,
    /** Records the variant in `meta.json`, so the next open lays out the piece list without asking. */
    private val onVariantDecided: suspend (StreamVariant) -> Unit,
    private val concurrency: Int,
    private val onHandleCountChanged: suspend () -> Unit,
) : AbstractTorrentFileEntry(
    index = index,
    length = length,
    saveDirectory = saveDirectory,
    relativePath = relativePath,
    torrentId = torrentId,
    isDebug = false,
    parentCoroutineContext = parentCoroutineContext,
), CloudReadiness {
    override val supportsStreaming: Boolean get() = true

    private val dataPath: SystemPath get() = saveDirectory.resolve(relativePath)

    // The base class takes the parent context but does not keep it, and the
    // fetcher needs one of its own: it outlives no handle and must not be
    // cancelled when a priority changes. It also has to carry a real
    // dispatcher, or the runBlocking in StreamSeekableInput would be running
    // the reader's own workers.
    private val streamContext: CoroutineContext = parentCoroutineContext + Dispatchers.IO_

    private val resolveMutex = Mutex()

    @Volatile
    private var stream: Stream = newStream(initialVariantLength)

    @Volatile
    private var variantResolved = false

    @Volatile
    private var startJob: Job? = null

    override val pieces: MutablePieceList get() = stream.pieces

    /**
     * Returns once the stream to read is settled and its bytes are on their way.
     *
     * Minting the link is what an invalid account or a full drive fails on.
     * Awaiting it here costs nothing the first read would not have paid, and
     * puts the failure where the caller can still fall back to another engine.
     */
    override suspend fun ensureCloudReady() {
        ensureStreamResolved()
    }

    /**
     * Settles which stream this entry reads, then starts fetching it.
     *
     * A complete file is left alone: it needs nothing from the cloud, and a
     * file the migration moved in has no gcid at all, so asking would throw
     * rather than merely cost a request.
     *
     * The probe is skipped whenever `meta.json` already records a variant
     * length, which is every open after the first. Resolving it at construction
     * instead would turn opening a season pack into one `getFile` per file.
     */
    private suspend fun ensureStreamResolved(): Stream {
        if (variantResolved) return stream
        resolveMutex.withLock {
            if (variantResolved) return stream
            if (stream.fetcher.isComplete) {
                variantResolved = true
                return stream
            }
            val variant = streamVariant()
            if (variant.length != stream.length) {
                logger.info { "[$torrentId] $fileName reads ${variant.length} bytes, not the torrent's ${stream.length}" }
                // Nothing has been fetched yet: an entry whose directory already
                // holds bytes arrives with its variant pinned, so the length
                // agrees and this branch is not taken. Discarding the old stream
                // therefore discards nothing.
                stream.fetcher.close()
                stream = newStream(variant.length)
            }
            onVariantDecided(variant)
            variantResolved = true
            return stream
        }
    }

    /**
     * Starts writing the whole file to disk.
     *
     * The controller is resumed here rather than only in [EntryHandle.resumeImpl]
     * because a handle that resumed before the probe returned did so against the
     * stream [ensureStreamResolved] may have replaced, leaving the new
     * controller with an empty work list and the fetcher spinning on nothing.
     */
    private suspend fun startFetching() {
        val current = ensureStreamResolved()
        // Checked again after the probe, not only before it: settling the stream
        // waits on PikPak, and a user who pauses the cache during that wait must
        // not have it start anyway when the answer arrives.
        if (!wantsWholeFile) return
        current.controller.resume()
        current.fetcher.start()
    }

    // Sampled rather than derived from a flow the fetcher owns: the stream is
    // replaced when the first probe reports a transcode, and a flow captured
    // here would then be reporting the discarded one forever.
    override val fileStats: Flow<TorrentFileEntry.Stats> = flow {
        while (true) {
            val current = stream
            val downloaded = current.fetcher.downloadedBytes
            emit(
                TorrentFileEntry.Stats(
                    downloadedBytes = downloaded,
                    downloadProgress = if (current.length <= 0L) 1f
                    else (downloaded.toFloat() / current.length).coerceIn(0f, 1f),
                ),
            )
            delay(1.seconds)
        }
    }

    /** What playback pulled from the CDN. Separate from the fetcher's count because playback keeps none of it. */
    private val streamDeliveredBytes = MutableStateFlow(0L)

    /** Bytes the CDN has handed over since this session started. Monotonic; the basis of the speed readout. */
    val deliveredBytes: Long get() = streamDeliveredBytes.value + stream.fetcher.deliveredBytes

    val downloadedBytes: Long get() = stream.fetcher.downloadedBytes

    /**
     * How many bytes the stream being read has, which a transcode makes differ
     * from [length].
     *
     * Session progress has to divide [downloadedBytes] by this and not by the
     * torrent's figure: a shorter transcode would otherwise never reach 100%
     * and a longer one would get there before it was done.
     */
    val streamLength: Long get() = stream.length

    val error: StateFlow<Throwable?> get() = stream.fetcher.error

    /** Whether the whole stream is on disk. Measured against the stream's length, which a transcode changes. */
    val isComplete: Boolean get() = stream.fetcher.isComplete

    private val openHandles = MutableStateFlow(0)
    val hasOpenHandles: Boolean get() = openHandles.value > 0

    inner class EntryHandle : AbstractTorrentFileHandle() {
        override val entry get() = this@PikPakFileEntry

        override fun resumeImpl(priority: FilePriority) {
            beginFetchingIfWanted()
        }

        override suspend fun closeImpl() {
            openHandles.update { (it - 1).coerceAtLeast(0) }
            onHandleCountChanged()
        }

        override suspend fun closeAndDelete() {
            // close() first, so nothing is fetching while the file goes away:
            // dropping the last handle of the session closes the session and
            // with it this entry, and deleteFiles() has to delete the file in
            // that case too. It does, because cancelling and joining a job
            // whose scope is already cancelled returns at once.
            close()
            deleteFiles()
        }
    }

    override fun createHandle(): TorrentFileHandle {
        openHandles.update { it + 1 }
        return EntryHandle()
    }

    /**
     * Whether a handle wants the file on disk rather than just played.
     *
     * The player asks HIGH and means "serve me what I read"; a cache record asks
     * NORMAL and means "keep all of it". Anything below HIGH therefore starts
     * the fetcher and HIGH alone starts nothing, because playback reads through
     * without writing.
     */
    private val wantsWholeFile: Boolean
        get() = priorityRequests.values.any { it != null && it != FilePriority.IGNORE && it < FilePriority.HIGH }

    override fun updatePriority() {
        val priority = requestingPriority
        if (wantsWholeFile) {
            beginFetchingIfWanted()
        } else {
            // The pending start is cancelled too, not just the fetcher: it may
            // be sitting in a retry delay, and waking up to start a download the
            // user has since paused.
            startJob?.cancel()
            startJob = null
            stream.fetcher.stop()
        }
        logger.info { "[$torrentId] $fileName priority -> $priority" }
    }

    /**
     * Gets the bytes moving, retrying until they do.
     *
     * Nothing may escape this launch: it is the whole body of a coroutine in a
     * supervisor scope, which does not catch, so a failure to reach PikPak here
     * would reach Android's uncaught handler and end the process. Retrying is
     * the behaviour a cache record had before the fetcher replaced it -- a
     * download interrupted by a dead network resumed on its own once the
     * network came back, and a permanent give-up looked identical to a bug.
     *
     * Playback does not retry: the caller that needs that failure awaits
     * [ensureCloudReady] and falls back to another engine on it.
     */
    private fun beginFetchingIfWanted() {
        if (startJob?.isActive == true) return
        startJob = scope.launch {
            while (isActive) {
                try {
                    if (wantsWholeFile) startFetching() else ensureStreamResolved()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!wantsWholeFile) {
                        logger.warn(e) { "[$torrentId] $fileName could not settle its stream" }
                        return@launch
                    }
                    logger.warn(e) { "[$torrentId] $fileName could not start fetching, retrying in $START_RETRY_DELAY" }
                    delay(START_RETRY_DELAY)
                }
            }
        }
    }

    /**
     * Reads whatever a cache download has already written off the disk and the
     * rest from the cloud, writing nothing.
     *
     * The entry cannot hold the input itself: several can be open at once and
     * each has its own lifetime, so each reports its delivered bytes here when
     * it closes and while it runs.
     */
    override suspend fun createInput(awaitCoroutineContext: CoroutineContext): SeekableInput =
        withContext(Dispatchers.IO_) {
            val current = ensureStreamResolved()
            HybridSeekableInput(
                dataPath = dataPath,
                pieces = current.pieces,
                size = current.length,
                openStream = {
                    StreamSeekableInput(
                        PikPakStreamReader(
                            source = source,
                            size = current.length,
                            concurrency = concurrency,
                            parentCoroutineContext = streamContext,
                        ),
                    )
                },
                onDelivered = { delta -> streamDeliveredBytes.update { it + delta } },
            )
        }

    /** Retires this entry. Nothing of it stands in the cloud: the file object went as soon as it had minted a link. */
    suspend fun close() {
        stream.fetcher.close()
        // The entry scope is a child of the storage's long-lived context for
        // entries built during cache restoration; left running it would keep
        // this entry, its piece list and the session callback alive until the
        // app exits.
        scope.cancel()
    }

    /**
     * Deletes this entry's bytes without retiring the entry.
     *
     * It used to call [close], which is the shutdown path: the fetcher's scope
     * was cancelled for good. The entry itself stays in its session, so as long
     * as another episode of the same pack held a handle the session survived,
     * and the next request for this file got a fetcher that could not be
     * started and a piece list describing a file that had been deleted.
     *
     * The whole stream is rebuilt rather than only the fetcher's piece states.
     * A controller that has seen the file through to the end has dropped the
     * header and footer from its high-priority set and latched
     * `allNormalPieceDownloaded`, and `resume()` clears neither: reusing it
     * would leave the metadata pieces unfetched and the window stuck after the
     * first one.
     */
    private suspend fun deleteFiles() {
        resolveMutex.withLock {
            val old = stream
            old.fetcher.deleteTarget()
            old.fetcher.close()
            stream = newStream(old.length)
        }
    }

    /**
     * The piece list, the fetcher and the controller for one stream.
     *
     * They are replaced together because they are one decision: the piece list
     * is laid out against the stream's byte count, and a transcode is a
     * different byte count from the torrent's own.
     */
    private class Stream(
        val length: Long,
        val pieces: MutablePieceList,
        val fetcher: PieceFetcher,
        val controller: TorrentDownloadController,
    )

    private fun newStream(length: Long): Stream {
        val pieces = PieceList.create(totalSize = length, pieceSize = PIECE_SIZE)
        // The controller needs the fetcher as its PiecePriorities and the
        // fetcher needs to report completions back to the controller, so one of
        // the two references is filled in after construction.
        var controller: TorrentDownloadController? = null
        val fetcher = PieceFetcher(
            source = source,
            file = dataPath,
            pieces = pieces,
            totalLength = length,
            concurrency = concurrency,
            logTag = "$torrentId/$fileName",
            onPieceDownloaded = { controller?.onPieceDownloaded(it) },
            parentCoroutineContext = streamContext,
        )
        return Stream(
            length = length,
            pieces = pieces,
            fetcher = fetcher,
            controller = TorrentDownloadController(
                pieces = pieces,
                priorities = fetcher,
                // Same reasoning as anitorrent's: a window much larger than the
                // connection count lets the fetcher spread itself over bytes the
                // player will not reach for a while.
                windowSize = (8 * 1024 * 1024 / PIECE_SIZE).toInt().coerceIn(2, 64),
                headerSize = HEADER_SIZE,
                footerSize = FOOTER_SIZE,
                possibleFooterSize = 8 * 1024 * 1024,
            ).also { controller = it },
        )
    }

    companion object {
        /**
         * Granularity of the piece list, and so of the progress bar and of the
         * wait a seek into new territory costs. Not a transport parameter: the
         * fetcher coalesces adjacent pieces into one request, and throughput was
         * measured to be indifferent to request size anyway.
         */
        const val PIECE_SIZE: Long = 512L * 1024

        /** Both taken from anitorrent: they decide how fast the player gets a header, not how bytes move. */
        const val HEADER_SIZE: Long = 2L * 1024 * 1024
        const val FOOTER_SIZE: Long = 512L * 1024

        /** Long because the thing it waits out is an account or a network, not a flaky request. */
        val START_RETRY_DELAY = 30.seconds
    }
}
