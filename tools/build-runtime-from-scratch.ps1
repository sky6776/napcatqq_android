# Windows wrapper around tools/runtime-build.sh.
# Runs the from-official-sources runtime build inside a WSL distro. The bash
# script writes runtime-packages/manifest.json and runtime-packages/build-info.json
# next to the archive; this wrapper verifies them and syncs the new SHA-256 into
# the BUNDLED_RUNTIME_SHA256 constant in RuntimeInstaller.java.
#
# Usage:
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\build-runtime-from-scratch.ps1
#
# Optional parameters (see runtime-build.sh for the full list):
#   -Distro Ubuntu                 WSL distro to use
#   -Profile current               NAPCATHOST_PROFILE (current | device)
#   -QqDeb  <path>                 NAPCATHOST_QQ_DEB  (device profile)
#   -QqTarZst <path>               NAPCATHOST_QQ_TARZST (device profile)
#   -GhMirror <url>                NAPCATHOST_GH_MIRROR (e.g. https://gh-proxy.example/)
#
# WSL requirements (one-time, inside the distro as root):
#   apt-get install -y mmdebstrap qemu-user-static proot zstd xz-utils unzip curl python3

param(
    [string]$Distro = 'Ubuntu',
    [ValidateSet('current', 'device')]
    [string]$Profile = 'current',
    [string]$QqDeb = '',
    [string]$QqTarZst = '',
    [string]$GhMirror = ''
)

$ErrorActionPreference = 'Stop'

$toolsDir = $PSScriptRoot
$repoDir = (Resolve-Path (Join-Path $toolsDir '..')).Path
$runtimeDir = Join-Path $repoDir 'runtime-packages'
$manifestFile = Join-Path $runtimeDir 'manifest.json'
$buildInfoFile = Join-Path $runtimeDir 'build-info.json'
$installerJava = Join-Path $repoDir 'app\src\main\java\dev\napcathost\RuntimeInstaller.java'
$archiveName = 'napcathost-runtime-arm64-v1.tar.zst'

Write-Output "== NapCatHost runtime build (from official sources) =="
Write-Output "Repo:   $repoDir"
Write-Output "Distro: $Distro   Profile: $Profile"

# --- locate wsl.exe ---------------------------------------------------------
$wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
if (-not $wsl) { $wsl = Get-Command "$env:WINDIR\System32\wsl.exe" -ErrorAction SilentlyContinue }
if (-not $wsl) { throw 'wsl.exe not found. Install WSL2 with a Debian/Ubuntu distro first.' }

# --- translate the repo path into the WSL mount ------------------------------
$wslTools = & $wsl.Source -d $Distro -- wslpath -u $toolsDir
if ($LASTEXITCODE -ne 0 -or -not $wslTools) {
    throw "Could not translate '$toolsDir' into a WSL path for distro '$Distro'. Is the distro installed?"
}
$wslTools = ($wslTools | Select-Object -First 1).Trim()
Write-Output "WSL path: $wslTools"

# --- assemble the bash command line ------------------------------------------
$bashEnv = "NAPCATHOST_PROFILE='$Profile'"
if ($QqDeb) {
    $wslDeb = (& $wsl.Source -d $Distro -- wslpath -u $QqDeb | Select-Object -First 1).Trim()
    $bashEnv += " NAPCATHOST_QQ_DEB='$wslDeb'"
}
if ($QqTarZst) {
    $wslTar = (& $wsl.Source -d $Distro -- wslpath -u $QqTarZst | Select-Object -First 1).Trim()
    $bashEnv += " NAPCATHOST_QQ_TARZST='$wslTar'"
}
if ($GhMirror) { $bashEnv += " NAPCATHOST_GH_MIRROR='$GhMirror'" }

$bashCommand = "cd '$wslTools' && $bashEnv bash ./runtime-build.sh"
Write-Output "Running: wsl -d $Distro -u root -- bash -lc <runtime-build.sh>"

# --- run the build (as root; mmdebstrap --qemu needs chroot) ------------------
& $wsl.Source -d $Distro -u root -- bash -lc $bashCommand
if ($LASTEXITCODE -ne 0) {
    throw "runtime-build.sh failed inside WSL (exit code $LASTEXITCODE). If WSL itself failed to start, enable the 'Virtual Machine Platform' Windows feature and run: wsl --update"
}

# --- read build-info.json produced by the bash script ------------------------
if (-not (Test-Path -LiteralPath $buildInfoFile)) { throw "Build finished but $buildInfoFile was not created." }
$buildInfo = Get-Content -LiteralPath $buildInfoFile -Raw | ConvertFrom-Json
$archivePath = Join-Path $runtimeDir $archiveName
if (-not (Test-Path -LiteralPath $archivePath)) { throw "Build finished but $archivePath was not created." }

$actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne $buildInfo.sha256) {
    throw "Archive checksum mismatch: $actualHash (expected $($buildInfo.sha256))"
}

# --- verify runtime-packages/manifest.json written by runtime-build.sh --------
if (-not (Test-Path -LiteralPath $manifestFile)) { throw "Build finished but $manifestFile was not created." }
$manifest = Get-Content -LiteralPath $manifestFile -Raw | ConvertFrom-Json
if ($manifest.sha256 -ne $actualHash) {
    throw "manifest.json sha256 mismatch: $($manifest.sha256) (expected $actualHash)"
}
Write-Output "manifest.json verified (sha256 $actualHash)."

# --- sync BUNDLED_RUNTIME_SHA256 in RuntimeInstaller.java ---------------------
# Read/write as UTF-8 without BOM: the file contains non-ASCII (Chinese) string
# literals that PowerShell's ANSI/ascii encodings would silently corrupt.
if (-not (Test-Path -LiteralPath $installerJava)) { throw "RuntimeInstaller.java not found: $installerJava" }
$java = [System.IO.File]::ReadAllText($installerJava)
$pattern = '(BUNDLED_RUNTIME_SHA256\s*=\s*")[0-9a-f]{64}(")'
if ($java -notmatch $pattern) { throw "BUNDLED_RUNTIME_SHA256 constant not found in RuntimeInstaller.java" }
$java = [regex]::Replace($java, $pattern, ('${1}' + $actualHash + '${2}'))
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($installerJava, $java, $utf8NoBom)
Write-Output "RuntimeInstaller.java BUNDLED_RUNTIME_SHA256 synced."

Write-Output ''
Write-Output "== DONE =="
Get-Item -LiteralPath $archivePath | Select-Object FullName, Length, LastWriteTime
Write-Output "SHA-256: $actualHash"
Write-Output "Components: Debian $($buildInfo.components.debian) / QQ $($buildInfo.components.linux_qq) / NapCat $($buildInfo.components.napcatqq) / xvfb $($buildInfo.components.xvfb)"
Write-Output ''
Write-Output 'Next step: build and install the APK with build.cmd (or tools\build.ps1).'
