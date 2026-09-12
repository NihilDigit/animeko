/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

plugins {
    id("ani.kmp-library")
    alias(libs.plugins.kotlin.plugin.serialization)
}

// Propagate PikPak credentials into JVM test tasks so PikPakLiveSmokeTest can
// talk to the live service. Two sources, both git-ignored:
//   - repo-root local.properties: `pikpak-username`, `pikpak-password`,
//     optional `pikpak-magnet`, mapped to PIKPAK_USERNAME / PIKPAK_PASSWORD /
//     PIKPAK_MAGNET.
//   - repo-root .env: `PIKPAK_*` lines, `KEY=value` or `KEY = value`.
// Comment lines (#) and blanks are ignored. local.properties wins on conflict.
tasks.withType<Test>().configureEach {
    fun readKeyValues(file: File): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return file.readLines().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val eq = line.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim().trim('"').trim('\'')
            key to value
        }.toMap()
    }

    readKeyValues(rootProject.file(".env"))
        .filterKeys { it.startsWith("PIKPAK_") }
        .forEach { (key, value) -> environment(key, value) }
    readKeyValues(rootProject.file("local.properties"))
        .filterKeys { it.startsWith("pikpak-") }
        .forEach { (key, value) ->
            environment("PIKPAK_" + key.removePrefix("pikpak-").replace('-', '_').uppercase(), value)
        }
}

kotlin {
    android {
        namespace = "me.him188.ani.torrent.pikpak"
    }
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.datetime)
        api(projects.torrent.torrentApi)
        api(projects.utils.platform)
        api(projects.utils.coroutines)
        api(projects.utils.io)
        api(projects.utils.ktorClient)
        api(projects.utils.logging)
        implementation(libs.atomicfu)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.ktor.client.content.negotiation)
        implementation(libs.ktor.serialization.kotlinx.json)
        // Auth, captcha, rate limiting, OSS signing, GCID, magnet resolution,
        // the seekable stream reader and the resuming downloader all live in
        // the SDK; this module supplies the cloud-object lifecycle, the
        // TorrentDownloader surface and the on-disk layout.
        // See https://github.com/NihilDigit/pikpak-kotlin.
        api("io.github.nihildigit:pikpak-kotlin:0.6.2")
    }
    sourceSets.commonTest.dependencies {
        // kotlin-test + kotlinx-coroutines-test come in transitively from
        // :utils:testing (injected by ani-mpp-lib-targets). Declaring
        // `kotlin("test")` here triggers JVM variant inference to
        // kotlin-test-junit (JUnit 4), which collides with the JUnit 5
        // variant the de.mannodermaus.android-junit5 plugin pulls onto
        // androidDeviceTestCompileClasspath — same capability, two modules.
    }
    sourceSets.getByName("desktopTest").dependencies {
        // Mock engine drives PikPakKtorAbiCompatTest, which forces the SDK's
        // Ktor companion-object accesses (HttpMethod.Post, ContentType.*, ...)
        // to resolve against animeko's pinned Ktor version. If the SDK was
        // built against an incompatible Ktor ABI, the first request path
        // throws IllegalAccessError at link time and the test fails.
        implementation(libs.ktor.client.mock)
    }
}
