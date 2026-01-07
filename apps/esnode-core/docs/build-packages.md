# ESNODE-Core build & packaging guide

This repository builds the ESNODE-Core agent (`esnode-core`). The ESNODE-Pulse controller lives in a separate, private codebase.

## Prerequisites
- Rust toolchain (cargo) installed.
- Optional: [`fpm`](https://fpm.readthedocs.io/en/latest/) installed for generating `.deb` and `.rpm` packages. If `fpm` is missing, scripts still emit generic tarballs.
- Run from repo root.

## Build artifacts layout (created by scripts)
- ESNODE-Core artifacts land under `public/distribution/esnode-core/...` (Linux tarball always; `.deb/.rpm` if `fpm` is present; Windows zip if target installed).

Supported OS targets (packaging/compat):
- Ubuntu Server (primary CUDA/AI target) → deb/tar.gz
- RHEL / Rocky / AlmaLinux → rpm/tar.gz
- NVIDIA DGX OS (Ubuntu-based) → deb/tar.gz
- SLES → rpm/tar.gz
- Debian → deb/tar.gz

## Build commands

Run tests first (recommended):
```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
```

ESNODE-Core packaging:
```bash
ESNODE_VERSION=1.0.0 scripts/dist/build-agent.sh
# or: scripts/dist/build-agent.sh 1.0.0
```
Outputs:
- `public/distribution/esnode-core/linux/esnode-core-<version>-linux-amd64.tar.gz`
- `.deb` per family:
  - Ubuntu: `public/distribution/esnode-core/linux/deb/ubuntu/esnode-core_<version>_amd64.deb`
  - Debian: `public/distribution/esnode-core/linux/deb/debian/esnode-core_<version>_amd64.deb`
  - NVIDIA DGX OS: `public/distribution/esnode-core/linux/deb/dgx/esnode-core_<version>_amd64.deb`
- `.rpm` per family (shared spec):
  - RHEL/Rocky/Alma: `public/distribution/esnode-core/linux/rpm/rhel/esnode-core-<version>-1.x86_64.rpm`
  - SLES: `public/distribution/esnode-core/linux/rpm/sles/esnode-core-<version>-1.x86_64.rpm`
- `public/distribution/esnode-core/windows/esnode-core-<version>-windows-amd64.zip` if the Windows target is installed

One-shot release (tar/deb/rpm for Linux targets, optional Windows zip):
```bash
scripts/dist/esnode-core-release.sh
# Uses version from crates/agent-bin/Cargo.toml unless ESNODE_VERSION is set
```
Artifacts are written to the canonical layout above and mirrored into `public/distribution/releases/<label>/` for easy pickup (e.g., `host`, `linux-amd64`).

## Notes
- The scripts assume amd64 builds; extend as needed for other targets. Override with:
  - `ESNODE_TARGET=<rust-target-triple>` (e.g., `x86_64-unknown-linux-gnu` when cross-compiling on macOS)
  - `ESNODE_ARCH=<deb/rpm arch>` (e.g., `amd64` or `arm64`)
  - When cross-compiling on macOS, install Linux toolchains via Homebrew (`x86_64-unknown-linux-gnu` and/or `aarch64-unknown-linux-gnu`). The release script auto-wires linkers/archivers if they are present, and will skip a target if the toolchain is missing.
- CI (`.github/workflows/tests.yml`) enforces fmt/clippy/tests on PRs/pushes and, on tags, installs `fpm`/rpm tooling to run `scripts/dist/build-agent.sh` as a packaging smoke.
- Install helpers:
  - Linux: `scripts/install/esnode-core-linux.sh` copies binaries to `/usr/local/bin` and installs systemd units.
  - Windows: `scripts/install/esnode-core-windows.ps1` copies to `C:\Program Files\ESNODE`, adds PATH, and optionally registers an NSSM service.
