ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB

# ESNODE Monitoring Examples

This document provides:

1. Example **Prometheus scrape configs** for ESNODE-Core and LLM/model metrics.
2. A **Grafana dashboard JSON outline** with sections for:
   - Node overview (CPU, memory, disk, network)
   - GPU & power
   - Model efficiency (power vs tokens/requests)

---

## 1. Prometheus Configuration Examples

### 1.1 Scraping ESNODE-Core (static targets)

Add to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'esnode'
    scrape_interval: 5s
    static_configs:
      - targets:
          - 'server1:9100'
          - 'server2:9100'
          - 'server3:9100'
```

This assumes ESNODE-Core listens on port `9100`.

---

### 1.2 Scraping ESNODE-Core in Kubernetes (DaemonSet)

If ESNODE-Core runs as a DaemonSet (one per node) and exposes port 9100:

```yaml
scrape_configs:
  - job_name: 'esnode'
    kubernetes_sd_configs:
      - role: node
    relabel_configs:
      # Rewrite node address to port 9100
      - source_labels: [__address__]
        regex: '([^:]+)(?::\\d+)?'
        replacement: '${1}:9100'
        target_label: __address__
```

Optional: keep node labels (region/zone/etc.):

```yaml
    metric_relabel_configs:
      - source_labels: [__meta_kubernetes_node_label_topology_kubernetes_io_region]
        target_label: region
      - source_labels: [__meta_kubernetes_node_label_topology_kubernetes_io_zone]
        target_label: zone
```

---

### 1.3 Scraping Application / Model Metrics

If your LLM/AI services expose metrics at `/metrics` on port 8080:

```yaml
scrape_configs:
  - job_name: 'llm-services'
    scrape_interval: 5s
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      # Only scrape pods with label app=llm-service
      - source_labels: [__meta_kubernetes_pod_label_app]
        regex: 'llm-service'
        action: keep

      # Rewrite address to target the metrics port
      - source_labels: [__meta_kubernetes_pod_ip]
        target_label: __address__
        replacement: '${1}:8080'
```

The app should export metrics like:

```text
model_requests_total{model="llama-3-70b",namespace="llm-serving",status="ok"}
model_tokens_total{model="llama-3-70b",namespace="llm-serving"}
model_latency_seconds_bucket{model="llama-3-70b",le="0.5"} ...
```

ESNODE metrics (host-level) + these model metrics (app-level) together enable **power vs work vs quality** analysis.

---

## 2. Example PromQL Queries

### 2.1 Node Overview

**CPU usage (all cores) per node:**

```promql
avg by (instance) (
  esnode_cpu_usage_percent
)
```

**Memory utilization percent per node:**

```promql
100 *
(
  esnode_memory_used_bytes
/
  esnode_memory_total_bytes
)
```

**Disk used percent (root filesystem):**

```promql
100 *
(
  esnode_disk_used_bytes{mount="/"}
/
  esnode_disk_total_bytes{mount="/"}
)
```

**Network throughput (bytes/sec) per interface:**

```promql
rate(esnode_network_rx_bytes_total[5m])
```

---

### 2.2 GPU & Power

**GPU utilization per GPU:**

```promql
esnode_gpu_utilization_percent
```

**Average GPU power by node (last 5 minutes):**

```promql
avg_over_time(esnode_gpu_power_watts[5m])
```

**Node total power (if you expose `esnode_node_power_watts`):**

```promql
esnode_node_power_watts
```

**CPU package power (RAPL/hwmon if available):**

```promql
esnode_cpu_package_power_watts
```

**GPU throttle reasons (thermal/power):**

```promql
esnode_gpu_throttle_reason{reason="thermal"} == 1
```

**NVLink traffic (if supported):**

```promql
rate(esnode_gpu_nvlink_rx_bytes_total[5m])
```

**Node power envelope breaches (requires config):**

```promql
esnode_node_power_envelope_exceeded
```

---

### 2.3 Model Efficiency (Tokens per Watt, etc.)

Assuming:

* Node power: `esnode_node_power_watts{instance="node1"}`
* Model tokens: `model_tokens_total{model="llama-3-70b"}`

**Tokens per watt-second (approx.) per model across cluster:**

```promql
sum by (model) (rate(model_tokens_total[5m]))
/
sum(rate(esnode_node_power_watts[5m]))
```

To focus on specific workloads (e.g., namespace `llm-serving`):

```promql
sum by (model) (
  rate(model_tokens_total{namespace="llm-serving"}[5m])
)
/
sum(
  rate(esnode_node_power_watts[5m])
)
```

**Requests per watt-second per model:**

```promql
sum by (model) (
  rate(model_requests_total{status="ok"}[5m])
)
/
sum(
  rate(esnode_node_power_watts[5m])
)
```

**Cluster tokens-per-watt via esnode-pulse (per agent):**

```promql
esnode_server_tokens_per_watt
```

You can turn these into **tokens per kWh** by scaling:

```promql
# tokens per kWh ≈ (tokens/sec) / (watts) * 3600 (sec/hr) / 1000
(
  sum by (model) (rate(model_tokens_total[5m]))
/
  sum(rate(esnode_node_power_watts[5m]))
)
* 3600 / 1000
```

Models with **high tokens per kWh and good success rate** are strong candidates to **scale up**.
Models with **low tokens per kWh** or poor outcomes can be **scaled down** or optimized.

---

## 3. Grafana Dashboard JSON Outline

Below is a **minimal outline** of a Grafana dashboard definition.
You can copy this into Grafana’s “Import dashboard” (after cleaning up IDs/uids) or use it as a starting point.

> This is intentionally simplified: you’ll likely tweak panel IDs, datasource UIDs, and layout.

```json
{
  "title": "ESNODE - AI Infra & Model Efficiency",
  "uid": "esnode-ai-infra",
  "timezone": "browser",
  "schemaVersion": 39,
  "version": 1,
  "refresh": "10s",
  "panels": [
    {
      "title": "Node CPU Usage (%)",
      "type": "timeseries",
      "gridPos": { "x": 0, "y": 0, "w": 12, "h": 8 },
      "targets": [
        {
          "refId": "A",
          "expr": "avg by (instance) (esnode_cpu_usage_percent)",
          "legendFormat": "{{instance}}"
        }
      ]
    },
    {
      "title": "Node Memory Usage (%)",
      "type": "timeseries",
      "gridPos": { "x": 12, "y": 0, "w": 12, "h": 8 },
      "targets": [
        {
          "refId": "A",
          "expr": "100 * (esnode_memory_used_bytes / esnode_memory_total_bytes)",
          "legendFormat": "{{instance}}"
        }
      ]
    },
    {
      "title": "Disk Usage (%) - Root FS",
      "type": "timeseries",
      "gridPos": { "x": 0, "y": 8, "w": 12, "h": 8 },
      "targets": [
        {
          "refId": "A",
          "expr": "100 * (esnode_disk_used_bytes{mount=\"/\"} / esnode_disk_total_bytes{mount=\"/\"})",
          "legendFormat": "{{instance}}"
        }
      ]
    },
    {
      "title": "Network RX/TX (bytes/sec)",
      "type": "timeseries",
      "gridPos": { "x": 12, "y": 8, "w": 12, "h": 8 },
      "targets": [
        {
          "refId": "A",
          "expr": "sum by (instance) (rate(esnode_network_rx_bytes_total[5m]))",
          "legendFormat": "{{instance}} RX"
        },
        {
          "refId": "B",
          "expr": "sum by (instance) (rate(esnode_network_tx_bytes_total[5m]))",
          "legendFormat": "{{instance}} TX"
        }
      ]
    },

    {
      "title": "GPU Utilization (%) per GPU",
      "type": "timeseries",
      "gridPos": { "x": 0, "y": 16, "w": 12, "h": 8 },
      "targets": [
        {
          "refId": "A",
          "expr": "esnode_gpu_utilization_percent",
          "legendFormat": "{{instance}} gpu={{gpu}}"
        }
      ]
    },
    {
      "title": "GPU Power (Watts)",
      "type": "timeseries",
      "gridPos": { "x": 12, "y": 16, "w": 12, "h": 8 },
      "targets": [
        {
          "refId": "A",
          "expr": "esnode_gpu_power_watts",
          "legendFormat": "{{instance}} gpu={{gpu}}"
        }
      ]
    },

    {
      "title": "Node Power (Watts)",
      "type": "timeseries",
      "gridPos": { "x": 0, "y": 24, "w": 12, "h": 8 },
      "targets": [
        {
          "refId": "A",
          "expr": "esnode_node_power_watts",
          "legendFormat": "{{instance}}"
        }
      ]
    },

    {
      "title": "Tokens per kWh by Model",
      "type": "timeseries",
      "gridPos": { "x": 12, "y": 24, "w": 12, "h": 8 },
      "targets": [
        {
          "refId": "A",
          "expr": "((sum by (model) (rate(model_tokens_total[5m])) / sum(rate(esnode_node_power_watts[5m]))) * 3600 / 1000)",
          "legendFormat": "{{model}}"
        }
      ]
    },

    {
      "title": "Model Requests per Watt-Second (Efficiency)",
      "type": "timeseries",
      "gridPos": { "x": 0, "y": 32, "w": 24, "h": 8 },
      "targets": [
        {
          "refId": "A",
          "expr": "sum by (model) (rate(model_requests_total{status=\"ok\"}[5m])) / sum(rate(esnode_node_power_watts[5m]))",
          "legendFormat": "{{model}}"
        }
      ]
    }
  ],
  "templating": {
    "list": [
      {
        "name": "model",
        "type": "query",
        "datasource": null,
        "query": "label_values(model_tokens_total, model)",
        "refresh": 2,
        "includeAll": true,
        "multi": true
      }
    ]
  }
}
```

### Dashboard Sections (Conceptually)

* **Row 1 – Node Overview**

  * CPU usage
  * Memory usage
  * Disk usage
  * Network RX/TX

* **Row 2 – GPU & Power**

  * GPU utilization
  * GPU power per GPU
  * Node total power (if available)

* **Row 3 – Model Efficiency**

  * Tokens per kWh per model
  * Requests per watt-second per model
  * (optional) Error rate per model vs power

---

## 4. Using These Dashboards to Optimize

With ESNODE + model metrics:

* Spot **nodes** where:

  * GPU utilization is low but power draw is high → consolidation / scheduling issues.
* Spot **models** where:

  * Tokens per kWh are poor → candidates to scale down, prune, or quantize.
  * Tokens per kWh are excellent and error rate is low → candidates to scale up.
* Over time, track how:

  * Changes to model architecture, quantization, batching strategies affect **power efficiency** (tokens per watt, requests per watt, etc.).

This is how you get from **raw power metrics** → **concrete decisions** about which LLM / agent workloads to scale up or down, and how to redesign them for **higher throughput per joule**.
