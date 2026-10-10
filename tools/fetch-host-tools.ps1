$ErrorActionPreference = 'Stop'

$repository = 'https://packages.termux.dev/apt/termux-main/'
$work = Join-Path $env:TEMP 'napcathost-host-tools'
$destination = Join-Path $PSScriptRoot '..\app\src\main\assets\runtime-host\arm64-v8a'

New-Item -ItemType Directory -Force -Path $work | Out-Null
New-Item -ItemType Directory -Force -Path $destination | Out-Null

$packages = @(
    @{
        Name = 'proot'
        Url = 'pool/main/p/proot/proot_5.1.107.85_aarch64.deb'
        Sha256 = '103e210d9e2ddc2b7b18147753dafccc775470ec88204f96a080534b36da261f'
    },
    @{
        Name = 'libandroid-shmem'
        Url = 'pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb'
        Sha256 = '0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6'
    },
    @{
        Name = 'libtalloc'
        Url = 'pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb'
        Sha256 = 'ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da'
    }
)

foreach ($package in $packages) {
    $deb = Join-Path $work ($package.Name + '.deb')
    curl.exe -L --fail --retry 3 ($repository + $package.Url) -o $deb
    $actual = (Get-FileHash -LiteralPath $deb -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $package.Sha256) {
        throw "Checksum mismatch for $($package.Name): $actual"
    }
    $stage = Join-Path $work $package.Name
    New-Item -ItemType Directory -Force -Path $stage | Out-Null
    tar.exe -xf $deb -C $stage data.tar.xz
}

$prootData = Join-Path $work 'proot\data.tar.xz'
$shmemData = Join-Path $work 'libandroid-shmem\data.tar.xz'
$tallocData = Join-Path $work 'libtalloc\data.tar.xz'

tar.exe -xf $prootData -C $work './data/data/com.termux/files/usr/bin/proot' './data/data/com.termux/files/usr/libexec/proot/loader'
tar.exe -xf $shmemData -C $work './data/data/com.termux/files/usr/lib/libandroid-shmem.so'
tar.exe -xf $tallocData -C $work './data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3'

Copy-Item -LiteralPath (Join-Path $work 'data\data\com.termux\files\usr\bin\proot') -Destination (Join-Path $destination 'proot') -Force
Copy-Item -LiteralPath (Join-Path $work 'data\data\com.termux\files\usr\libexec\proot\loader') -Destination (Join-Path $destination 'loader') -Force
Copy-Item -LiteralPath (Join-Path $work 'data\data\com.termux\files\usr\lib\libandroid-shmem.so') -Destination (Join-Path $destination 'libandroid-shmem.so') -Force
Copy-Item -LiteralPath (Join-Path $work 'data\data\com.termux\files\usr\lib\libtalloc.so.2.4.3') -Destination (Join-Path $destination 'libtalloc.so.2') -Force

Get-ChildItem -File $destination | Select-Object Name, Length, @{Name='SHA256'; Expression={(Get-FileHash $_.FullName -Algorithm SHA256).Hash}}
