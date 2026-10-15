package me.him188.ani.app.data.models.preference

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PikPakConfigTest {
    @Test
    fun `saved settings without a download limit use the default`() {
        assertEquals(3, Json.decodeFromString<PikPakConfig>("{}").effectiveMaxConcurrentDownloads)
    }

    @Test
    fun `download limit survives persistence and invalid values are bounded`() {
        val settings = PikPakConfig(maxConcurrentDownloads = 6)
        assertEquals(settings, Json.decodeFromString<PikPakConfig>(Json.encodeToString(settings)))
        assertEquals(1, settings.copy(maxConcurrentDownloads = 0).effectiveMaxConcurrentDownloads)
        assertEquals(16, settings.copy(maxConcurrentDownloads = Int.MAX_VALUE).effectiveMaxConcurrentDownloads)
    }
}
