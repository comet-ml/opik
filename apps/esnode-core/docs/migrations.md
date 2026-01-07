# Metric label migration guide (UUID-first)

## Background
GPU and MIG metrics now use UUID-first labels (`uuid`, `index`) and MIG UUIDs where applicable. This improves stability across reordering/reboots but can break dashboards that assumed a `gpu` index-only label.

## What changed
- GPU metrics: primary labels are `uuid` and `index`. MIG metrics include `gpu_uuid`, `gpu_index`, and `mig` (MIG UUID or ID).
- Compatibility metrics: when `k8s_mode = true`, a subset of GPU metrics also emit `_compat` series with a single `gpu` label using Kubernetes/CDI-style resource names (`nvidia.com/gpu`, `nvidia.com/mig-<profile>`).
- MIG compatibility labels mirror the MIG resource name when `k8s_mode = true`.

## How to migrate dashboards
1) Prefer UUID-based selectors: use `uuid` (and `mig` for MIG metrics) as the stable key. Keep `index` for readability only.
2) For legacy dashboards that assume `gpu`, enable `k8s_mode = true` to get `_compat` metrics and update panel queries to the UUID-based series over time.
3) If you need MIG compatibility labels, enable both `k8s_mode` and `enable_gpu_mig` (with `gpu-nvml-ffi` build) to expose MIG resource names.
4) Avoid hard-coding GPU indices as identifiers; they can change across boots/hardware changes.

## Deprecation
- Compatibility metrics are intended for a transition period. Future major versions may remove `_compat` series; track release notes/CHANGELOG for timelines.

## Flags involved
- `k8s_mode`: emits `_compat` metrics with `gpu` label using Kubernetes/CDI resource names.
- `enable_gpu_mig`: required for MIG metrics/labels when built with `gpu-nvml-ffi`.

## Known gaps
- MIG compatibility labels only emit when `k8s_mode` is on and MIG support is built/enabled.
- No compatibility aliases for every metric; core GPU util/mem/temp/power have `_compat`, others require UUID-based selectors.
