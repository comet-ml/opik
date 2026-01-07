#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/public/distribution"
mkdir -p "$OUT_DIR"

# Read version from Cargo.toml
AGENT_VER=$(sed -n 's/^version = "\([^"\]*\)"$/\1/p' "$ROOT_DIR/crates/agent-bin/Cargo.toml" | head -n1)

echo "==> Building native host binary (release)"
cargo build -p agent-bin --release

HOST_OS=$(uname -s | tr '[:upper:]' '[:lower:]')
HOST_ARCH=$(uname -m)

AGENT_BIN="$ROOT_DIR/target/release/esnode-core"

PKG_DIR="$OUT_DIR/${HOST_OS}-${HOST_ARCH}"
mkdir -p "$PKG_DIR"

echo "==> Packaging native host: $HOST_OS-$HOST_ARCH"
cp "$AGENT_BIN" "$PKG_DIR/esnode-core"
tar -C "$PKG_DIR" -czf "$OUT_DIR/esnode-core-${HOST_OS}-${HOST_ARCH}-v${AGENT_VER}.tar.gz" esnode-core

echo "==> Attempting cross-builds (best-effort)"
if command -v rustup >/dev/null 2>&1; then
  # Linux x86_64 GNU
  if rustup target add x86_64-unknown-linux-gnu >/dev/null 2>&1; then
    cargo build --release --target x86_64-unknown-linux-gnu -p agent-bin || true
    LDIR="$ROOT_DIR/target/x86_64-unknown-linux-gnu/release"
    if [[ -f "$LDIR/esnode-core" ]]; then
      tar -C "$LDIR" -czf "$OUT_DIR/esnode-core-linux-amd64-v${AGENT_VER}.tar.gz" esnode-core
    fi
  fi
  # Linux ARM64 GNU
  if rustup target add aarch64-unknown-linux-gnu >/dev/null 2>&1; then
    cargo build --release --target aarch64-unknown-linux-gnu -p agent-bin || true
    LDIR="$ROOT_DIR/target/aarch64-unknown-linux-gnu/release"
    if [[ -f "$LDIR/esnode-core" ]]; then
      tar -C "$LDIR" -czf "$OUT_DIR/esnode-core-linux-arm64-v${AGENT_VER}.tar.gz" esnode-core
    fi
  fi
  # macOS ARM64
  if rustup target add aarch64-apple-darwin >/dev/null 2>&1; then
    cargo build --release --target aarch64-apple-darwin -p agent-bin || true
    DDIR="$ROOT_DIR/target/aarch64-apple-darwin/release"
    if [[ -f "$DDIR/esnode-core" ]]; then
      tar -C "$DDIR" -czf "$OUT_DIR/esnode-core-darwin-arm64-v${AGENT_VER}.tar.gz" esnode-core
    fi
  fi
  # macOS x86_64
  if rustup target add x86_64-apple-darwin >/dev/null 2>&1; then
    cargo build --release --target x86_64-apple-darwin -p agent-bin || true
    DDIR="$ROOT_DIR/target/x86_64-apple-darwin/release"
    if [[ -f "$DDIR/esnode-core" ]]; then
      tar -C "$DDIR" -czf "$OUT_DIR/esnode-core-darwin-amd64-v${AGENT_VER}.tar.gz" esnode-core
    fi
  fi
else
  echo "rustup not found; skipping cross-builds"
fi

echo "==> IBM/AIX/zOS/AS400 builds"
echo "NOTE: No official Rust toolchain or dependency support in this repo for AIX, z/OS, AS/400."
echo "      Building for these platforms requires specialized cross-toolchains and replacing dependencies (axum, tokio, prometheus, nvml)."
echo "      This script leaves placeholders; integration plan required."

echo "==> Packages written to $OUT_DIR"
ls -lh "$OUT_DIR" | awk '{print $9, $5}'
