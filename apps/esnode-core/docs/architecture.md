ESNODE | Source Available BUSL-1.1 | Copyright (c) 2025 Estimatedstocks AB

# ESNODE Architecture (ESNODE-Core + ESNODE-Pulse)

ESNODE-Core lives in this repository. ESNODE-Pulse is the licensed controller shipped separately; references below are for operators who have access to it.

## Components
- `esnode-core`: per-node collector exposing:
  - `/metrics` Prometheus text (host + GPU + power + self-metrics)
  - `/status` and `/v1/status` JSON snapshot (load, power, temps, GPUs, last scrape/errors)
  - `/events` SSE stream of status snapshots (5s default)
  - `/healthz`
- `esnode-orchestrator`: optional autonomous resource manager (embedded lib, CLI-configurable) exposing:
  - `/orchestrator/metrics` JSON status
- `esnode-pulse`: polling/aggregator that:
  - polls agents’ `/status` and `/metrics`
  - exposes aggregated `/agents` JSON
- exposes `/metrics` (server-side Prometheus: agent availability, node power, tokens-per-watt, tokens-per-joule)
  - `/healthz`
  - simple master/slave hint via `--role` plus optional `--master-url` heartbeat failover

## Data Flow
1) Agent collectors gather host/GPU/power metrics on interval; publish to Prometheus + JSON snapshot + SSE.
2) Server polls agents (master role only), caches JSON, scrapes metrics to derive node power and tokens-per-watt (if `model_tokens_total` is present) and tokens-per-joule (using token and node energy deltas).
3) Server exposes aggregated metrics for Prometheus/Grafana.

## Failover (minimal stub)
- `--role master|slave`
- Slaves can watch `--master-url`; on heartbeat failure, they self-promote (best-effort).
- Stateless: caches live in memory; persistence can be added (sled/sqlite) later.

## Notes
- NVLink counters and ECC are best-effort; set only when supported by NVML (ECC deltas emitted per GPU/type).
- Power envelope breach gauge (`esnode_node_power_envelope_exceeded`) relies on optional `--node-power-envelope-watts`.
- CPU package power/energy comes from RAPL where available; node power from hwmon/BMC/IPMI; GPU energy from NVML.
- KV cache fragmentation and tokens-per-watt/joule require app-side `model_*` metrics.

## Recommended Plan to Add IBM Support

- AIX
  - Investigate available Rust target support and libc portability; swap Linux filesystems with AIX APIs for CPU/mem/disk/net.
- z/OS
  - Assess Rust support and viable async/network stacks; use z/OS system calls and SMF for metrics if feasible.
- AS/400 (IBM i)
  - Use IBM i APIs (MI/OS APIs) for metrics; build with appropriate cross-compiler or native toolchain.
- General
  - Abstract collectors behind per-OS adapters; provide feature flags to compile the appropriate backends.
  - Replace NVML for GPU metrics or disable GPU metrics on non-NVIDIA platforms.
  - Add CI build matrix (GitHub Actions/enterprise CI) targeting Linux/macOS/Windows first, then IBM platforms when toolchains are validated.
  - Define packaging outputs under `public/distribution/<platform>` with OS-native installers.
