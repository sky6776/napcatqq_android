$ErrorActionPreference = 'Stop'

# Override with $env:NAPCATHOST_TOOLCHAIN to use a different local toolchain.
$toolchain = if ($env:NAPCATHOST_TOOLCHAIN) { $env:NAPCATHOST_TOOLCHAIN } else { 'E:\my-android\android-toolchain' }
$adb = Join-Path $toolchain 'sdk\platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) {
    throw "ADB not found: $adb"
}

$apk = (Resolve-Path (Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk')).Path
$apkSize = (Get-Item -LiteralPath $apk).Length
$deviceApk = '/data/local/tmp/napcathost-debug.apk'

& $adb get-state | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw 'No ADB device is connected.'
}

& $adb push $apk $deviceApk
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to push APK.'
}

$create = & $adb shell pm install-create -r -g --user 0 -S $apkSize
if ($create -notmatch '\[(\d+)\]') {
    throw "Could not create install session: $create"
}
$session = $Matches[1]
Write-Output "Install session: $session"

& $adb shell pm install-write -S $apkSize $session base $deviceApk
if ($LASTEXITCODE -ne 0) {
    & $adb shell pm install-abandon $session | Out-Null
    throw 'Failed to write install session.'
}

Write-Output 'Confirm USB installation on the phone if vivo displays a prompt.'
$commit = & $adb shell pm install-commit $session
$commit | Out-Host
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^Success') {
    throw "Install commit failed: $commit"
}

$expected = '/data/local/tmp/napcathost-debug.apk'
$resolved = (& $adb shell realpath $deviceApk).Trim()
if ($resolved -eq $expected) {
    & $adb shell rm -f -- $expected
}

& $adb shell am start -n dev.napcathost/.MainActivity | Out-Host
