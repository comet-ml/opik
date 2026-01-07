# Changelog

All notable changes to this project will be documented here.

## Release notes & policy
- We follow semver: MAJOR for breaking config/CLI/metrics/labels, MINOR for additive, PATCH for fixes.
- Deprecations are announced here and in release notes; removals occur only in the next MAJOR when possible.
- Metric/label changes must include migration notes and, where feasible, compatibility labels during a deprecation window.
- Platform validation updates (GPU/driver/CUDA/K8s) should be recorded here and in `docs/platform-matrix.md`.
- UUID-first metrics: see `docs/migrations.md` for guidance. `_compat` metrics (when `k8s_mode` is enabled) are provided for a transition period; plan to move dashboards to UUID-based labels.

## Unreleased
- Fixed local TSDB default path for non-root runs by resolving to `$XDG_DATA_HOME/esnode/tsdb` or `~/.local/share/esnode/tsdb`, and now disable with a clear warning if initialization fails (resolves GitHub issue #2). Documented upgrade guidance in README and quickstart.
- App collector now uses async HTTP with a 2s timeout to avoid blocking the scrape loop; CLI client uses a lightweight HTTP helper with timeouts to keep status/metrics fetches non-blocking even when endpoints hang.
- TSDB export now snapshots the current block without closing it, preventing index resets and missing samples when exporting mid-window.
- Orchestrator control API is loopback-only by default; set `orchestrator.allow_public=true` to expose `/orchestrator/*` on non-loopback listeners and `orchestrator.token` to require bearer auth.
- Added swap/disk/network degradation flags, aggregate degradation score, and audit logging on orchestrator actions; created a living gap logbook (`docs/gap-logbook.md`).
- Add GitHub Actions release pipeline, packaging via `scripts/dist/esnode-core-release.sh`, and artifact checksums.
- Expanded GPU/MIG telemetry, K8s compatibility labels, and NVML FFI scaffolding.
- Added contributor documentation (CONTRIBUTING.md, CODE_OF_CONDUCT.md) and security policy.

## [v0.1.0] - 2024-xx-xx
- Initial public source-available release of ESNODE-Core (BUSL-1.1).

_Fill in dated sections when tagging releases._
