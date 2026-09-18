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
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
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
    // Mints the link now, so sign-in and quota failures surface while the resolver can still fall
    // back to BT. A no-op default keeps a plain RangeSource usable in tests.
    private val prepareSource: suspend () -> Unit = {},
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
    private var stream: Stream = newStream()

    @Volatile
    private var cloudReady = false

    @Volatile
    private var startJob: Job? = null

    override val pieces: MutablePieceList get() = stream.pieces

    override suspend fun ensureCloudReady() {
        if (cloudReady) return
        resolveMutex.withLock {
            if (cloudReady) return
            // Complete imports have no GCID and must never need the cloud.
            if (stream.fetcher.isComplete) {
                cloudReady = true
                return
            }
            prepareSource()
            cloudReady = true
        }
    }

    private suspend fun startFetching() {
        ensureCloudReady()

        if (!wantsWholeFile) return
        val current = stream
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
                    downloadProgress = if (length <= 0L) 1f
                    else (downloaded.toFloat() / length).coerceIn(0f, 1f),
                ),
            )
            delay(1.seconds)
        }
    }

    private val streamDeliveredBytes = MutableStateFlow(0L)

    val deliveredBytes: Long get() = streamDeliveredBytes.value + stream.fetcher.deliveredBytes

    val downloadedBytes: Long get() = stream.fetcher.downloadedBytes

    override val error: StateFlow<Throwable?> get() = stream.fetcher.error

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
                    if (wantsWholeFile) startFetching() else ensureCloudReady()
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
            ensureCloudReady()
            val current = stream
            // 播放已经开着一个 input 时再来的是进度条预览: 它按悬停位置零星取几帧, 而 SDK 的读优先级由读的
            // 种类决定, 外面传不进去, 能压住它的只有连接数和预读窗口. 让位的判据是"这个文件上已经有 input",
            // 因为 createInput 拿不到调用方是谁.
            val secondary = current.claimInput() > 0
            logger.info { "[$torrentId] $fileName creating ${if (secondary) "a secondary" else "an"} input" }
            HybridSeekableInput(
                name = fileName,
                dataPath = dataPath,
                pieces = current.pieces,
                size = length,
                openStream = {
                    StreamSeekableInput(
                        name = fileName,
                        reader = PikPakStreamReader(
                            source = source,
                            size = length,
                            concurrency = if (secondary) SECONDARY_CONCURRENCY else concurrency,
                            parentCoroutineContext = streamContext,
                        ),
                    )
                },
                onDelivered = { delta -> streamDeliveredBytes.update { it + delta } },
                onCloudReadStarted = slot::openStream,
                onCloudReadFinished = slot::closeStream,
                onClosed = { current.releaseInput() },
                // A complete import is read from disk, where seeking to the index costs nothing.
                tail = if (!PREFETCH_TAIL || current.fetcher.isComplete) null else current.tail(),
                isStale = { stream !== current },
            )
        }

    suspend fun close() {
        startJob?.cancelAndJoin()
        startJob = null

        resolveMutex.withLock {
            stream.closeTail()
            stream.fetcher.close()
        }

        slot.release()

        scope.cancel()
    }

    // Rebuild the controller too: its completed-window state cannot resume a deleted file.
    private suspend fun deleteFiles() {
        resolveMutex.withLock {
            val old = stream
            old.fetcher.deleteTarget()
            old.closeTail()
            old.fetcher.close()
            stream = newStream()
        }
    }

    private class Stream(
        val pieces: MutablePieceList,
        val fetcher: PieceFetcher,
        val controller: TorrentDownloadController,
        private val createTail: () -> TailPrefetch,
    ) {
        private val tailLock = SynchronizedObject()
        private var tail: TailPrefetch? = null

        // 预取的是这个文件末尾的一段, 归文件所有而不是归某个 input: 进度条预览会为同一个文件再开一个
        // input, 各建一份就把同样的字节取两遍, 而预取排在播放读之前, 第二份是直接和正在播的读抢连接.
        fun tail(): TailPrefetch = synchronized(tailLock) { tail ?: createTail().also { tail = it } }

        fun closeTail() = synchronized(tailLock) {
            tail?.close()
            tail = null
        }

        private var liveInputs = 0

        /** 返回本次之前还有几个 input 活着. */
        fun claimInput(): Int = synchronized(tailLock) { liveInputs.also { liveInputs = it + 1 } }

        fun releaseInput() = synchronized(tailLock) {
            if (liveInputs > 0) liveInputs--
        }
    }

    private fun newStream(): Stream {
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
            streaming = slot.streaming,
        )
        return Stream(
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
            createTail = { TailPrefetch(fileName, source, length, streamContext) },
        )
    }

    companion object {
        /**
         * Measurement switch, not a setting and not user-facing.
         *
         * False sends the container index over the stream instead, which is the arrangement
         * TailPrefetch exists to beat, so one build can be run both ways on a device. Expect the
         * difference to show up in time to first frame rather than in throughput, and to grow with
         * round-trip time: the demuxer's jump to the index and back is the one part of startup that
         * cannot be spread across connections.
         */
        const val PREFETCH_TAIL = true

        const val PIECE_SIZE: Long = 512L * 1024

        // 预览取的是悬停位置的单帧, 一个连接够用. 预读窗口本来更该压 (SDK 默认 32 MiB 是为持续播放
        // 准备的), 但设它的构造在 SDK 里是 internal, 外面只能给并发数.
        // 压窗口试过一次, 回退了: 预览帧要从它前面那个关键帧开始解, 这个码率下一个关键帧间隔就是几 MB,
        // 512 KiB 的窗口把每次悬停变成了单连接上按块前挪, 实测一次读 19.9 秒且预览始终没出来. 要再压,
        // 得按关键帧间隔取值, 不是按块数.
        const val SECONDARY_CONCURRENCY = 1

        const val HEADER_SIZE: Long = 2L * 1024 * 1024
        const val FOOTER_SIZE: Long = 512L * 1024

        val START_RETRY_DELAY = 30.seconds
    }
}

// Resolvers await this before handing data to the player, while engine fallback is still possible.
interface CloudReadiness {
    suspend fun ensureCloudReady()
}
