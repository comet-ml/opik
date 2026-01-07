# Support & Upgrade Policy

## Versioning
- We follow semantic versioning for the public API surface (binaries/config/metrics):  
  - **MAJOR**: breaking changes to config/CLI/metrics/labels or removal of deprecated fields.  
  - **MINOR**: backward-compatible features and metrics additions.  
  - **PATCH**: fixes and non-breaking changes.
- Metrics and labels: additions are allowed in MINOR/PATCH. Removals/renames only in MAJOR and must carry a prior deprecation notice.

## Deprecations
- Deprecate first, then remove in the next MAJOR. Deprecations must be called out in CHANGELOG/RELEASE_NOTES and, when feasible, provide compatibility labels or fallbacks.
- For metrics label changes, maintain compatibility series (e.g., `_compat`) during the deprecation window when possible.

## Releases & Notes
- Tags `vX.Y.Z` trigger CI builds and published artifacts. Each release must include CHANGELOG/RELEASE_NOTES entries with:
  - New features
  - Fixes/known issues
  - Deprecations/migrations (config/CLI/metrics)
  - Validation/compatibility updates (GPU/driver/CUDA/K8s)

## Platform validation
- See `docs/platform-matrix.md` for validated GPU/driver/CUDA/K8s combinations and feature status (MIG/NVSwitch/K8s mode). Update after each validation run.

## Support channels
- Community issues/PRs: GitHub (subject to LICENSE and CLA).
- Security: security@estimatedstocks.com (see `SECURITY.md`).
- Conduct: conduct@estimatedstocks.com (see `CODE_OF_CONDUCT.md`).

## Data & telemetry disclosure
- Collected locally: host metrics (CPU, memory, disk, network), GPU metrics (NVML; MIG/NVLink), power readings (RAPL/hwmon/BMC), and optional GPU events (XID/ECC). Containers/K8s labels are derived from visible device lists (`NVIDIA_VISIBLE_DEVICES`, etc.).
- Emitted externally: Prometheus `/metrics` text, JSON `/status` (`/v1/status`), and optional SSE `/events`. No outbound calls are made unless configured to connect to ESNODE-Pulse.
- Persistence: optional local TSDB when `enable_local_tsdb` is true (JSONL blocks under `local_tsdb_path`, defaulting to `$XDG_DATA_HOME/esnode/tsdb` or `~/.local/share/esnode/tsdb` for non-root runs); no other on-disk persistence beyond logs and config.
- Sensitive data: no credentials are collected; avoid embedding secrets in labels/config. Hostnames/PCI IDs are exposed in metrics/labels.
## Expectations
- No guarantees for backward compatibility on unreleased/main branch builds.
- Default build is unsigned; production use should verify checksums/signatures when available in releases.
