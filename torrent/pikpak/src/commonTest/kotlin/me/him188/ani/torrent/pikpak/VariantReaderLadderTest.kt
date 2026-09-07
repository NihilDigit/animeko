/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.UrlRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Covers the step of the self-healing ladder that costs the most to get wrong:
 * a 404 means the cloud file id is dead, and the reader must resolve the file
 * again by its path in the torrent and persist the id it found. Getting the
 * bookkeeping wrong here would let a session heal once and then fail forever
 * on the id it did not save.
 */
class VariantReaderLadderTest {
    private fun reader(
        initialFileId: String,
        linkSource: VariantLinkSource,
        relocate: suspend () -> RelocateResult,
        onRelocated: suspend (String, String) -> Unit = { _, _ -> },
    ) = VariantReader(
        clientProvider = { error("the ladder must not need a client") },
        mediaId = null,
        connectionBudget = 4,
        initialFileId = initialFileId,
        onRelocated = onRelocated,
        relocate = relocate,
        linkSource = linkSource,
    )

    @Test
    fun `an initial request just resolves the current file id`() = runBlocking {
        val asked = mutableListOf<String>()
        val reader = reader(
            initialFileId = "file-1",
            linkSource = { id, _ -> asked += id; VariantLink("https://cdn/$id", null) },
            relocate = { throw AssertionError("relocate must not run for Initial") },
        )

        assertEquals("https://cdn/file-1", reader.provideUrl(UrlRequest.Initial))
        assertEquals("https://cdn/file-1", reader.provideUrl(UrlRequest.Expired("https://cdn/file-1")))
        assertEquals(listOf("file-1", "file-1"), asked)
    }

    @Test
    fun `a rejection relocates, persists the new id, and keeps using it`() = runBlocking {
        val persisted = mutableListOf<Pair<String, String>>()
        val asked = mutableListOf<String>()
        var relocations = 0
        val reader = reader(
            initialFileId = "file-1",
            linkSource = { id, _ -> asked += id; VariantLink("https://cdn/$id", null) },
            relocate = { relocations++; RelocateResult(fileId = "file-2", bucketId = "bucket-2") },
            onRelocated = { fileId, bucketId -> persisted += fileId to bucketId },
        )

        assertEquals("https://cdn/file-2", reader.provideUrl(UrlRequest.Rejected("https://cdn/file-1", 404)))
        // The next expiry must not relocate again: the id is good now, and a
        // relocate costs a listing at best and a resubmit at worst.
        assertEquals("https://cdn/file-2", reader.provideUrl(UrlRequest.Expired("https://cdn/file-2")))

        assertEquals(1, relocations)
        assertEquals(listOf("file-2" to "bucket-2"), persisted)
        assertEquals(listOf("file-2", "file-2"), asked)
        assertEquals("file-2", reader.currentFileId)
    }

    @Test
    fun `an empty file id relocates instead of asking about a file that does not exist`() = runBlocking {
        // Caches imported from the old HTTP path carry no cloud file id.
        // Passing "" to getFile would be a 404 and a wasted round trip before
        // landing in the relocate branch anyway.
        val asked = mutableListOf<String>()
        var relocations = 0
        val reader = reader(
            initialFileId = "",
            linkSource = { id, _ -> asked += id; VariantLink("https://cdn/$id", null) },
            relocate = { relocations++; RelocateResult(fileId = "found-1", bucketId = "bucket-1") },
        )

        assertEquals("https://cdn/found-1", reader.provideUrl(UrlRequest.Initial))
        assertEquals(1, relocations)
        assertEquals(listOf("found-1"), asked, "getFile must never be called with an empty id")
    }

    @Test
    fun `a file id that is already gone on the first request relocates instead of failing forever`() = runBlocking {
        // A cold start on a cache whose cloud copy was evicted: getFile answers
        // 404, so no URL is ever obtained and the reader never gets to reject
        // one. Without this the same failing getFile is repeated on every read.
        val asked = mutableListOf<String>()
        val persisted = mutableListOf<Pair<String, String>>()
        var relocations = 0
        val reader = reader(
            initialFileId = "file-1",
            linkSource = { id, _ ->
                asked += id
                if (id == "file-1") throw PikPakException(-1, "file_not_found", httpStatus = 404)
                VariantLink("https://cdn/$id", null)
            },
            relocate = { relocations++; RelocateResult(fileId = "file-2", bucketId = "bucket-2") },
            onRelocated = { fileId, bucketId -> persisted += fileId to bucketId },
        )

        assertEquals("https://cdn/file-2", reader.provideUrl(UrlRequest.Initial))
        assertEquals(1, relocations)
        assertEquals(listOf("file-1", "file-2"), asked)
        assertEquals(listOf("file-2" to "bucket-2"), persisted)
        assertEquals("file-2", reader.currentFileId)
    }

    @Test
    fun `a failure that is not a missing file is not worth a relocate`() = runBlocking {
        val reader = reader(
            initialFileId = "file-1",
            linkSource = { _, _ -> throw PikPakException(-1, "internal error", httpStatus = 500) },
            relocate = { throw AssertionError("a 500 must not cost a listing and a resubmit") },
        )

        assertFailsWith<PikPakException> { reader.provideUrl(UrlRequest.Initial) }
        assertEquals("file-1", reader.currentFileId)
    }

    @Test
    fun `an expired link is dropped from the cache instead of being handed back`() = runBlocking {
        // The CDN answered 401/403, so the link is dead well before the expiry
        // it advertises. A cache keyed on that expiry would return it again,
        // and the reader rejects a provider that hands back the URL it just
        // refused.
        var issued = 0
        val far = Clock.System.now() + 12.hours
        val source = CachingVariantLinkSource(
            delegate = { id, _ -> issued++; VariantLink("https://cdn/$id/$issued", far) },
        )
        val reader = reader(
            initialFileId = "file-1",
            linkSource = source,
            relocate = { throw AssertionError("an expiry must not relocate") },
        )

        val first = reader.provideUrl(UrlRequest.Initial)
        assertEquals("https://cdn/file-1/1", first)
        // Nothing rejected this one, so the cached link is still the answer.
        assertEquals(first, reader.provideUrl(UrlRequest.Initial))

        val second = reader.provideUrl(UrlRequest.Expired(first))
        assertNotEquals(first, second, "the revoked link came back unchanged")
        assertEquals(2, issued)
    }

    @Test
    fun `a relocation failure propagates instead of leaving a stale id in place`() = runBlocking {
        val reader = reader(
            initialFileId = "file-1",
            linkSource = { id, _ -> VariantLink("https://cdn/$id", null) },
            relocate = { throw PikPakTaskException("bucket gone and resubmit produced nothing") },
        )

        assertFailsWith<PikPakTaskException> {
            reader.provideUrl(UrlRequest.Rejected("https://cdn/file-1", 404))
        }
        assertEquals("file-1", reader.currentFileId)
    }
}
