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
import kotlinx.coroutines.cancelAndJoin
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

internal class PikPakFileEntry(
    index: Int,
    length: Long,
    private val saveDirectory: SystemPath,
    relativePath: String,
    torrentId: String,
    parentCoroutineContext: CoroutineContext,
    val meta: PikPakFileMeta,
    private val source: RangeSource,
    private val initialVariantLength: Long,
    private val streamVariant: suspend () -> StreamVariant,
    private val onVariantDecided: suspend (StreamVariant) -> Unit,
    private val concurrency: Int,
    scheduler: DownloadScheduler,
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

    private val streamContext: CoroutineContext = parentCoroutineContext + Dispatchers.IO_

    private val slot = scheduler.newSlot("$torrentId/$fileName")

    private val resolveMutex = Mutex()

    @Volatile
    private var stream: Stream = newStream(initialVariantLength)

    @Volatile
    private var variantResolved = false

    @Volatile
    private var startJob: Job? = null

    override val pieces: MutablePieceList get() = stream.pieces

    // Surface authentication and quota failures while the resolver can still fall back to BT.
    override suspend fun ensureCloudReady() {
        ensureStreamResolved()
    }

    private suspend fun ensureStreamResolved(): Stream {
        if (variantResolved) return stream
        resolveMutex.withLock {
            if (variantResolved) return stream
            // Complete imports have no GCID and must never need the cloud.
            if (stream.fetcher.isComplete) {
                variantResolved = true
                return stream
            }
            val variant = streamVariant()
            if (variant.length != stream.length) {
                logger.info { "[$torrentId] $fileName reads ${variant.length} bytes, not the torrent's ${stream.length}" }

                stream.fetcher.close()
                stream = newStream(variant.length)
            }
            onVariantDecided(variant)
            variantResolved = true
            return stream
        }
    }

    private suspend fun startFetching() {
        val current = ensureStreamResolved()

        if (!wantsWholeFile) return
        current.controller.resume()
        current.fetcher.start()
    }

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

    private val streamDeliveredBytes = MutableStateFlow(0L)

    val deliveredBytes: Long get() = streamDeliveredBytes.value + stream.fetcher.deliveredBytes

    val downloadedBytes: Long get() = stream.fetcher.downloadedBytes

    val streamLength: Long get() = stream.length

    val error: StateFlow<Throwable?> get() = stream.fetcher.error

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
            close()
            deleteFiles()
        }
    }

    override fun createHandle(): TorrentFileHandle {
        openHandles.update { it + 1 }
        return EntryHandle()
    }

    // HIGH belongs to playback; lower active priorities request a persistent copy.
    private val wantsWholeFile: Boolean
        get() = priorityRequests.values.any { it != null && it != FilePriority.IGNORE && it < FilePriority.HIGH }

    override fun updatePriority() {
        val priority = requestingPriority
        if (wantsWholeFile) {
            beginFetchingIfWanted()
        } else {
            startJob?.cancel()
            startJob = null
            stream.fetcher.stop()
        }
        logger.info { "[$torrentId] $fileName priority -> $priority" }
    }

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

    override suspend fun createInput(awaitCoroutineContext: CoroutineContext): SeekableInput =
        withContext(Dispatchers.IO_) {
            val current = ensureStreamResolved()
            // Each input opens its own cloud stream and starts its read-ahead from scratch, so the
            // player asking for a second one is worth seeing.
            logger.info { "[$torrentId] $fileName creating an input" }
            HybridSeekableInput(
                name = fileName,
                dataPath = dataPath,
                pieces = current.pieces,
                size = current.length,
                openStream = {
                    StreamSeekableInput(
                        name = fileName,
                        reader = PikPakStreamReader(
                            source = source,
                            size = current.length,
                            concurrency = concurrency,
                            parentCoroutineContext = streamContext,
                        ),
                    )
                },
                onDelivered = { delta -> streamDeliveredBytes.update { it + delta } },
                onCloudReadStarted = slot::openStream,
                onCloudReadFinished = slot::closeStream,
                // A complete import is read from disk, where seeking to the index costs nothing.
                tail = if (current.fetcher.isComplete) {
                    null
                } else {
                    TailPrefetch(fileName, source, current.length, streamContext)
                },
            )
        }

    suspend fun close() {
        startJob?.cancelAndJoin()
        startJob = null

        resolveMutex.withLock { stream.fetcher.close() }

        slot.release()

        scope.cancel()
    }

    // Rebuild the controller too: its completed-window state cannot resume a deleted file.
    private suspend fun deleteFiles() {
        resolveMutex.withLock {
            val old = stream
            old.fetcher.deleteTarget()
            old.fetcher.close()
            stream = newStream(old.length)
        }
    }

    private class Stream(
        val length: Long,
        val pieces: MutablePieceList,
        val fetcher: PieceFetcher,
        val controller: TorrentDownloadController,
    )

    private fun newStream(length: Long): Stream {
        val pieces = PieceList.create(totalSize = length, pieceSize = PIECE_SIZE)

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
            slot = slot,
        )
        return Stream(
            length = length,
            pieces = pieces,
            fetcher = fetcher,
            controller = TorrentDownloadController(
                pieces = pieces,
                priorities = fetcher,
                windowSize = (8 * 1024 * 1024 / PIECE_SIZE).toInt().coerceIn(2, 64),
                headerSize = HEADER_SIZE,
                footerSize = FOOTER_SIZE,
                possibleFooterSize = 8 * 1024 * 1024,
            ).also { controller = it },
        )
    }

    companion object {
        const val PIECE_SIZE: Long = 512L * 1024

        const val HEADER_SIZE: Long = 2L * 1024 * 1024
        const val FOOTER_SIZE: Long = 512L * 1024

        val START_RETRY_DELAY = 30.seconds
    }
}

// Resolvers await this before handing data to the player, while engine fallback is still possible.
interface CloudReadiness {
    suspend fun ensureCloudReady()
}
