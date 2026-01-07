ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB

# ESNODE-Core Quickstart

ESNODE-Core is a single, vendor-neutral node agent that exposes **CPU, memory, disk, network, and GPU** metrics for any Linux server or VPS. It integrates with **Prometheus, Grafana, and OTEL-based systems** via standard `/metrics` endpoints. Tested packaging targets for AI infra: Ubuntu (primary), RHEL/Rocky/Alma, NVIDIA DGX OS (Ubuntu-based), SLES, and Debian; Windows zip is provided for hybrid labs.

GPU/NVML requirements:
- NVIDIA drivers + NVML available on host.
- ECC, NVLink, PCIe, power/energy are best-effort per device; if unsupported, metrics stay absent.

Power requirements:
- CPU package power/energy from RAPL where exposed; node power from hwmon/BMC/IPMI if present; GPU power/energy from NVML.

> **Tagline:** ESNODE — a GPU-aware node_exporter for the AI era. One binary, all metrics.
>
> **Control plane note:** Agents can run standalone (full local TUI/CLI) or attach to an ESNODE-Pulse for centralized control. Metrics (`/metrics`, OTLP, logs) stay enabled in both modes.
>
> **Operator heads-up:** The local TSDB defaults to a user-writable XDG path (e.g. `~/.local/share/esnode/tsdb`) so non-root runs succeed; set `local_tsdb_path` to `/var/lib/esnode/tsdb` if you prefer a system path. Orchestrator control APIs are loopback-only by default; set `orchestrator.allow_public=true` **and** `orchestrator.token` to expose them safely.

## Installation options (choose your path)
- Packages: `.deb` and `.rpm` in `public/distribution/releases/linux-amd64/`
- Tarball: `esnode-core-0.1.0-linux-amd64.tar.gz` (direct binary)
- Docker: `docker build -t <repo>/esnode-core:0.1.0 -f Dockerfile .`
- Docker Compose: `docker-compose up -d` (uses repo Dockerfile; set `ESNODE_IMAGE` to override tag)
- Kubernetes:
  - Plain manifests: `deploy/k8s/*.yaml` (ConfigMap, DaemonSet, Service)
  - Helm chart: `deploy/helm/esnode-core/` (`helm upgrade --install ...`)
  - Terraform module wrapping Helm: `deploy/terraform/esnode-core/`

Pick the method that fits your deployment and flip the same config knobs (`esnode.toml` / Helm values / Terraform inputs).

---

## 1. Prerequisites

- **OS:** Linux (Ubuntu / Debian / RHEL / CentOS / Rocky, etc.)
- **Architecture:** x86_64 (AMD64)  
  > ARM64 support can be added later.
- **GPU (optional):**
  - NVIDIA GPU with driver + NVML available for GPU metrics.
  - If no GPU is present, ESNODE-Core still works (GPU metrics are simply absent).

---

## 2. Basic Installation (Standalone Binary)

### 2.1 Download the binary

```bash
# Example; adjust version and URL when you publish releases
VERSION=v0.1.0
curl -L -o esnode-core "https://github.com/your-org/esnode-core/releases/download/${VERSION}/esnode-core-linux-amd64"
chmod +x esnode-core
```

Move it somewhere on your `$PATH` (optional but recommended):

```bash
sudo mv esnode-core /usr/local/bin/esnode-core
```

### 2.2 Run ESNODE-Core with default settings

```bash
esnode-core
```

**Defaults:**

* Listens on: `0.0.0.0:9100`
* Scrape interval: `5` seconds
* Collectors enabled: CPU, memory, disk, network, GPU

### 2.3 Check that it’s running

In a second terminal:

```bash
curl http://localhost:9100/healthz
# Expect: HTTP 200 OK

# JSON status snapshot (load/power/temps/GPU/errors)
curl http://localhost:9100/status
# Versioned alias
curl http://localhost:9100/v1/status
# SSE stream (keep open)
curl http://localhost:9100/events
```

Fetch metrics:

```bash
curl http://localhost:9100/metrics | head
```

You should see `esnode_...` metrics in Prometheus text format.

---

## 3. Configuration

ESNODE-Core reads configuration from:

1. **CLI flags** (highest precedence)
2. **Environment variables**
3. **Config file** (`esnode.toml`)
4. **Built-in defaults**

### 3.1 Example `esnode.toml`

Create `/etc/esnode/esnode.toml` (or run from working directory):

```toml
listen_address = "0.0.0.0:9100"
scrape_interval_seconds = 5
enable_cpu = true
enable_memory = true
enable_disk = true
enable_network = true
enable_gpu = true
enable_gpu_amd = false          # AMD GPUs (ROCm/rsmi) - experimental
enable_power = true
enable_gpu_mig = false         # set true to scrape MIG (requires gpu-nvml-ffi build)
enable_gpu_events = false      # set true to run NVML event loop (best-effort)
k8s_mode = false               # emit compat GPU/MIG labels like nvidia.com/gpu
gpu_visible_devices = "all"    # or CSV / env NVIDIA_VISIBLE_DEVICES
mig_config_devices = ""        # or CSV / env NVIDIA_MIG_CONFIG_DEVICES
node_power_envelope_watts = 1200.0
log_level = "info"
# Optional on-agent TSDB buffer for short-term history/backfill (disabled by default)
# enable_local_tsdb = true
# local_tsdb_path = "~/.local/share/esnode/tsdb"  # defaults to XDG_DATA_HOME/esnode/tsdb if unset
# local_tsdb_retention_hours = 48
# local_tsdb_max_disk_mb = 2048
# Optional control-plane attachment (managed mode)
# managed_server = "esnode-master-01:7443"
# managed_cluster_id = "CLU-XXXX"
# managed_node_id = "gpu-node-01"
# managed_join_token = "ABC123"

[orchestrator]
enabled = false                # Master toggle for orchestration
# allow_public = false         # Control API (/orchestrator/*) only binds on loopback unless explicitly set true
# token = "CHANGEME"           # Optional bearer token required on /orchestrator/* when set
enable_zombie_reaper = true    # Kill zombie processes
enable_turbo_mode = false      # Latency optimization
enable_bin_packing = false     # Task scheduling
enable_flash_preemption = false# Priority preemption
enable_dataset_prefetch = false# Storage prefetching
enable_bandwidth_reserve = false# Network QoS
enable_fs_cleanup = false      # Disk cleanup
```

Run ESNODE-Core pointing to this config (if needed):

```bash
ESNODE_CONFIG=/etc/esnode/esnode.toml esnode-core
```

> Adjust the actual env var name once you define it in code (e.g. `ESNODE_CONFIG`).

Note on TSDB path: by default the agent now resolves `local_tsdb_path` to `$XDG_DATA_HOME/esnode/tsdb` or `~/.local/share/esnode/tsdb` to avoid root-only directories. If you prefer `/var/lib/esnode/tsdb`, set it explicitly and pre-create the directory with writable permissions for the agent user.

App collector timeout: the app/model metrics collector uses a 2s HTTP timeout to avoid blocking other collectors; slow/hung endpoints will be skipped for that interval and logged once.

CLI note: `esnode-core status/metrics` uses a lightweight HTTP client with a 2s connect/read timeout to avoid hanging when the agent endpoint is slow.

### 3.2 Common CLI flags (suggested)

> The exact flag names may differ depending on your implementation. Example:

```bash
esnode-core \
  --listen-address "0.0.0.0:9100" \
  --scrape-interval 5s \
  --enable-gpu true \
  --enable-gpu-amd false \
  --enable-gpu-mig false \
  --enable-gpu-events false \
  --k8s-mode false \
  --gpu-visible-devices "all" \
  --mig-config-devices "" \
  --log-level info \
  --enable-local-tsdb true \
  --local-tsdb-path "/var/lib/esnode/tsdb" \
  --local-tsdb-retention-hours 48 \
  --local-tsdb-retention-hours 48 \
  --local-tsdb-max-disk-mb 2048 \
  --enable-orchestrator false

Local TSDB:
- 2h on-disk blocks (`samples.jsonl` + `index.json`) with label hashes and per-metric counts.
- Periodic flush (30s) and flush-on-shutdown; retention + disk budget pruning.
- Export for backfill: `GET /tsdb/export?from=...&to=...&metrics=esnode_*` returns newline Prom-compatible samples.
  Storage is JSON Lines per time block under `local_tsdb_path`.

GPU/MIG visibility notes:
- MIG metrics only emit when compiled with `gpu-nvml-ffi` and `enable_gpu_mig = true`. Without both, MIG series stay at zero.
- Visibility filters honor `gpu_visible_devices`/`NVIDIA_VISIBLE_DEVICES`; MIG scraping additionally honors `mig_config_devices`/`NVIDIA_MIG_CONFIG_DEVICES`.
- `k8s_mode = true` publishes compatibility labels (`nvidia.com/gpu`, `nvidia.com/mig-<profile>`) in addition to UUID/index labels.
- `enable_gpu_events = true` starts a best-effort NVML event loop (short timeout) for XID/ECC/clock/power events; not guaranteed to capture every burst.

Degradation & status surfaces:
- The `/status` payload and TUI surfaces now include disk/network/swap degradation flags and an aggregate `degradation_score`. GPU throttle/ECC flags are also exposed via metrics.
- Orchestrator screen in the TUI shows whether loopback-only is enforced and whether a bearer token is required.

Developer tip:
- `cargo test --workspace` includes a ratatui-backed render smoke test for the TUI; no PTY needed to validate layout rendering.

## Docker & Kubernetes builds

### Build a container image (linux/amd64)
```bash
docker build -t myregistry/esnode-core:0.1.0 -f Dockerfile .
# Image pulls binary from public/distribution/releases/linux-amd64/esnode-core-0.1.0-linux-amd64.tar.gz
```

### Kubernetes manifests (DaemonSet)
Manifests live in `deploy/k8s/`:
- `esnode-configmap.yaml` – default `esnode.toml` (loopback-only orchestrator, TSDB at `/var/lib/esnode/tsdb`, collectors on).
- `esnode-daemonset.yaml` – hostNetwork/hostPID, privileged for NVML access, mounts `/dev` and TSDB hostPath, adds liveness/readiness probes.
- `esnode-service.yaml` – headless Service for `/metrics` scraping on port 9100.

Validate/apply (requires cluster access):
```bash
kubectl apply --dry-run=client -f deploy/k8s/esnode-configmap.yaml
kubectl apply --dry-run=client -f deploy/k8s/esnode-service.yaml
kubectl apply --dry-run=client -f deploy/k8s/esnode-daemonset.yaml
kubectl apply -f deploy/k8s/
```

Notes:
- Set `image:` to your registry/tag; provide matching tarball per arch if building multi-arch images.
- Keep `orchestrator.allow_public=false` unless intentionally exposing control APIs; set `orchestrator.token` when enabling public exposure.
- Adjust `local_tsdb_path` to match your volume and permissions; defaults to `/var/lib/esnode/tsdb` in the manifest.

## Dashboards & alerts
- Grafana dashboard: `docs/dashboards/grafana-esnode-core.json`
- Prometheus alerts: `docs/dashboards/alerts.yaml` (includes disk/network/gpu degradation and aggregate score)
```

Common control-plane commands:
```bash
# Attach to ESNODE-Pulse (persist server + IDs, disables local tuning)
esnode-core server connect --address esnode-master-01:7443 --token ABC123
# Disconnect (return to standalone/local control)
esnode-core server disconnect
# Show connection state
esnode-core server status
```

The metrics plane stays active in all states; `/metrics` keeps exporting Prometheus text unless you explicitly disable it in config.

### 3.3 ESNODE-Pulse + TSDB flags

> ESNODE-Pulse is the licensed controller distributed separately; these flags are provided for operators who have access to that binary.

```bash
esnode-pulse \
  --listen 0.0.0.0:9200 \
  --agent http://node1:9100 \
  --agent http://node2:9100 \
  --tsdb-backend opentsdb \
  --tsdb-url http://tsdb:4242 \
  --tsdb-auth "Bearer XXX" \
  --tsdb-write-enabled true \
  --tsdb-read-enabled true \
  --tsdb-backfill-enabled true \
  --storage-path /var/lib/esnode-pulse
```

- `tsdb-backend`: `opentsdb` (live), `remote_write`/`victoriametrics`/`timescale` (stubs ready for future).
- Write path: server polls `/metrics` and forwards to TSDB; `esnode_server_tsdb_write_errors` tracks failures.
- Read path: `/api/metrics/history?agent_id=...&metric=...&from=...&to=...` proxies to the backend (Prom/Grafana friendly JSON).
- Backfill: when enabled, gaps trigger `GET /tsdb/export` on agents and stream into the backend; cursors persist in `storage_path/last_seen.json`.

---

## 4. Running as a systemd Service

### 4.1 Install binary and config

```bash
sudo mkdir -p /etc/esnode
sudo cp esnode-core /usr/local/bin/esnode-core
sudo chmod +x /usr/local/bin/esnode-core

sudo tee /etc/esnode/esnode.toml >/dev/null << 'EOF'
listen_address = "0.0.0.0:9100"
scrape_interval_seconds = 5
enable_cpu = true
enable_memory = true
enable_disk = true
enable_network = true
enable_gpu = true
log_level = "info"
EOF
```

### 4.2 Create systemd unit file

`/etc/systemd/system/esnode-core.service`:

```ini
[Unit]
Description=ESNODE Agent - GPU-aware host metrics
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/esnode-core
Environment=ESNODE_CONFIG=/etc/esnode/esnode.toml
Restart=on-failure
RestartSec=5

# Run as non-root (recommended), adjust user/group as needed
User=esnode
Group=esnode

[Install]
WantedBy=multi-user.target
```

Create the `esnode` user/group if you use it:

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin esnode || true
sudo chown -R esnode:esnode /etc/esnode
```

### 4.3 Enable and start

```bash
sudo systemctl daemon-reload
sudo systemctl enable esnode-core
sudo systemctl start esnode-core
sudo systemctl status esnode-core
```

Check metrics:

```bash
curl http://localhost:9100/metrics | head
```

Managed vs standalone:
- Standalone: run `esnode-core cli` to open the AS/400-style console and toggle metric sets.
- Managed (after `server connect` to a licensed ESNODE-Pulse controller): `esnode-core cli` shows a read-only “Managed by ESNODE-Pulse” screen; tuning must be done on the server via `esnode-pulse cli` or server CLI commands. Metrics remain available at `/metrics`.

---

## 5. Running with Docker

### 5.1 Simple Docker run (no GPU)

```bash
docker run --rm \
  -p 9100:9100 \
  ghcr.io/your-org/esnode-core:v0.1.0
```

### 5.2 Docker with NVIDIA GPU (example)

If you’re using NVIDIA Container Toolkit:

```bash
docker run --rm \
  --gpus all \
  --ipc=host \
  -p 9100:9100 \
  ghcr.io/your-org/esnode-core:v0.1.0
```

> You may need to pass additional env vars or mount `/usr/lib/x86_64-linux-gnu/` etc., depending on how NVML is accessed in your image.

---

## 6. Running as a Kubernetes DaemonSet

ESNODE-Core can run on every node in your cluster using a DaemonSet.

### 6.1 Example DaemonSet (simplified)

```yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: esnode-core
  namespace: monitoring
spec:
  selector:
    matchLabels:
      app: esnode-core
  template:
    metadata:
      labels:
        app: esnode-core
    spec:
      hostNetwork: true
      dnsPolicy: ClusterFirstWithHostNet
      containers:
        - name: esnode-core
          image: ghcr.io/your-org/esnode-core:v0.1.0
          imagePullPolicy: IfNotPresent
          ports:
            - name: metrics
              containerPort: 9100
              hostPort: 9100
          env:
            - name: ESNODE_CONFIG
              value: /etc/esnode/esnode.toml
          volumeMounts:
            - name: esnode-config
              mountPath: /etc/esnode
      volumes:
        - name: esnode-config
          configMap:
            name: esnode-config
```

Create the namespace, ConfigMap, and DaemonSet:

```bash
kubectl create namespace monitoring

kubectl create configmap esnode-config \
  --namespace monitoring \
  --from-literal=esnode.toml='listen_address = "0.0.0.0:9100"
scrape_interval_seconds = 5
enable_cpu = true
enable_memory = true
enable_disk = true
enable_network = true
enable_gpu = true
log_level = "info"'

kubectl apply -f esnode-daemonset.yaml
```

---

## 7. Integrating with Prometheus

Add a scrape job in `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'esnode'
    static_configs:
      - targets:
          - 'server1:9100'
          - 'server2:9100'
          - 'server3:9100'
```

Or if running inside Kubernetes with the DaemonSet:

```yaml
- job_name: 'esnode'
  kubernetes_sd_configs:
    - role: node
  relabel_configs:
    - source_labels: [__address__]
      regex: '(.*):\\d+'
      replacement: '${1}:9100'
      target_label: __address__
```

Reload Prometheus and verify that the `esnode` job is being scraped.

---

## ESNODE-Pulse (aggregator stub)

The ESNODE-Pulse controller is licensed and maintained separately. Use the registered distribution or private repository for controller builds; it is not part of this standalone ESNODE-Core project.

---

## 8. Verifying GPU Metrics

On a node with NVIDIA GPUs and NVML available, check for GPU metrics:

```bash
curl http://localhost:9100/metrics | grep esnode_gpu | head
```

You should see metrics like:

```text
esnode_gpu_utilization_percent{gpu="0"} 25
esnode_gpu_memory_used_bytes{gpu="0"} 123456789
...
```

If there are no GPUs or NVML is missing, these metrics simply won’t appear. Check logs for messages about GPU collector initialization.

---

## 9. Next Steps

* Import the sample Grafana dashboards from `docs/monitoring-examples.md` (once available).
* Roll out ESNODE-Core to more nodes.
* Start tracking:

  * GPU utilization vs load
  * Node hotspots
  * Capacity planning and right-sizing decisions

ESNODE-Core is designed to be a **small, boring, reliable building block** for AI infrastructure observability.
