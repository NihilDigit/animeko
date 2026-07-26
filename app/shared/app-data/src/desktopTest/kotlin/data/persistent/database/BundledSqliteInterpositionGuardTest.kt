/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import me.him188.ani.utils.platform.currentPlatformDesktop
import me.him188.ani.utils.platform.isLinux
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for #3188 / #3195 / #3213.
 *
 * The crash these tests protect against is a load-order race that cannot be triggered on demand,
 * so they assert the *invariant* instead of the symptom: after the guard runs, the process must
 * contain exactly one `libsqliteJni.so` mapping, and it must be the guard's copy rather than the
 * driver's own `RTLD_LOCAL` extraction under the system temp directory.
 *
 * This also acts as the canary for androidx.sqlite upgrades — the redirect relies on the
 * semi-internal `androidx.sqlite.driver.bundled.path`/`.name` properties, and an upgrade that
 * stops honouring them shows up here as a second mapping.
 */
class BundledSqliteInterpositionGuardTest {
    @Test
    fun `driver reuses the preloaded image instead of extracting its own`() {
        if (!currentPlatformDesktop().isLinux()) return

        BundledSqliteInterpositionGuard.install(Files.createTempDirectory("ani-sqlite-guard-test"))

        // Forces BundledSQLiteDriver to load its JNI library, which is what would extract a second
        // copy into /tmp if the redirect properties were not in place before this point.
        BundledSQLiteDriver().open(":memory:").use { }

        val mappings = sqliteJniMappings()
        assertEquals(
            1, mappings.size,
            "expected exactly one libsqliteJni image in the process, got $mappings",
        )
        assertTrue(
            mappings.single().endsWith(BundledSqliteInterpositionGuard.LIB_FILE_NAME),
            "the mapped image is not the guard's copy: ${mappings.single()}",
        )
    }

    @Test
    fun `install is idempotent`() {
        if (!currentPlatformDesktop().isLinux()) return

        BundledSqliteInterpositionGuard.install(Files.createTempDirectory("ani-sqlite-guard-test"))
        val path = System.getProperty("androidx.sqlite.driver.bundled.path")
        val name = System.getProperty("androidx.sqlite.driver.bundled.name")

        // A second call must not repoint the driver at a different file: by the time anything calls
        // install() twice, the driver may already have resolved these properties.
        BundledSqliteInterpositionGuard.install(Files.createTempDirectory("ani-sqlite-guard-test-2"))

        assertEquals(path, System.getProperty("androidx.sqlite.driver.bundled.path"))
        assertEquals(name, System.getProperty("androidx.sqlite.driver.bundled.name"))
        assertEquals(1, sqliteJniMappings().size)
    }
}
