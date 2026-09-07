/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.torrent.seeding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoDao
import me.him188.ani.app.data.persistent.database.dao.TorrentCacheInfoEntity
import me.him188.ani.app.domain.media.cache.MediaCache
import me.him188.ani.app.domain.media.cache.engine.MediaCacheEngineKey
import me.him188.ani.app.domain.media.cache.engine.TorrentEngineAccess
import me.him188.ani.app.domain.media.cache.engine.UnsafeTorrentEngineAccessApi
import me.him188.ani.app.domain.media.cache.storage.MediaSaveDirProvider
import me.him188.ani.app.domain.torrent.TorrentEngine
import me.him188.ani.app.torrent.api.TorrentDownloader
import me.him188.ani.app.torrent.api.TorrentSession
import me.him188.ani.app.torrent.api.files.EncodedTorrentInfo
import me.him188.ani.app.torrent.api.files.FilePriority
import me.him188.ani.app.torrent.api.files.TorrentFileHandle
import me.him188.ani.torrent.pikpak.PikPakSavedFiles
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.absolutePath
import me.him188.ani.utils.io.copyTo
import me.him188.ani.utils.io.createDirectories
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.inSystem
import me.him188.ani.utils.io.length
import me.him188.ani.utils.io.resolve
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration.Companion.minutes

/**
 * 把 PikPak 已经下全的原画文件交给 anitorrent 做种.
 *
 * 云盘里那份字节和种子里的逐字节相同, 所以不必重新下载: 把文件硬链接进 anitorrent 的保存目录,
 * 再把同一个磁链交给 anitorrent, 让它校验磁盘并把这些 piece 分享出去. 硬链接不占额外空间,
 * 两个引擎读的是同一份数据.
 *
 * 只做原画. 转码变体是另一套字节 (MPEG-TS), 校验必然失败.
 *
 * 开关关闭时把这些任务从 anitorrent 撤掉并删掉硬链接, PikPak 那份文件不动.
 *
 * 安卓上做种会启动 `AniTorrentService` 并弹出前台通知. 这是 BT 分享本来就有的行为, 不是这里
 * 要绕开的东西: 用户打开了开关就是要分享.
 */
class PikPakReseeder(
    private val pikpakCaches: Flow<List<MediaCache>>,
    private val cacheInfo: TorrentCacheInfoDao,
    private val anitorrentEngine: TorrentEngine,
    private val anitorrentAccess: TorrentEngineAccess,
    private val reseedingEnabled: Flow<Boolean>,
    private val baseSaveDirProvider: MediaSaveDirProvider,
) {
    private val logger = logger<PikPakReseeder>()

    private val active = mutableMapOf<String, SeedingTorrent>()

    /** 是否已经向 [anitorrentAccess] 要求引擎保持可用. 有任务在做种时为 `true`. */
    private var holdingEngine = false

    /**
     * 一直运行, 直到调用方取消.
     */
    suspend fun run() {
        combine(reseedingEnabled, pikpakCaches, cacheInfo.getAll()) { enabled, caches, entities ->
            seedPlan(enabled, caches, entities, ::sessionDirOf, PikPakSavedFiles::isCompleteOriginal)
        }
            // 判定候选要读 meta.json 和 stat 文件, 而 torrent_cache 表每更新一次进度就会重新
            // emit 一遍, 这些同步 IO 不能落在收集方的线程上.
            .flowOn(Dispatchers.IO_)
            .distinctUntilChanged()
            // 不用 collectLatest: reconcile 中途被取消会留下开着的会话和一半的硬链接, 而这几个
            // 上游 flow 都不高频, 排队执行的代价可以忽略.
            .collect { wanted -> reconcile(wanted) }
    }

    /** 撤掉全部做种任务. 调用方在关闭时使用. */
    suspend fun stopAll() = reconcile(emptyMap())

    private fun sessionDirOf(entity: TorrentCacheInfoEntity): SystemPath =
        Path(baseSaveDirProvider.saveDir, entity.relativeDir).inSystem

    private suspend fun reconcile(wanted: Map<String, List<SeedCandidate>>) {
        // 有任务要开始就先提出请求, 再去拿 downloader: 安卓上 getDownloader() 挂起等 service 连上,
        // 而让它连上的正是这个请求. 放在 start 之后就是死等, 而且这个 flow 是顺序收集的, 挂住之后
        // 连「开关被关掉」都收不到.
        if (wanted.isNotEmpty()) holdEngine(true)
        for ((uri, seeding) in active.entries.toList()) {
            val now = wanted[uri]
            // 文件集合变了就整个重来: 新完成的记录要靠重新加入任务 (丢掉 resume 记录) 才能被
            // libtorrent 校验到, 没有别的入口。
            if (now == null || now.mapTo(mutableSetOf()) { it.flatName } != seeding.seededFlatNames) {
                stop(uri, seeding)
            }
        }
        for ((uri, candidates) in wanted) {
            if (uri in active) continue
            start(uri, candidates)
        }
        updateEngineHold()
    }

    /** 一轮 [reconcile] 结束后按实际在做种的任务收敛请求: 一个都没起来就把开头那次请求还回去. */
    private fun updateEngineHold() = holdEngine(active.isNotEmpty())

    @OptIn(UnsafeTorrentEngineAccessApi::class)
    private fun holdEngine(hold: Boolean) {
        if (hold == holdingEngine) return
        // 做种要引擎一直活着, 不是一次调用就结束, 所以不能用 withServiceRequest 包一个 block:
        // 请求在这一轮的第一个任务开始之前提出, 在最后一个任务撤掉时释放.
        anitorrentAccess.requestService(this, hold)
        holdingEngine = hold
    }

    private suspend fun start(uri: String, candidates: List<SeedCandidate>) {
        val downloader = try {
            anitorrentEngine.getDownloader()
        } catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            logger.warn(e) { "[reseed] anitorrent unavailable, not seeding ${candidates.size} file(s)" }
            return
        }

        val data = try {
            downloader.fetchTorrent(uri)
        } catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            logger.warn(e) { "[reseed] failed to fetch torrent for ${uri.take(60)}" }
            return
        }

        val torrentPaths = readTorrentPaths(downloader, data) ?: return
        val saveDir = downloader.getSaveDirForTorrent(data)
        val placed = placeFiles(candidates, torrentPaths, saveDir)
        if (placed.isEmpty()) {
            logger.info { "[reseed] nothing placed for ${uri.take(60)}, not seeding" }
            return
        }

        // 上一步的探测会话关闭时把 resume 记录写回了盘上, 那份记录里一个 piece 都没有. 不丢掉它,
        // 这次加入就会跳过校验, 刚放进去的文件对 libtorrent 等于不存在. 丢记录只在没有会话开着时
        // 成立, 而走到这里就说明探测会话真的关掉了 (见 [readTorrentPaths]).
        downloader.discardResumeData(data)

        val session = downloader.startDownload(data)
        val handles = session.getFiles()
            .filter { it.pathInTorrent in placed.keys }
            // 其余文件没有 handle, 优先级保持 0, 不会被下载. 做种的这个设为 NORMAL, 盘上已经是
            // 完整的, 校验之后同样不产生下载.
            .map { it.createHandle().also { handle -> handle.resume(FilePriority.NORMAL) } }

        active[uri] = SeedingTorrent(
            data = data,
            session = session,
            handles = handles,
            createdLinks = createdLinksOf(placed.values),
            seededFlatNames = candidates.mapTo(mutableSetOf()) { it.flatName },
        )
        logger.info { "[reseed] seeding ${placed.size} file(s) of ${uri.take(60)} from ${saveDir.absolutePath}" }
    }

    /**
     * 先把种子加进来一次, 只为拿到文件清单.
     *
     * 云盘把种子压平了 (见 [matchTorrentPath]), 不看种子的元数据就不知道硬链接该放在哪个相对路径
     * 上; 而 libtorrent 是在任务加入时校验磁盘的, 没有「先拿清单再校验」的顺序可调. 于是加两次,
     * 中间放文件. 磁链的元数据要从 DHT 取, 所以这一次可能要等一会儿, 也可能等不到.
     *
     * 同一个磁链在 anitorrent 里只有一个会话, [TorrentDownloader.startDownload] 拿到的可能是别人
     * (在播的本地 BT 回退、anitorrent 自己的缓存记录) 正在用的那个. 关不掉就说明有人开着文件, 这时
     * 返回 `null` 放弃这个种子: 强行关掉会把对方的播放或下载打断, 而这些字节本来就在被 anitorrent
     * 下载或分享, 没有再做一遍的必要. 下次机会是缓存记录再变一次.
     */
    private suspend fun readTorrentPaths(
        downloader: TorrentDownloader,
        data: EncodedTorrentInfo,
    ): List<String>? {
        val probe = try {
            downloader.startDownload(data)
        } catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            logger.warn(e) { "[reseed] failed to open a probe session" }
            return null
        }
        val files = try {
            withTimeoutOrNull(METADATA_TIMEOUT) { probe.getFiles() }
        } catch (e: Throwable) {
            runCatching { probe.closeIfNotInUse() }
            throw e
        }
        if (!probe.closeIfNotInUse()) {
            logger.info { "[reseed] session in use by another consumer, leaving this torrent alone" }
            return null
        }
        if (files == null) {
            // 这一轮不重试: 计划没变时上游不会再 emit, 下次机会是缓存记录再变一次或者重启.
            // 元数据取不到通常意味着这个种子当下没人做种, 立刻重试也拿不到.
            logger.warn { "[reseed] no metadata within $METADATA_TIMEOUT, giving up for now" }
            return null
        }
        return files.map { it.pathInTorrent }
    }

    /**
     * @return 实际放好的 种子内路径 -> 放置结果.
     */
    private fun placeFiles(
        candidates: List<SeedCandidate>,
        torrentPaths: List<String>,
        saveDir: SystemPath,
    ): Map<String, Placement> {
        val placed = mutableMapOf<String, Placement>()
        for (candidate in candidates) {
            val pathInTorrent = matchTorrentPath(torrentPaths, candidate.flatName)
            if (pathInTorrent == null) {
                logger.warn { "[reseed] no unique torrent file named '${candidate.flatName}', skipping it" }
                continue
            }
            val target = saveDir.resolve(pathInTorrent)
            val existingLength = if (target.exists()) target.length() else null
            val decision = decidePlacement(target, existingLength, candidate.sourceFile.length())
            if (!decision.createdByUs) {
                placed[pathInTorrent] = decision
                continue
            }
            target.path.parent?.inSystem?.createDirectories()
            if (!createHardLink(candidate.sourceFile, target)) {
                logger.info { "[reseed] hard link refused for '$pathInTorrent', copying instead" }
                try {
                    candidate.sourceFile.copyTo(target)
                } catch (e: Throwable) {
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    logger.warn(e) { "[reseed] failed to place '$pathInTorrent'" }
                    continue
                }
            }
            placed[pathInTorrent] = decision
        }
        return placed
    }

    private suspend fun stop(uri: String, seeding: SeedingTorrent) {
        active.remove(uri)
        for (handle in seeding.handles) {
            runCatching { handle.close() }
        }
        val closed = runCatching { seeding.session.closeIfNotInUse() }.getOrDefault(false)
        if (closed) {
            for (link in seeding.createdLinks) {
                // 删的是目录项, 不是字节: PikPak 目录里那份还在, 硬链接本来就没有占额外空间.
                runCatching { if (link.exists()) link.delete() }
            }
        } else {
            // 会话还开着说明在做种期间有别人 (播放或缓存记录) 接手了同一个种子, 这些路径正被它读写.
            // 链接留在原地, 下次撤种或者清理保存目录时再说.
            logger.info { "[reseed] session still in use, keeping ${seeding.createdLinks.size} link(s)" }
        }
        logger.info { "[reseed] stopped seeding ${uri.take(60)}" }
    }

    private class SeedingTorrent(
        val data: EncodedTorrentInfo,
        val session: TorrentSession,
        val handles: List<TorrentFileHandle>,
        /** 只有本次运行放进去的文件. 复用的那些不在其中, 见 [Placement]. */
        val createdLinks: List<SystemPath>,
        val seededFlatNames: Set<String>,
    )

    private companion object {
        val METADATA_TIMEOUT = 2.minutes
    }
}

/**
 * 一个做种文件在 anitorrent 保存目录里的落点.
 */
internal data class Placement(
    val target: SystemPath,
    /**
     * 这个位置上的文件是不是本次运行放进去的.
     *
     * 撤任务时只删本次放进去的那些. 复用的那份可能是 anitorrent 自己下的正片, 删掉它等于用户
     * 关一下做种开关就丢了一份已经下好的缓存.
     */
    val createdByUs: Boolean,
)

/**
 * 目标位置上已经有一份等长的文件时复用, 否则由本次运行创建.
 *
 * 等长即认为是同一份: 硬链接指向的就是同一批字节, 而 anitorrent 自己下完的那份是按同一个种子
 * 校验过的. 逐字节比对要读满整个文件, 换不到更多的确定性.
 *
 * @param existingLength 目标位置上那份文件的长度, 不存在时为 `null`.
 */
internal fun decidePlacement(target: SystemPath, existingLength: Long?, sourceLength: Long): Placement =
    Placement(target, createdByUs = existingLength != sourceLength)

/** 撤任务时该删掉的路径. */
internal fun createdLinksOf(placements: Collection<Placement>): List<SystemPath> =
    placements.filter { it.createdByUs }.map { it.target }

/**
 * 一个可以拿去做种的文件.
 */
internal data class SeedCandidate(
    /** 磁链或 `.torrent` 地址. */
    val uri: String,
    val mediaId: String,
    /** PikPak 目录里那份字节的位置. */
    val sourceFile: SystemPath,
    /** PikPak 记录的文件名. 见 [matchTorrentPath], 它是个裸文件名. */
    val flatName: String,
)

/**
 * 这一刻应该做种的任务, 按种子地址分组.
 *
 * 开关关闭时是空的, 而不是「保持不变」: 上层拿这个结果与正在做种的集合求差, 空集合就是全部撤掉.
 */
internal fun seedPlan(
    enabled: Boolean,
    caches: List<MediaCache>,
    entities: List<TorrentCacheInfoEntity>,
    sessionDirOf: (TorrentCacheInfoEntity) -> SystemPath,
    isSeedableOriginal: (sessionDir: SystemPath, pathInTorrent: String) -> Boolean,
): Map<String, List<SeedCandidate>> {
    if (!enabled) return emptyMap()
    return selectSeedCandidates(caches, entities, sessionDirOf, isSeedableOriginal).groupBy { it.uri }
}

/**
 * 挑出可以做种的缓存记录.
 *
 * 四个条件缺一不可: 有对应的 `torrent_cache` 记录、记录已完成、记录知道自己是哪个文件、盘上那份
 * 是原画且已经完整.
 *
 * 完成与文件路径取自每条记录自己的 [MediaCache.metadata], 数据库行只提供保存目录和种子数据.
 * 行按 mediaId 建主键, 整季包各集共用一行, 行里的 completed/pathInTorrent 说的是「这个种子里
 * 有文件下完了, 是哪个」. 拿它当每一集的答案, 包里所有剧集会一起指向最先下完的那个文件, 而且
 * 那个文件对其他剧集来说可能才下了一半.
 */
internal fun selectSeedCandidates(
    caches: List<MediaCache>,
    entities: List<TorrentCacheInfoEntity>,
    sessionDirOf: (TorrentCacheInfoEntity) -> SystemPath,
    isSeedableOriginal: (sessionDir: SystemPath, pathInTorrent: String) -> Boolean,
): List<SeedCandidate> {
    // 表里两个引擎的行并存, anitorrent 那一行的 relativeDir 指向它自己的保存目录, 不是云盘字节的位置.
    val byMediaId = entities.filter { it.engine == MediaCacheEngineKey.PikPak.key }.associateBy { it.mediaId }
    return caches.mapNotNull { cache ->
        val entity = byMediaId[cache.origin.mediaId] ?: return@mapNotNull null
        val metadata = cache.metadata
        if (!metadata.completed) return@mapNotNull null
        val pathInTorrent = metadata.pathInTorrent?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val sessionDir = sessionDirOf(entity)
        if (!isSeedableOriginal(sessionDir, pathInTorrent)) return@mapNotNull null
        val uri = cache.origin.download.uri.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        SeedCandidate(
            uri = uri,
            mediaId = entity.mediaId,
            sourceFile = sessionDir.resolve(pathInTorrent),
            // 记录里的路径可能带子目录 (`specials/S00E01.mkv`), 匹配只认裸文件名.
            flatName = pathInTorrent.substringAfterLast('/'),
        )
    }
}

/**
 * 种子里哪个文件对应 PikPak 的 [flatName].
 *
 * 设计文档里「PikPak 目录里的相对路径与种子文件路径逐字相同」这个前提不成立: 引擎把种子的顶层
 * 目录去掉了, 所以种子里的 `Pack/01.mkv` 在 PikPak 这边叫 `01.mkv`, `Pack/specials/01.mkv` 叫
 * `specials/01.mkv`. 硬链接用的是种子里的路径, PikPak 的路径只用来找那份字节从哪来.
 *
 * 只按裸文件名匹配, 不比对子目录: 顶层目录被去掉这一点是确定的, 但两边的中间目录是否逐字相同
 * 没有保证, 多比一段只会多一种匹配不上的方式. 种子里同名文件不止一个时无从分辨, 返回 `null`
 * 放弃, 不猜: 猜错的代价是校验失败之后 libtorrent 把那份字节当成损坏数据重新下载.
 */
internal fun matchTorrentPath(torrentPaths: List<String>, flatName: String): String? =
    torrentPaths.singleOrNull { it.substringAfterLast('/').substringAfterLast('\\') == flatName }
