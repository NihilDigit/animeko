/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.RangeReader
import io.github.nihildigit.pikpak.UrlRequest
import io.github.nihildigit.pikpak.getFile
import io.github.nihildigit.pikpak.variant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What a [PikPakFileEntry] needs of its remote file: the bytes, a way to
 * resolve the link before anything reads, and a way to let it go.
 *
 * [VariantReader] is the only implementation. The interface is here so an
 * entry can be built without an account, since standing up a [VariantReader]
 * that serves a byte means standing up a `PikPakClient`.
 */
internal interface EntrySource : RangeSource, AutoCloseable {
    /** Resolves the signed link now, so the first read does not pay for it. */
    suspend fun prewarm()
}

/**
 * Keeps one file readable across everything that can invalidate its signed URL.
 *
 * A [RangeReader] already refreshes its own URL on 401/403 by calling back into
 * the provider installed here. This class adds the two things it cannot know
 * about: which cloud file the bytes live in now, and when the current signature
 * is about to expire.
 *
 * The healing ladder, cheapest first:
 *  - `Initial` / `Expired`: re-fetch the file detail and take the link of the
 *    same variant. One request.
 *  - `Rejected`, or an `Initial`/`Expired` whose detail request came back 404:
 *    the id itself stopped resolving. Ask [PikPakDriveIndex] to find the
 *    file again by `(sourceKey, pathInTorrent)`, resubmitting the
 *    source if its bucket is gone, and write the new id back to `meta.json`.
 *
 * This works because the local identity of a file is its path in the torrent,
 * not its cloud id: PikPak deduplicates by content hash, so a bucket that was
 * evicted and refetched yields byte-identical files and the pieces already on
 * disk stay valid. Transcoded variants are equally stable across a refetch.
 *
 * Proactive refresh is done by replacing the reader rather than by telling it
 * to refresh, because [RangeReader] exposes no such call. Replacing is safe: it
 * holds no connection between reads, and a read already in flight keeps the
 * instance it started on.
 */
internal class VariantReader(
    private val clientProvider: suspend () -> PikPakClient,
    private val mediaId: String?,
    private val connectionBudget: Int,
    initialFileId: String,
    private val onRelocated: suspend (newFileId: String, newBucketId: String) -> Unit,
    private val relocate: suspend () -> RelocateResult,
    private val linkSource: VariantLinkSource = PikPakVariantLinkSource(clientProvider),
) : EntrySource {
    private val logger = logger<VariantReader>()

    private val mutex = Mutex()

    @Volatile
    private var fileId: String = initialFileId

    @Volatile
    private var reader: RangeReader? = null

    // Written from provideUrl, which RangeReader calls outside this class's
    // mutex, and read inside it.
    @Volatile
    private var expiresAt: Instant? = null

    @Volatile
    private var closed = false

    /** Current file id, for callers that persist it. */
    val currentFileId: String get() = fileId

    /**
     * A reader whose signature will still be valid for a while. Callers must
     * not hold the result across reads; ask again each time.
     */
    suspend fun get(): RangeReader = mutex.withLock {
        check(!closed) { "VariantReader is closed" }
        val existing = reader
        val expiry = expiresAt
        if (existing != null && (expiry == null || expiry - Clock.System.now() > REFRESH_MARGIN)) {
            return existing
        }
        if (existing != null) {
            logger.info { "[pikpak] link for $fileId expires at $expiry, swapping in a fresh reader" }
            existing.close()
        }
        // Left null on purpose: the replacement has not resolved a URL yet, and
        // keeping the old expiry would make the very next get() replace it again.
        expiresAt = null
        RangeReader(
            client = clientProvider(),
            urlProvider = ::provideUrl,
            connectionBudget = connectionBudget,
        ).also { reader = it }
    }

    override suspend fun <T> read(
        start: Long,
        length: Long,
        priority: Int,
        block: suspend (io.ktor.utils.io.ByteReadChannel) -> T,
    ): T = get().read(start, length, priority, block)

    override suspend fun prewarm() {
        get().prewarm()
    }

    /**
     * The callback [RangeReader] invokes. Internal rather than private so a
     * test can drive the ladder directly instead of having to make a CDN
     * answer 404 first.
     */
    internal suspend fun provideUrl(request: UrlRequest): String {
        // An empty id belongs to a cache imported from the old HTTP path, where
        // there was no cloud file to record. It takes the same branch as a
        // rejection: find the file by bucket and path, resubmitting the source
        // if the bucket is gone. getFile("") would be a 404 and a wasted round
        // trip before landing here anyway.
        val unknownFile = fileId.isEmpty()
        var relocated = false
        if (request is UrlRequest.Rejected || unknownFile) {
            val reason = if (unknownFile) "no recorded file id" else "HTTP ${(request as UrlRequest.Rejected).status}"
            logger.info { "[pikpak] relocating '$fileId' ($reason)" }
            // The CDN refused the link we had, so a cached copy of it is no good either.
            linkSource.invalidate(fileId, mediaId)
            doRelocate()
            relocated = true
        } else if (request is UrlRequest.Expired) {
            // The CDN answered 401/403, which means this link stopped working
            // ahead of its recorded expiry. A cached copy of it is still inside
            // its refresh margin and would be handed straight back, and the
            // reader rejects a provider that returns the URL it just refused.
            linkSource.invalidate(fileId, mediaId)
            expiresAt = null
        }
        val link = try {
            linkSource.linkFor(fileId, mediaId)
        } catch (e: PikPakException) {
            // The id is dead, which the reader cannot report as a rejection
            // because it never got a URL to have rejected. Without this the
            // same failing getFile is repeated on every read and the relocate
            // ladder is never reached at all.
            if (relocated || !isFileGone(e)) throw e
            logger.info { "[pikpak] '$fileId' no longer exists (${e.message}), relocating" }
            linkSource.invalidate(fileId, mediaId)
            doRelocate()
            linkSource.linkFor(fileId, mediaId)
        }
        expiresAt = link.expiresAt
        return link.url
    }

    private suspend fun doRelocate() {
        val result = relocate()
        fileId = result.fileId
        onRelocated(result.fileId, result.bucketId)
    }

    override fun close() {
        closed = true
        reader?.close()
        reader = null
    }

    private companion object {
        /**
         * Whether the failure says the file id itself is gone, as opposed to
         * the request having been unlucky. PikPak answers a `getFile` for an
         * evicted or deleted file with HTTP 404 and `file_not_found`; a 5xx or
         * a timeout must not cost a relocate, which is a listing at best and a
         * resubmit at worst.
         */
        fun isFileGone(e: PikPakException): Boolean =
            e.httpStatus == 404 || e.errorMessage.contains("file_not_found")

        /**
         * PikPak links live 24 hours. Swapping this far ahead keeps the
         * expiry off the playback path entirely; the reader's own 403 handling
         * remains as the backstop for a link revoked early.
         */
        val REFRESH_MARGIN = 5.minutes
    }
}

/** A signed link plus when it stops working. */
internal data class VariantLink(val url: String, val expiresAt: Instant?)

/**
 * Where [VariantReader] gets a link. Separated from the reader so the healing
 * ladder can be exercised without a PikPak account.
 */
internal fun interface VariantLinkSource {
    suspend fun linkFor(fileId: String, mediaId: String?): VariantLink

    /** Forgets a link the CDN has refused. Sources that hold nothing have nothing to forget. */
    suspend fun invalidate(fileId: String, mediaId: String?) {}
}

/**
 * Remembers links across sessions.
 *
 * A [VariantReader] lives as long as its file entry, and an entry as long as
 * its session. Switching episode closes the session once the last handle goes,
 * and with it every link the pack warm-up had resolved; the next session then
 * paid a getFile for the very file that had just been prefetched. The engine
 * owns one of these and every reader goes through it, so a link survives the
 * session that fetched it and is handed out until [REFRESH_MARGIN] before it
 * expires.
 */
internal class CachingVariantLinkSource(
    private val delegate: VariantLinkSource,
    private val clock: Clock = Clock.System,
) : VariantLinkSource {
    private val mutex = Mutex()
    private val links = HashMap<Pair<String, String?>, VariantLink>()

    override suspend fun linkFor(fileId: String, mediaId: String?): VariantLink {
        val key = fileId to mediaId
        mutex.withLock { links[key] }?.let { cached ->
            val expiry = cached.expiresAt
            if (expiry == null || expiry - clock.now() > REFRESH_MARGIN) return cached
        }
        val fresh = delegate.linkFor(fileId, mediaId)
        mutex.withLock { links[key] = fresh }
        return fresh
    }

    override suspend fun invalidate(fileId: String, mediaId: String?) {
        mutex.withLock { links.remove(fileId to mediaId) }
    }

    private companion object {
        /** Same margin as [VariantReader]: a link about to expire is not worth handing out. */
        val REFRESH_MARGIN = 5.minutes
    }
}

internal class PikPakVariantLinkSource(
    private val clientProvider: suspend () -> PikPakClient,
) : VariantLinkSource {
    override suspend fun linkFor(fileId: String, mediaId: String?): VariantLink {
        val resolved = clientProvider().getFile(fileId).variant(mediaId)
            ?: throw PikPakException(-1, "variant $mediaId is no longer present on file $fileId")
        val url = resolved.link.url.takeIf { it.isNotBlank() }
            ?: throw PikPakException(-1, "variant $mediaId on file $fileId has no link")
        return VariantLink(url, resolved.link.expiresAt)
    }
}
