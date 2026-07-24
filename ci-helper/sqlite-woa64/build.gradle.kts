/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

plugins {
    `java-library`
}

// AndroidX sqlite-bundled-jvm 没有发布 Windows ARM64 native 库.
// 此模块把预编译的 sqliteJni.dll 打成只含资源的 jar, 由 app-data 在 Windows ARM64 主机上
// 以 runtimeOnly 引入, 使 BundledSQLiteDriver 能通过 classloader 找到 natives/windows_arm64/sqliteJni.dll.
// 详见本目录 build-sqlite-jni-woa64.ps1 的头注释. AndroidX 官方发布 windows_arm64 后删除此模块.
tasks.jar {
    from("sqliteJni.dll") {
        into("natives/windows_arm64")
    }
}
