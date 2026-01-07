# Feature: Integrate ESNODE-Core for Full-Stack AI Infrastructure Observability

## 🚀 Motivation

**Opik** provides world-class observability for the "Logical" layer of AI applications (Prompt Engineering, Traces, Spans, Evaluations). However, AI applications are uniquely dependent on the "Physical" layer—specifically the GPU compute, memory bandwidth, and power availability of the underlying infrastructure.

**The Problem:** A latency spike in an LLM chain could be caused by an inefficient prompt (Logical) *or* by a GPU thermal throttle / memory saturation (Physical). Currently, Opik users can only debug the former.

**The Solution:** This PR introduces **ESNODE-Core**, a high-performance Rust-based agent, effectively bridging the gap between Model Performance and Machine Health. By merging this integration, Opik becomes a **Full-Stack AI Observability Platform**, capable of correlating a failed trace with a hardware anomaly.

## ✨ Key Changes

### 1. New Service: `apps/esnode-core`
- Added the source code for **ESNODE-Core**, a lightweight, privileged agent written in Rust.
- **Capabilities**:
    - **NVIDIA GPU Monitoring**: Utilization, Memory, Temperature, Power, Fan Speed.
    - **Host Metrics**: CPU Load, RAM Usage, Disk I/O, Network Traffic.
    - **Safety**: Runs as a daemon; gracefully degrades (disables GPU collector) on non-NVIDIA hosts (e.g., MacBooks) without crashing.

### 2. Infrastructure Integration
- **`deployment/docker-compose/docker-compose.yaml`**:
    - Added `esnode-core` service (Profile: `opik`).
    - Added `prometheus` and `grafana` services (Profile: `opik-otel`) to provide immediate, out-of-the-box visualization of these new metrics.
- **`opik.sh`**:
    - Updated to include `opik-esnode-core-1` in lifecycle management.

### 3. Observability Pipeline
- **`otel-collector-config.yaml`**:
    - Configured to scrape `esnode-core:9100`.
    - This ensures infrastructure metrics flow into Opik's existing OpenTelemetry pipeline, paving the way for future correlation features (e.g., "Show me GPU Load *during* this specific Trace").

## 🛠️ Testing & Verification

We have verified this integration locally on both macOS (non-GPU) and Linux (GPU) environments.

**Steps to Verify:**
1.  **Start the Stack**:
    ```bash
    ./opik.sh
    docker compose -f deployment/docker-compose/docker-compose.yaml --profile opik-otel up -d
    ```
2.  **Verify Agent Health**:
    ```bash
    docker logs opik-esnode-core-1
    # Expect: "Starting esnode-core..." and "Metrics listener active on 0.0.0.0:9100"
    ```
3.  **Verify Visualization**:
    - Navigate to `http://localhost:3000` (Grafana).
    - Login: `admin` / `admin`.
    - Explore metrics like `esnode_node_power_watts` or `esnode_cpu_usage_percent`.

## ✅ Checklist

- [x] Code compiles and runs locally (`./opik.sh`).
- [x] Documentation updated (`README.md` and `apps/esnode-core/README.md`).
- [x] No breaking changes to existing Opik services (Backend/Frontend).
- [x] Agent follows security best practices (Read-only host mounts).

---

**To the Opik Team:** We believe this integration significantly enhances the value proposition of Opik for Self-Hosted and Enterprise users managing their own Inference infrastructure. We look forward to your feedback!
