# ESNODE-Core for Opik

ESNODE-Core is an advanced infrastructure monitoring agent designed to provide deep observability into the underlying hardware powering your AI applications. By integrating ESNODE-Core into Opik, we extend observability from the logical layer (LLM traces, spans) to the physical layer (GPU usage, power consumption, memory metrics).

## 🚀 Features

*   **GPU Monitoring**: Real-time tracking of GPU utilization, memory, temperature, and power for NVIDIA GPUs.
*   **System Metrics**: Comprehensive CPU, Memory, Disk I/O, and Network statistics.
*   **Power Awareness**: Detailed tracking of power usage (Watts) and efficiency (Tokens/Watt if combined with application metrics).
*   **Opik Integration**: Seamlessly integrates with Opik's OpenTelemetry pipeline.

## 🛠️ Usage with Opik

ESNODE-Core is included as an optional component in the Opik Docker Compose setup.

### Prerequisites

*   **Linux Host**: For full GPU monitoring, a Linux host with NVIDIA drivers is required.
*   **Docker & Docker Compose**: Standard Opik requirements.

### Quick Start

To start Opik with Infrastructure Monitoring enabled (ESNODE-Core + Prometheus + Grafana):

```bash
# Start the standard Opik stack
./opik.sh

# Start the Observability stack (OTEL Collector, Prometheus, Grafana)
docker compose -f deployment/docker-compose/docker-compose.yaml --profile opik-otel up -d
```

### 📊 Visualizing Metrics

Once running, you can access the infrastructure dashboard via Grafana:

1.  Open **[http://localhost:3000](http://localhost:3000)** in your browser.
2.  Login with default credentials:
    *   **User**: `admin`
    *   **Password**: `admin`
3.  Navigate to **Explore** or create a new Dashboard.
4.  Select `Prometheus` as the datasource.
5.  Query for metrics starting with `esnode_` (e.g., `esnode_gpu_utilization_percent`, `esnode_node_power_watts`).

## 🔧 Configuration

The agent is configured via environment variables in `docker-compose.yaml`. Key variables include:

*   `ESNODE_HOST_ROOT`: Path to the host's root directory (mounted read-only) for system-level metric collection.

## 🏗️ Architecture

ESNODE-Core runs as a privileged container to access hardware counters. It exposes Prometheus-format metrics on port `9100`. The Opik OpenTelemetry Collector scrapes this endpoint and forwards the data to the configured backend (Prometheus in the `opik-otel` profile).
