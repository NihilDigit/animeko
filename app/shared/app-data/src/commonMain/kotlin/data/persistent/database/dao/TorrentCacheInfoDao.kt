/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.persistent.database.ProtoConverters
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.storage.MediaCacheSave
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.torrent.api.files.TorrentFileEntry
import me.him188.ani.datasources.api.Media
import me.him188.ani.utils.platform.annotations.TestOnly

/**
 * 存储 BitTorrent 引擎缓存的媒体的信息.
 * 
 * 种子文件的最终目录应该是 [MediaSaveDirProvider.saveDir] + [relativeDir] + [pathInTorrent]
 */
@Entity(
    tableName = "torrent_cache",
    primaryKeys = ["mediaId", "engine"],
    // 非唯一: 同一个 mediaId 现在可以有多行, 每个引擎一行. 索引留着是因为除 stats 之外
    // 所有查询都带 mediaId.
    indices = [Index(value = ["mediaId"])],
)
data class TorrentCacheInfoEntity(
    /**
     * 媒体 ID, 对应 [MediaCacheSave.origin] 中的 [Media.mediaId]
     */
    val mediaId: String,
    /**
     * 建立这一行的引擎, 取 [MediaCacheEngineKey.key] ("anitorrent" / "pikpak").
     *
     * 与 [mediaId] 一起构成主键: 同一个 media 可以同时被两个引擎缓存, 只按 mediaId 建行时后写的
     * 一方会覆盖先写一方的 [torrentData] 与 [relativeDir], 恢复时把 PikPak 的 JSON 喂给
     * anitorrent, 删掉任意一边的缓存又会把另一边的行一起删掉.
     */
    @ColumnInfo(defaultValue = "anitorrent")
    val engine: String,
    /**
     * 种子信息
     */
    val torrentData: ByteArray,
    /**
     * 种子的缓存目录, 相对于 [MediaSaveDirProvider.saveDir] 的相对路径.
     *
     * 注意, 一个 MediaCache 可能只对应该种子资源的其中一个文件.
     */
    val relativeDir: String,
    /**
     * 这个种子里已经有文件下完并达到分享率. 注意主键不含剧集, 整季包的每一集共用这一行, 所以它
     * 说的是「种子」而不是「某一集」: 判断某一集下完没有要看那条记录的 `MediaCacheMetadata.completed`.
     *
     * 仍在写, 因为有三个消费者按种子的粒度读它: 安卓的 `TorrentServiceConnectionManager` 用它决定
     * BT 服务能不能停, `PikPakReseeder` 用它挑要做种的种子, [TorrentMediaCacheEngine.stats] 用它
     * 累计已完成的上传下载量.
     */
    val completed: Boolean = false,
    /**
     * [completed] 对应的那个文件. 同样是种子粒度: 整季包里最先下完的那个, 不代表任何一集.
     *
     * @see TorrentFileEntry.pathInTorrent
     */
    val pathInTorrent: String = "",
    /**
     * 该种子已下载的大小, 字节
     */
    val downloadSize: Long = 0,
    /**
     * 该种子已上传的大小, 字节
     */
    val uploadSize: Long = 0,
    /**
     * 该种子内所有文件的 [TorrentFileEntry.pathInTorrent]. 整季包为其中一集建立缓存后, 靠它判断包里
     * 是否真的有另一集的文件, 不必开启会话.
     *
     * `null` 表示这条记录建立时还没有记录清单, 不是「种子里没有文件」. 这种记录不产生派生命中,
     * 直到它下次被 restore 时补上.
     */
    @field:TypeConverters(ProtoConverters.StringList::class)
    val filesInTorrent: List<String>? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as TorrentCacheInfoEntity

        if (completed != other.completed) return false
        if (downloadSize != other.downloadSize) return false
        if (uploadSize != other.uploadSize) return false
        if (mediaId != other.mediaId) return false
        if (engine != other.engine) return false
        if (!torrentData.contentEquals(other.torrentData)) return false
        if (relativeDir != other.relativeDir) return false
        if (pathInTorrent != other.pathInTorrent) return false
        if (filesInTorrent != other.filesInTorrent) return false

        return true
    }

    override fun hashCode(): Int {
        var result = completed.hashCode()
        result = 31 * result + downloadSize.hashCode()
        result = 31 * result + uploadSize.hashCode()
        result = 31 * result + mediaId.hashCode()
        result = 31 * result + engine.hashCode()
        result = 31 * result + torrentData.contentHashCode()
        result = 31 * result + relativeDir.hashCode()
        result = 31 * result + pathInTorrent.hashCode()
        result = 31 * result + filesInTorrent.hashCode()
        return result
    }
}

@Dao
interface TorrentCacheInfoDao {
    @Query("""SELECT * FROM torrent_cache""")
    fun getAll(): Flow<List<TorrentCacheInfoEntity>>

    @Query("""SELECT * FROM torrent_cache WHERE mediaId = :mediaId AND engine = :engine LIMIT 1""")
    suspend fun get(mediaId: String, engine: String): TorrentCacheInfoEntity?

    @Query("""SELECT * FROM torrent_cache WHERE mediaId in (:mediaIds) AND engine = :engine""")
    suspend fun batchGet(mediaIds: List<String>, engine: String): List<TorrentCacheInfoEntity>

    @Upsert
    suspend fun upsert(item: TorrentCacheInfoEntity)

    @Query("""UPDATE torrent_cache SET filesInTorrent = :files WHERE mediaId = :mediaId AND engine = :engine""")
    suspend fun updateFilesInTorrent(
        mediaId: String,
        engine: String,
        @TypeConverters(ProtoConverters.StringList::class) files: List<String>,
    )

    @Query("""DELETE FROM torrent_cache WHERE mediaId = :mediaId AND engine = :engine""")
    suspend fun deleteByMediaId(mediaId: String, engine: String)
}

@TestOnly
fun createMemoryTorrentCacheInfoDao(): TorrentCacheInfoDao {
    return object : TorrentCacheInfoDao {
        private val store = MemoryDataStore(listOf<TorrentCacheInfoEntity>())

        override fun getAll(): Flow<List<TorrentCacheInfoEntity>> {
            return store.data
        }

        override suspend fun get(mediaId: String, engine: String): TorrentCacheInfoEntity? {
            return store.data.firstOrNull()?.find { it.mediaId == mediaId && it.engine == engine }
        }

        override suspend fun batchGet(mediaIds: List<String>, engine: String): List<TorrentCacheInfoEntity> {
            return store.data.firstOrNull()?.filter { it.mediaId in mediaIds && it.engine == engine } ?: emptyList()
        }

        override suspend fun upsert(item: TorrentCacheInfoEntity) {
            store.updateData {
                val existing = it.indexOfFirst { e -> e.mediaId == item.mediaId && e.engine == item.engine }
                if (existing >= 0) {
                    it.toMutableList().apply { this[existing] = item }
                } else {
                    it + item
                }
            }
        }

        override suspend fun updateFilesInTorrent(mediaId: String, engine: String, files: List<String>) {
            store.updateData { list ->
                list.map {
                    if (it.mediaId == mediaId && it.engine == engine) it.copy(filesInTorrent = files) else it
                }
            }
        }

        override suspend fun deleteByMediaId(mediaId: String, engine: String) {
            store.updateData {
                it.filter { e -> e.mediaId != mediaId || e.engine != engine }
            }
        }
    }
}