plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `ani-mpp-lib-targets`
    alias(libs.plugins.kotlin.plugin.serialization)
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
    }
    sourceSets.getByName("desktopTest").dependencies {
        implementation(libs.kotlinx.coroutines.test)
        implementation(kotlin("test"))
    }
}
