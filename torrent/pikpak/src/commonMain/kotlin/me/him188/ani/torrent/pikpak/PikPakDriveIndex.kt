/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.CreateUrlResult
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.FolderNotFoundException
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.batchDelete
import io.github.nihildigit.pikpak.createFolder
import io.github.nihildigit.pikpak.createUrlFile
import io.github.nihildigit.pikpak.getFolderId
import io.github.nihildigit.pikpak.getOrCreateDeepFolderId
import io.github.nihildigit.pikpak.getTask
import io.github.nihildigit.pikpak.listFiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.utils.logging.debug
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** One remote file that a torrent produced, as seen inside its bucket. */
internal data class RemoteEntry(
    val fileId: String,
    /**
     * Path relative to the pack folder, `/`-separated, e.g. `specials/S00E01.mkv`.
     * A file the torrent put at its top level has a bare name here.
     */
    val path: String,
    val sizeBytes: Long,
) {
    /** The bare file name. Anything that judges a file by its extension wants this, not [path]. */
    val name: String get() = path.substringAfterLast('/')
}

/**
 * The cloud half of the engine: where a torrent's files live in the user's
 * PikPak drive, and how to get them there.
 *
 * Layout is `Animeko-Playing/<sourceKey>/`, one bucket per source. PikPak drops
 * a single-file torrent into the bucket as a file and a multi-file torrent as
 * one pack folder, whose own directory structure it preserves.
 *
 * Folder ids are memoized because resolving one costs a listing of its parent,
 * and the slot folder is on the critical path of every playback start. A
 * memoized id that has been evicted surfaces as a 404 on the next use, which
 * [invalidate] then clears.
 */
internal class PikPakDriveIndex(
    private val clientProvider: suspend () -> PikPakClient,
    private val slotFolderName: String = DEFAULT_SLOT_FOLDER,
    /** Steady-state gap between polls; see [taskPollDelay] for the ramp before it. */
    private val pollInterval: Duration = 2.seconds,
    private val taskTimeout: Duration = 5.minutes,
) {
    private val logger = logger<PikPakDriveIndex>()

    private val lock = Mutex()
    private var slotFolderId: String? = null
    private val bucketIds = mutableMapOf<String, String>()

    suspend fun slotId(): String {
        lock.withLock { slotFolderId }?.let { return it }
        val id = clientProvider().getOrCreateDeepFolderId(parentId = "", path = slotFolderName)
        lock.withLock { slotFolderId = id }
        return id
    }

    /** Cached bucket id, without any network call. */
    suspend fun cachedBucketId(sourceKey: String): String? = lock.withLock { bucketIds[sourceKey] }

    /** Existing bucket id, or null when the source has none yet. */
    suspend fun findBucket(sourceKey: String): String? {
        cachedBucketId(sourceKey)?.let { return it }
        val id = try {
            clientProvider().getFolderId(slotId(), sourceKey)
        } catch (_: FolderNotFoundException) {
            return null
        }
        lock.withLock { bucketIds[sourceKey] = id }
        return id
    }

    suspend fun createBucket(sourceKey: String): String {
        val id = clientProvider().createFolder(slotId(), sourceKey)
        lock.withLock { bucketIds[sourceKey] = id }
        return id
    }

    suspend fun invalidate(sourceKey: String) {
        lock.withLock { bucketIds.remove(sourceKey) }
    }

    suspend fun invalidateSlot() {
        lock.withLock {
            slotFolderId = null
            bucketIds.clear()
        }
        clientProvider().clearFolderIdCache()
    }

    /** Everything a torrent produced inside [bucketId]. See [walkBucket]. */
    suspend fun listBucket(bucketId: String): List<RemoteEntry> =
        walkBucket(bucketId) { clientProvider().listFiles(parentId = it) }

    /** Torrent display name: the bucket's single top-level entry. */
    suspend fun bucketDisplayName(bucketId: String, fallback: String): String =
        clientProvider().listFiles(parentId = bucketId).firstOrNull()?.name ?: fallback

    /**
     * Submits [uri] into [bucketId] and waits for the offline task to finish.
     *
     * The first poll is immediate: a resource PikPak already holds is complete
     * by the time the submission returns, and sleeping first would add the poll
     * interval to every warm start. Subsequent gaps follow [taskPollDelay].
     */
    suspend fun submitAndAwait(bucketId: String, uri: String) {
        val client = clientProvider()
        val queued = when (val result = client.createUrlFile(parentId = bucketId, url = uri)) {
            is CreateUrlResult.InstantComplete -> {
                logger.info { "[pikpak] instant-complete, nothing to poll" }
                return
            }

            is CreateUrlResult.Queued -> result.task
        }

        var task = queued
        var waited = Duration.ZERO
        var polls = 0
        while (task.phase !in TaskPhase.TERMINAL) {
            if (waited > taskTimeout) {
                throw PikPakTaskException("PikPak offline task ${task.id} still ${task.phase} after $waited")
            }
            task = clientProvider().getTask(task.id)
            polls++
            logger.debug { "[pikpak] task ${task.id} poll $polls: ${task.phase} ${task.progress}%" }
            if (task.phase in TaskPhase.TERMINAL) break
            val gap = taskPollDelay(polls - 1, pollInterval)
            delay(gap)
            waited += gap
        }
        if (task.phase == TaskPhase.ERROR) {
            throw PikPakTaskException("PikPak offline task ${task.id} failed: ${task.message}")
        }
        logger.info { "[pikpak] task ${task.id} complete after $polls polls" }
    }

    /**
     * Finds the current cloud file id of a file the engine already has on disk.
     *
     * Ordered by cost: the bucket usually still exists and only the file id
     * moved, which is one listing. A missing bucket means the source was
     * evicted and has to be fetched again, which for anything PikPak has seen
     * before finishes in seconds because of its content-level deduplication.
     * Listing before resubmitting is not optional for a multi-file torrent: a
     * resubmit into a bucket that still holds the pack would produce a second
     * copy of it.
     */
    suspend fun relocate(sourceKey: String, uri: String, pathInTorrent: String): RelocateResult {
        val existing = findBucket(sourceKey)
        if (existing != null) {
            try {
                return relocateWithin(existing, sourceKey, uri, pathInTorrent)
            } catch (e: PikPakException) {
                // A memoized id outlives the folder when the bucket is deleted
                // from outside this process. Without this the same 404 would
                // come back on every read and the recreate branch below would
                // never be reached.
                if (!isFolderGone(e)) throw e
                logger.info { "[pikpak] bucket $sourceKey ($existing) no longer exists; recreating" }
                invalidate(sourceKey)
            }
        }

        val bucket = createBucketOrRefreshSlot(sourceKey)
        submitAndAwait(bucket, uri)
        val found = listBucket(bucket).firstOrNull { it.path == pathInTorrent }
            ?: throw PikPakTaskException("Re-fetching $sourceKey did not produce '$pathInTorrent'")
        return RelocateResult(found.fileId, bucket)
    }

    private suspend fun relocateWithin(
        bucketId: String,
        sourceKey: String,
        uri: String,
        pathInTorrent: String,
    ): RelocateResult {
        listBucket(bucketId).firstOrNull { it.path == pathInTorrent }?.let {
            return RelocateResult(it.fileId, bucketId)
        }
        logger.info { "[pikpak] bucket $sourceKey exists but has no '$pathInTorrent'; resubmitting" }
        submitAndAwait(bucketId, uri)
        listBucket(bucketId).firstOrNull { it.path == pathInTorrent }?.let {
            return RelocateResult(it.fileId, bucketId)
        }
        throw PikPakTaskException("Resubmitting $sourceKey did not produce '$pathInTorrent'")
    }

    /**
     * [createBucket], retried once with the slot folder resolved afresh when
     * the memoized slot id turns out to be deleted as well.
     */
    suspend fun createBucketOrRefreshSlot(sourceKey: String): String =
        try {
            createBucket(sourceKey)
        } catch (e: PikPakException) {
            if (!isFolderGone(e)) throw e
            logger.info { "[pikpak] slot folder no longer exists; resolving it again" }
            invalidateSlot()
            createBucket(sourceKey)
        }

    /**
     * Trims the slot down to [queueLength] buckets. [protectedKeys] are source
     * keys the local disk still holds an unfinished cache for; evicting those
     * would force a resubmit the moment the user resumes them.
     *
     * @return the source keys whose cloud buckets were deleted, so the caller
     *  can drop their local scratch too: the bytes are worthless once the file
     *  they were read from is gone.
     */
    suspend fun evict(currentSourceKey: String, queueLength: Int, protectedKeys: Set<String>): List<String> {
        if (queueLength >= PikPakEngineConfig.SLOT_QUEUE_UNLIMITED) return emptyList()
        val top = clientProvider().listFiles(parentId = slotId())
        val victims = pickBucketsToEvict(top, currentSourceKey, queueLength, protectedKeys)
        if (victims.isEmpty()) return emptyList()
        logger.info { "[pikpak] evicting ${victims.size} bucket(s) from the slot" }
        clientProvider().batchDelete(victims.map { it.id })
        victims.forEach { invalidate(it.name) }
        return victims.map { it.name }
    }

    companion object {
        const val DEFAULT_SLOT_FOLDER = "Animeko-Playing"
    }
}

/**
 * Every file under [bucketId], with the pack folder stripped from the paths but
 * any folder below it kept, e.g. `specials/S00E01.mkv`.
 *
 * The walk is recursive because a pack is not flat: a season pack routinely
 * carries a `specials/` folder, and a walk that stopped one level below the
 * bucket dropped those files from the index without saying so, which left the
 * app unable to find the episodes in them.
 *
 * The pack folder itself is stripped because the engine already gives each
 * source a directory of its own on disk, so keeping it would nest every file
 * one level deeper for nothing.
 *
 * [listChildren] takes a folder id and answers its direct children. Taking it
 * as a parameter is what makes the layout testable: a PikPak client cannot be
 * stood up without an account.
 */
internal suspend fun walkBucket(
    bucketId: String,
    listChildren: suspend (folderId: String) -> List<FileStat>,
): List<RemoteEntry> {
    val out = mutableListOf<RemoteEntry>()

    suspend fun descend(folderId: String, prefix: String, depth: Int) {
        if (depth > MAX_BUCKET_DEPTH) {
            walkLogger.warn { "[pikpak] bucket walk stopped at '$prefix', deeper than $MAX_BUCKET_DEPTH levels" }
            return
        }
        for (entry in listChildren(folderId)) {
            val path = if (prefix.isEmpty()) entry.name else "$prefix/${entry.name}"
            when {
                entry.isFolder -> descend(entry.id, path, depth + 1)
                entry.isFile -> out += RemoteEntry(entry.id, path, entry.sizeBytes)
            }
        }
    }

    for (entry in listChildren(bucketId)) {
        when {
            entry.isFolder -> descend(entry.id, prefix = "", depth = 1)
            entry.isFile -> out += RemoteEntry(entry.id, entry.name, entry.sizeBytes)
        }
    }
    return out
}

/**
 * Whether a drive call failed because the folder it named is gone, as opposed
 * to the request being unlucky. PikPak answers with HTTP 404 and
 * `file_not_found`, the same way it does for a deleted file; a 5xx or a
 * timeout must not throw away a memoized id that is still valid.
 */
internal fun isFolderGone(e: PikPakException): Boolean =
    e.httpStatus == 404 || e.errorMessage.contains("not_found")

/**
 * Levels below the pack folder the walk descends. A pack nested deeper than
 * this is not something PikPak produces; the cap is only there so a listing
 * that somehow answered with a cycle cannot spin forever.
 */
internal const val MAX_BUCKET_DEPTH = 8

private val walkLogger = logger("PikPakBucketWalk")

/**
 * How long to sleep before the next `getTask` poll, given [pollIndex] polls
 * already made (0-based: index 0 is the gap after the immediate first poll).
 *
 * An already-cached magnet finishes within a second or two, so the first two
 * gaps are short and only a task that is genuinely downloading ever reaches the
 * [steadyState] ceiling. The result never exceeds [steadyState]: a caller that
 * configures a gap shorter than the ramp wants that gap, not a slower start.
 */
internal fun taskPollDelay(pollIndex: Int, steadyState: Duration): Duration {
    val ramp = if (pollIndex < 2) 1.seconds else steadyState
    return minOf(ramp, steadyState)
}

internal data class RelocateResult(val fileId: String, val bucketId: String)

class PikPakTaskException(message: String) : Exception(message)

/**
 * Which slot buckets to delete. Pure so the policy edges are testable without
 * a client. Unlike the retired offline-download engine's eviction, buckets
 * with an unfinished local cache are never candidates.
 *
 * Bucket age comes from `createdTime`, which PikPak returns as ISO-8601, so a
 * lexicographic sort is chronological.
 */
internal fun pickBucketsToEvict(
    slotEntries: List<FileStat>,
    currentSourceKey: String,
    queueLength: Int,
    protectedKeys: Set<String>,
): List<FileStat> {
    if (queueLength >= PikPakEngineConfig.SLOT_QUEUE_UNLIMITED) return emptyList()
    val keepCount = (queueLength - 1).coerceAtLeast(0)
    return slotEntries
        .filter { it.name != currentSourceKey && it.name !in protectedKeys }
        .sortedByDescending { it.createdTime }
        .drop(keepCount)
}
