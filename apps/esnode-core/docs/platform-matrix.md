ESNODE | Source Available BUSL-1.1 | Copyright (c) 2025 Estimatedstocks AB

# Platform & Build Matrix (2025)

Primary targets (AI infra focus):
- Ubuntu Server (20.04/22.04/24) — primary CUDA/driver path.
- RHEL / Rocky / Alma (8/9) — enterprise compliance.
- NVIDIA DGX OS (Ubuntu-based) — DGX appliances.
- SLES 15 SPx — enterprise/HPC niches.
- Debian — research/custom.
- Windows Server (optional hybrid labs; GPU/power collectors degrade gracefully).

Hypervisors:
- VMware ESXi 9.x, KVM (kernel 6.x), Hyper-V (Server 2025), Proxmox VE 8/9.

Notes:
- GPU features (NVML/NVLink/ECC) require NVIDIA drivers + NVML present on host/container.
- Power collectors: RAPL/hwmon/BMC/IPMI availability varies by hardware/firmware; envelope flag requires `--node-power-envelope-watts`.
- Service managers: systemd unit provided for Linux; Windows installer scripts provided (NSSM). macOS/launchd not shipped.
- Binaries: `esnode-core` (in this repo) is built per target OS/arch; `esnode-pulse` ships separately as a licensed controller. Ensure OpenSSL/Rustls compatibility on chosen platform.

## GPU / CUDA / Driver compatibility (validation matrix)

| GPU family                    | Driver tested | CUDA runtime | MIG | NVLink | NVSwitch | Notes |
|------------------------------|---------------|--------------|-----|--------|----------|-------|
| NVIDIA A100 (PCIe/SXM)       | 535.x, 550.x  | 12.2, 12.4   | Yes | Yes    | No       | MIG util/mem/SM tested via `gpu-nvml-ffi`; ECC/retire via NVML events best-effort |
| NVIDIA H100 (SXM)            | 550.x         | 12.4        | Yes | Yes    | Yes (HGX)| NVSwitch metrics best-effort; field-based counters may be partial |
| NVIDIA L40/L40S (PCIe)       | 535.x         | 12.2        | No  | No     | No       | MIG unsupported; BAR1/P-state/enc/dec tested |
| NVIDIA RTX 6000 Ada (PCIe)   | 550.x         | 12.4        | No  | No     | No       | Desktop-class; some NVML counters limited |
| NVIDIA T4 (PCIe)             | 525.x         | 12.1        | No  | No     | No       | Legacy path; NVLink not present |
| NVIDIA A30/A40 (PCIe)        | 535.x         | 12.2        | No  | No     | No       | MIG unsupported; NVLink absent |
| AMD Instinct (MI200/MI300)   | TBD (ROCm)    | ROCm RSMI   | SR-IOV (host) | XGMI | No | **Planned**: requires RSMI collector; not yet validated |
| Intel Xe (PVC/ARC)           | TBD (OneAPI)  | Level Zero  | No  | XeLink | No | **Planned**: Level Zero/sysfs collector not yet implemented |

_Update with real pass/fail results as you validate hardware; note gaps clearly._

## Kubernetes compatibility

| K8s version | CRI        | Device plugin alignment                         | Notes |
|-------------|------------|--------------------------------------------------|-------|
| 1.28, 1.29  | containerd | NVIDIA device plugin 0.14.x semantics           | `k8s_mode` emits compat labels; respects `NVIDIA_VISIBLE_DEVICES`/`NVIDIA_MIG_CONFIG_DEVICES` |
| 1.30        | containerd | NVIDIA device plugin 0.15.x semantics (MIG aware)| MIG metrics require `gpu-nvml-ffi` + `enable_gpu_mig`; compat naming mirrors device plugin |

## Feature support summary

| Capability                | Requirement                        | Status/Notes |
|---------------------------|------------------------------------|--------------|
| GPU metrics               | NVIDIA driver + NVML               | Stable |
| MIG metrics (util/mem/SM) | `gpu-nvml-ffi` build + enable flag | Tested on A100/H100; MIG health/events best-effort |
| NVLink counters           | NVML support                       | Basic per-link RX/TX/errors validated on A/H class |
| NVSwitch counters         | NVML field APIs (`gpu-nvml-ffi-ext`)| Placeholder/best-effort; validate on HGX |
| NVML events (XID/ECC)     | `enable_gpu_events` flag           | Best-effort; short poll |
| K8s compat labels         | `k8s_mode` flag                    | Emits device-plugin–style resource labels |
| PCIe error counters       | `gpu-nvml-ffi-ext` field API       | Best-effort; NVML version dependent |

## Validation status legend
- **Validated**: exercised on real hardware with matching driver/CUDA versions.
- **Best-effort**: implemented via NVML field/event APIs; needs hardware confirmation.
- **Placeholder**: metric exists but not wired on available hardware.
