ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB

# ESNODE Metrics Reference v1.0

All ESNODE-Core metrics are exposed at `/metrics` in Prometheus format and use the `esnode_` prefix.

---

## 1. CPU Metrics

| Metric name                          | Type    | Labels    | Description                                                |
|--------------------------------------|---------|-----------|------------------------------------------------------------|
| `esnode_cpu_load_avg_1m`             | Gauge   | *(none)*  | 1-minute load average.                                    |
| `esnode_cpu_load_avg_5m`             | Gauge   | *(none)*  | 5-minute load average.                                    |
| `esnode_cpu_load_avg_15m`            | Gauge   | *(none)*  | 15-minute load average.                                   |
| `esnode_cpu_usage_percent`           | Gauge   | `core`    | Instantaneous CPU usage per logical core.                 |
| `esnode_cpu_time_seconds_total`      | Counter | `state`   | CPU time by state (user/system/idle/iowait/irq/softirq/steal). |
| `esnode_cpu_interrupts_total`        | Counter | *(none)*  | Interrupts since boot.                                    |
| `esnode_cpu_context_switches_total`  | Counter | *(none)*  | Context switches since boot.                              |

---

## 2. NUMA Metrics (AI/HPC)

| Metric name                           | Type    | Labels      | Description                                                  |
|---------------------------------------|---------|-------------|--------------------------------------------------------------|
| `esnode_numa_memory_total_bytes`      | Gauge   | `node`      | Total memory for the NUMA node.                              |
| `esnode_numa_memory_free_bytes`       | Gauge   | `node`      | Available memory per NUMA node.                              |
| `esnode_numa_memory_used_bytes`       | Gauge   | `node`      | Used memory per NUMA node.                                   |
| `esnode_numa_cpu_usage_percent`       | Gauge   | `node`      | CPU usage for cores belonging to a NUMA node.                |
| `esnode_numa_page_faults_total`       | Counter | `node`      | Page faults per NUMA domain.                                 |
| `esnode_numa_distance`                | Gauge   | `node`,`to` | NUMA distance matrix (hardware affinity, read-only).         |

---

## 3. Memory Metrics

| Metric name                          | Type    | Labels   | Description                           |
|--------------------------------------|---------|----------|---------------------------------------|
| `esnode_memory_total_bytes`          | Gauge   | *(none)* | Total RAM.                            |
| `esnode_memory_used_bytes`           | Gauge   | *(none)* | Used RAM.                             |
| `esnode_memory_free_bytes`           | Gauge   | *(none)* | Free RAM.                             |
| `esnode_memory_available_bytes`      | Gauge   | *(none)* | Available RAM.                        |
| `esnode_memory_buffers_bytes`        | Gauge   | *(none)* | Buffers.                              |
| `esnode_memory_cached_bytes`         | Gauge   | *(none)* | Cache.                                |
| `esnode_swap_total_bytes`            | Gauge   | *(none)* | Total swap.                           |
| `esnode_swap_used_bytes`             | Gauge   | *(none)* | Used swap.                            |
| `esnode_swap_free_bytes`             | Gauge   | *(none)* | Free swap.                            |
| `esnode_page_in_bytes_total`         | Counter | *(none)* | Page-in bytes.                        |
| `esnode_page_out_bytes_total`        | Counter | *(none)* | Page-out bytes.                       |

---

## 4. Disk Metrics

| Metric name                          | Type    | Labels    | Description                                   |
|--------------------------------------|---------|-----------|-----------------------------------------------|
| `esnode_disk_total_bytes`            | Gauge   | `mount`   | Total storage in bytes for the mount point.   |
| `esnode_disk_used_bytes`             | Gauge   | `mount`   | Used bytes for the mount point.               |
| `esnode_disk_free_bytes`             | Gauge   | `mount`   | Free bytes for the mount point.               |
| `esnode_disk_read_bytes_total`       | Counter | `device`  | Bytes read from the block device since boot.  |
| `esnode_disk_written_bytes_total`    | Counter | `device`  | Bytes written to the block device since boot. |
| `esnode_disk_read_ops_total`         | Counter | `device`  | Read operations since boot.                   |
| `esnode_disk_write_ops_total`        | Counter | `device`  | Write operations since boot.                  |
| `esnode_disk_io_time_ms_total`       | Counter | `device`  | I/O time (milliseconds) since boot.           |
| `esnode_disk_io_avg_latency_ms`      | Gauge   | `device`  | Avg I/O latency (ms/op) per scrape window.    |
| `esnode_disk_degradation_latency`    | Gauge   | `device`  | 1 when latency exceeds threshold in scrape.   |
| `esnode_disk_degradation_busy`       | Gauge   | `device`  | 1 when disk is heavily utilized in scrape.    |

---

## 5. Network Metrics

| Metric name                          | Type    | Labels   | Description                                         |
|--------------------------------------|---------|----------|-----------------------------------------------------|
| `esnode_network_rx_bytes_total`      | Counter | `iface`  | Total bytes received on the interface.              |
| `esnode_network_tx_bytes_total`      | Counter | `iface`  | Total bytes transmitted on the interface.           |
| `esnode_network_rx_errors_total`     | Counter | `iface`  | Receive errors on the interface.                    |
| `esnode_network_rx_packets_total`    | Counter | `iface`  | Packets received on the interface.                  |
| `esnode_network_tx_packets_total`    | Counter | `iface`  | Packets transmitted on the interface.               |
| `esnode_network_rx_dropped_total`    | Counter | `iface`  | Dropped receive packets on the interface.           |
| `esnode_network_tx_dropped_total`    | Counter | `iface`  | Dropped transmit packets on the interface.          |
| `esnode_network_degradation_drops`   | Gauge   | `iface`  | 1 when drops occurred in the scrape window.         |
| `esnode_network_tcp_retrans_total`   | Counter | *(none)* | TCP retransmissions (TCPSegRetrans).                |
| `esnode_network_degradation_retrans` | Gauge   | *(none)* | 1 when retransmissions occurred in scrape window.   |

---

## 6. AI-GPU Metrics – Device Level

| Metric name                                  | Type    | Labels        | Description                                             |
|----------------------------------------------|---------|---------------|---------------------------------------------------------|
| `esnode_gpu_utilization_percent`             | Gauge   | `gpu`         | GPU utilization.                                        |
| `esnode_gpu_memory_total_bytes`              | Gauge   | `gpu`         | Total VRAM.                                             |
| `esnode_gpu_memory_used_bytes`               | Gauge   | `gpu`         | Used VRAM.                                              |
| `esnode_gpu_temperature_celsius`             | Gauge   | `gpu`         | GPU temperature.                                        |
| `esnode_gpu_power_watts`                     | Gauge   | `gpu`         | Power draw.                                             |
| `esnode_gpu_energy_joules_total`             | Counter | `gpu`         | Total GPU energy consumed.                              |
| `esnode_gpu_ecc_errors_total`                | Counter | `gpu`,`type`  | ECC errors (volatile, aggregate).                       |
| `esnode_gpu_fan_speed_percent`               | Gauge   | `gpu`         | Fan speed percent.                                      |
| `esnode_gpu_clock_sm_mhz`                    | Gauge   | `gpu`         | SM clock.                                               |
| `esnode_gpu_clock_mem_mhz`                   | Gauge   | `gpu`         | Memory clock.                                           |
| `esnode_gpu_clock_graphics_mhz`              | Gauge   | `gpu`         | Graphics clock.                                         |
| `esnode_gpu_power_limit_watts`               | Gauge   | `gpu`         | Power cap limit.                                        |
| `esnode_gpu_throttle_reason`                 | Gauge   | `gpu`,`reason`| Throttle reason active = 1.                             |
| `esnode_gpu_degradation_throttle`            | Gauge   | `gpu`         | 1 when thermal or power throttle is active.             |
| `esnode_gpu_degradation_ecc`                 | Gauge   | `gpu`         | 1 when ECC errors observed during the scrape.           |
| `esnode_gpu_pcie_tx_bytes_total`             | Counter | `gpu`         | PCIe TX traffic.                                        |
| `esnode_gpu_pcie_rx_bytes_total`             | Counter | `gpu`         | PCIe RX traffic.                                        |
| `esnode_gpu_nvlink_rx_bytes_total`           | Counter | `gpu`,`link`  | NVLink RX bytes.                                        |
| `esnode_gpu_nvlink_tx_bytes_total`           | Counter | `gpu`,`link`  | NVLink TX bytes.                                        |
| `esnode_gpu_nvlink_errors_total`             | Counter | `gpu`,`link`  | NVLink error counters.                                  |
| `esnode_gpu_pcie_replay_errors_total`        | Counter | `gpu`         | PCIe replay/correctable errors.                         |
| `esnode_gpu_pcie_uncorrectable_errors_total` | Counter | `gpu`         | PCIe uncorrectable errors.                              |

---

## 7. MIG Metrics – Per Instance

| Metric name                          | Type    | Labels               | Description                                 |
|--------------------------------------|---------|----------------------|---------------------------------------------|
| `esnode_mig_utilization_percent`     | Gauge   | `gpu`,`instance`     | MIG instance utilization.                   |
| `esnode_mig_memory_used_bytes`       | Gauge   | `gpu`,`instance`     | Used memory per MIG slice.                  |
| `esnode_mig_memory_total_bytes`      | Gauge   | `gpu`,`instance`     | Total memory per MIG slice.                 |
| `esnode_mig_sm_count`                | Gauge   | `gpu`,`instance`     | SM count assigned to the MIG slice.         |
| `esnode_mig_energy_joules_total`     | Counter | `gpu`,`instance`     | Estimated slice-based energy usage.         |

---

## 8. PCIe / NVLink / Fabric Metrics

| Metric name                          | Type    | Labels          | Description                                    |
|--------------------------------------|---------|-----------------|------------------------------------------------|
| `esnode_pcie_bandwidth_percent`      | Gauge   | `gpu`           | PCIe bandwidth saturation.                     |
| `esnode_pcie_link_width`             | Gauge   | `gpu`           | Current PCIe link width (x16/x8/…).            |
| `esnode_pcie_link_gen`               | Gauge   | `gpu`           | PCIe link generation (3/4/5).                  |
| `esnode_nvswitch_errors_total`       | Counter | `switch`,`port` | NVSwitch error counters.                       |
| `esnode_fabric_latency_microseconds` | Gauge   | `gpu`,`peer`    | GPU-to-GPU latency (best effort).              |

---

## 9. Node & CPU Power / Energy Metrics

| Metric name                                  | Type    | Labels    | Description                             |
|----------------------------------------------|---------|-----------|-----------------------------------------|
| `esnode_cpu_package_power_watts`             | Gauge   | `package` | CPU socket/package power.               |
| `esnode_cpu_package_energy_joules_total`     | Counter | `package` | CPU energy consumed.                    |
| `esnode_cpu_core_power_watts`                | Gauge   | `core`    | CPU core power (if available).          |
| `esnode_node_power_watts`                    | Gauge   | *(none)*  | Total node power.                       |
| `esnode_node_energy_joules_total`            | Counter | *(none)*  | Integrated node energy.                 |
| `esnode_pdu_outlet_power_watts`              | Gauge   | `outlet`  | Data-center PDU outlet power.           |

---

## 10. AI-Efficiency Derived Metrics

| Metric name                             | Type  | Labels  | Description                              |
|-----------------------------------------|-------|---------|------------------------------------------|
| `esnode_ai_tokens_per_joule`            | Gauge | `agent` | Instant tokens per joule efficiency (agent label = managed node ID or `local`). |
| `esnode_ai_tokens_per_watt`             | Gauge | `agent` | Instant tokens per watt (agent label = managed node ID or `local`).             |
| `esnode_ai_cost_per_million_tokens_usd` | Gauge | `agent` | Cost per million tokens (power-based).   |
| `esnode_ai_carbon_grams_per_token`      | Gauge | `agent` | Estimated carbon per token.              |
| `esnode_degradation_score`              | Gauge | *(none)*| Sum of degradation flags (disk/net/swap).|

*Requires model-serving layer metrics such as `model_tokens_total` and `model_requests_total` to compute.*

---

## 11. Agent Self-Metrics

| Metric name                           | Type    | Labels          | Description                                    |
|---------------------------------------|---------|-----------------|------------------------------------------------|
| `esnode_agent_scrape_duration_seconds`| Gauge   | `collector`     | Scrape time per collector.                     |
| `esnode_agent_errors_total`           | Counter | `collector`     | Collector errors.                              |
| `esnode_agent_running`                | Gauge   | *(none)*        | Always 1 while running.                        |
| `esnode_agent_start_time_seconds`     | Gauge   | *(none)*        | Start time.                                    |
| `esnode_agent_build_info`             | Gauge   | `version`,`commit` | Build metadata (value = 1).                |
| `esnode_agent_config_reloads_total`   | Counter | *(none)*        | Number of config reloads.                      |
| `esnode_agent_collector_disabled`     | Gauge   | `collector`     | 1 = collector disabled.                        |

---

### Metric Type Overview

- **Gauge**: Value that can go up or down (utilization %, temperature, bytes in use).
- **Counter**: Monotonic value (bytes total, errors, energy).
