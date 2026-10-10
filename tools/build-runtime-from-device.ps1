$ErrorActionPreference = 'Stop'

$outputDirectory = Join-Path $PSScriptRoot '..\runtime-packages'
$output = Join-Path $outputDirectory 'napcathost-runtime-arm64-v1.tar.zst'
$deviceOutput = '/sdcard/Download/napcathost-runtime-arm64-v1-build.tar.zst'

if (Test-Path -LiteralPath $output) {
    throw "Refusing to overwrite existing runtime archive: $output"
}

New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null

$deviceScript = @'
set -euo pipefail
PREFIX=/data/data/com.termux/files/usr
export PATH="$PREFIX/bin:/system/bin"
ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/napcat"
OUT=/sdcard/Download/napcathost-runtime-arm64-v1-build.tar.zst

test -d "$ROOTFS/root/Napcat/opt/QQ"
if [ -e "$OUT" ]; then
  echo "Refusing to overwrite existing $OUT" >&2
  exit 20
fi

cd "$ROOTFS"
tar --numeric-owner --owner=0 --group=0 \
  --exclude='./apex' \
  --exclude='./data' \
  --exclude='./dev' \
  --exclude='./odm' \
  --exclude='./sdcard' \
  --exclude='./storage' \
  --exclude='./system' \
  --exclude='./sys' \
  --exclude='./vendor' \
  --exclude='./root/.config/QQ' \
  --exclude='./root/.cache' \
  --exclude='./root/.ssh' \
  --exclude='./root/.bash_history' \
  --exclude='./root/.wget-hsts' \
  --exclude='./root/guild*.db*' \
  --exclude='./root/Napcat/opt/QQ/resources/app/app_launcher/napcat/config' \
  --exclude='./root/Napcat/opt/QQ/resources/app/app_launcher/napcat/logs' \
  --exclude='./root/Napcat/opt/QQ/resources/app/app_launcher/napcat/cache' \
  --exclude='./tmp/*' \
  --exclude='./var/tmp/*' \
  --exclude='./var/cache/apt/archives/*.deb' \
  --exclude='./var/lib/apt/lists/*' \
  -cf - . | zstd -T2 -6 --long=27 -o "$OUT"

sha256sum "$OUT"
ls -lh "$OUT"
'@

$deviceScript | adb shell run-as com.termux /data/data/com.termux/files/usr/bin/bash -s
if ($LASTEXITCODE -ne 0) {
    throw 'Runtime archive creation failed.'
}

adb pull $deviceOutput $output
if ($LASTEXITCODE -ne 0) {
    throw 'Runtime archive pull failed.'
}

Get-Item -LiteralPath $output | Select-Object FullName, Length
Get-FileHash -LiteralPath $output -Algorithm SHA256 | Select-Object Hash

$expectedDevicePath = '/storage/emulated/0/Download/napcathost-runtime-arm64-v1-build.tar.zst'
$resolvedDevicePath = (adb shell realpath $deviceOutput).Trim()
if ($resolvedDevicePath -ne $expectedDevicePath) {
    throw "Unexpected temporary device path: $resolvedDevicePath"
}
adb shell rm -f -- $expectedDevicePath
