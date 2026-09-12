/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.datasources.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaCacheMetadataSerializationTest {
    // 与 DataStoreJson 同构: 缓存记录就是用这个配置写进 mediaCacheMetadataV2 的
    private val json = Json { ignoreUnknownKeys = true }

    private fun metadata(pathInTorrent: String? = null) = MediaCacheMetadata(
        subjectId = "1",
        episodeId = "2",
        subjectNames = emptyList(),
        episodeSort = EpisodeSort("11.5"),
        episodeName = "SP",
        pathInTorrent = pathInTorrent,
    )

    @Test
    fun `record written before pathInTorrent existed decodes to null`() {
        // 没有这个字段的记录就是这个形状: 默认值不写盘, 所以它同时也是今天不带选择的记录的形状
        val encoded = json.encodeToString(MediaCacheMetadata.serializer(), metadata())
        assertEquals(false, encoded.contains("pathInTorrent"))
        assertNull(json.decodeFromString(MediaCacheMetadata.serializer(), encoded).pathInTorrent)
    }

    @Test
    fun `pathInTorrent survives a round trip`() {
        val original = metadata("specials/S00E01.mkv")
        assertEquals(
            original.pathInTorrent,
            json.decodeFromString(
                MediaCacheMetadata.serializer(),
                json.encodeToString(MediaCacheMetadata.serializer(), original),
            ).pathInTorrent,
        )
    }
}
