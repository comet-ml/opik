#!/bin/sh
set -eu

# ESNODE-Core installer (one-command quickstart).
# Intended to be hosted at https://esnode.co/install.sh
#
# Usage:
#   curl -fsSL https://esnode.co/install.sh | sh
#
# Options:
#   --version <v>        Install a specific version (default: latest)
#   --prefix <dir>       Install prefix (default: /usr/local)
#   --no-service         Do not install/enable systemd service (Linux only)
#   --dry-run            Print what would happen, don't change anything
#
# Env overrides:
#   ESNODE_VERSION, ESNODE_PREFIX, ESNODE_NO_SERVICE=1, ESNODE_DRY_RUN=1
#   ESNODE_DOWNLOAD_BASE_URL (default: https://esnode.co/downloads)
#   ESNODE_GITHUB_REPO (default: ESNODE/ESNODE-Core)
#
# Notes:
# - The default path installs to /usr/local/bin and (on Linux) sets up systemd.
# - If you don't want sudo prompts, run with a user-writable prefix, e.g.:
#     curl -fsSL https://esnode.co/install.sh | sh -s -- --prefix "$HOME/.local" --no-service

say() { printf '%s\n' "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

download() {
  url=$1
  out=$2
  if have curl; then
    curl -fsSL "$url" -o "$out"
  elif have wget; then
    wget -qO "$out" "$url"
  else
    die "need curl or wget to download artifacts"
  fi
}

fetch_text() {
  url=$1
  if have curl; then
    curl -fsSL "$url"
  elif have wget; then
    wget -qO- "$url"
  else
    return 1
  fi
}

run() {
  if [ "${DRY_RUN}" -eq 1 ]; then
    say "+ $*"
    return 0
  fi
  # shellcheck disable=SC2086
  sh -c "$*"
}

sudo_run() {
  if [ "${DRY_RUN}" -eq 1 ]; then
    say "+ sudo $*"
    return 0
  fi
  if have sudo; then
    # shellcheck disable=SC2086
    sudo sh -c "$*"
  else
    die "sudo not found; re-run as root or set --prefix to a user-writable path and use --no-service"
  fi
}

OS=$(uname -s 2>/dev/null || echo unknown)
ARCH=$(uname -m 2>/dev/null || echo unknown)

VERSION=${ESNODE_VERSION:-latest}
PREFIX=${ESNODE_PREFIX:-/usr/local}
NO_SERVICE=${ESNODE_NO_SERVICE:-0}
DRY_RUN=${ESNODE_DRY_RUN:-0}
BASE_URL=${ESNODE_DOWNLOAD_BASE_URL:-https://esnode.co/downloads}
GITHUB_REPO=${ESNODE_GITHUB_REPO:-ESNODE/ESNODE-Core}

usage() {
  cat <<EOF
ESNODE-Core installer

Usage:
  curl -fsSL https://esnode.co/install.sh | sh

Options:
  --version <v>        Install specific version (default: latest)
  --prefix <dir>       Install prefix (default: /usr/local)
  --no-service         Skip systemd setup (Linux only)
  --dry-run            Print actions; make no changes
  -h, --help           Show this help
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION=${2:-}; shift 2 ;;
    --prefix) PREFIX=${2:-}; shift 2 ;;
    --no-service) NO_SERVICE=1; shift 1 ;;
    --dry-run) DRY_RUN=1; shift 1 ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
done

case "$OS" in
  Linux|Darwin) ;;
  *) die "unsupported OS: $OS (supported: Linux, macOS)" ;;
esac

case "$ARCH" in
  x86_64|amd64) LINUX_ARCH=amd64; GH_ARCH=x86_64 ;;
  aarch64|arm64) LINUX_ARCH=arm64; GH_ARCH=aarch64 ;;
  *) die "unsupported architecture: $ARCH (supported: x86_64/amd64, aarch64/arm64)" ;;
esac

resolve_latest_version() {
  # Prefer esnode.co if a simple version marker is published.
  v=$(fetch_text "${BASE_URL}/esnode-core/latest.txt" 2>/dev/null || true)
  if [ -n "${v}" ]; then
    printf '%s' "$v" | tr -d '\r\n' | sed 's/^v//'
    return 0
  fi

  # Fallback: GitHub releases API.
  json=$(fetch_text "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" 2>/dev/null || true)
  if [ -n "${json}" ]; then
    tag=$(printf '%s\n' "$json" | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1)
    if [ -n "${tag}" ]; then
      printf '%s' "$tag" | sed 's/^v//'
      return 0
    fi
  fi

  return 1
}

if [ "${VERSION}" = "latest" ] || [ -z "${VERSION}" ]; then
  say "==> Resolving latest version..."
  VERSION=$(resolve_latest_version) || die "could not resolve latest version; re-run with --version X.Y.Z"
fi

# Download and extract
tmpdir=$(mktemp -d 2>/dev/null || mktemp -d -t esnode)
cleanup() { rm -rf "$tmpdir"; }
trap cleanup EXIT INT TERM

asset=""
archive="${tmpdir}/esnode-core.tgz"

try_download() {
  url=$1
  say "==> Downloading: $url"
  if download "$url" "$archive" 2>/dev/null; then
    asset=$url
    return 0
  fi
  return 1
}

if [ "$OS" = "Linux" ]; then
  # Primary: versioned tarballs on esnode.co.
  try_download "${BASE_URL}/esnode-core-${VERSION}-linux-${LINUX_ARCH}.tar.gz" || \
  try_download "${BASE_URL}/esnode-core-${VERSION}-linux-${GH_ARCH}.tar.gz" || \
  # Fallback: GitHub release assets (historical naming).
  try_download "https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/esnode-core-linux-${GH_ARCH}.tar.gz" || \
  try_download "https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/esnode-core-linux-${LINUX_ARCH}.tar.gz" || \
  die "failed to download a Linux tarball for ${VERSION} (${ARCH})"
elif [ "$OS" = "Darwin" ]; then
  # Prefer macOS artifacts from GitHub Releases.
  try_download "https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/esnode-core-macos-${GH_ARCH}.tar.gz" || \
  try_download "https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/esnode-core-macos-aarch64.tar.gz" || \
  try_download "https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/esnode-core-macos-x86_64.tar.gz" || \
  die "failed to download a macOS tarball for ${VERSION} (${ARCH})"
fi

say "==> Extracting..."
run "tar -xzf \"$archive\" -C \"$tmpdir\""

if [ ! -f "${tmpdir}/esnode-core" ]; then
  die "archive did not contain expected 'esnode-core' binary"
fi

install_bin_dir="${PREFIX%/}/bin"
bin_dst="${install_bin_dir}/esnode-core"

say "==> Installing esnode-core to ${bin_dst}"
run "mkdir -p \"$install_bin_dir\""

# Use sudo for system paths when not writable.
if [ -w "$install_bin_dir" ]; then
  run "install -m 0755 \"${tmpdir}/esnode-core\" \"$bin_dst\""
else
  sudo_run "mkdir -p \"$install_bin_dir\""
  sudo_run "install -m 0755 \"${tmpdir}/esnode-core\" \"$bin_dst\""
fi

if [ "$OS" = "Linux" ] && [ "${NO_SERVICE}" -eq 0 ]; then
  if ! have systemctl; then
    warn "systemctl not found; skipping systemd setup. Run manually: ${bin_dst} daemon"
  else
    say "==> Setting up systemd service"
    service_dst="/etc/systemd/system/esnode-core.service"
    config_dst="/etc/esnode/esnode.toml"

    if [ -f "${tmpdir}/esnode-core.service" ]; then
      sudo_run "install -m 0644 \"${tmpdir}/esnode-core.service\" \"$service_dst\""
    else
      # Minimal unit: matches repo default.
      sudo_run "cat >\"$service_dst\" <<'EOF'
[Unit]
Description=ESNODE-Core - GPU-aware host metrics
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=${bin_dst} daemon
Environment=ESNODE_CONFIG=${config_dst}
Restart=on-failure
RestartSec=5
User=root

[Install]
WantedBy=multi-user.target
EOF"
    fi

    sudo_run "mkdir -p /etc/esnode"
    if [ ! -f "$config_dst" ]; then
      say "==> Writing default config to ${config_dst}"
      sudo_run "cat >\"$config_dst\" <<'EOF'
listen_address = \"0.0.0.0:9100\"
scrape_interval_seconds = 5
enable_cpu = true
enable_memory = true
enable_disk = true
enable_network = true
enable_gpu = true
enable_gpu_amd = false
enable_power = true
enable_gpu_mig = false
enable_gpu_events = false
k8s_mode = false
gpu_visible_devices = \"all\"
mig_config_devices = \"\"
log_level = \"info\"

[orchestrator]
enabled = false
EOF"
    fi

    # Optional: create a predictable writable TSDB dir if operators want to enable it later.
    sudo_run "mkdir -p /var/lib/esnode/tsdb && chmod 755 /var/lib/esnode /var/lib/esnode/tsdb"

    sudo_run "systemctl daemon-reload"
    sudo_run "systemctl enable --now esnode-core.service"
  fi
fi

say ""
say "Installed ESNODE-Core ${VERSION}"
say "Binary: ${bin_dst}"
if [ "$OS" = "Linux" ] && [ "${NO_SERVICE}" -eq 0 ] && have systemctl; then
  say "Service: esnode-core.service"
  say "Check:  systemctl status esnode-core.service --no-pager"
fi
say "Verify: curl -fsSL http://localhost:9100/metrics | head"
