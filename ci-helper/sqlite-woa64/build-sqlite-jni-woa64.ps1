<#
  Copyright (C) 2024-2026 OpenAni and contributors.

  此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
  Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.

  https://github.com/open-ani/ani/blob/main/LICENSE
#>

# 在 Windows 上用 MSVC 重建本目录的 sqliteJni.dll (Windows ARM64).
# 需要: PowerShell 7+ (pwsh), Visual Studio "C++ ARM64 build tools" 组件, JAVA_HOME 指向 JDK.
# 注意: Windows 自带的 Windows PowerShell 5.1 可能缺少 Get-FileHash
# (Microsoft.PowerShell.Utility 不完整), 请用 pwsh 运行本脚本:
#   pwsh -NoProfile -ExecutionPolicy Bypass -File ci-helper/sqlite-woa64/build-sqlite-jni-woa64.ps1
#
# 背景:
# AndroidX sqlite-bundled-jvm 只发布 Windows x64 / Linux / macOS 的 native 库 (2.6.1 ~ 2.7.0
# 均无 natives/windows_arm64/sqliteJni.dll), 导致 WoA64 上 BundledSQLiteDriver 无法加载.
# 由于 NativeLibraryLoader 是通过 classloader 查找该资源, 我们把预编译 DLL 打成资源 jar
# (模块 :ci-helper:sqlite-woa64), 由 app-data 在 Windows ARM64 主机上 runtimeOnly 引入,
# run / test / 打包 / 发版全部生效, 无需修改官方 jar.
# AndroidX 官方发布 windows_arm64 后, 删除本目录、模块注册和 app-data 中的 runtimeOnly 即可.
#
# DLL 内容: SQLite 3.50.1 amalgamation + androidx.sqlite 2.6.2 的 sqlite_bindings.cpp,
# 编译宏与 AndroidX 官方构建一致, 源码以下方 sha256 钉死.
# 本脚本使用 /Brepro, 相同源码与工具链下构建字节级可复现;
# 更新 DLL 提交前请连续构建两次, 确认输出的 sha256 一致.
#
# 何时重建: 升级 gradle/libs.versions.toml 的 sqlite 版本时, 核对该版本对应的
# sqlite_bindings.cpp 是否有变化, 有则更新下方 commit / 哈希并重新运行本脚本.

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Invoke-WithRetry {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock]$Script,
        [Parameter(Mandatory = $true)]
        [string]$Description,
        [int]$Attempts = 5
    )

    for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
        try {
            return & $Script
        }
        catch {
            if ($attempt -eq $Attempts) {
                throw "Failed to $Description after $Attempts attempts. Last error: $($_.Exception.Message)"
            }

            $delaySeconds = [int][Math]::Min(60, [Math]::Pow(2, $attempt) * 5)
            Write-Warning "$Description failed on attempt $attempt/$Attempts. Retrying in $delaySeconds seconds. $($_.Exception.Message)"
            Start-Sleep -Seconds $delaySeconds
        }
    }
}

function Assert-Sha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$Expected
    )

    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash
    if ($actual -ne $Expected) {
        throw "SHA256 mismatch for $Path. Expected $Expected, got $actual."
    }
}

$outputDir = "build/sqlite-woa64"
$workDir = Join-Path $outputDir "work"
New-Item -ItemType Directory -Force -Path $workDir | Out-Null
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

$sqliteVersion = "3.50.1"
$sqliteZipVersion = "3500100"
$sqliteYear = "2025"
$sqliteZipHash = "41716B44AC8777188C4C3F1F370F01C9CB9E3B6428EB5C981D086C35DE2D9D3F"
$sqliteZip = Join-Path $workDir "sqlite-amalgamation-$sqliteZipVersion.zip"
$sqliteDir = Join-Path $workDir "sqlite-amalgamation-$sqliteZipVersion"
$sqliteC = Join-Path $sqliteDir "sqlite3.c"

$androidxSqliteCommit = "fe30df161d480829efb21f37ff67a9f8cac9c620" # androidx.sqlite 2.6.2
$androidxBindingHash = "F9F4747111A6635DFFD5991126247CA0F2F6C851DE1EAF4ADD3866A0518AC2E0"
$binding = Join-Path $workDir "sqlite_bindings.cpp"

# AndroidX sqlite-bundled-jvm currently publishes Windows x64, Linux, and macOS
# native binaries, but not Windows ARM64. Remove this workaround once AndroidX
# ships natives/windows_arm64/sqliteJni.dll in sqlite-bundled-jvm.
if (!(Test-Path $sqliteZip)) {
    Invoke-WithRetry `
        -Description "download SQLite amalgamation" `
        -Script {
            Invoke-WebRequest `
                -UseBasicParsing `
                -Uri "https://www.sqlite.org/$sqliteYear/sqlite-amalgamation-$sqliteZipVersion.zip" `
                -OutFile $sqliteZip
        }
}
Assert-Sha256 -Path $sqliteZip -Expected $sqliteZipHash

if (!(Test-Path $sqliteC)) {
    Expand-Archive -Path $sqliteZip -DestinationPath $workDir -Force
}

$sqliteHeader = Get-Content (Join-Path $sqliteDir "sqlite3.h") -Raw
if ($sqliteHeader -notmatch "#define\s+SQLITE_VERSION\s+`"$([regex]::Escape($sqliteVersion))`"") {
    throw "Downloaded SQLite amalgamation does not match $sqliteVersion."
}

if (!(Test-Path $binding)) {
    $encodedBinding = Invoke-WithRetry `
        -Description "download AndroidX SQLite JNI binding" `
        -Script {
            (Invoke-WebRequest `
                    -UseBasicParsing `
                    -Uri "https://android.googlesource.com/platform/frameworks/support/+/$androidxSqliteCommit/sqlite/sqlite-bundled/src/jvmAndroidMain/jni/sqlite_bindings.cpp?format=TEXT").Content.Trim()
        }
    [System.IO.File]::WriteAllBytes($binding, [System.Convert]::FromBase64String($encodedBinding))
}
Assert-Sha256 -Path $binding -Expected $androidxBindingHash

$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$vsInstall = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.ARM64 -property installationPath
if ([string]::IsNullOrWhiteSpace($vsInstall)) {
    throw "Visual Studio ARM64 C++ tools were not found."
}

$vcvars = Join-Path $vsInstall "VC\Auxiliary\Build\vcvarsall.bat"
$javaInclude = Join-Path $env:JAVA_HOME "include"
$javaWinInclude = Join-Path $javaInclude "win32"
$sqliteObj = Join-Path $outputDir "sqlite3.obj"
$bindingObj = Join-Path $outputDir "sqlite_bindings.obj"
$sqliteDll = Join-Path $outputDir "sqliteJni.dll"

$sqliteDefines = @(
    "/DHAVE_USLEEP=1",
    "/DSQLITE_DEFAULT_AUTOVACUUM=1",
    "/DSQLITE_DEFAULT_MEMSTATUS=0",
    "/DSQLITE_DEFAULT_WAL_SYNCHRONOUS=1",
    "/DSQLITE_ENABLE_COLUMN_METADATA",
    "/DSQLITE_ENABLE_FTS3",
    "/DSQLITE_ENABLE_FTS3_PARENTHESIS",
    "/DSQLITE_ENABLE_FTS4",
    "/DSQLITE_ENABLE_FTS5",
    "/DSQLITE_ENABLE_JSON1",
    "/DSQLITE_ENABLE_MATH_FUNCTIONS",
    "/DSQLITE_ENABLE_NORMALIZE",
    "/DSQLITE_ENABLE_RTREE",
    "/DSQLITE_ENABLE_STAT4",
    "/DSQLITE_HAVE_ISNAN",
    "/DSQLITE_OMIT_BUILTIN_TEST",
    "/DSQLITE_OMIT_DEPRECATED",
    "/DSQLITE_OMIT_PROGRESS_CALLBACK",
    "/DSQLITE_OMIT_SHARED_CACHE",
    "/DSQLITE_SECURE_DELETE",
    "/DSQLITE_TEMP_STORE=3",
    "/DSQLITE_THREADSAFE=2"
) -join " "

$compile = @(
    "`"$vcvars`" arm64",
    "cl /nologo /O2 /Brepro /MT /utf-8 $sqliteDefines /I`"$sqliteDir`" /Fo`"$sqliteObj`" /c `"$sqliteC`"",
    "cl /nologo /O2 /Brepro /MT /EHsc /std:c++17 /utf-8 $sqliteDefines /I`"$sqliteDir`" /I`"$javaInclude`" /I`"$javaWinInclude`" /Fo`"$bindingObj`" /c `"$binding`"",
    "link /nologo /Brepro /DLL /OUT:`"$sqliteDll`" /IMPLIB:`"$outputDir\sqliteJni.lib`" `"$sqliteObj`" `"$bindingObj`""
) -join " && "

cmd.exe /d /s /c $compile
if ($LASTEXITCODE -ne 0) {
    throw "Failed to build Windows ARM64 AndroidX SQLite JNI runtime."
}

Copy-Item -Force $sqliteDll (Join-Path $scriptDir "sqliteJni.dll")
Write-Host "Written: $scriptDir\sqliteJni.dll"
(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $scriptDir "sqliteJni.dll")).Hash
