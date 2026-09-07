/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.torrent.pikpak

import kotlinx.io.bytestring.encodeToByteString
import me.him188.ani.utils.io.DigestAlgorithm
import me.him188.ani.utils.io.digest

/**
 * Deterministic cache key for a torrent source. Used both as a folder name in
 * the user's PikPak drive and as the local save directory name, so it (a) has
 * to be a valid file name and (b) must not collide across distinct sources — a
 * collision would let one source read another's cached bytes.
 *
 * Rules:
 *  - Magnet: take the infohash after `xt=urn:btih:` and canonicalise it.
 *    Magnet URIs can carry the infohash as 40-char hex, 32-char RFC-4648
 *    base32, or 64-char hex (BEP-52 v2). All three are normalised to
 *    uppercase hex so two magnets pointing at the same torrent — even
 *    through different encodings — map to the same bucket.
 *  - HTTP `.torrent` URL (or a magnet with a missing/unrecognised infohash):
 *    SHA-256 of the URL, take the first 16 hex chars (64-bit). The `h-`
 *    prefix avoids clashing with hex-encoded infohashes. `String.hashCode()`
 *    used to live here; 32-bit is not wide enough to rule out a wrong-bucket
 *    hit at scale — SHA-256 eliminates the risk.
 */
internal fun sourceKeyFor(uri: String): String {
    val rawInfoHash = Regex("xt=urn:btih:([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
        .find(uri)?.groupValues?.get(1)
    val canonical = rawInfoHash?.let { canonicalizeBtih(it) }
    if (canonical != null) return canonical
    return "h-" + sha256HexShort(uri)
}

private fun canonicalizeBtih(raw: String): String? {
    val upper = raw.uppercase()
    return when {
        // BEP-9 (SHA-1) infohash — already canonical.
        upper.length == 40 && upper.all { it in HEX_ALPHABET } -> upper
        // BEP-52 (SHA-256) infohash for v2 torrents.
        upper.length == 64 && upper.all { it in HEX_ALPHABET } -> upper
        // RFC-4648 base32, no padding — the other form BEP-9 allows.
        upper.length == 32 && upper.all { it in BASE32_ALPHABET } -> base32ToHexUpper(upper)
        else -> null
    }
}

private const val HEX_ALPHABET = "0123456789ABCDEF"
private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

private fun base32ToHexUpper(s: String): String {
    // 32 base32 chars × 5 bits = 160 bits = 20 bytes = 40 hex chars.
    val out = StringBuilder(s.length * 5 / 4)
    var buffer = 0
    var bitsInBuffer = 0
    for (c in s) {
        val v = BASE32_ALPHABET.indexOf(c)
        buffer = (buffer shl 5) or v
        bitsInBuffer += 5
        while (bitsInBuffer >= 4) {
            bitsInBuffer -= 4
            val nibble = (buffer shr bitsInBuffer) and 0xF
            out.append(HEX_ALPHABET[nibble])
        }
    }
    return out.toString()
}

private fun sha256HexShort(input: String): String {
    // 64-bit (16 hex chars) is overkill for a user's slot namespace but keeps
    // the key short enough to read in logs. Lowercase for easy visual
    // distinction from the uppercase-hex infohash bucket names above.
    val digest = input.encodeToByteString().digest(DigestAlgorithm.SHA256)
    val hexLower = "0123456789abcdef"
    val sb = StringBuilder(16)
    for (i in 0 until 8) {
        val b = digest[i].toInt() and 0xFF
        sb.append(hexLower[b ushr 4])
        sb.append(hexLower[b and 0x0F])
    }
    return sb.toString()
}
