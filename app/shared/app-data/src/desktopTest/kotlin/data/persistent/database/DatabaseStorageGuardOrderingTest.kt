/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database

import androidx.compose.ui.window.WindowState
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import me.him188.ani.app.platform.DesktopContext
import me.him188.ani.app.platform.ExtraWindowProperties
import me.him188.ani.utils.platform.currentPlatformDesktop
import me.him188.ani.utils.platform.isLinux
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asserts constraint 1 of [BundledSqliteInterpositionGuard] structurally: obtaining a Room builder
 * must install the guard, so no `BundledSQLiteDriver` can reach sqlite ahead of it no matter how
 * desktop startup is later reordered. This is the invariant #3195 broke by installing the guard
 * from a coroutine that raced Koin.
 *
 * **Must run in a JVM where nothing else has installed the guard.** [install] is idempotent and
 * process-global, so any earlier caller would set the redirect properties and mask a missing hook.
 * Hence its own class and its own CI step with a `--tests` filter, rather than a method on
 * [BundledSqliteInterpositionGuardTest].
 */
class DatabaseStorageGuardOrderingTest {
    @Test
    fun `createDatabaseBuilder installs the guard before handing out a builder`() {
        if (!currentPlatformDesktop().isLinux()) return

        val root = Files.createTempDirectory("ani-guard-ordering-test")
        val context = DesktopContext(
            windowState = WindowState(),
            dataDir = root.resolve("data").toFile(),
            cacheDir = root.resolve("cache").toFile(),
            logsDir = root.resolve("logs").toFile(),
            extraWindowProperties = ExtraWindowProperties(),
        )

        context.createDatabaseBuilder()

        assertNotNull(
            System.getProperty("androidx.sqlite.driver.bundled.path"),
            "createDatabaseBuilder returned without installing the guard; " +
                    "the driver is free to extract its own RTLD_LOCAL copy",
        )

        // Nothing installed the guard before this test, so the driver's image can only be correct
        // if createDatabaseBuilder put the redirect in place above.
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
}
