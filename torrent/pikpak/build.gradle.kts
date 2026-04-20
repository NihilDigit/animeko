plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `ani-mpp-lib-targets`
    alias(libs.plugins.kotlin.plugin.serialization)
}

// Propagate PIKPAK_* vars from the repo-root .env file into JVM test tasks
// so PikPakLiveSmokeTest / CleanupProbeTest can talk to the live service.
// .env lines may use `KEY=value` or `KEY = value` (the latter matches what
// the user already wrote); comment lines (#) and blanks are ignored.
tasks.withType<Test>().configureEach {
    val dotenv = rootProject.file(".env")
    if (!dotenv.exists()) return@configureEach
    dotenv.readLines().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim().trim('"').trim('\'')
        if (key.startsWith("PIKPAK_")) environment(key, value)
    }
}

kotlin {
    androidLibrary {
        namespace = "me.him188.ani.torrent.pikpak"
    }
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.datetime)
        api(projects.utils.platform)
        api(projects.utils.coroutines)
        api(projects.utils.io)
        api(projects.utils.ktorClient)
        api(projects.utils.logging)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.ktor.client.content.negotiation)
        implementation(libs.ktor.serialization.kotlinx.json)
        // Auth, captcha, rate limiting, OSS signing, GCID etc. live in the
        // SDK — this module only supplies the offline-task orchestration
        // layer on top. See https://github.com/NihilDigit/pikpak-kotlin.
        api("io.github.nihildigit:pikpak-kotlin:0.4.1")
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
        implementation(libs.kotlinx.coroutines.test)
    }
    sourceSets.getByName("desktopTest").dependencies {
        implementation(libs.kotlinx.coroutines.test)
        implementation(kotlin("test"))
    }
}
