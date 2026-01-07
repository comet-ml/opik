// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use std::collections::VecDeque;
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc, RwLock,
};

use serde::{Deserialize, Serialize};

#[derive(Default, Clone)]
pub struct StatusState {
    healthy: Arc<AtomicBool>,
    node_power_microwatts: Arc<AtomicU64>,
    cpu_package_power_watts: Arc<RwLock<Vec<PackagePower>>>,
    cpu_temperatures: Arc<RwLock<Vec<TemperatureReading>>>,
    gpu_status: Arc<RwLock<Vec<GpuStatus>>>,
    load_avg_1m: Arc<AtomicU64>,
    last_errors: Arc<RwLock<VecDeque<CollectorError>>>,
    last_scrape_unix_ms: Arc<AtomicU64>,
    host: Arc<RwLock<HostMetrics>>,
    disk_degraded: Arc<AtomicBool>,
    network_degraded: Arc<AtomicBool>,
    swap_degraded: Arc<AtomicBool>,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct StatusSnapshot {
    pub healthy: bool,
    pub load_avg_1m: f64,
    #[serde(default)]
    pub load_avg_5m: Option<f64>,
    #[serde(default)]
    pub load_avg_15m: Option<f64>,
    #[serde(default)]
    pub uptime_seconds: Option<u64>,
    pub last_scrape_unix_ms: u64,
    pub last_errors: Vec<CollectorError>,
    pub node_power_watts: Option<f64>,
    pub cpu_package_power_watts: Vec<PackagePower>,
    pub cpu_temperatures: Vec<TemperatureReading>,
    pub gpus: Vec<GpuStatus>,
    #[serde(default)]
    pub cpu_cores: Option<u64>,
    #[serde(default)]
    pub cpu_util_percent: Option<f64>,
    #[serde(default)]
    pub mem_total_bytes: Option<u64>,
    #[serde(default)]
    pub mem_used_bytes: Option<u64>,
    #[serde(default)]
    pub mem_free_bytes: Option<u64>,
    #[serde(default)]
    pub swap_used_bytes: Option<u64>,
    #[serde(default)]
    pub disk_root_total_bytes: Option<u64>,
    #[serde(default)]
    pub disk_root_used_bytes: Option<u64>,
    #[serde(default)]
    pub disk_root_io_time_ms: Option<u64>,
    #[serde(default)]
    pub primary_nic: Option<String>,
    #[serde(default)]
    pub net_rx_bytes_per_sec: Option<f64>,
    #[serde(default)]
    pub net_tx_bytes_per_sec: Option<f64>,
    #[serde(default)]
    pub net_drops_per_sec: Option<f64>,
    #[serde(default)]
    pub app_tokens_per_sec: Option<f64>,
    #[serde(default)]
    pub app_tokens_per_watt: Option<f64>,
    #[serde(default)]
    pub disk_degraded: bool,
    #[serde(default)]
    pub network_degraded: bool,
    #[serde(default)]
    pub swap_degraded: bool,
    #[serde(default)]
    pub degradation_score: u64,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct PackagePower {
    pub package: String,
    pub watts: f64,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct TemperatureReading {
    pub sensor: String,
    pub celsius: f64,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct CollectorError {
    pub collector: String,
    pub message: String,
    pub unix_ms: u64,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct GpuStatus {
    #[serde(default)]
    pub uuid: Option<String>,
    pub gpu: String,
    #[serde(default)]
    pub vendor: Option<GpuVendor>,
    #[serde(default)]
    pub capabilities: Option<GpuCapabilities>,
    #[serde(default)]
    pub identity: Option<GpuIdentity>,
    #[serde(default)]
    pub topo: Option<GpuTopo>,
    #[serde(default)]
    pub health: Option<GpuHealth>,
    #[serde(default)]
    pub nvlink: Option<NvLinkState>,
    #[serde(default)]
    pub fabric_links: Option<Vec<FabricLink>>,
    #[serde(default)]
    pub mig_tree: Option<MigTree>,
    pub temperature_celsius: Option<f64>,
    pub power_watts: Option<f64>,
    pub util_percent: Option<f64>,
    pub memory_total_bytes: Option<f64>,
    pub memory_used_bytes: Option<f64>,
    pub fan_percent: Option<f64>,
    pub clock_sm_mhz: Option<f64>,
    pub clock_mem_mhz: Option<f64>,
    pub thermal_throttle: bool,
    pub power_throttle: bool,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct GpuIdentity {
    #[serde(default)]
    pub pci_bus_id: Option<String>,
    #[serde(default)]
    pub pci_domain: Option<u32>,
    #[serde(default)]
    pub pci_bus: Option<u32>,
    #[serde(default)]
    pub pci_device: Option<u32>,
    #[serde(default)]
    pub pci_function: Option<u32>,
    #[serde(default)]
    pub pci_gen: Option<u32>,
    #[serde(default)]
    pub pci_link_width: Option<u32>,
    #[serde(default)]
    pub driver_version: Option<String>,
    #[serde(default)]
    pub nvml_version: Option<String>,
    #[serde(default)]
    pub cuda_driver_version: Option<i32>,
    #[serde(default)]
    pub device_id: Option<u32>,
    #[serde(default)]
    pub subsystem_id: Option<u32>,
    #[serde(default)]
    pub board_id: Option<u32>,
    #[serde(default)]
    pub numa_node: Option<i32>,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct GpuTopo {
    #[serde(default)]
    pub pci_link_gen: Option<u32>,
    #[serde(default)]
    pub pci_link_width: Option<u32>,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct GpuCapabilities {
    #[serde(default)]
    pub mig: bool,
    #[serde(default)]
    pub sriov: bool,
    #[serde(default)]
    pub mcm_tiles: bool,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct GpuHealth {
    #[serde(default)]
    pub pstate: Option<u32>,
    #[serde(default)]
    pub power_cap_reason: Option<String>,
    #[serde(default)]
    pub throttle_reasons: Vec<String>,
    #[serde(default)]
    pub ecc_mode: Option<String>,
    #[serde(default)]
    pub retired_pages: Option<u64>,
    #[serde(default)]
    pub last_xid: Option<i32>,
    #[serde(default)]
    pub encoder_util_percent: Option<f64>,
    #[serde(default)]
    pub decoder_util_percent: Option<f64>,
    #[serde(default)]
    pub copy_util_percent: Option<f64>,
    #[serde(default)]
    pub bar1_total_bytes: Option<u64>,
    #[serde(default)]
    pub bar1_used_bytes: Option<u64>,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct MigDeviceStatus {
    pub id: String,
    #[serde(default)]
    pub uuid: Option<String>,
    #[serde(default)]
    pub memory_total_bytes: Option<u64>,
    #[serde(default)]
    pub memory_used_bytes: Option<u64>,
    #[serde(default)]
    pub util_percent: Option<u32>,
    #[serde(default)]
    pub sm_count: Option<u32>,
    #[serde(default)]
    pub profile: Option<String>,
    #[serde(default)]
    pub placement: Option<String>,
    #[serde(default)]
    pub bar1_total_bytes: Option<u64>,
    #[serde(default)]
    pub bar1_used_bytes: Option<u64>,
    #[serde(default)]
    pub ecc_corrected: Option<u64>,
    #[serde(default)]
    pub ecc_uncorrected: Option<u64>,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct MigTree {
    #[serde(default)]
    pub supported: bool,
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub gpu_instances: Vec<GpuInstanceNode>,
    #[serde(default)]
    pub compute_instances: Vec<ComputeInstanceNode>,
    #[serde(default)]
    pub devices: Vec<MigDeviceStatus>,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct GpuInstanceNode {
    pub id: u32,
    #[serde(default)]
    pub profile_id: Option<u32>,
    #[serde(default)]
    pub placement: Option<String>,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct ComputeInstanceNode {
    pub gpu_instance_id: u32,
    pub id: u32,
    #[serde(default)]
    pub profile_id: Option<u32>,
    #[serde(default)]
    pub eng_profile_id: Option<u32>,
    #[serde(default)]
    pub placement: Option<String>,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct NvLinkState {
    #[serde(default)]
    pub links: Vec<NvLinkStats>,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct NvLinkStats {
    pub link: u32,
    #[serde(default)]
    pub rx_bytes: Option<u64>,
    #[serde(default)]
    pub tx_bytes: Option<u64>,
    #[serde(default)]
    pub errors: Option<u64>,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct FabricLink {
    pub link: u32,
    pub link_type: FabricLinkType,
    #[serde(default)]
    pub rx_bytes: Option<u64>,
    #[serde(default)]
    pub tx_bytes: Option<u64>,
    #[serde(default)]
    pub errors: Option<u64>,
}

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
pub enum FabricLinkType {
    NvLink,
    InfinityFabric,
    XeLink,
    #[default]
    Pcie,
}

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
pub enum GpuVendor {
    Nvidia,
    Amd,
    Intel,
    #[default]
    Unknown,
}

#[derive(Default, Clone, Serialize, Deserialize)]
pub struct HostMetrics {
    pub load_avg_5m: Option<f64>,
    pub load_avg_15m: Option<f64>,
    pub uptime_seconds: Option<u64>,
    pub cpu_cores: Option<u64>,
    pub cpu_util_percent: Option<f64>,
    pub mem_total_bytes: Option<u64>,
    pub mem_used_bytes: Option<u64>,
    pub mem_free_bytes: Option<u64>,
    pub swap_used_bytes: Option<u64>,
    pub disk_root_total_bytes: Option<u64>,
    pub disk_root_used_bytes: Option<u64>,
    pub disk_root_io_time_ms: Option<u64>,
    pub primary_nic: Option<String>,
    pub net_rx_bytes_per_sec: Option<f64>,
    pub net_tx_bytes_per_sec: Option<f64>,
    pub net_drops_per_sec: Option<f64>,
    pub app_tokens_per_sec: Option<f64>,
}

impl StatusState {
    pub fn new(healthy: Arc<AtomicBool>) -> Self {
        Self {
            healthy,
            node_power_microwatts: Arc::new(AtomicU64::new(0)),
            cpu_package_power_watts: Arc::new(RwLock::new(Vec::new())),
            cpu_temperatures: Arc::new(RwLock::new(Vec::new())),
            gpu_status: Arc::new(RwLock::new(Vec::new())),
            load_avg_1m: Arc::new(AtomicU64::new(0)),
            last_errors: Arc::new(RwLock::new(VecDeque::new())),
            last_scrape_unix_ms: Arc::new(AtomicU64::new(0)),
            host: Arc::new(RwLock::new(HostMetrics::default())),
            disk_degraded: Arc::new(AtomicBool::new(false)),
            network_degraded: Arc::new(AtomicBool::new(false)),
            swap_degraded: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn set_app_metrics(&self, tokens_per_sec: f64) {
        if let Ok(mut guard) = self.host.write() {
            guard.app_tokens_per_sec = Some(tokens_per_sec);
        }
    }

    #[must_use]
    pub fn snapshot(&self) -> StatusSnapshot {
        let host = self.host.read().map(|h| h.clone()).unwrap_or_default();
        StatusSnapshot {
            healthy: self.healthy.load(Ordering::Relaxed),
            load_avg_1m: self.load_avg_1m.load(Ordering::Relaxed) as f64 / 1000.0,
            load_avg_5m: host.load_avg_5m,
            load_avg_15m: host.load_avg_15m,
            uptime_seconds: host.uptime_seconds,
            last_scrape_unix_ms: self.last_scrape_unix_ms.load(Ordering::Relaxed),
            last_errors: self
                .last_errors
                .read()
                .map(|g| g.iter().cloned().collect())
                .unwrap_or_default(),
            node_power_watts: {
                let v = self.node_power_microwatts.load(Ordering::Relaxed);
                if v == 0 {
                    None
                } else {
                    Some(v as f64 / 1_000_000.0)
                }
            },
            cpu_package_power_watts: self
                .cpu_package_power_watts
                .read()
                .map(|g| g.clone())
                .unwrap_or_default(),
            cpu_temperatures: self
                .cpu_temperatures
                .read()
                .map(|g| g.clone())
                .unwrap_or_default(),
            gpus: self
                .gpu_status
                .read()
                .map(|g| g.clone())
                .unwrap_or_default(),
            cpu_cores: host.cpu_cores,
            cpu_util_percent: host.cpu_util_percent,
            mem_total_bytes: host.mem_total_bytes,
            mem_used_bytes: host.mem_used_bytes,
            mem_free_bytes: host.mem_free_bytes,
            swap_used_bytes: host.swap_used_bytes,
            disk_root_total_bytes: host.disk_root_total_bytes,
            disk_root_used_bytes: host.disk_root_used_bytes,
            disk_root_io_time_ms: host.disk_root_io_time_ms,
            primary_nic: host.primary_nic,
            net_rx_bytes_per_sec: host.net_rx_bytes_per_sec,
            net_tx_bytes_per_sec: host.net_tx_bytes_per_sec,
            net_drops_per_sec: host.net_drops_per_sec,
            app_tokens_per_sec: host.app_tokens_per_sec,
            app_tokens_per_watt: {
                if let (Some(tokens), Some(microwatts)) = (
                    host.app_tokens_per_sec,
                    Some(self.node_power_microwatts.load(Ordering::Relaxed)),
                ) {
                    if microwatts > 0 {
                        let watts = microwatts as f64 / 1_000_000.0;
                        Some(tokens / watts)
                    } else {
                        None
                    }
                } else {
                    None
                }
            },
            disk_degraded: self.disk_degraded.load(Ordering::Relaxed),
            network_degraded: self.network_degraded.load(Ordering::Relaxed),
            swap_degraded: self.swap_degraded.load(Ordering::Relaxed),
            degradation_score: {
                let mut score = 0u64;
                if self.disk_degraded.load(Ordering::Relaxed) {
                    score += 1;
                }
                if self.network_degraded.load(Ordering::Relaxed) {
                    score += 1;
                }
                if self.swap_degraded.load(Ordering::Relaxed) {
                    score += 1;
                }
                score
            },
        }
    }

    pub fn set_node_power(&self, watts: f64) {
        self.node_power_microwatts
            .store((watts * 1_000_000.0) as u64, Ordering::Relaxed);
    }

    pub fn set_load_avg(&self, load: f64) {
        self.load_avg_1m
            .store((load * 1000.0) as u64, Ordering::Relaxed);
    }

    pub fn set_last_scrape(&self, unix_ms: u64) {
        self.last_scrape_unix_ms.store(unix_ms, Ordering::Relaxed);
    }

    pub fn record_error(&self, collector: &str, message: String, unix_ms: u64) {
        if let Ok(mut guard) = self.last_errors.write() {
            guard.push_back(CollectorError {
                collector: collector.to_string(),
                message,
                unix_ms,
            });
            if guard.len() > 10 {
                let _ = guard.pop_front();
            }
        }
    }

    pub fn set_cpu_package_power(&self, package: String, watts: f64) {
        if let Ok(mut guard) = self.cpu_package_power_watts.write() {
            let mut updated = false;
            for p in guard.iter_mut() {
                if p.package == package {
                    p.watts = watts;
                    updated = true;
                    break;
                }
            }
            if !updated {
                guard.push(PackagePower { package, watts });
            }
        }
    }

    pub fn set_cpu_temperatures(&self, readings: Vec<TemperatureReading>) {
        if let Ok(mut guard) = self.cpu_temperatures.write() {
            *guard = readings;
        }
    }

    pub fn set_gpu_statuses(&self, statuses: Vec<GpuStatus>) {
        if let Ok(mut guard) = self.gpu_status.write() {
            *guard = statuses;
        }
    }

    pub fn set_cpu_summary(
        &self,
        cores: Option<u64>,
        util_percent: Option<f64>,
        load_1m: f64,
        load_5m: Option<f64>,
        load_15m: Option<f64>,
        uptime_seconds: Option<u64>,
    ) {
        self.load_avg_1m
            .store((load_1m * 1000.0) as u64, Ordering::Relaxed);
        if let Ok(mut guard) = self.host.write() {
            guard.cpu_cores = cores;
            guard.cpu_util_percent = util_percent;
            guard.load_avg_5m = load_5m;
            guard.load_avg_15m = load_15m;
            guard.uptime_seconds = uptime_seconds;
        }
    }

    pub fn set_memory_summary(
        &self,
        total_bytes: Option<u64>,
        used_bytes: Option<u64>,
        free_bytes: Option<u64>,
        swap_used_bytes: Option<u64>,
    ) {
        if let Ok(mut guard) = self.host.write() {
            guard.mem_total_bytes = total_bytes;
            guard.mem_used_bytes = used_bytes;
            guard.mem_free_bytes = free_bytes;
            guard.swap_used_bytes = swap_used_bytes;
        }
    }

    pub fn set_disk_summary(
        &self,
        total_bytes: Option<u64>,
        used_bytes: Option<u64>,
        io_time_ms: Option<u64>,
    ) {
        if let Ok(mut guard) = self.host.write() {
            guard.disk_root_total_bytes = total_bytes;
            guard.disk_root_used_bytes = used_bytes;
            guard.disk_root_io_time_ms = io_time_ms;
        }
    }

    pub fn set_network_summary(
        &self,
        primary_nic: Option<String>,
        rx_bytes_per_sec: Option<f64>,
        tx_bytes_per_sec: Option<f64>,
        drops_per_sec: Option<f64>,
    ) {
        if let Ok(mut guard) = self.host.write() {
            guard.primary_nic = primary_nic;
            guard.net_rx_bytes_per_sec = rx_bytes_per_sec;
            guard.net_tx_bytes_per_sec = tx_bytes_per_sec;
            guard.net_drops_per_sec = drops_per_sec;
        }
    }

    pub fn set_disk_degraded(&self, degraded: bool) {
        self.disk_degraded.store(degraded, Ordering::Relaxed);
    }

    pub fn set_network_degraded(&self, degraded: bool) {
        self.network_degraded.store(degraded, Ordering::Relaxed);
    }

    pub fn set_swap_degraded(&self, degraded: bool) {
        self.swap_degraded.store(degraded, Ordering::Relaxed);
    }

    pub fn update_degradation_score(&self, metrics: &crate::metrics::MetricsRegistry) {
        let mut score = 0.0;
        if self.disk_degraded.load(Ordering::Relaxed) {
            score += 1.0;
        }
        if self.network_degraded.load(Ordering::Relaxed) {
            score += 1.0;
        }
        if self.swap_degraded.load(Ordering::Relaxed) {
            score += 1.0;
        }
        metrics.degradation_score.set(score);
    }
}
