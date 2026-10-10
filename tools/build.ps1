$ErrorActionPreference = 'Stop'

# Override with $env:NAPCATHOST_TOOLCHAIN to build against a different local toolchain.
$toolchain = if ($env:NAPCATHOST_TOOLCHAIN) { $env:NAPCATHOST_TOOLCHAIN } else { 'E:\my-android\android-toolchain' }
$env:JAVA_HOME = Join-Path $toolchain 'jdk\jdk-17.0.19+10'
$env:ANDROID_HOME = Join-Path $toolchain 'sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $toolchain 'gradle-user-home'

$gradle = Join-Path $toolchain 'gradle\gradle-9.1.0\bin\gradle.bat'
if (-not (Test-Path -LiteralPath $gradle)) {
    throw "Gradle not found: $gradle"
}

# The offline runtime archive is a build input and is not stored in git.
$runtimeArchive = Join-Path $PSScriptRoot '..\runtime-packages\napcathost-runtime-arm64-v1.tar.zst'
if (-not (Test-Path -LiteralPath $runtimeArchive)) {
    throw "Runtime archive not found: $runtimeArchive. Build it from official sources first: powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\build-runtime-from-scratch.ps1"
}

# Avoid a full clean: deleting/repacking the 426 MiB offline asset is slow and
# can race with Windows Defender/indexers. Gradle tracks source and asset inputs.
& $gradle --console=plain cleanTest test lintDebug assembleDebug
if ($LASTEXITCODE -ne 0) {
    throw "Gradle failed with exit code $LASTEXITCODE"
}

$apk = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'
$metadataFile = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\output-metadata.json'
$metadata = Get-Content -LiteralPath $metadataFile -Raw | ConvertFrom-Json
$versionName = $metadata.elements[0].versionName
if ([string]::IsNullOrWhiteSpace($versionName)) {
    throw "Version name is missing from $metadataFile"
}
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$distDirectory = Join-Path $projectRoot 'dist'
$distFileName = "NapCatHost-offline-v$versionName-debug.apk"
$distApk = Join-Path $distDirectory $distFileName
$checksumFile = Join-Path $distDirectory 'SHA256SUMS.txt'
$latestApkFile = Join-Path $distDirectory 'LATEST_APK.txt'

New-Item -ItemType Directory -Path $distDirectory -Force | Out-Null
Copy-Item -LiteralPath $apk -Destination $distApk -Force
$hash = (Get-FileHash -LiteralPath $distApk -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath $checksumFile -Encoding ascii -Value "$hash  $distFileName"
Set-Content -LiteralPath $latestApkFile -Encoding ascii -Value $distFileName

Get-Item -LiteralPath $distApk | Select-Object FullName, Length, LastWriteTime
Write-Output "SHA-256: $hash"
Write-Output "Checksum file: $checksumFile"
