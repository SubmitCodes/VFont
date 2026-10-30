$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$versionLine = Get-Content -LiteralPath Cargo.toml | Where-Object { $_ -match '^version = "([^"]+)"$' } | Select-Object -First 1
if ($versionLine -notmatch '^version = "([^"]+)"$') { throw 'Could not read the app version from Cargo.toml.' }
$appVersion = $Matches[1]
$toolchainPath = Join-Path $sdkPath 'ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin'
if (!(Test-Path -LiteralPath (Join-Path $toolchainPath 'clang.exe'))) { throw 'Install Android NDK 28.2.13676358 first.' }
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $toolchainPath 'aarch64-linux-android34-clang.cmd'
$env:CC_aarch64_linux_android = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
$env:AR_aarch64_linux_android = Join-Path $toolchainPath 'llvm-ar.exe'
$env:RUSTFLAGS = '-C link-arg=-Wl,-z,max-page-size=16384'
& cargo build --locked --release --target aarch64-linux-android
if ($LASTEXITCODE -ne 0) { throw 'Rust build failed' }
$repoPath = (Get-Location).Path
$jniOutput = Join-Path $repoPath 'android\app\src\main\jniLibs\arm64-v8a'
New-Item -ItemType Directory -Force -Path $jniOutput | Out-Null
Copy-Item -LiteralPath (Join-Path $repoPath 'target\aarch64-linux-android\release\libfontlab.so') -Destination $jniOutput
('sdk.dir=' + $sdkPath.Replace('\','/')) | Set-Content -LiteralPath (Join-Path $repoPath 'android\local.properties') -Encoding ASCII
Push-Location (Join-Path $repoPath 'android')
try {
    & .\gradlew.bat assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'APK build failed' }
} finally { Pop-Location }
New-Item -ItemType Directory -Force -Path (Join-Path $repoPath 'dist') | Out-Null
$apkName = "VFont-$appVersion.apk"
Copy-Item -LiteralPath 'android\app\build\outputs\apk\debug\app-debug.apk' -Destination (Join-Path 'dist' $apkName)
$apkHash = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path 'dist' $apkName)
($apkHash.Hash.ToLowerInvariant() + '  ' + $apkName) | Set-Content -LiteralPath 'dist\SHA256SUMS.txt' -Encoding ASCII
$apkHash
