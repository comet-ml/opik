### **PR Request Notes: ESNODE-Core Integration**

**Title:** Feature: Integrate ESNODE-Core for Infrastructure Observability

**Description:**
This PR introduces **ESNODE-Core**, a high-performance Rust-based agent for infrastructure monitoring, into the Opik ecosystem. This integration bridges the gap between application-level observability (traces, spans) and physical infrastructure metrics (GPU health, power usage, system resources), providing a holistic view of AI application performance.

**Key Changes:**

1.  **New Application Service**:
    *   Added `apps/esnode-core` containing the source code and `Dockerfile` for the monitoring agent.
    *   Implemented a multi-stage `Dockerfile` (Rust builder + Slim Debian runtime) for efficient and secure images.

2.  **Docker Compose Integration**:
    *   Updated `deployment/docker-compose/docker-compose.yaml` to include the `esnode-core` service.
    *   The service runs in privileged mode (required for hardware counters) and mounts host system directories (`/proc`, `/sys`) read-only.
    *   Added `prometheus` and `grafana` services (under the `opik-otel` profile) to provide valid out-of-the-box visualization for physical metrics.

3.  **Observability Pipeline**:
    *   Updated `otel-collector-config.yaml` to scrape metrics from `esnode-core:9100`.
    *   Configured Prometheus to scrape `esnode-core`.
    *   Pre-provisioned Grafana with a Prometheus datasource for immediate dashboarding.

4.  **Management Script**:
    *   Updated `opik.sh` to include `opik-esnode-core-1` in the `OPIK_CONTAINERS` list, ensuring it starts and is verified alongside other core services.

**Value Proposition:**
*   **GPU Visibility**: Direct insight into GPU utilization, memory pressure, and thermal throttling, which are critical for debugging latency in LLM inference.
*   **Power Awareness**: Real-time power consumption metrics allow for tracking the energy efficiency and carbon footprint of AI workloads (e.g., Tokens/Watt).
*   **Unified Stack**: Users can now spin up a complete AI engineering platform—monitoring both the *model's mind* (Opik) and the *machine's body* (ESNODE)—with a single command.

**Testing Instructions:**
1.  Run `./opik.sh` to start the core stack. Verify `opik-esnode-core-1` is healthy.
2.  Run `docker compose -f deployment/docker-compose/docker-compose.yaml --profile opik-otel up -d` to start the observability stack.
3.  Visit `http://localhost:3000` (Grafana), login (`admin`/`admin`), and verify Prometheus is connected.
4.  Explore metrics starting with `esnode_` to see real-time system data.

**Notes for Reviewers:**
*   The `esnode-core` service is configured to fail gracefully. If GPU drivers are missing (e.g., on Mac or non-GPU nodes), it simply disables the GPU collector and continues monitoring CPU/RAM without crashing.
*   The `prometheus` and `grafana` services are optional (behind the `opik-otel` profile) to keep the default footprint light.
