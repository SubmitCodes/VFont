param([switch]$Release)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$versionLine = Get-Content -LiteralPath Cargo.toml | Where-Object { $_ -match '^version = "([^"]+)"$' } | Select-Object -First 1
if ($versionLine -notmatch '^version = "([^"]+)"$') { throw 'Could not read the app version from Cargo.toml.' }
$appVersion = $Matches[1]
$toolchainPath = Join-Path $sdkPath 'ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin'
if (!(Test-Path -LiteralPath (Join-Path $toolchainPath 'clang.exe'))) { throw 'Install Android NDK 28.2.13676358 first.' }
$repoPath = (Get-Location).Path
$cargoRoot = if ($env:CARGO_HOME) { $env:CARGO_HOME } else { Join-Path $HOME '.cargo' }
$env:CARGO_ENCODED_RUSTFLAGS = @('-C', 'link-arg=-Wl,-z,max-page-size=16384', "--remap-path-prefix=$repoPath=.", "--remap-path-prefix=$cargoRoot=cargo") -join [char]0x1f
$architectures = @(
    @{ Target = 'aarch64-linux-android'; Abi = 'arm64-v8a'; Compiler = 'aarch64-linux-android' },
    @{ Target = 'armv7-linux-androideabi'; Abi = 'armeabi-v7a'; Compiler = 'armv7a-linux-androideabi' },
    @{ Target = 'x86_64-linux-android'; Abi = 'x86_64'; Compiler = 'x86_64-linux-android' },
    @{ Target = 'i686-linux-android'; Abi = 'x86'; Compiler = 'i686-linux-android' }
)
foreach ($architecture in $architectures) {
    $targetKey = $architecture.Target.Replace('-', '_')
    $linkerPath = Join-Path $toolchainPath ($architecture.Compiler + '31-clang.cmd')
    [Environment]::SetEnvironmentVariable(('CARGO_TARGET_' + $targetKey.ToUpperInvariant() + '_LINKER'), $linkerPath, 'Process')
    [Environment]::SetEnvironmentVariable(('CC_' + $targetKey), $linkerPath, 'Process')
    [Environment]::SetEnvironmentVariable(('AR_' + $targetKey), (Join-Path $toolchainPath 'llvm-ar.exe'), 'Process')
    & cargo build --locked --release --target $architecture.Target
    if ($LASTEXITCODE -ne 0) { throw "Rust build failed for $($architecture.Target). See docs/building.md for prerequisites." }
    $jniOutput = Join-Path $repoPath ('android\app\src\main\jniLibs\' + $architecture.Abi)
    New-Item -ItemType Directory -Force -Path $jniOutput | Out-Null
    Copy-Item -LiteralPath (Join-Path $repoPath ('target\' + $architecture.Target + '\release\libfontlab.so')) -Destination $jniOutput
}
('sdk.dir=' + $sdkPath.Replace('\','/').Replace(':','\:')) | Set-Content -LiteralPath (Join-Path $repoPath 'android\local.properties') -Encoding ASCII -NoNewline
$gradleTask = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }
$builtApk = if ($Release) { 'android\app\build\outputs\apk\release\app-release.apk' } else { 'android\app\build\outputs\apk\debug\app-debug.apk' }
Push-Location (Join-Path $repoPath 'android')
try {
    & .\gradlew.bat $gradleTask
    if ($LASTEXITCODE -ne 0) { throw 'APK build failed' }
} finally { Pop-Location }
New-Item -ItemType Directory -Force -Path (Join-Path $repoPath 'dist') | Out-Null
$apkName = if ($Release) { "VFont-$appVersion-universal.apk" } else { "VFont-$appVersion-debug-universal.apk" }
Copy-Item -LiteralPath $builtApk -Destination (Join-Path 'dist' $apkName)
$apkHash = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path 'dist' $apkName)
($apkHash.Hash.ToLowerInvariant() + '  ' + $apkName) | Set-Content -LiteralPath 'dist\SHA256SUMS.txt' -Encoding ASCII
$apkHash
