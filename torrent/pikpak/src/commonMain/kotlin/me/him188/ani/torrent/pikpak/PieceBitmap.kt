/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.utils.io.SystemPath
import me.him188.ani.utils.io.delete
import me.him188.ani.utils.io.exists
import me.him188.ani.utils.io.moveTo
import me.him188.ani.utils.io.name
import me.him188.ani.utils.io.readBytes
import me.him188.ani.utils.io.resolveSibling
import me.him188.ani.utils.io.writeBytes

/**
 * Which pieces are already on disk, persisted so a restart resumes without asking PikPak.
 *
 * This is the only record of progress: the data file is preallocated to its full length, so
 * its length says nothing about how much of it is real.
 *
 * Held whole in memory and rewritten whole, because it is tiny: a 1.5 GB video at 512 KiB
 * pieces needs under 400 bytes.
 *
 * There is no migration from the sequential format that recorded progress as the data file's
 * length. That format only ever existed on this branch, which has not shipped, so nothing on a
 * user's disk is in it; the one on-disk format that does predate this is a completed file moved
 * in by the WebM3u migration, and [PikPakSavedFiles.importCompletedFile] writes a full bitmap
 * for those.
 */
internal class PieceBitmap(
    private val file: SystemPath,
    private val pieceCount: Int,
) {
    private val byteCount = (pieceCount + 7) / 8
    private val lock = SynchronizedObject()
    private var bits = ByteArray(byteCount)
    private var dirty = false

    /**
     * Reads the stored bitmap, or all-false when there is none.
     *
     * Anything unexpected — missing, short, unreadable — is all-false rather than an error: a
     * lost bitmap costs a re-download, while a bitmap trusted wrongly costs corrupt playback.
     *
     * The data file's length is deliberately not consulted as a fallback. Preallocation makes a
     * barely-started download full length already, so inferring completeness from it would mark
     * uninitialised bytes FINISHED.
     */
    fun load(): BooleanArray {
        val stored = runCatching { if (file.exists()) file.readBytes() else null }.getOrNull()
        val fresh = ByteArray(byteCount)
        if (stored != null && stored.size >= byteCount) {
            stored.copyInto(fresh, endIndex = byteCount)
        }
        synchronized(lock) {
            bits = fresh
            dirty = false
        }
        return BooleanArray(pieceCount) { fresh[it / 8].toInt() and (1 shl (it % 8)) != 0 }
    }

    /** Callable from any fetch worker, so it takes a lock rather than a [kotlinx.coroutines.sync.Mutex]. */
    fun set(listIndex: Int) {
        if (listIndex !in 0 until pieceCount) return
        synchronized(lock) {
            val mask = (1 shl (listIndex % 8)).toByte()
            val old = bits[listIndex / 8]
            if (old.toInt() and mask.toInt() != 0) return
            bits[listIndex / 8] = (old.toInt() or mask.toInt()).toByte()
            dirty = true
        }
    }

    fun clear(listIndex: Int) {
        if (listIndex !in 0 until pieceCount) return
        synchronized(lock) {
            val mask = 1 shl (listIndex % 8)
            val old = bits[listIndex / 8].toInt()
            if (old and mask == 0) return
            bits[listIndex / 8] = (old and mask.inv()).toByte()
            dirty = true
        }
    }

    suspend fun flush() {
        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            bits.copyOf()
        }
        try {
            withContext(Dispatchers.IO_) { writeWhole(snapshot) }
        } catch (e: Throwable) {
            // The bits only ever existed in memory, so the next flush has to carry them again.
            synchronized(lock) { dirty = true }
            throw e
        }
    }

    private fun writeWhole(snapshot: ByteArray) {
        val temp = file.resolveSibling(file.name + ".tmp")
        try {
            temp.writeBytes(snapshot)
            temp.moveTo(file)
        } catch (e: Throwable) {
            // A filesystem that refuses the rename leaves the direct write as the only option; a
            // write torn by a crash reads back as short, which load() treats as nothing
            // downloaded — the safe direction.
            runCatching { if (temp.exists()) temp.delete() }
            file.writeBytes(snapshot)
        }
    }

    suspend fun delete() {
        synchronized(lock) {
            bits = ByteArray(byteCount)
            dirty = false
        }
        withContext(Dispatchers.IO_) {
            runCatching { if (file.exists()) file.delete() }
        }
    }

    companion object {
        /**
         * Where the bitmap of [dataFile] lives. Everyone writing one goes through here, so the
         * fetcher and the importer of an already-complete file cannot drift apart on the name.
         */
        fun pathFor(dataFile: SystemPath): SystemPath = dataFile.resolveSibling(dataFile.name + ".bits")
    }
}
