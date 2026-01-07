// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use anyhow::Context;
use prometheus::{
    proto::MetricFamily, CounterVec, Encoder, Gauge, GaugeVec, IntCounter, IntCounterVec, Opts,
    Registry, TextEncoder,
};

const GPU_LABELS: &[&str] = &["uuid", "index"];
const GPU_LINK_LABELS: &[&str] = &["uuid", "index", "link"];
const MIG_LABELS: &[&str] = &["gpu_uuid", "gpu_index", "mig"];

#[derive(Clone)]
pub struct MetricsRegistry {
    registry: Registry,
    pub cpu_load_avg_1m: Gauge,
    pub cpu_load_avg_5m: Gauge,
    pub cpu_load_avg_15m: Gauge,
    pub cpu_usage_percent: GaugeVec,
    pub cpu_time_seconds_total: CounterVec,
    pub cpu_interrupts_total: IntCounter,
    pub cpu_context_switches_total: IntCounter,
    pub numa_memory_total_bytes: GaugeVec,
    pub numa_memory_free_bytes: GaugeVec,
    pub numa_memory_used_bytes: GaugeVec,
    pub numa_cpu_usage_percent: GaugeVec,
    pub numa_page_faults_total: IntCounterVec,
    pub numa_distance: GaugeVec,
    pub memory_total_bytes: Gauge,
    pub memory_used_bytes: Gauge,
    pub memory_free_bytes: Gauge,
    pub memory_available_bytes: Gauge,
    pub memory_buffers_bytes: Gauge,
    pub memory_cached_bytes: Gauge,
    pub swap_total_bytes: Gauge,
    pub swap_used_bytes: Gauge,
    pub swap_free_bytes: Gauge,
    pub page_in_bytes_total: IntCounter,
    pub page_out_bytes_total: IntCounter,
    pub disk_total_bytes: GaugeVec,
    pub disk_used_bytes: GaugeVec,
    pub disk_free_bytes: GaugeVec,
    pub disk_read_bytes_total: IntCounterVec,
    pub disk_written_bytes_total: IntCounterVec,
    pub disk_read_ops_total: IntCounterVec,
    pub disk_write_ops_total: IntCounterVec,
    pub disk_io_time_ms_total: IntCounterVec,
    pub disk_degradation_busy: GaugeVec,
    pub disk_io_avg_latency_ms: GaugeVec,
    pub disk_degradation_latency: GaugeVec,
    pub network_rx_bytes_total: IntCounterVec,
    pub network_tx_bytes_total: IntCounterVec,
    pub network_rx_errors_total: IntCounterVec,
    pub network_rx_packets_total: IntCounterVec,
    pub network_tx_packets_total: IntCounterVec,
    pub network_rx_dropped_total: IntCounterVec,
    pub network_tx_dropped_total: IntCounterVec,
    pub network_degradation_drops: GaugeVec,
    pub network_tcp_retrans_total: IntCounter,
    pub network_degradation_retrans: Gauge,
    pub swap_degradation_spike: Gauge,
    pub degradation_score: Gauge,
    pub cpu_package_power_watts: GaugeVec,
    pub node_power_watts: Gauge,
    pub node_energy_joules_total: IntCounter,
    pub gpu_utilization_percent: GaugeVec,
    pub gpu_memory_total_bytes: GaugeVec,
    pub gpu_memory_used_bytes: GaugeVec,
    pub gpu_temperature_celsius: GaugeVec,
    pub gpu_power_watts: GaugeVec,
    // Compatibility metrics (single gpu label) to help legacy dashboards when k8s_mode is enabled.
    pub gpu_utilization_percent_compat: GaugeVec,
    pub gpu_memory_used_bytes_compat: GaugeVec,
    pub gpu_temperature_celsius_compat: GaugeVec,
    pub gpu_power_watts_compat: GaugeVec,
    pub gpu_power_limit_watts: GaugeVec,
    pub gpu_ecc_errors_total: IntCounterVec,
    pub gpu_ecc_corrected_total: IntCounterVec,
    pub gpu_ecc_uncorrected_total: IntCounterVec,
    pub gpu_ecc_mode: GaugeVec,
    pub gpu_retired_pages_total: IntCounterVec,
    pub gpu_last_xid_code: GaugeVec,
    pub gpu_last_event_unix_ms: GaugeVec,
    pub gpu_energy_joules_total: IntCounterVec,
    pub gpu_xid_errors_total: IntCounterVec,
    pub gpu_pcie_tx_bytes_total: IntCounterVec,
    pub gpu_pcie_rx_bytes_total: IntCounterVec,
    pub gpu_pcie_correctable_errors_total: IntCounterVec,
    pub gpu_pcie_atomic_requests_total: IntCounterVec,
    pub gpu_nvlink_errors_total: IntCounterVec,
    pub gpu_pcie_replay_errors_total: IntCounterVec,
    pub gpu_pcie_uncorrectable_errors_total: IntCounterVec,
    pub gpu_nvswitch_errors_total: IntCounterVec,
    pub gpu_degradation_throttle: GaugeVec,
    pub gpu_degradation_ecc: GaugeVec,
    pub gpu_fan_speed_percent: GaugeVec,
    pub gpu_clock_sm_mhz: GaugeVec,
    pub gpu_clock_mem_mhz: GaugeVec,
    pub gpu_clock_graphics_mhz: GaugeVec,
    pub gpu_pstate: GaugeVec,
    pub gpu_bar1_used_bytes: GaugeVec,
    pub gpu_bar1_total_bytes: GaugeVec,
    pub gpu_encoder_utilization_percent: GaugeVec,
    pub gpu_decoder_utilization_percent: GaugeVec,
    pub gpu_copy_utilization_percent: GaugeVec,
    pub gpu_throttle_reason: GaugeVec,
    pub gpu_events_total: IntCounterVec,
    pub cpu_temperature_celsius: GaugeVec,
    pub gpu_nvlink_rx_bytes_total: IntCounterVec,
    pub gpu_nvlink_tx_bytes_total: IntCounterVec,
    pub mig_utilization_percent: GaugeVec,
    pub mig_memory_used_bytes: GaugeVec,
    pub mig_memory_total_bytes: GaugeVec,
    pub mig_sm_count: GaugeVec,
    pub mig_energy_joules_total: IntCounterVec,
    pub mig_ecc_corrected_total: IntCounterVec,
    pub mig_ecc_uncorrected_total: IntCounterVec,
    pub mig_bar1_total_bytes: GaugeVec,
    pub mig_bar1_used_bytes: GaugeVec,
    pub gpu_mig_supported: GaugeVec,
    pub gpu_mig_enabled: GaugeVec,
    pub mig_info: GaugeVec,
    pub mig_gpu_instance_info: GaugeVec,
    pub mig_compute_instance_info: GaugeVec,
    pub pcie_bandwidth_percent: GaugeVec,
    pub pcie_link_width: GaugeVec,
    pub pcie_link_gen: GaugeVec,
    pub nvswitch_errors_total: IntCounterVec,
    pub fabric_latency_microseconds: GaugeVec,
    pub cpu_package_energy_joules_total: IntCounterVec,
    pub cpu_core_power_watts: GaugeVec,
    pub pdu_outlet_power_watts: GaugeVec,
    pub node_power_envelope_exceeded: Gauge,
    pub agent_scrape_duration_seconds: GaugeVec,
    pub agent_errors_total: IntCounterVec,
    pub agent_running: Gauge,
    pub agent_start_time_seconds: Gauge,
    pub agent_build_info: GaugeVec,
    pub ai_tokens_per_joule: GaugeVec,
    pub ai_tokens_per_watt: GaugeVec,
    pub ai_cost_per_million_tokens_usd: GaugeVec,
    pub ai_carbon_grams_per_token: GaugeVec,
    pub agent_config_reloads_total: IntCounter,
    pub agent_collector_disabled: GaugeVec,
    pub app_tokens_per_sec: Gauge,
}

impl MetricsRegistry {
    #[must_use]
    pub fn gather_families(&self) -> Vec<MetricFamily> {
        self.registry.gather()
    }

    pub fn new() -> anyhow::Result<Self> {
        let registry = Registry::new();

        let cpu_load_avg_1m = Gauge::with_opts(Opts::new(
            "esnode_cpu_load_avg_1m",
            "1-minute system load average",
        ))?;
        let cpu_usage_percent = GaugeVec::new(
            Opts::new(
                "esnode_cpu_usage_percent",
                "CPU usage percentage per logical core",
            ),
            &["core"],
        )?;
        let cpu_load_avg_5m = Gauge::with_opts(Opts::new(
            "esnode_cpu_load_avg_5m",
            "5-minute system load average",
        ))?;
        let cpu_load_avg_15m = Gauge::with_opts(Opts::new(
            "esnode_cpu_load_avg_15m",
            "15-minute system load average",
        ))?;
        let cpu_time_seconds_total = CounterVec::new(
            Opts::new(
                "esnode_cpu_time_seconds_total",
                "Cumulative CPU time in seconds by state",
            ),
            &["state"],
        )?;
        let cpu_interrupts_total =
            IntCounter::with_opts(Opts::new("esnode_cpu_interrupts_total", "Total interrupts"))?;
        let cpu_context_switches_total = IntCounter::with_opts(Opts::new(
            "esnode_cpu_context_switches_total",
            "Total context switches",
        ))?;
        let numa_memory_total_bytes = GaugeVec::new(
            Opts::new(
                "esnode_numa_memory_total_bytes",
                "Total memory for the NUMA node",
            ),
            &["node"],
        )?;
        let numa_memory_free_bytes = GaugeVec::new(
            Opts::new(
                "esnode_numa_memory_free_bytes",
                "Available memory for the NUMA node",
            ),
            &["node"],
        )?;
        let numa_memory_used_bytes = GaugeVec::new(
            Opts::new(
                "esnode_numa_memory_used_bytes",
                "Used memory for the NUMA node",
            ),
            &["node"],
        )?;
        let numa_cpu_usage_percent = GaugeVec::new(
            Opts::new(
                "esnode_numa_cpu_usage_percent",
                "CPU usage for cores in the NUMA node",
            ),
            &["node"],
        )?;
        let numa_page_faults_total = IntCounterVec::new(
            Opts::new(
                "esnode_numa_page_faults_total",
                "Page faults per NUMA domain",
            ),
            &["node"],
        )?;
        let numa_distance = GaugeVec::new(
            Opts::new(
                "esnode_numa_distance",
                "NUMA distance matrix value from node to target",
            ),
            &["node", "to"],
        )?;

        let memory_total_bytes = Gauge::with_opts(Opts::new(
            "esnode_memory_total_bytes",
            "Total physical memory in bytes",
        ))?;
        let memory_used_bytes = Gauge::with_opts(Opts::new(
            "esnode_memory_used_bytes",
            "Used memory in bytes",
        ))?;
        let memory_free_bytes = Gauge::with_opts(Opts::new(
            "esnode_memory_free_bytes",
            "Free memory in bytes",
        ))?;
        let memory_available_bytes = Gauge::with_opts(Opts::new(
            "esnode_memory_available_bytes",
            "Available memory in bytes",
        ))?;
        let memory_buffers_bytes = Gauge::with_opts(Opts::new(
            "esnode_memory_buffers_bytes",
            "Memory buffers in bytes",
        ))?;
        let memory_cached_bytes = Gauge::with_opts(Opts::new(
            "esnode_memory_cached_bytes",
            "Cached memory in bytes",
        ))?;
        let swap_total_bytes =
            Gauge::with_opts(Opts::new("esnode_swap_total_bytes", "Total swap in bytes"))?;
        let swap_used_bytes =
            Gauge::with_opts(Opts::new("esnode_swap_used_bytes", "Used swap in bytes"))?;
        let swap_free_bytes =
            Gauge::with_opts(Opts::new("esnode_swap_free_bytes", "Free swap in bytes"))?;
        let page_in_bytes_total = IntCounter::with_opts(Opts::new(
            "esnode_page_in_bytes_total",
            "Total page-in bytes",
        ))?;
        let page_out_bytes_total = IntCounter::with_opts(Opts::new(
            "esnode_page_out_bytes_total",
            "Total page-out bytes",
        ))?;

        let disk_total_bytes = GaugeVec::new(
            Opts::new(
                "esnode_disk_total_bytes",
                "Total disk space for the given mount point",
            ),
            &["mount"],
        )?;
        let disk_used_bytes = GaugeVec::new(
            Opts::new(
                "esnode_disk_used_bytes",
                "Used disk space for the given mount point",
            ),
            &["mount"],
        )?;
        let disk_free_bytes = GaugeVec::new(
            Opts::new(
                "esnode_disk_free_bytes",
                "Free disk space for the given mount point",
            ),
            &["mount"],
        )?;
        let disk_read_bytes_total = IntCounterVec::new(
            Opts::new(
                "esnode_disk_read_bytes_total",
                "Total bytes read per block device",
            ),
            &["device"],
        )?;
        let disk_written_bytes_total = IntCounterVec::new(
            Opts::new(
                "esnode_disk_written_bytes_total",
                "Total bytes written per block device",
            ),
            &["device"],
        )?;
        let disk_read_ops_total = IntCounterVec::new(
            Opts::new(
                "esnode_disk_read_ops_total",
                "Total read operations per block device",
            ),
            &["device"],
        )?;
        let disk_write_ops_total = IntCounterVec::new(
            Opts::new(
                "esnode_disk_write_ops_total",
                "Total write operations per block device",
            ),
            &["device"],
        )?;
        let disk_io_time_ms_total = IntCounterVec::new(
            Opts::new(
                "esnode_disk_io_time_ms_total",
                "Total I/O time ms per block device",
            ),
            &["device"],
        )?;
        let disk_degradation_busy = GaugeVec::new(
            Opts::new(
                "esnode_disk_degradation_busy",
                "Flag (0/1) when block device is heavily utilized during the scrape window",
            ),
            &["device"],
        )?;
        let disk_io_avg_latency_ms = GaugeVec::new(
            Opts::new(
                "esnode_disk_io_avg_latency_ms",
                "Average I/O latency (ms/op) during the scrape window",
            ),
            &["device"],
        )?;
        let disk_degradation_latency = GaugeVec::new(
            Opts::new(
                "esnode_disk_degradation_latency",
                "Flag (0/1) when disk latency exceeds threshold during the scrape window",
            ),
            &["device"],
        )?;

        let network_rx_bytes_total = IntCounterVec::new(
            Opts::new(
                "esnode_network_rx_bytes_total",
                "Total bytes received on the network interface",
            ),
            &["iface"],
        )?;
        let network_tx_bytes_total = IntCounterVec::new(
            Opts::new(
                "esnode_network_tx_bytes_total",
                "Total bytes transmitted on the network interface",
            ),
            &["iface"],
        )?;
        let network_rx_errors_total = IntCounterVec::new(
            Opts::new(
                "esnode_network_rx_errors_total",
                "Total receive errors on the network interface",
            ),
            &["iface"],
        )?;
        let network_rx_packets_total = IntCounterVec::new(
            Opts::new(
                "esnode_network_rx_packets_total",
                "Total packets received on the network interface",
            ),
            &["iface"],
        )?;
        let network_tx_packets_total = IntCounterVec::new(
            Opts::new(
                "esnode_network_tx_packets_total",
                "Total packets transmitted on the network interface",
            ),
            &["iface"],
        )?;
        let network_rx_dropped_total = IntCounterVec::new(
            Opts::new(
                "esnode_network_rx_dropped_total",
                "Total dropped receive packets on the network interface",
            ),
            &["iface"],
        )?;
        let network_tx_dropped_total = IntCounterVec::new(
            Opts::new(
                "esnode_network_tx_dropped_total",
                "Total dropped transmit packets on the network interface",
            ),
            &["iface"],
        )?;
        let network_degradation_drops = GaugeVec::new(
            Opts::new(
                "esnode_network_degradation_drops",
                "Flag (0/1) when interface sees packet drops during the scrape window",
            ),
            &["iface"],
        )?;
        let network_tcp_retrans_total = IntCounter::with_opts(Opts::new(
            "esnode_network_tcp_retrans_total",
            "Total TCP retransmissions (from /proc/net/netstat TCPSegRetrans)",
        ))?;
        let network_degradation_retrans = Gauge::with_opts(Opts::new(
            "esnode_network_degradation_retrans",
            "Flag (0/1) when TCP retransmissions occur during the scrape window",
        ))?;
        let swap_degradation_spike = Gauge::with_opts(Opts::new(
            "esnode_swap_degradation_spike",
            "Flag (0/1) when swap in/out spikes during the scrape window",
        ))?;
        let degradation_score = Gauge::with_opts(Opts::new(
            "esnode_degradation_score",
            "Aggregate degradation score (sum of domain flags)",
        ))?;

        let cpu_package_power_watts = GaugeVec::new(
            Opts::new(
                "esnode_cpu_package_power_watts",
                "CPU package (socket) power draw in watts",
            ),
            &["package"],
        )?;

        let node_power_watts = Gauge::with_opts(Opts::new(
            "esnode_node_power_watts",
            "Whole-node power draw in watts (from BMC/IPMI/PDU/hwmon if available)",
        ))?;
        let node_energy_joules_total = IntCounter::with_opts(Opts::new(
            "esnode_node_energy_joules_total",
            "Total node energy consumption in joules",
        ))?;

        let gpu_utilization_percent = GaugeVec::new(
            Opts::new(
                "esnode_gpu_utilization_percent",
                "GPU utilization percentage per device",
            ),
            GPU_LABELS,
        )?;
        let gpu_memory_total_bytes = GaugeVec::new(
            Opts::new(
                "esnode_gpu_memory_total_bytes",
                "Total GPU memory in bytes per device",
            ),
            GPU_LABELS,
        )?;
        let gpu_memory_used_bytes = GaugeVec::new(
            Opts::new(
                "esnode_gpu_memory_used_bytes",
                "Used GPU memory in bytes per device",
            ),
            GPU_LABELS,
        )?;
        let gpu_temperature_celsius = GaugeVec::new(
            Opts::new(
                "esnode_gpu_temperature_celsius",
                "GPU temperature in Celsius per device",
            ),
            GPU_LABELS,
        )?;
        let gpu_power_watts = GaugeVec::new(
            Opts::new(
                "esnode_gpu_power_watts",
                "Instantaneous GPU power draw in watts per device",
            ),
            GPU_LABELS,
        )?;
        let gpu_utilization_percent_compat = GaugeVec::new(
            Opts::new(
                "esnode_gpu_utilization_percent_compat",
                "GPU utilization percent (legacy single gpu label)",
            ),
            &["gpu"],
        )?;
        let gpu_memory_used_bytes_compat = GaugeVec::new(
            Opts::new(
                "esnode_gpu_memory_used_bytes_compat",
                "Used GPU memory (legacy single gpu label)",
            ),
            &["gpu"],
        )?;
        let gpu_temperature_celsius_compat = GaugeVec::new(
            Opts::new(
                "esnode_gpu_temperature_celsius_compat",
                "GPU temperature (legacy single gpu label)",
            ),
            &["gpu"],
        )?;
        let gpu_power_watts_compat = GaugeVec::new(
            Opts::new(
                "esnode_gpu_power_watts_compat",
                "GPU power (legacy single gpu label)",
            ),
            &["gpu"],
        )?;
        let gpu_power_limit_watts = GaugeVec::new(
            Opts::new(
                "esnode_gpu_power_limit_watts",
                "GPU power management limit in watts per device",
            ),
            GPU_LABELS,
        )?;
        let gpu_ecc_errors_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_ecc_errors_total",
                "Total ECC error count per GPU device",
            ),
            &["uuid", "index", "type"],
        )?;
        let gpu_ecc_corrected_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_ecc_corrected_total",
                "Corrected ECC errors per GPU by scope",
            ),
            &["uuid", "index", "scope"],
        )?;
        let gpu_ecc_uncorrected_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_ecc_uncorrected_total",
                "Uncorrected ECC errors per GPU by scope",
            ),
            &["uuid", "index", "scope"],
        )?;
        let gpu_ecc_mode = GaugeVec::new(
            Opts::new("esnode_gpu_ecc_mode", "ECC mode enabled=1 disabled=0"),
            GPU_LABELS,
        )?;
        let gpu_last_xid_code = GaugeVec::new(
            Opts::new("esnode_gpu_last_xid_code", "Last XID code seen"),
            GPU_LABELS,
        )?;
        let gpu_last_event_unix_ms = GaugeVec::new(
            Opts::new(
                "esnode_gpu_last_event_unix_ms",
                "Last GPU event timestamp (ms since epoch)",
            ),
            GPU_LABELS,
        )?;
        let gpu_xid_errors_total = IntCounterVec::new(
            Opts::new("esnode_gpu_xid_errors_total", "Total XID errors per GPU"),
            GPU_LABELS,
        )?;
        let gpu_retired_pages_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_retired_pages_total",
                "Total retired pages per GPU (all causes)",
            ),
            GPU_LABELS,
        )?;
        let gpu_energy_joules_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_energy_joules_total",
                "Accumulated GPU energy consumption in joules",
            ),
            GPU_LABELS,
        )?;
        let gpu_pcie_tx_bytes_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_pcie_tx_bytes_total",
                "Total PCIe transmit bytes per GPU",
            ),
            GPU_LABELS,
        )?;
        let gpu_pcie_rx_bytes_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_pcie_rx_bytes_total",
                "Total PCIe receive bytes per GPU",
            ),
            GPU_LABELS,
        )?;
        let gpu_pcie_correctable_errors_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_pcie_correctable_errors_total",
                "PCIe correctable errors per GPU (best effort)",
            ),
            GPU_LABELS,
        )?;
        let gpu_pcie_atomic_requests_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_pcie_atomic_requests_total",
                "PCIe atomic requests per GPU (best effort)",
            ),
            GPU_LABELS,
        )?;
        let gpu_nvlink_errors_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_nvlink_errors_total",
                "NVLink error counters per link",
            ),
            GPU_LINK_LABELS,
        )?;
        let gpu_pcie_replay_errors_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_pcie_replay_errors_total",
                "PCIe replay/correctable errors per GPU",
            ),
            GPU_LABELS,
        )?;
        let gpu_pcie_uncorrectable_errors_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_pcie_uncorrectable_errors_total",
                "PCIe uncorrectable errors per GPU",
            ),
            GPU_LABELS,
        )?;
        let gpu_degradation_throttle = GaugeVec::new(
            Opts::new(
                "esnode_gpu_degradation_throttle",
                "Flag (0/1) when GPU is thermally or power throttled",
            ),
            GPU_LABELS,
        )?;
        let gpu_degradation_ecc = GaugeVec::new(
            Opts::new(
                "esnode_gpu_degradation_ecc",
                "Flag (0/1) when GPU reports ECC errors during the scrape window",
            ),
            GPU_LABELS,
        )?;
        let gpu_nvswitch_errors_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_nvswitch_errors_total",
                "NVSwitch error counters per GPU (best effort)",
            ),
            GPU_LABELS,
        )?;

        let gpu_fan_speed_percent = GaugeVec::new(
            Opts::new(
                "esnode_gpu_fan_speed_percent",
                "GPU fan speed percentage per device",
            ),
            GPU_LABELS,
        )?;

        let gpu_clock_sm_mhz = GaugeVec::new(
            Opts::new(
                "esnode_gpu_clock_sm_mhz",
                "Streaming multiprocessor clock speed in MHz",
            ),
            GPU_LABELS,
        )?;

        let gpu_clock_mem_mhz = GaugeVec::new(
            Opts::new("esnode_gpu_clock_mem_mhz", "Memory clock speed in MHz"),
            GPU_LABELS,
        )?;
        let gpu_clock_graphics_mhz = GaugeVec::new(
            Opts::new(
                "esnode_gpu_clock_graphics_mhz",
                "Graphics clock speed in MHz",
            ),
            GPU_LABELS,
        )?;
        let gpu_pstate = GaugeVec::new(
            Opts::new(
                "esnode_gpu_pstate",
                "Current GPU performance state (P0..P15)",
            ),
            GPU_LABELS,
        )?;
        let gpu_bar1_used_bytes = GaugeVec::new(
            Opts::new("esnode_gpu_bar1_used_bytes", "BAR1 used bytes per GPU"),
            GPU_LABELS,
        )?;
        let gpu_bar1_total_bytes = GaugeVec::new(
            Opts::new("esnode_gpu_bar1_total_bytes", "BAR1 total bytes per GPU"),
            GPU_LABELS,
        )?;
        let gpu_encoder_utilization_percent = GaugeVec::new(
            Opts::new(
                "esnode_gpu_encoder_utilization_percent",
                "Encoder utilization percent per GPU",
            ),
            GPU_LABELS,
        )?;
        let gpu_decoder_utilization_percent = GaugeVec::new(
            Opts::new(
                "esnode_gpu_decoder_utilization_percent",
                "Decoder utilization percent per GPU",
            ),
            GPU_LABELS,
        )?;
        let gpu_copy_utilization_percent = GaugeVec::new(
            Opts::new(
                "esnode_gpu_copy_utilization_percent",
                "Copy engine utilization percent per GPU (best effort)",
            ),
            GPU_LABELS,
        )?;

        let gpu_throttle_reason = GaugeVec::new(
            Opts::new(
                "esnode_gpu_throttle_reason",
                "GPU throttle reason flag (1 active, 0 inactive)",
            ),
            &["uuid", "index", "reason"],
        )?;
        let gpu_events_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_events_total",
                "GPU event counts (xid, ecc, retire, pstate, clock)",
            ),
            &["uuid", "index", "event"],
        )?;

        let cpu_temperature_celsius = GaugeVec::new(
            Opts::new(
                "esnode_cpu_temperature_celsius",
                "CPU/package temperature in Celsius",
            ),
            &["sensor"],
        )?;

        let gpu_nvlink_rx_bytes_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_nvlink_rx_bytes_total",
                "Total NVLink receive bytes (if supported)",
            ),
            GPU_LINK_LABELS,
        )?;

        let gpu_nvlink_tx_bytes_total = IntCounterVec::new(
            Opts::new(
                "esnode_gpu_nvlink_tx_bytes_total",
                "Total NVLink transmit bytes (if supported)",
            ),
            GPU_LINK_LABELS,
        )?;
        let mig_utilization_percent = GaugeVec::new(
            Opts::new(
                "esnode_mig_utilization_percent",
                "MIG instance utilization percent",
            ),
            MIG_LABELS,
        )?;
        let mig_memory_used_bytes = GaugeVec::new(
            Opts::new("esnode_mig_memory_used_bytes", "Used memory per MIG slice"),
            MIG_LABELS,
        )?;
        let mig_memory_total_bytes = GaugeVec::new(
            Opts::new(
                "esnode_mig_memory_total_bytes",
                "Total memory per MIG slice",
            ),
            MIG_LABELS,
        )?;
        let mig_sm_count = GaugeVec::new(
            Opts::new("esnode_mig_sm_count", "SM count assigned to MIG slice"),
            MIG_LABELS,
        )?;
        let mig_energy_joules_total = IntCounterVec::new(
            Opts::new(
                "esnode_mig_energy_joules_total",
                "Estimated MIG slice energy",
            ),
            MIG_LABELS,
        )?;
        let mig_ecc_corrected_total = IntCounterVec::new(
            Opts::new(
                "esnode_mig_ecc_corrected_total",
                "Corrected ECC errors per MIG device",
            ),
            MIG_LABELS,
        )?;
        let mig_ecc_uncorrected_total = IntCounterVec::new(
            Opts::new(
                "esnode_mig_ecc_uncorrected_total",
                "Uncorrected ECC errors per MIG device",
            ),
            MIG_LABELS,
        )?;
        let mig_bar1_total_bytes = GaugeVec::new(
            Opts::new(
                "esnode_mig_bar1_total_bytes",
                "BAR1 total bytes per MIG device",
            ),
            MIG_LABELS,
        )?;
        let mig_bar1_used_bytes = GaugeVec::new(
            Opts::new(
                "esnode_mig_bar1_used_bytes",
                "BAR1 used bytes per MIG device",
            ),
            MIG_LABELS,
        )?;
        let gpu_mig_supported = GaugeVec::new(
            Opts::new(
                "esnode_gpu_mig_supported",
                "1 if MIG metrics are supported on this GPU, 0 otherwise",
            ),
            GPU_LABELS,
        )?;
        let gpu_mig_enabled = GaugeVec::new(
            Opts::new(
                "esnode_gpu_mig_enabled",
                "1 if MIG mode is enabled on this GPU, 0 otherwise",
            ),
            GPU_LABELS,
        )?;
        let mig_info = GaugeVec::new(
            Opts::new(
                "esnode_mig_info",
                "MIG instance info (profile/placement labels, value=1)",
            ),
            &["gpu_uuid", "gpu_index", "mig", "profile", "placement"],
        )?;
        let mig_gpu_instance_info = GaugeVec::new(
            Opts::new(
                "esnode_mig_gpu_instance_info",
                "MIG GPU instance info (profile/placement labels, value=1)",
            ),
            &["gpu_uuid", "gpu_index", "gi", "profile", "placement"],
        )?;
        let mig_compute_instance_info = GaugeVec::new(
            Opts::new(
                "esnode_mig_compute_instance_info",
                "MIG Compute instance info (profile/placement labels, value=1)",
            ),
            &[
                "gpu_uuid",
                "gpu_index",
                "gi",
                "ci",
                "profile",
                "eng_profile",
                "placement",
            ],
        )?;
        let pcie_bandwidth_percent = GaugeVec::new(
            Opts::new(
                "esnode_pcie_bandwidth_percent",
                "PCIe bandwidth saturation percentage",
            ),
            GPU_LABELS,
        )?;
        let pcie_link_width = GaugeVec::new(
            Opts::new("esnode_pcie_link_width", "Current PCIe link width (lanes)"),
            GPU_LABELS,
        )?;
        let pcie_link_gen = GaugeVec::new(
            Opts::new("esnode_pcie_link_gen", "Current PCIe link generation"),
            GPU_LABELS,
        )?;
        let nvswitch_errors_total = IntCounterVec::new(
            Opts::new("esnode_nvswitch_errors_total", "NVSwitch error counters"),
            &["switch", "port"],
        )?;
        let fabric_latency_microseconds = GaugeVec::new(
            Opts::new(
                "esnode_fabric_latency_microseconds",
                "GPU-to-GPU latency (best effort)",
            ),
            &["gpu", "peer"],
        )?;
        let cpu_package_energy_joules_total = IntCounterVec::new(
            Opts::new(
                "esnode_cpu_package_energy_joules_total",
                "CPU package energy consumed in joules",
            ),
            &["package"],
        )?;
        let cpu_core_power_watts = GaugeVec::new(
            Opts::new(
                "esnode_cpu_core_power_watts",
                "CPU core power draw in watts (if available)",
            ),
            &["core"],
        )?;
        let pdu_outlet_power_watts = GaugeVec::new(
            Opts::new(
                "esnode_pdu_outlet_power_watts",
                "PDU outlet power reading in watts",
            ),
            &["outlet"],
        )?;

        let node_power_envelope_exceeded = Gauge::with_opts(Opts::new(
            "esnode_node_power_envelope_exceeded",
            "1 if node power envelope is exceeded; otherwise 0",
        ))?;

        let agent_scrape_duration_seconds = GaugeVec::new(
            Opts::new(
                "esnode_agent_scrape_duration_seconds",
                "Duration of the last scrape per collector",
            ),
            &["collector"],
        )?;
        let agent_errors_total = IntCounterVec::new(
            Opts::new(
                "esnode_agent_errors_total",
                "Total errors encountered by collector",
            ),
            &["collector"],
        )?;
        let agent_running =
            Gauge::with_opts(Opts::new("esnode_agent_running", "Agent running flag"))?;
        let agent_start_time_seconds = Gauge::with_opts(Opts::new(
            "esnode_agent_start_time_seconds",
            "Agent process start time in unix seconds",
        ))?;
        let agent_build_info = GaugeVec::new(
            Opts::new("esnode_agent_build_info", "Agent build information"),
            &["version", "commit"],
        )?;
        let ai_tokens_per_joule = GaugeVec::new(
            Opts::new(
                "esnode_ai_tokens_per_joule",
                "Instant tokens per joule efficiency",
            ),
            &["agent"],
        )?;
        let ai_tokens_per_watt = GaugeVec::new(
            Opts::new(
                "esnode_ai_tokens_per_watt",
                "Instant tokens per watt efficiency",
            ),
            &["agent"],
        )?;
        let ai_cost_per_million_tokens_usd = GaugeVec::new(
            Opts::new(
                "esnode_ai_cost_per_million_tokens_usd",
                "Cost per million tokens based on power usage",
            ),
            &["agent"],
        )?;
        let ai_carbon_grams_per_token = GaugeVec::new(
            Opts::new(
                "esnode_ai_carbon_grams_per_token",
                "Estimated carbon per token",
            ),
            &["agent"],
        )?;
        let agent_config_reloads_total = IntCounter::with_opts(Opts::new(
            "esnode_agent_config_reloads_total",
            "Number of config reloads",
        ))?;
        let agent_collector_disabled = GaugeVec::new(
            Opts::new(
                "esnode_agent_collector_disabled",
                "Collector disabled flag (1 disabled)",
            ),
            &["collector"],
        )?;
        let app_tokens_per_sec = Gauge::with_opts(Opts::new(
            "esnode_app_tokens_per_sec",
            "Application token generation rate (tokens/sec)",
        ))?;

        let metrics = Self {
            registry,
            cpu_load_avg_1m,
            cpu_load_avg_5m,
            cpu_load_avg_15m,
            cpu_usage_percent,
            cpu_time_seconds_total,
            cpu_interrupts_total,
            cpu_context_switches_total,
            numa_memory_total_bytes,
            numa_memory_free_bytes,
            numa_memory_used_bytes,
            numa_cpu_usage_percent,
            numa_page_faults_total,
            numa_distance,
            memory_total_bytes,
            memory_used_bytes,
            memory_free_bytes,
            memory_available_bytes,
            memory_buffers_bytes,
            memory_cached_bytes,
            swap_total_bytes,
            swap_used_bytes,
            swap_free_bytes,
            page_in_bytes_total,
            page_out_bytes_total,
            disk_total_bytes,
            disk_used_bytes,
            disk_free_bytes,
            disk_read_bytes_total,
            disk_written_bytes_total,
            disk_read_ops_total,
            disk_write_ops_total,
            disk_io_time_ms_total,
            disk_degradation_busy,
            disk_io_avg_latency_ms,
            disk_degradation_latency,
            network_rx_bytes_total,
            network_tx_bytes_total,
            network_rx_errors_total,
            network_rx_packets_total,
            network_tx_packets_total,
            network_rx_dropped_total,
            network_tx_dropped_total,
            network_degradation_drops,
            network_tcp_retrans_total,
            network_degradation_retrans,
            swap_degradation_spike,
            degradation_score,
            cpu_package_power_watts,
            node_power_watts,
            node_energy_joules_total,
            gpu_utilization_percent,
            gpu_memory_total_bytes,
            gpu_memory_used_bytes,
            gpu_temperature_celsius,
            gpu_power_watts,
            gpu_utilization_percent_compat,
            gpu_memory_used_bytes_compat,
            gpu_temperature_celsius_compat,
            gpu_power_watts_compat,
            gpu_power_limit_watts,
            gpu_ecc_errors_total,
            gpu_ecc_corrected_total,
            gpu_ecc_uncorrected_total,
            gpu_ecc_mode,
            gpu_retired_pages_total,
            gpu_last_xid_code,
            gpu_last_event_unix_ms,
            gpu_energy_joules_total,
            gpu_xid_errors_total,
            gpu_pcie_tx_bytes_total,
            gpu_pcie_rx_bytes_total,
            gpu_pcie_correctable_errors_total,
            gpu_pcie_atomic_requests_total,
            gpu_nvlink_errors_total,
            gpu_pcie_replay_errors_total,
            gpu_pcie_uncorrectable_errors_total,
            gpu_nvswitch_errors_total,
            gpu_degradation_throttle,
            gpu_degradation_ecc,
            gpu_fan_speed_percent,
            gpu_clock_sm_mhz,
            gpu_clock_mem_mhz,
            gpu_clock_graphics_mhz,
            gpu_pstate,
            gpu_bar1_used_bytes,
            gpu_bar1_total_bytes,
            gpu_encoder_utilization_percent,
            gpu_decoder_utilization_percent,
            gpu_copy_utilization_percent,
            gpu_throttle_reason,
            gpu_events_total,
            cpu_temperature_celsius,
            gpu_nvlink_rx_bytes_total,
            gpu_nvlink_tx_bytes_total,
            mig_utilization_percent,
            mig_memory_used_bytes,
            mig_memory_total_bytes,
            mig_sm_count,
            mig_energy_joules_total,
            mig_ecc_corrected_total,
            mig_ecc_uncorrected_total,
            mig_bar1_total_bytes,
            mig_bar1_used_bytes,
            gpu_mig_supported,
            gpu_mig_enabled,
            mig_info,
            mig_gpu_instance_info,
            mig_compute_instance_info,
            pcie_bandwidth_percent,
            pcie_link_width,
            pcie_link_gen,
            nvswitch_errors_total,
            fabric_latency_microseconds,
            cpu_package_energy_joules_total,
            cpu_core_power_watts,
            pdu_outlet_power_watts,
            node_power_envelope_exceeded,
            agent_scrape_duration_seconds,
            agent_errors_total,
            agent_running,
            agent_start_time_seconds,
            agent_build_info,
            ai_tokens_per_joule,
            ai_tokens_per_watt,
            ai_cost_per_million_tokens_usd,
            ai_carbon_grams_per_token,
            agent_config_reloads_total,
            agent_collector_disabled,
            app_tokens_per_sec,
        };

        metrics.register_all()?;
        Ok(metrics)
    }

    fn register_all(&self) -> anyhow::Result<()> {
        let mut regs: Vec<Box<dyn prometheus::core::Collector>> = vec![
            Box::new(self.cpu_load_avg_1m.clone()),
            Box::new(self.cpu_load_avg_5m.clone()),
            Box::new(self.cpu_load_avg_15m.clone()),
            Box::new(self.cpu_usage_percent.clone()),
            Box::new(self.cpu_time_seconds_total.clone()),
            Box::new(self.cpu_interrupts_total.clone()),
            Box::new(self.cpu_context_switches_total.clone()),
            Box::new(self.numa_memory_total_bytes.clone()),
            Box::new(self.numa_memory_free_bytes.clone()),
            Box::new(self.numa_memory_used_bytes.clone()),
            Box::new(self.numa_cpu_usage_percent.clone()),
            Box::new(self.numa_page_faults_total.clone()),
            Box::new(self.numa_distance.clone()),
            Box::new(self.memory_total_bytes.clone()),
            Box::new(self.memory_used_bytes.clone()),
            Box::new(self.memory_free_bytes.clone()),
            Box::new(self.memory_available_bytes.clone()),
            Box::new(self.memory_buffers_bytes.clone()),
            Box::new(self.memory_cached_bytes.clone()),
            Box::new(self.swap_total_bytes.clone()),
            Box::new(self.swap_used_bytes.clone()),
            Box::new(self.swap_free_bytes.clone()),
            Box::new(self.page_in_bytes_total.clone()),
            Box::new(self.page_out_bytes_total.clone()),
            Box::new(self.disk_total_bytes.clone()),
            Box::new(self.disk_used_bytes.clone()),
            Box::new(self.disk_free_bytes.clone()),
            Box::new(self.disk_read_bytes_total.clone()),
            Box::new(self.disk_written_bytes_total.clone()),
            Box::new(self.disk_read_ops_total.clone()),
            Box::new(self.disk_write_ops_total.clone()),
            Box::new(self.disk_io_time_ms_total.clone()),
            Box::new(self.disk_degradation_busy.clone()),
            Box::new(self.disk_io_avg_latency_ms.clone()),
            Box::new(self.disk_degradation_latency.clone()),
            Box::new(self.network_rx_bytes_total.clone()),
            Box::new(self.network_tx_bytes_total.clone()),
            Box::new(self.network_rx_errors_total.clone()),
            Box::new(self.network_rx_packets_total.clone()),
            Box::new(self.network_tx_packets_total.clone()),
            Box::new(self.network_rx_dropped_total.clone()),
            Box::new(self.network_tx_dropped_total.clone()),
            Box::new(self.network_degradation_drops.clone()),
            Box::new(self.network_tcp_retrans_total.clone()),
            Box::new(self.network_degradation_retrans.clone()),
            Box::new(self.swap_degradation_spike.clone()),
            Box::new(self.degradation_score.clone()),
            Box::new(self.cpu_package_power_watts.clone()),
            Box::new(self.node_power_watts.clone()),
            Box::new(self.node_energy_joules_total.clone()),
            Box::new(self.gpu_utilization_percent.clone()),
            Box::new(self.gpu_memory_total_bytes.clone()),
            Box::new(self.gpu_memory_used_bytes.clone()),
            Box::new(self.gpu_temperature_celsius.clone()),
            Box::new(self.gpu_power_watts.clone()),
            Box::new(self.gpu_utilization_percent_compat.clone()),
            Box::new(self.gpu_memory_used_bytes_compat.clone()),
            Box::new(self.gpu_temperature_celsius_compat.clone()),
            Box::new(self.gpu_power_watts_compat.clone()),
            Box::new(self.gpu_power_limit_watts.clone()),
            Box::new(self.gpu_ecc_errors_total.clone()),
            Box::new(self.gpu_ecc_corrected_total.clone()),
            Box::new(self.gpu_ecc_uncorrected_total.clone()),
            Box::new(self.gpu_ecc_mode.clone()),
            Box::new(self.gpu_retired_pages_total.clone()),
            Box::new(self.gpu_last_xid_code.clone()),
            Box::new(self.gpu_last_event_unix_ms.clone()),
            Box::new(self.gpu_xid_errors_total.clone()),
            Box::new(self.gpu_energy_joules_total.clone()),
            Box::new(self.gpu_pcie_tx_bytes_total.clone()),
            Box::new(self.gpu_pcie_rx_bytes_total.clone()),
            Box::new(self.gpu_pcie_correctable_errors_total.clone()),
            Box::new(self.gpu_pcie_atomic_requests_total.clone()),
            Box::new(self.gpu_nvlink_errors_total.clone()),
            Box::new(self.gpu_pcie_replay_errors_total.clone()),
            Box::new(self.gpu_pcie_uncorrectable_errors_total.clone()),
            Box::new(self.gpu_nvswitch_errors_total.clone()),
            Box::new(self.gpu_degradation_throttle.clone()),
            Box::new(self.gpu_degradation_ecc.clone()),
            Box::new(self.gpu_fan_speed_percent.clone()),
            Box::new(self.gpu_clock_sm_mhz.clone()),
            Box::new(self.gpu_clock_mem_mhz.clone()),
            Box::new(self.gpu_clock_graphics_mhz.clone()),
            Box::new(self.gpu_pstate.clone()),
            Box::new(self.gpu_bar1_used_bytes.clone()),
            Box::new(self.gpu_bar1_total_bytes.clone()),
            Box::new(self.gpu_encoder_utilization_percent.clone()),
            Box::new(self.gpu_decoder_utilization_percent.clone()),
            Box::new(self.gpu_copy_utilization_percent.clone()),
            Box::new(self.gpu_throttle_reason.clone()),
            Box::new(self.gpu_events_total.clone()),
            Box::new(self.cpu_temperature_celsius.clone()),
            Box::new(self.gpu_nvlink_rx_bytes_total.clone()),
            Box::new(self.gpu_nvlink_tx_bytes_total.clone()),
            Box::new(self.mig_utilization_percent.clone()),
            Box::new(self.mig_memory_used_bytes.clone()),
            Box::new(self.mig_memory_total_bytes.clone()),
            Box::new(self.mig_sm_count.clone()),
            Box::new(self.mig_energy_joules_total.clone()),
            Box::new(self.mig_ecc_corrected_total.clone()),
            Box::new(self.mig_ecc_uncorrected_total.clone()),
            Box::new(self.mig_bar1_total_bytes.clone()),
            Box::new(self.mig_bar1_used_bytes.clone()),
            Box::new(self.gpu_mig_supported.clone()),
            Box::new(self.gpu_mig_enabled.clone()),
            Box::new(self.mig_info.clone()),
            Box::new(self.mig_gpu_instance_info.clone()),
            Box::new(self.mig_compute_instance_info.clone()),
            Box::new(self.pcie_bandwidth_percent.clone()),
            Box::new(self.pcie_link_width.clone()),
            Box::new(self.pcie_link_gen.clone()),
            Box::new(self.nvswitch_errors_total.clone()),

            Box::new(self.fabric_latency_microseconds.clone()),
            Box::new(self.cpu_package_energy_joules_total.clone()),
            Box::new(self.cpu_core_power_watts.clone()),
            Box::new(self.pdu_outlet_power_watts.clone()),
            Box::new(self.node_power_envelope_exceeded.clone()),
            Box::new(self.agent_scrape_duration_seconds.clone()),
            Box::new(self.agent_errors_total.clone()),
            Box::new(self.agent_running.clone()),
            Box::new(self.agent_start_time_seconds.clone()),
            Box::new(self.agent_build_info.clone()),
            Box::new(self.ai_tokens_per_joule.clone()),
            Box::new(self.ai_tokens_per_watt.clone()),
            Box::new(self.ai_cost_per_million_tokens_usd.clone()),
            Box::new(self.ai_carbon_grams_per_token.clone()),
            Box::new(self.agent_config_reloads_total.clone()),
            Box::new(self.agent_collector_disabled.clone()),
            Box::new(self.app_tokens_per_sec.clone()),
        ];

        for collector in regs.drain(..) {
            self.registry
                .register(collector)
                .context("registering metric")?;
        }

        Ok(())
    }

    #[must_use]
    pub fn gather(&self) -> Vec<MetricFamily> {
        self.registry.gather()
    }

    pub fn encode(&self) -> anyhow::Result<Vec<u8>> {
        let encoder = TextEncoder::new();
        let metrics = self.gather();
        let mut buffer = Vec::new();
        encoder.encode(&metrics, &mut buffer)?;
        Ok(buffer)
    }

    pub fn observe_scrape_duration(&self, collector: &str, duration_secs: f64) {
        self.agent_scrape_duration_seconds
            .with_label_values(&[collector])
            .set(duration_secs);
    }

    pub fn inc_error(&self, collector: &str) {
        self.agent_errors_total
            .with_label_values(&[collector])
            .inc();
    }
}
