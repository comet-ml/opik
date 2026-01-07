# ESNODE-Core

This repository contains the source, build tooling, and documentation for the ESNODE-Core Agent.

**Supported server OS targets (AI infra focused)**
- Ubuntu Server (primary)
- RHEL-compatible: RHEL / Rocky Linux / AlmaLinux
- NVIDIA DGX OS
- SUSE Linux Enterprise Server (SLES)
- Debian

ESNODE-Core is a GPU-aware host metrics exporter for Linux nodes. It exposes CPU, memory, disk, network, and GPU telemetry at `/metrics` in Prometheus text format.

## Features
- Single binary with zero-config defaults (`0.0.0.0:9100`, 5s interval).
- **Collectors**: CPU, memory, disk, network, GPU (NVML-based).
- **Power-aware**: optional power collector reads RAPL/hwmon/BMC paths for CPU/package/node power; GPU power via NVML.
- **Health endpoint** at `/healthz`.
- **JSON status endpoint** at `/status`.

## Quick Start (Docker)

```bash
docker run -d \
  --name esnode-core \
  --net=host \
  --pid=host \
  --privileged \
  -v /:/host:ro,rslave \
  esnodecore/esnode-core:latest
```

*Note: Privileged mode and host PID/network are recommended for full metric visibility (GPU, RAPL, etc).*

## Documentation
For full documentation, please visit the [GitHub Repository](https://github.com/ESNODE/ESNODE-Core).
