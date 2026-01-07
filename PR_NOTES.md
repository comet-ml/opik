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

4.  **Frontend Integration ("Single Pane of Glass")**:
    *   Added a new **Infrastructure** page to the Opik sidebar.
    *   This page embeds the Grafana dashboard directly within the application, providing a seamless user experience.
    *   Users can monitor model metrics (traces) and machine metrics (GPUs) without leaving the Opik window.

5.  **Management Script**:
    *   Updated `opik.sh` to include `opik-esnode-core-1` in the `OPIK_CONTAINERS` list, ensuring it starts and is verified alongside other core services.

**Value Proposition:**
*   **Unified Experience**: No need to open separate tabs or remember port numbers. Infrastructure monitoring is just one click away inside the Opik app.
*   **GPU Visibility**: Direct insight into GPU utilization, memory pressure, and thermal throttling, which are critical for debugging latency in LLM inference.
*   **Power Awareness**: Real-time power consumption metrics allow for tracking the energy efficiency and carbon footprint of AI workloads (e.g., Tokens/Watt).

**Testing Instructions:**
1.  Run `./opik.sh` to start and verify the stack.
2.  Start observability: `docker compose -f deployment/docker-compose/docker-compose.yaml --profile opik-otel up -d`.
3.  Open Opik (`http://localhost:5173`) and navigate to the new **Infrastructure** menu item.
4.  Verify the Grafana dashboard loads within the iframe.

**Notes for Reviewers:**
*   The embedded dashboard URL defaults to `http://localhost:3000` but can be configured via `VITE_GRAFANA_URL` for production deployments behind ingress.
*   The `esnode-core` service is configured to fail gracefully if no GPU is present.
