#!/usr/bin/env bash
# Build the NapCatHost offline runtime archive (napcathost-runtime-arm64-v1.tar.zst)
# entirely from official sources, without any committed binaries.
#
# What it does:
#   1. Creates a Debian arm64 rootfs with mmdebstrap straight from the official
#      Debian mirrors. Arm64 code executes transparently through the host's
#      qemu-user-static + binfmt_misc handlers (mmdebstrap has no qemu flag
#      of its own; root-mode chroots just rely on the kernel dispatching
#      foreign binaries to qemu).
#   2. Installs xvfb and the Linux QQ runtime dependencies via proot + qemu.
#   3. Extracts the official Linux QQ arm64 .deb into /root/Napcat (same layout
#      the NapCat rootless installer produces) and wires NapCatQQ into QQ.
#   4. Repacks everything as runtime-packages/napcathost-runtime-arm64-v1.tar.zst
#      and writes runtime-packages/build-info.json and manifest.json.
#
# Requirements (Linux / WSL2 / GitHub Actions ubuntu runner, run as root):
#   mmdebstrap qemu-user-static binfmt-support proot zstd xz-utils unzip curl dpkg-deb python3
#
# Profiles (NAPCATHOST_PROFILE, default "current"):
#   current - QQ 3.2.32-52194 + NapCatQQ 4.18.35, the pairing currently pinned
#             by the official NapCat installer. Every input is downloadable
#             today, so this profile works end-to-end on CI.
#   device  - QQ 3.2.25-45758 + NapCatQQ 4.18.9, the pairing verified on the
#             reference device. Tencent's CDN and the community mirrors have
#             removed this QQ build, so provide the .deb yourself:
#               NAPCATHOST_QQ_DEB=/path/to/linuxqq_3.2.25-45758_arm64.deb
#             or extract QQ from an existing runtime archive:
#               NAPCATHOST_QQ_TARZST=/path/to/napcathost-runtime-arm64-v1.tar.zst
#
# After a successful build, sync the new SHA-256 into RuntimeInstaller.java:
#   - Windows: tools\build-runtime-from-scratch.ps1 does this for you.
#   - CI / manual: see tools/build-runtime-from-scratch.ps1 or the workflow.

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_DIR=$(cd "$SCRIPT_DIR/.." && pwd)

log()  { printf '[runtime-build] %s\n' "$*" >&2; }
die()  { printf '[runtime-build] ERROR: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Pinned official sources
# ---------------------------------------------------------------------------
PROFILE="${NAPCATHOST_PROFILE:-current}"

DEBIAN_SUITE="${NAPCATHOST_DEBIAN_SUITE:-trixie}"
DEBIAN_MIRROR="${NAPCATHOST_DEBIAN_MIRROR:-https://deb.debian.org/debian}"
DEBIAN_SECURITY_MIRROR="${NAPCATHOST_DEBIAN_SECURITY_MIRROR:-https://security.debian.org/debian-security}"
DEBIAN_COMPONENTS="${NAPCATHOST_DEBIAN_COMPONENTS:-main,contrib}"

# NapCat GitHub release mirror prefix for restricted networks, e.g.
#   NAPCATHOST_GH_MIRROR=https://gh-proxy.example/
GH_MIRROR="${NAPCATHOST_GH_MIRROR:-}"

case "$PROFILE" in
  current)
    QQ_VERSION="3.2.32-52194"
    QQ_DEB_FILE="QQ_3.2.32_260812_arm64_01.deb"
    QQ_DEB_URL="https://qqdl.gtimg.cn/qqfile/QQNT/9.9.33/release/3f89efc5/QQ_3.2.32_260812_arm64_01.deb"
    QQ_DEB_MIRROR_URL="https://github.com/Rodert/qq-versions/releases/download/qq-packages-20260813-1d08f1d4/QQ_3.2.32_260812_arm64_01.deb"
    QQ_DEB_SHA256="8796ccfd66acc025ef18db37185532d40bc8c58921e17da6d28c295acbcf8f92"
    NAPCAT_VERSION="4.18.35"
    NAPCAT_SHA256="009af41683371d83a271e6ae1c81db7b4fd3247b716f14d3c955dd03034be003"
    ;;
  device)
    QQ_VERSION="3.2.25-45758"
    QQ_DEB_FILE="linuxqq_3.2.25-45758_arm64.deb"
    QQ_DEB_URL=""           # Tencent removed this build from the CDN.
    QQ_DEB_MIRROR_URL=""    # Community mirrors do not keep it either.
    QQ_DEB_SHA256=""
    NAPCAT_VERSION="4.18.9"
    NAPCAT_SHA256="e729346240e1bfc51bd924cac8dbe13f4996d3ba78572dbb08a313b3874426d5"
    ;;
  *)
    die "Unknown NAPCATHOST_PROFILE '$PROFILE' (expected: current | device)"
    ;;
esac

NAPCAT_ZIP_FILE="NapCat.Shell.zip"
NAPCAT_ZIP_URL="https://github.com/NapNeko/NapCatQQ/releases/download/v${NAPCAT_VERSION}/NapCat.Shell.zip"

WORKDIR="${NAPCATHOST_WORKDIR:-$HOME/napcathost-build}"
ZSTD_LEVEL="${NAPCATHOST_ZSTD_LEVEL:-14}"
OUT_DIR="${NAPCATHOST_OUTDIR:-$REPO_DIR/runtime-packages}"
ARCHIVE_NAME="napcathost-runtime-arm64-v1.tar.zst"

QQ_PREFIX_IN_ROOTFS="root/Napcat"          # QQ lands at /root/Napcat/opt/QQ
NAPCAT_APP_LAUNCHER="$QQ_PREFIX_IN_ROOTFS/opt/QQ/resources/app/app_launcher"

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
[ "$(id -u)" -eq 0 ] || die "must run as root (mmdebstrap chroots into the arm64 rootfs); try: sudo -E $0"

for cmd in curl tar zstd mmdebstrap proot dpkg-deb unzip python3; do
  command -v "$cmd" >/dev/null 2>&1 || die "missing command: $cmd (Debian/Ubuntu: apt-get install mmdebstrap qemu-user-static proot zstd xz-utils unzip curl python3)"
done

QEMU="${NAPCATHOST_QEMU:-}"
if [ -z "$QEMU" ]; then
  for candidate in /usr/bin/qemu-aarch64-static /usr/bin/qemu-aarch64 /usr/libexec/qemu-binfmt/aarch64; do
    if [ -x "$candidate" ]; then QEMU="$candidate"; break; fi
  done
fi
[ -n "$QEMU" ] && [ -x "$QEMU" ] || die "qemu-aarch64 not found; apt-get install qemu-user-static"

# mmdebstrap runs arm64 binaries inside its chroot through the kernel's
# binfmt_misc handlers (it has no --qemu flag), so make sure qemu-aarch64 is
# registered before we get there. arch-test is pulled in by mmdebstrap.
if command -v update-binfmts >/dev/null 2>&1; then
  update-binfmts --enable >/dev/null 2>&1 || true
fi
if command -v arch-test >/dev/null 2>&1 && ! arch-test arm64; then
  die "arm64 binaries cannot execute on this host; install qemu-user-static + binfmt-support and enable binfmt_misc (update-binfmts --enable), or run on an arm64 host"
fi

case "$PROFILE" in
  device)
    if [ -z "${NAPCATHOST_QQ_DEB:-}" ] && [ -z "${NAPCATHOST_QQ_TARZST:-}" ]; then
      die "profile 'device' needs the QQ $QQ_VERSION arm64 .deb, which Tencent no longer publishes. Set NAPCATHOST_QQ_DEB=/path/to.deb or NAPCATHOST_QQ_TARZST=/path/to/napcathost-runtime-arm64-v1.tar.zst"
    fi
    ;;
esac

mkdir -p "$WORKDIR/downloads" "$WORKDIR/out"
DOWNLOADS="$WORKDIR/downloads"
ROOTFS="$WORKDIR/rootfs"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
fetch() { # fetch <url> <sha256|-> <dest>; returns 1 on download failure, dies on checksum mismatch
  local url="$1" expected="$2" dest="$3"
  if [ -s "$dest" ] && [ "$expected" != "-" ]; then
    if [ "$(sha256sum "$dest" | cut -d' ' -f1)" = "$expected" ]; then
      log "reusing $(basename "$dest") (checksum ok)"
      return 0
    fi
    rm -f "$dest"
  fi
  log "downloading $(basename "$dest") from $url"
  if ! curl -fL --retry 3 --connect-timeout 30 -o "$dest" "$url"; then
    rm -f "$dest"
    log "download failed: $url"
    return 1
  fi
  local actual
  actual=$(sha256sum "$dest" | cut -d' ' -f1)
  if [ "$expected" != "-" ]; then
    [ "$actual" = "$expected" ] || die "checksum mismatch for $(basename "$dest"): $actual (expected $expected)"
    log "$(basename "$dest") checksum ok"
  else
    log "WARNING: $(basename "$dest") is unpinned, sha256=$actual"
  fi
  return 0
}

run_rootfs() { # run command inside the arm64 rootfs via proot+qemu
  proot -R "$ROOTFS" -q "$QEMU" -w /root /usr/bin/env -i \
    HOME=/root USER=root LOGNAME=root LANG=C.UTF-8 TERM=xterm \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    DEBIAN_FRONTEND=noninteractive \
    /bin/bash -eo pipefail -c "$*"
}

# ---------------------------------------------------------------------------
# 1. Fetch pinned artifacts
# ---------------------------------------------------------------------------
QQ_DEB="$DOWNLOADS/$QQ_DEB_FILE"
QQ_FROM_TARZST=0
case "$PROFILE" in
  current)
    if [ -n "${NAPCATHOST_QQ_DEB:-}" ]; then
      log "using caller-provided QQ deb: $NAPCATHOST_QQ_DEB"
      cp -f "$NAPCATHOST_QQ_DEB" "$QQ_DEB"
      actual=$(sha256sum "$QQ_DEB" | cut -d' ' -f1)
      [ "$actual" = "$QQ_DEB_SHA256" ] || die "provided QQ deb checksum mismatch: $actual (expected $QQ_DEB_SHA256)"
    else
      if ! fetch "$QQ_DEB_URL" "$QQ_DEB_SHA256" "$QQ_DEB"; then
        log "official CDN failed, trying community mirror"
        fetch "$QQ_DEB_MIRROR_URL" "$QQ_DEB_SHA256" "$QQ_DEB"
      fi
    fi
    ;;
  device)
    if [ -n "${NAPCATHOST_QQ_DEB:-}" ]; then
      log "using caller-provided QQ deb: $NAPCATHOST_QQ_DEB"
      cp -f "$NAPCATHOST_QQ_DEB" "$QQ_DEB"
      if [ -n "$QQ_DEB_SHA256" ]; then
        actual=$(sha256sum "$QQ_DEB" | cut -d' ' -f1)
        [ "$actual" = "$QQ_DEB_SHA256" ] || log "WARNING: provided deb sha256=$actual differs from the pinned one"
      fi
    else
      QQ_FROM_TARZST=1
      [ -f "${NAPCATHOST_QQ_TARZST:-}" ] || die "NAPCATHOST_QQ_TARZST not found: ${NAPCATHOST_QQ_TARZST:-<unset>}"
    fi
    ;;
esac

NAPCAT_SHA256_ARG="-"
if [ -n "$NAPCAT_SHA256" ] && [ "${NAPCATHOST_ALLOW_UNPINNED:-0}" != "1" ]; then
  NAPCAT_SHA256_ARG="$NAPCAT_SHA256"
fi
fetch "${GH_MIRROR:+$GH_MIRROR}$NAPCAT_ZIP_URL" "$NAPCAT_SHA256_ARG" "$DOWNLOADS/$NAPCAT_ZIP_FILE"

# ---------------------------------------------------------------------------
# 2. Debian arm64 rootfs from the official mirrors
# ---------------------------------------------------------------------------
PROFILE_MARKER="$ROOTFS/.napcathost-build-profile"
if [ -d "$ROOTFS/usr" ]; then
  if [ -f "$PROFILE_MARKER" ] && [ "$(cat "$PROFILE_MARKER")" = "$PROFILE" ]; then
    log "reusing existing rootfs at $ROOTFS (profile $PROFILE)"
  else
    log "existing rootfs was built for another profile, rebuilding"
    rm -rf "$ROOTFS"
  fi
fi
if [ ! -d "$ROOTFS/usr" ]; then
  log "creating Debian $DEBIAN_SUITE arm64 rootfs with mmdebstrap (runs under qemu, be patient)"

  # Ubuntu's debian-archive-keyring predates the Debian 13 (trixie) archive
  # signing keys (created 2025-03), so apt inside the bootstrap fails with
  # NO_PUBKEY. Install the current keyring straight from Debian's own pool.
  KEYRING_POOL="https://deb.debian.org/debian/pool/main/d/debian-archive-keyring"
  KEYRING_DEB=$(curl -fsSL "$KEYRING_POOL/" | grep -oE 'debian-archive-keyring_[0-9][^"<>]*_all\.deb' | sort -uV | tail -n1)
  [ -n "$KEYRING_DEB" ] || die "could not find debian-archive-keyring .deb under $KEYRING_POOL"
  log "installing $KEYRING_DEB (current Debian archive signing keys)"
  curl -fL --retry 3 -o "$DOWNLOADS/$KEYRING_DEB" "$KEYRING_POOL/$KEYRING_DEB"
  dpkg -i "$DOWNLOADS/$KEYRING_DEB"

  rm -rf "$WORKDIR/rootfs.tar"
  # mmdebstrap does NOT append "-security" to explicitly passed mirrors (it
  # only derives <suite>-security automatically when no mirror is given), so
  # the security suite must be handed over as a one-line sources entry.
  mmdebstrap --architectures=arm64 --variant=minbase \
    --components="$DEBIAN_COMPONENTS" \
    --include=ca-certificates \
    --aptopt='Acquire::Retries "5"' \
    "$DEBIAN_SUITE" "$WORKDIR/rootfs.tar" \
    "$DEBIAN_MIRROR" \
    "deb $DEBIAN_SECURITY_MIRROR $DEBIAN_SUITE-security main"
  mkdir -p "$ROOTFS"
  tar --same-owner -C "$ROOTFS" -xf "$WORKDIR/rootfs.tar"
  printf '%s' "$PROFILE" > "$PROFILE_MARKER"
fi

# ---------------------------------------------------------------------------
# 3. Packages: xvfb stack + Linux QQ dependencies
# ---------------------------------------------------------------------------
if [ ! -x "$ROOTFS/usr/bin/xvfb-run" ]; then
  if [ -f "$QQ_DEB" ]; then
    log "resolving Linux QQ dependencies from the .deb control data"
    QQ_DEPS=$( { dpkg-deb -f "$QQ_DEB" Depends 2>/dev/null || true;
                 dpkg-deb -f "$QQ_DEB" Pre-Depends 2>/dev/null || true; } \
      | tr ',' '\n' \
      | sed -e 's/([^)]*)//g' -e 's/[<=(][^)<>]*[)>]//g' \
      | awk -F'|' '{gsub(/^[[:space:]]+|[[:space:]]+$/, "", $1); if ($1 != "" && $1 !~ /:/) print $1}' \
      | sort -u )
    [ -n "$QQ_DEPS" ] || die "failed to parse dependencies from $QQ_DEB"
  else
    log "no QQ .deb available, using the dependency set recorded from the reference environment"
    QQ_DEPS="libgtk-3-0t64 libnss3 libasound2t64 libatk1.0-0t64 libatk-bridge2.0-0t64 \
libatspi2.0-0t64 libcups2t64 libdbus-1-3 libdrm2 libgbm1 libgl1 mesa-libgallium \
libx11-6 libx11-xcb1 libxcb1 libxcomposite1 libxcursor1 libxdamage1 libxext6 \
libxfixes3 libxkbcommon0 libxrandr2 libxrender1 libxtst6 libxshmfence1 libxi6 \
fontconfig fonts-dejavu-core shared-mime-info"
  fi
  log "QQ dependency set: $QQ_DEPS"

  log "installing packages inside the rootfs (qemu-emulated, this takes a while)"
  run_rootfs "apt-get update"
  # shellcheck disable=SC2086
  run_rootfs "apt-get install -y --no-install-recommends xvfb xauth xfonts-base fonts-dejavu-core tzdata shared-mime-info ca-certificates procps psmisc $QQ_DEPS"
  run_rootfs "apt-get clean && rm -rf /var/lib/apt/lists/*"
else
  log "xvfb already present, skipping package install"
fi

for soname in libgtk-3.so.0 libnss3.so libasound.so.2 libgbm.so.1 libX11.so.6 libXtst.so.6; do
  run_rootfs "ldconfig -p | grep -q $soname" || die "required library $soname missing in rootfs"
done
log "runtime library check passed"

DEBIAN_VERSION=$(cat "$ROOTFS/etc/debian_version")
XVFB_VERSION=$(run_rootfs "dpkg-query -W -f='\${Version}' xvfb" | tr -d "'")
log "rootfs: Debian $DEBIAN_VERSION, xvfb $XVFB_VERSION"

# ---------------------------------------------------------------------------
# 4. Linux QQ into /root/Napcat (NapCat rootless-installer layout)
# ---------------------------------------------------------------------------
QQ_BIN="$ROOTFS/$QQ_PREFIX_IN_ROOTFS/opt/QQ/qq"
if [ ! -x "$QQ_BIN" ]; then
  if [ "$QQ_FROM_TARZST" = "1" ]; then
    log "extracting Linux QQ $QQ_VERSION from $NAPCATHOST_QQ_TARZST"
    tar --zstd --wildcards -C "$ROOTFS" -xf "$NAPCATHOST_QQ_TARZST" \
      './root/Napcat/opt/QQ*'
  else
    log "extracting Linux QQ $QQ_VERSION from $QQ_DEB_FILE"
    dpkg-deb -x "$QQ_DEB" "$ROOTFS/$QQ_PREFIX_IN_ROOTFS"
  fi
fi
[ -x "$QQ_BIN" ] || die "Linux QQ executable missing: $QQ_BIN"

# ---------------------------------------------------------------------------
# 5. NapCatQQ wiring (same as the official installer)
# ---------------------------------------------------------------------------
log "installing NapCatQQ $NAPCAT_VERSION"
rm -rf "$WORKDIR/napcat-src"
mkdir -p "$WORKDIR/napcat-src"
unzip -q -o "$DOWNLOADS/$NAPCAT_ZIP_FILE" -d "$WORKDIR/napcat-src"

NAPCAT_DIR="$ROOTFS/$NAPCAT_APP_LAUNCHER/napcat"
rm -rf "$NAPCAT_DIR"
mkdir -p "$NAPCAT_DIR"
cp -a "$WORKDIR/napcat-src/." "$NAPCAT_DIR/"
chmod -R a+rX "$NAPCAT_DIR"
[ -f "$NAPCAT_DIR/napcat.mjs" ] || die "napcat.mjs missing after copy (unexpected NapCat.Shell.zip layout)"

APP_PACKAGE_JSON="$ROOTFS/$QQ_PREFIX_IN_ROOTFS/opt/QQ/resources/app/package.json"
LOADNAPCAT_JS="$ROOTFS/$QQ_PREFIX_IN_ROOTFS/opt/QQ/resources/app/loadNapCat.js"
cat > "$LOADNAPCAT_JS" <<EOF
(async () => {await import('file:////$NAPCAT_APP_LAUNCHER/napcat/napcat.mjs');})();
EOF
python3 - "$APP_PACKAGE_JSON" <<'PY'
import json, sys
path = sys.argv[1]
with open(path, "r", encoding="utf-8") as fh:
    package = json.load(fh)
package["main"] = "./loadNapCat.js"
with open(path, "w", encoding="utf-8") as fh:
    json.dump(package, fh, indent=2, ensure_ascii=False)
    fh.write("\n")
PY
log "NapCat hooked via loadNapCat.js + package.json main"

# ---------------------------------------------------------------------------
# 6. Guest pseudo files bound by RuntimeEnvironment (values taken from the
#    reference device environment archive)
# ---------------------------------------------------------------------------
mkdir -p "$ROOTFS/proc"
printf '4096\n'                     > "$ROOTFS/proc/.sysctl_inotify_max_user_watches"
printf '40\n'                       > "$ROOTFS/proc/.sysctl_entry_cap_last_cap"
printf '124.08 932.80\n'           > "$ROOTFS/proc/.uptime"
printf '0.12 0.07 0.02 2/165 765\n' > "$ROOTFS/proc/.loadavg"
cat > "$ROOTFS/proc/.version" <<'EOF'
Linux version 6.17.0-PRoot-Distro (proot@termux) (gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) #1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000
EOF
cat > "$ROOTFS/proc/.stat" <<'EOF'
cpu  1957 0 2877 93280 262 342 254 87 0 0
cpu0 31 0 226 12027 82 10 4 9 0 0
cpu1 45 0 664 11144 21 263 233 12 0 0
cpu2 494 0 537 11283 27 10 3 8 0 0
cpu3 359 0 234 11723 24 26 5 7 0 0
cpu4 295 0 268 11772 10 12 2 12 0 0
cpu5 270 0 251 11833 15 3 1 10 0 0
cpu6 430 0 520 11386 30 8 1 12 0 0
cpu7 30 0 172 12108 50 8 1 13 0 0
intr 127541 38 290 0 0 0 0 4 0 1 0 0 25329 258 0 5777 277 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
ctxt 140223
btime 1680020856
processes 772
procs_running 2
procs_blocked 0
softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
EOF

# /etc files: the Android app rewrites these at install time anyway; keeping
# them consistent here makes a manually imported archive self-contained too.
mkdir -p "$ROOTFS/etc"
cat > "$ROOTFS/etc/resolv.conf" <<'EOF'
nameserver 223.5.5.5
nameserver 119.29.29.29
nameserver 1.1.1.1
EOF
cat > "$ROOTFS/etc/hosts" <<'EOF'
127.0.0.1 localhost
::1 localhost ip6-localhost ip6-loopback
EOF

# ---------------------------------------------------------------------------
# 7. Cleanup + sanity, then repack
# ---------------------------------------------------------------------------
rm -rf "$ROOTFS/tmp"/* "$ROOTFS/var/tmp"/* 2>/dev/null || true
rm -rf "$ROOTFS/root/.bash_history" "$ROOTFS/root/.wget-hsts" "$ROOTFS/root/.cache" 2>/dev/null || true
rm -rf "$NAPCAT_DIR/config" "$NAPCAT_DIR/logs" "$NAPCAT_DIR/cache" 2>/dev/null || true
mkdir -p "$ROOTFS/tmp" "$ROOTFS/var/tmp" "$ROOTFS/root/.config/QQ"
chmod 1777 "$ROOTFS/tmp" "$ROOTFS/var/tmp"

[ -x "$ROOTFS/bin/bash" ] || die "/bin/bash missing in rootfs"
[ -x "$ROOTFS/usr/bin/xvfb-run" ] || die "xvfb-run missing in rootfs"
[ -x "$QQ_BIN" ] || die "QQ binary missing"

log "packing $ARCHIVE_NAME (zstd -$ZSTD_LEVEL, this takes a few minutes)"
OUT="$WORKDIR/out/$ARCHIVE_NAME"
rm -f "$OUT"
( cd "$ROOTFS" && tar --numeric-owner --owner=0 --group=0 \
    --exclude='./dev' --exclude='./sys' \
    --exclude='./.napcathost-build-profile' \
    --exclude='./tmp/*' --exclude='./var/tmp/*' \
    --exclude='./var/lib/apt/lists/*' \
    --exclude='./var/cache/apt/archives/*.deb' \
    -cf - . ) | zstd -q -T0 -"$ZSTD_LEVEL" --long=27 -o "$OUT"

ARCHIVE_SHA256=$(sha256sum "$OUT" | cut -d' ' -f1)
ARCHIVE_SIZE=$(stat -c '%s' "$OUT")
log "archive built: $OUT ($ARCHIVE_SIZE bytes, sha256 $ARCHIVE_SHA256)"

mkdir -p "$OUT_DIR"
cp -f "$OUT" "$OUT_DIR/$ARCHIVE_NAME"

CREATED=$(date -u +'%Y-%m-%dT%H:%M:%S+00:00')
python3 - "$OUT_DIR/build-info.json" "$OUT_DIR/manifest.json" <<PY
import json, sys

build_info = {
    "profile": "$PROFILE",
    "archive": "$ARCHIVE_NAME",
    "size": int("$ARCHIVE_SIZE"),
    "sha256": "$ARCHIVE_SHA256",
    "created": "$CREATED",
    "architecture": "arm64-v8a",
    "components": {
        "debian": "$DEBIAN_VERSION",
        "linux_qq": "$QQ_VERSION",
        "napcatqq": "$NAPCAT_VERSION",
        "xvfb": "$XVFB_VERSION",
    },
    "sources": {
        "debian_mirror": "$DEBIAN_MIRROR",
        "linuxqq": "${QQ_DEB_URL:-provided-locally}",
        "napcatqq": "$NAPCAT_ZIP_URL",
    },
}
manifest = {
    "format": 1,
    "archive": build_info["archive"],
    "size": build_info["size"],
    "sha256": build_info["sha256"],
    "architecture": build_info["architecture"],
    "created": build_info["created"],
    "profile": build_info["profile"],
    "components": build_info["components"],
    "excluded": [
        "QQ login data and databases",
        "NapCat configuration, credentials, logs and caches",
        "SSH data and shell history",
        "temporary files and apt download caches",
        "Android bind-mount placeholder directories",
    ],
    "distribution": "private-sideload-only",
}

for path, payload in zip(sys.argv[1:], (build_info, manifest)):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, ensure_ascii=False)
        fh.write("\n")
PY

log "DONE"
log "  archive   : $OUT_DIR/$ARCHIVE_NAME"
log "  sha256    : $ARCHIVE_SHA256"
log "  metadata  : $OUT_DIR/build-info.json, $OUT_DIR/manifest.json"
log "  components: Debian $DEBIAN_VERSION / QQ $QQ_VERSION / NapCat $NAPCAT_VERSION / xvfb $XVFB_VERSION"
log "next: sync this sha256 into RuntimeInstaller.java (tools\\build-runtime-from-scratch.ps1 does it), then build the APK."
