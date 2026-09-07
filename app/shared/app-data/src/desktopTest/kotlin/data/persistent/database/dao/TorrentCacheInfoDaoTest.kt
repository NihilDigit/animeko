/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.persistent.database.AniDatabase
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 同一个 media 可以同时被 anitorrent 和 PikPak 缓存: PikPak 排在引擎列表前面接管 BT 源, 用户此前
 * 用 anitorrent 缓存过的资源仍在盘上. 主键只有 mediaId 时后写的引擎会盖掉先写的那一行, 恢复时把
 * PikPak 的 JSON 喂给 anitorrent, 删掉任意一边又会把另一边的行一起删掉.
 */
class TorrentCacheInfoDaoTest {
    private fun runDatabaseTest(block: suspend (TorrentCacheInfoDao) -> Unit) = runBlocking {
        val database: AniDatabase = createTestAniDatabase()
        try {
            block(database.torrentCacheInfoDao())
        } finally {
            database.close()
        }
    }

    private fun entity(engine: MediaCacheEngineKey, torrentData: Byte) = TorrentCacheInfoEntity(
        mediaId = MEDIA_ID,
        engine = engine.key,
        torrentData = byteArrayOf(torrentData),
        relativeDir = "/${engine.key}/dir",
    )

    @Test
    fun `两个引擎缓存同一个 media 时各自读到自己的行`() = runDatabaseTest { dao ->
        dao.upsert(entity(MediaCacheEngineKey.Anitorrent, 1))
        dao.upsert(entity(MediaCacheEngineKey.PikPak, 2))

        assertEquals(2, dao.getAll().first().size)

        val anitorrent = assertNotNull(dao.get(MEDIA_ID, MediaCacheEngineKey.Anitorrent.key))
        assertContentEquals(byteArrayOf(1), anitorrent.torrentData)
        assertEquals("/anitorrent/dir", anitorrent.relativeDir)

        val pikpak = assertNotNull(dao.get(MEDIA_ID, MediaCacheEngineKey.PikPak.key))
        assertContentEquals(byteArrayOf(2), pikpak.torrentData)
        assertEquals("/pikpak/dir", pikpak.relativeDir)
    }

    @Test
    fun `删掉一个引擎的行不影响另一个引擎`() = runDatabaseTest { dao ->
        dao.upsert(entity(MediaCacheEngineKey.Anitorrent, 1))
        dao.upsert(entity(MediaCacheEngineKey.PikPak, 2))

        dao.deleteByMediaId(MEDIA_ID, MediaCacheEngineKey.PikPak.key)

        assertNull(dao.get(MEDIA_ID, MediaCacheEngineKey.PikPak.key))
        assertNotNull(dao.get(MEDIA_ID, MediaCacheEngineKey.Anitorrent.key))
    }

    @Test
    fun `文件清单只写进指定引擎的行`() = runDatabaseTest { dao ->
        dao.upsert(entity(MediaCacheEngineKey.Anitorrent, 1))
        dao.upsert(entity(MediaCacheEngineKey.PikPak, 2))

        dao.updateFilesInTorrent(MEDIA_ID, MediaCacheEngineKey.PikPak.key, listOf("a.mkv"))

        assertEquals(listOf("a.mkv"), dao.get(MEDIA_ID, MediaCacheEngineKey.PikPak.key)?.filesInTorrent)
        assertNull(dao.get(MEDIA_ID, MediaCacheEngineKey.Anitorrent.key)?.filesInTorrent)
    }

    @Test
    fun `batchGet 只返回指定引擎的行`() = runDatabaseTest { dao ->
        dao.upsert(entity(MediaCacheEngineKey.Anitorrent, 1))
        dao.upsert(entity(MediaCacheEngineKey.PikPak, 2))

        val rows = dao.batchGet(listOf(MEDIA_ID), MediaCacheEngineKey.Anitorrent.key)
        assertEquals(1, rows.size)
        assertEquals("/anitorrent/dir", rows.single().relativeDir)
    }

    private companion object {
        const val MEDIA_ID = "dmhy.1"
    }
}
