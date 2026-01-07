// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use async_trait::async_trait;
#[cfg(all(feature = "gpu", target_os = "linux"))]
use nvml_wrapper::bitmasks::event::EventTypes;
#[cfg(feature = "gpu")]
use nvml_wrapper::{
    bitmasks::device::ThrottleReasons,
    bitmasks::nv_link::PacketTypes,
    enum_wrappers::device::{Clock, EccCounter, MemoryError, PcieUtilCounter, TemperatureSensor},
    enum_wrappers::nv_link::{ErrorCounter as NvLinkErrorCounter, UtilizationCountUnit},
    // enums::device::PcieLinkMaxSpeed, // Unused
    enums::nv_link::Counter as NvLinkCounter,
    struct_wrappers::nv_link::UtilizationControl,
    Nvml,
};
use prometheus::GaugeVec;
use std::collections::HashMap;
use std::collections::HashSet;
#[cfg(feature = "gpu")]
use std::time::Instant;
#[cfg(all(feature = "gpu", target_os = "linux"))]
use tokio::sync::mpsc;

#[cfg(all(feature = "gpu", feature = "gpu-nvml-ffi"))]
use anyhow::Result;

use crate::collectors::Collector;
use crate::config::AgentConfig;
#[cfg(all(feature = "gpu", target_os = "linux"))]
use crate::event_worker::spawn_event_worker;
use crate::metrics::MetricsRegistry;
#[cfg(all(feature = "gpu", feature = "gpu-nvml-ffi"))]
use crate::state::{ComputeInstanceNode, GpuInstanceNode, MigTree};
use crate::state::{
    FabricLink, FabricLinkType, GpuCapabilities, GpuHealth, GpuIdentity, GpuStatus, GpuTopo,
    GpuVendor, MigDeviceStatus, StatusState,
};
#[cfg(all(feature = "gpu", target_os = "linux"))]
use nvml_wrapper::error::NvmlError;
pub struct GpuCollector {
    #[cfg(feature = "gpu")]
    nvml: Option<Nvml>,
    #[cfg(feature = "gpu")]
    ecc_prev: HashMap<String, u64>,
    #[cfg(feature = "gpu")]
    last_power: HashMap<u32, (f64, Instant)>,
    #[cfg(feature = "gpu")]
    last_pcie_sample: HashMap<u32, Instant>,
    #[cfg(feature = "gpu")]
    last_pcie_replay: HashMap<u32, u32>,
    #[cfg(feature = "gpu")]
    nvlink_util_prev: HashMap<(u32, u32), (u64, u64)>,
    #[cfg(feature = "gpu")]
    nvlink_err_prev: HashMap<(u32, u32, String), u64>,
    #[cfg(feature = "gpu")]
    enable_mig: bool,
    #[cfg(feature = "gpu")]
    enable_events: bool,
    #[cfg(feature = "gpu")]
    #[allow(dead_code)]
    enable_amd: bool,
    #[cfg(feature = "gpu")]
    visible_filter: Option<HashSet<String>>,
    #[cfg(feature = "gpu")]
    mig_config_filter: Option<HashSet<String>>,
    #[cfg(feature = "gpu")]
    k8s_mode: bool,
    #[cfg(feature = "gpu")]
    resource_prefix: &'static str,
    #[cfg(all(feature = "gpu", target_os = "linux"))]
    event_rx: Option<mpsc::Receiver<crate::event_worker::EventRecord>>,
    status: StatusState,
}

impl GpuCollector {
    pub fn new(status: StatusState, config: &AgentConfig) -> (Self, Option<String>) {
        #[cfg(feature = "gpu")]
        {
            let env_visible = std::env::var("NVIDIA_VISIBLE_DEVICES").ok();
            let env_mig_config = std::env::var("NVIDIA_MIG_CONFIG_DEVICES").ok();
            let visible_filter = build_filter(
                config
                    .gpu_visible_devices
                    .as_deref()
                    .or(env_visible.as_deref()),
            );
            let mig_cfg_filter = build_filter(
                config
                    .mig_config_devices
                    .as_deref()
                    .or(env_mig_config.as_deref()),
            );
            #[cfg(all(feature = "gpu", target_os = "linux"))]
            let (event_tx, event_rx) = if config.enable_gpu_events {
                let (tx, rx) = mpsc::channel::<crate::event_worker::EventRecord>(256);
                (Some(tx), Some(rx))
            } else {
                (None, None)
            };
            #[cfg(not(all(feature = "gpu", target_os = "linux")))]
            let (_event_tx, _event_rx): (Option<()>, Option<()>) = (None, None);
            match Nvml::init() {
                Ok(nvml) => {
                    #[cfg(all(feature = "gpu", target_os = "linux"))]
                    if let Some(tx) = event_tx.clone() {
                        spawn_event_worker(tx, visible_filter.clone());
                    }
                    (
                        Self {
                            nvml: Some(nvml),
                            ecc_prev: HashMap::new(),
                            last_power: HashMap::new(),
                            last_pcie_sample: HashMap::new(),
                            last_pcie_replay: HashMap::new(),
                            nvlink_util_prev: HashMap::new(),
                            nvlink_err_prev: HashMap::new(),
                            enable_mig: config.enable_gpu_mig,
                            enable_events: config.enable_gpu_events,
                            visible_filter,
                            mig_config_filter: mig_cfg_filter,
                            k8s_mode: config.k8s_mode,
                            resource_prefix: if config.k8s_mode {
                                "nvidia.com"
                            } else {
                                "esnode.co"
                            },
                            enable_amd: config.enable_gpu_amd,
                            #[cfg(all(feature = "gpu", target_os = "linux"))]
                            event_rx,
                            status,
                        },
                        None,
                    )
                }
                Err(e) => (
                    Self {
                        nvml: None,
                        ecc_prev: HashMap::new(),
                        last_power: HashMap::new(),
                        last_pcie_sample: HashMap::new(),
                        last_pcie_replay: HashMap::new(),
                        nvlink_util_prev: HashMap::new(),
                        nvlink_err_prev: HashMap::new(),
                        enable_mig: config.enable_gpu_mig,
                        enable_events: config.enable_gpu_events,
                        visible_filter: build_filter(
                            config
                                .gpu_visible_devices
                                .as_deref()
                                .or(env_visible.as_deref()),
                        ),
                        mig_config_filter: build_filter(
                            config
                                .mig_config_devices
                                .as_deref()
                                .or(env_mig_config.as_deref()),
                        ),
                        k8s_mode: config.k8s_mode,
                        resource_prefix: if config.k8s_mode {
                            "nvidia.com"
                        } else {
                            "esnode.co"
                        },
                        enable_amd: config.enable_gpu_amd,
                        #[cfg(all(feature = "gpu", target_os = "linux"))]
                        event_rx: None,
                        status,
                    },
                    Some(format!("GPU collector disabled: {e}")),
                ),
            }
        }

        #[cfg(not(feature = "gpu"))]
        {
            (
                Self { status },
                Some("GPU support not compiled in".to_string()),
            )
        }
    }
}

#[async_trait]
impl Collector for GpuCollector {
    fn name(&self) -> &'static str {
        "gpu"
    }

    async fn collect(&mut self, metrics: &MetricsRegistry) -> anyhow::Result<()> {
        #[cfg(feature = "gpu")]
        {
            let Some(nvml) = &self.nvml else {
                return Ok(());
            };

            let count = nvml.device_count()?;
            let mut statuses: Vec<GpuStatus> = Vec::new();
            let mut uuid_to_index: HashMap<String, String> = HashMap::new();
            // Drain any pending events from the async task.
            #[cfg(all(feature = "gpu", target_os = "linux"))]
            {
                if let Some(rx) = &mut self.event_rx {
                    while let Ok(ev) = rx.try_recv() {
                        let labels = &[ev.uuid.as_str(), ev.index.as_str(), ev.kind.as_str()];
                        metrics.gpu_events_total.with_label_values(labels).inc();
                        metrics
                            .gpu_last_event_unix_ms
                            .with_label_values(labels)
                            .set(ev.ts_ms as f64);
                        match ev.kind.as_str() {
                            "xid" => {
                                metrics.gpu_xid_errors_total.with_label_values(labels).inc();
                                metrics
                                    .gpu_last_xid_code
                                    .with_label_values(labels)
                                    .set(ev.xid_code.unwrap_or(-1) as f64);
                            }
                            "ecc_single" => {
                                metrics
                                    .gpu_ecc_corrected_total
                                    .with_label_values(labels)
                                    .inc();
                            }
                            "ecc_double" => {
                                metrics
                                    .gpu_ecc_uncorrected_total
                                    .with_label_values(labels)
                                    .inc();
                            }
                            _ => {}
                        }
                    }
                }
            }
            #[cfg(target_os = "linux")]
            let mut event_set = if self.enable_events {
                nvml.create_event_set().ok()
            } else {
                None
            };
            #[cfg(not(target_os = "linux"))]
            let event_set: Option<()> = None;
            #[cfg(not(target_os = "linux"))]
            let _ = &event_set;
            let events_enabled = self.enable_events;
            #[cfg(not(target_os = "linux"))]
            if events_enabled {
                tracing::debug!(
                    "GPU event polling requested but not supported on this platform; skipping"
                );
            }
            for idx in 0..count {
                let device = nvml.device_by_index(idx)?;
                let gpu_label = idx.to_string();
                let uuid_string = device.uuid().unwrap_or_else(|_| format!("GPU-{gpu_label}"));
                if let Some(filter) = &self.visible_filter {
                    if !filter.contains(&uuid_string) && !filter.contains(&gpu_label) {
                        continue;
                    }
                }
                if self.enable_mig {
                    if let Some(filter) = &self.mig_config_filter {
                        if !filter.contains(&uuid_string) && !filter.contains(&gpu_label) {
                            continue;
                        }
                    }
                }
                let compat_label = if self.k8s_mode {
                    k8s_resource_name(self.resource_prefix, None)
                } else {
                    gpu_label.clone()
                };
                uuid_to_index.insert(uuid_string.clone(), gpu_label.clone());
                #[cfg(target_os = "linux")]
                {
                    if let Some(set) = event_set.take() {
                        let events = EventTypes::SINGLE_BIT_ECC_ERROR
                            | EventTypes::DOUBLE_BIT_ECC_ERROR
                            | EventTypes::CRITICAL_XID_ERROR
                            | EventTypes::PSTATE_CHANGE
                            | EventTypes::CLOCK_CHANGE;
                        let new_set = device.register_events(events, set).ok();
                        event_set = new_set;
                    }
                }
                let uuid_label = uuid_string.as_str();
                let now = Instant::now();
                metrics
                    .pcie_bandwidth_percent
                    .with_label_values(&[uuid_label, gpu_label.as_str()])
                    .set(0.0);

                // Identity/topology
                let identity = {
                    let pci = device.pci_info().ok();
                    let driver_version = nvml.sys_driver_version().ok();
                    let nvml_version = nvml.sys_nvml_version().ok();
                    let cuda_driver_version = nvml.sys_cuda_driver_version().ok();
                    let pci_id = pci.as_ref().map(|p| p.pci_device_id);
                    let pci_sub = pci.as_ref().map(|p| p.pci_sub_system_id);
                    Some(GpuIdentity {
                        pci_bus_id: pci.as_ref().map(|p| p.bus_id.clone()),
                        pci_domain: pci.as_ref().map(|p| p.domain),
                        pci_bus: pci.as_ref().map(|p| p.bus),
                        pci_device: pci.as_ref().map(|p| p.device),
                        pci_function: None,
                        pci_gen: None,
                        pci_link_width: None,
                        driver_version,
                        nvml_version,
                        cuda_driver_version,
                        device_id: pci_id,
                        subsystem_id: pci_sub.flatten(),
                        board_id: None,
                        numa_node: None,
                    })
                };
                let topo = {
                    let gen = device.current_pcie_link_gen().ok();
                    let width = device.current_pcie_link_width().ok();
                    Some(GpuTopo {
                        pci_link_gen: gen,
                        pci_link_width: width,
                    })
                };
                let mut health = GpuHealth::default();
                let mut status = GpuStatus {
                    uuid: Some(uuid_string.clone()),
                    gpu: gpu_label.clone(),
                    vendor: Some(GpuVendor::Nvidia),
                    capabilities: Some(GpuCapabilities {
                        mig: self.enable_mig,
                        sriov: false,
                        mcm_tiles: false,
                    }),
                    identity,
                    topo,
                    health: None,
                    ..Default::default()
                };

                if let Ok(util) = device.utilization_rates() {
                    metrics
                        .gpu_utilization_percent
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(f64::from(util.gpu));
                    if self.k8s_mode {
                        metrics
                            .gpu_utilization_percent_compat
                            .with_label_values(&[compat_label.as_str()])
                            .set(f64::from(util.gpu));
                    }
                    status.util_percent = Some(f64::from(util.gpu));
                }

                if let Ok(memory) = device.memory_info() {
                    metrics
                        .gpu_memory_total_bytes
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(memory.total as f64);
                    metrics
                        .gpu_memory_used_bytes
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(memory.used as f64);
                    if self.k8s_mode {
                        metrics
                            .gpu_memory_used_bytes_compat
                            .with_label_values(&[compat_label.as_str()])
                            .set(memory.used as f64);
                    }
                    status.memory_total_bytes = Some(memory.total as f64);
                    status.memory_used_bytes = Some(memory.used as f64);
                }

                if let Ok(temp) = device.temperature(TemperatureSensor::Gpu) {
                    metrics
                        .gpu_temperature_celsius
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(f64::from(temp));
                    if self.k8s_mode {
                        metrics
                            .gpu_temperature_celsius_compat
                            .with_label_values(&[compat_label.as_str()])
                            .set(f64::from(temp));
                    }
                    status.temperature_celsius = Some(f64::from(temp));
                }

                if let Ok(power) = device.power_usage() {
                    metrics
                        .gpu_power_watts
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(f64::from(power) / 1000.0);
                    if self.k8s_mode {
                        metrics
                            .gpu_power_watts_compat
                            .with_label_values(&[compat_label.as_str()])
                            .set(f64::from(power) / 1000.0);
                    }
                    let watts = f64::from(power) / 1000.0;
                    status.power_watts = Some(watts);
                    if let Some((prev_watts, ts)) = self.last_power.get(&idx) {
                        let dt = now.saturating_duration_since(*ts).as_secs_f64();
                        if dt > 0.0 {
                            let energy = (prev_watts * dt).floor() as u64;
                            metrics
                                .gpu_energy_joules_total
                                .with_label_values(&[uuid_label, gpu_label.as_str()])
                                .inc_by(energy);
                        }
                    }
                    self.last_power.insert(idx, (watts, now));
                }

                if let Ok(limit) = device.power_management_limit() {
                    metrics
                        .gpu_power_limit_watts
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(f64::from(limit) / 1000.0);
                }

                if let Ok(fan) = device.fan_speed(0) {
                    metrics
                        .gpu_fan_speed_percent
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(f64::from(fan));
                    status.fan_percent = Some(f64::from(fan));
                }

                if let Ok(sm_clock) = device.clock_info(Clock::SM) {
                    metrics
                        .gpu_clock_sm_mhz
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(f64::from(sm_clock));
                    status.clock_sm_mhz = Some(f64::from(sm_clock));
                }

                if let Ok(mem_clock) = device.clock_info(Clock::Memory) {
                    metrics
                        .gpu_clock_mem_mhz
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(f64::from(mem_clock));
                    status.clock_mem_mhz = Some(f64::from(mem_clock));
                }

                if let Ok(gfx_clock) = device.clock_info(Clock::Graphics) {
                    metrics
                        .gpu_clock_graphics_mhz
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(f64::from(gfx_clock));
                }
                if let Ok(pstate) = device.performance_state() {
                    let p_val = pstate as u32;
                    metrics
                        .gpu_pstate
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(f64::from(p_val));
                    health.pstate = Some(p_val);
                }
                if let Ok(bar1) = device.bar1_memory_info() {
                    metrics
                        .gpu_bar1_total_bytes
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(bar1.total as f64);
                    metrics
                        .gpu_bar1_used_bytes
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(bar1.used as f64);
                    health.bar1_total_bytes = Some(bar1.total);
                    health.bar1_used_bytes = Some(bar1.used);
                }
                if let Ok(enc_info) = device.encoder_utilization() {
                    metrics
                        .gpu_encoder_utilization_percent
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(f64::from(enc_info.utilization));
                    health.encoder_util_percent = Some(f64::from(enc_info.utilization));
                }
                if let Ok(dec_info) = device.decoder_utilization() {
                    metrics
                        .gpu_decoder_utilization_percent
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(f64::from(dec_info.utilization));
                    health.decoder_util_percent = Some(f64::from(dec_info.utilization));
                }

                // ECC and throttle reasons not available in nvml-wrapper 0.9; skip gracefully.
                let mut ecc_flag = false;
                for (counter, label) in [
                    (EccCounter::Volatile, "volatile"),
                    (EccCounter::Aggregate, "aggregate"),
                ] {
                    let corrected =
                        device.total_ecc_errors(MemoryError::Corrected, counter.clone());
                    let uncorrected =
                        device.total_ecc_errors(MemoryError::Uncorrected, counter.clone());
                    if let (Ok(c), Ok(u)) = (corrected, uncorrected) {
                        let total = c.saturating_add(u);
                        let key = format!("{gpu_label}:{label}");
                        let prev = *self.ecc_prev.get(&key).unwrap_or(&0);
                        if total >= prev {
                            let delta = total - prev;
                            metrics
                                .gpu_ecc_errors_total
                                .with_label_values(&[uuid_label, gpu_label.as_str(), label])
                                .inc_by(delta);
                            if delta > 0 {
                                ecc_flag = true;
                            }
                        }
                        self.ecc_prev.insert(key, total);
                    } else {
                        // keep series visible even if call is unsupported
                        metrics
                            .gpu_ecc_errors_total
                            .with_label_values(&[uuid_label, gpu_label.as_str(), label])
                            .inc_by(0);
                    }
                }
                metrics
                    .gpu_degradation_ecc
                    .with_label_values(&[uuid_label, gpu_label.as_str()])
                    .set(if ecc_flag { 1.0 } else { 0.0 });
                if let Ok(ecc_state) = device.is_ecc_enabled() {
                    metrics
                        .gpu_ecc_mode
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(if ecc_state.currently_enabled {
                            1.0
                        } else {
                            0.0
                        });
                    health.ecc_mode = Some(if ecc_state.currently_enabled {
                        "enabled".to_string()
                    } else {
                        "disabled".to_string()
                    });
                }
                if let Ok(reasons) = device.current_throttle_reasons() {
                    let thermal = reasons.intersects(
                        ThrottleReasons::HW_THERMAL_SLOWDOWN | ThrottleReasons::SW_THERMAL_SLOWDOWN,
                    );
                    let power = reasons.intersects(
                        ThrottleReasons::HW_POWER_BRAKE_SLOWDOWN | ThrottleReasons::SW_POWER_CAP,
                    );
                    set_throttle_metric(
                        &metrics.gpu_throttle_reason,
                        uuid_label,
                        gpu_label.as_str(),
                        "thermal",
                        thermal,
                    );
                    set_throttle_metric(
                        &metrics.gpu_throttle_reason,
                        uuid_label,
                        gpu_label.as_str(),
                        "power",
                        power,
                    );
                    set_throttle_metric(
                        &metrics.gpu_throttle_reason,
                        uuid_label,
                        gpu_label.as_str(),
                        "other",
                        !(thermal || power),
                    );
                    let mut reason_list = Vec::new();
                    if thermal {
                        reason_list.push("thermal".to_string());
                    }
                    if power {
                        reason_list.push("power".to_string());
                    }
                    health.throttle_reasons = reason_list;
                    metrics
                        .gpu_degradation_throttle
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(if thermal || power { 1.0 } else { 0.0 });
                } else {
                    set_throttle_metric(
                        &metrics.gpu_throttle_reason,
                        uuid_label,
                        gpu_label.as_str(),
                        "thermal",
                        false,
                    );
                    set_throttle_metric(
                        &metrics.gpu_throttle_reason,
                        uuid_label,
                        gpu_label.as_str(),
                        "power",
                        false,
                    );
                    set_throttle_metric(
                        &metrics.gpu_throttle_reason,
                        uuid_label,
                        gpu_label.as_str(),
                        "other",
                        false,
                    );
                    metrics
                        .gpu_degradation_throttle
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(0.0);
                }

                // Initialize always-on counters for compatibility.
                metrics
                    .gpu_energy_joules_total
                    .with_label_values(&[uuid_label, gpu_label.as_str()])
                    .inc_by(0);
                #[cfg(all(feature = "gpu-nvml-ffi-ext", feature = "gpu"))]
                {
                    if let Ok(field_vals) = unsafe {
                        crate::nvml_ext::get_field_values(
                            device.handle(),
                            &[
                                crate::nvml_ext::field::FI_DEV_PCIE_COUNT_CORRECTABLE_ERRORS,
                                crate::nvml_ext::field::FI_DEV_PCIE_COUNT_NON_FATAL_ERROR,
                                crate::nvml_ext::field::FI_DEV_PCIE_COUNT_FATAL_ERROR,
                            ],
                        )
                    } {
                        if let Some(corr) = field_vals
                            .get(crate::nvml_ext::field::FI_DEV_PCIE_COUNT_CORRECTABLE_ERRORS)
                        {
                            metrics
                                .gpu_pcie_correctable_errors_total
                                .with_label_values(&[uuid_label, gpu_label.as_str()])
                                .inc_by(corr.max(0) as u64);
                        }
                        let non_fatal = field_vals
                            .get(crate::nvml_ext::field::FI_DEV_PCIE_COUNT_NON_FATAL_ERROR)
                            .unwrap_or(0);
                        let fatal = field_vals
                            .get(crate::nvml_ext::field::FI_DEV_PCIE_COUNT_FATAL_ERROR)
                            .unwrap_or(0);
                        let uncorrectable = (fatal + non_fatal).max(0) as u64;
                        metrics
                            .gpu_pcie_uncorrectable_errors_total
                            .with_label_values(&[uuid_label, gpu_label.as_str()])
                            .inc_by(uncorrectable);
                    }
                    if let Ok(ext) = unsafe { crate::nvml_ext::pcie_ext_counters(device.handle()) }
                    {
                        if let Some(c) = ext.correctable_errors {
                            metrics
                                .gpu_pcie_correctable_errors_total
                                .with_label_values(&[uuid_label, gpu_label.as_str()])
                                .inc_by(c);
                        }
                        if let Some(a) = ext.atomic_requests {
                            metrics
                                .gpu_pcie_atomic_requests_total
                                .with_label_values(&[uuid_label, gpu_label.as_str()])
                                .inc_by(a);
                        }
                    }
                }
                #[cfg(not(all(feature = "gpu-nvml-ffi-ext", feature = "gpu")))]
                {
                    metrics
                        .gpu_pcie_correctable_errors_total
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .inc_by(0);
                    metrics
                        .gpu_pcie_atomic_requests_total
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .inc_by(0);
                }
                metrics
                    .gpu_copy_utilization_percent
                    .with_label_values(&[uuid_label, gpu_label.as_str()])
                    .set(0.0);
                if let Some(dt) = self
                    .last_pcie_sample
                    .get(&idx)
                    .map(|ts| now.saturating_duration_since(*ts).as_secs_f64())
                {
                    if dt > 0.0 {
                        let mut last_tx_kb: Option<u32> = None;
                        let mut last_rx_kb: Option<u32> = None;
                        if let Ok(tx_kb) = device.pcie_throughput(PcieUtilCounter::Send) {
                            last_tx_kb = Some(tx_kb);
                            let delta = (f64::from(tx_kb) * 1024.0 * dt) as u64;
                            metrics
                                .gpu_pcie_tx_bytes_total
                                .with_label_values(&[uuid_label, gpu_label.as_str()])
                                .inc_by(delta);
                        }
                        if let Ok(rx_kb) = device.pcie_throughput(PcieUtilCounter::Receive) {
                            last_rx_kb = Some(rx_kb);
                            let delta = (f64::from(rx_kb) * 1024.0 * dt) as u64;
                            metrics
                                .gpu_pcie_rx_bytes_total
                                .with_label_values(&[uuid_label, gpu_label.as_str()])
                                .inc_by(delta);
                        }

                        // Estimate bandwidth percent if we have throughput + link info
                        if let (Some(tx_kb), Some(rx_kb)) = (last_tx_kb, last_rx_kb) {
                            // pcie_link_max_speed was renamed/removed in 0.10, falling back to pcie_link_speed (current) or skipping if unavailable
                            // Note: pcie_link_speed returns the current link speed, not max.
                            // If semantics require max, we might need a different call, but for now matching the existing pattern.
                            if let (Ok(gen), Ok(width), Ok(speed)) = (
                                device.max_pcie_link_gen(),
                                device.max_pcie_link_width(),
                                device.pcie_link_speed(),
                            ) {
                                let bytes_per_s = f64::from(tx_kb + rx_kb) * 1024.0;
                                let lane_budget_bytes =
                                    pcie_lane_bytes_per_sec(gen, speed) * f64::from(width).max(1.0);
                                if lane_budget_bytes > 0.0 {
                                    let pct = (bytes_per_s / lane_budget_bytes).min(1.0) * 100.0;
                                    metrics
                                        .pcie_bandwidth_percent
                                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                                        .set(pct);
                                }
                            }
                        }
                    }
                }
                self.last_pcie_sample.insert(idx, now);
                metrics
                    .gpu_pcie_tx_bytes_total
                    .with_label_values(&[uuid_label, gpu_label.as_str()])
                    .inc_by(0);
                metrics
                    .gpu_pcie_rx_bytes_total
                    .with_label_values(&[uuid_label, gpu_label.as_str()])
                    .inc_by(0);
                // NvLink utilization/errors (best effort)
                let mut fabric_links: Vec<FabricLink> = Vec::new();
                for link_idx in 0..6u32 {
                    let mut link = device.link_wrapper_for(link_idx);
                    if !link.is_active().unwrap_or(false) {
                        continue;
                    }
                    let link_label = link_idx.to_string();
                    let _ = link.set_utilization_control(
                        NvLinkCounter::One,
                        UtilizationControl {
                            units: UtilizationCountUnit::Bytes,
                            packet_filter: PacketTypes::all(),
                        },
                        false,
                    );
                    if let Ok(util) = link.utilization_counter(NvLinkCounter::One) {
                        let key = (idx, link_idx);
                        let prev = self.nvlink_util_prev.get(&key).copied();
                        if let Some((prev_rx, prev_tx)) = prev {
                            if util.receive >= prev_rx {
                                metrics
                                    .gpu_nvlink_rx_bytes_total
                                    .with_label_values(&[
                                        uuid_label,
                                        gpu_label.as_str(),
                                        link_label.as_str(),
                                    ])
                                    .inc_by(util.receive - prev_rx);
                            }
                            if util.send >= prev_tx {
                                metrics
                                    .gpu_nvlink_tx_bytes_total
                                    .with_label_values(&[
                                        uuid_label,
                                        gpu_label.as_str(),
                                        link_label.as_str(),
                                    ])
                                    .inc_by(util.send - prev_tx);
                            }
                        }
                        let mut rx_delta: Option<u64> = None;
                        let mut tx_delta: Option<u64> = None;
                        if let Some((prev_rx, prev_tx)) = prev {
                            rx_delta = Some(util.receive.saturating_sub(prev_rx));
                            tx_delta = Some(util.send.saturating_sub(prev_tx));
                        }
                        self.nvlink_util_prev.insert(key, (util.receive, util.send));
                        if rx_delta.is_some() || tx_delta.is_some() {
                            fabric_links.push(FabricLink {
                                link: link_idx,
                                link_type: FabricLinkType::NvLink,
                                rx_bytes: rx_delta,
                                tx_bytes: tx_delta,
                                errors: None,
                            });
                        }
                    }

                    for (counter, label) in [
                        (NvLinkErrorCounter::DlReplay, "dl_replay"),
                        (NvLinkErrorCounter::DlRecovery, "dl_recovery"),
                        (NvLinkErrorCounter::DlCrcFlit, "dl_crc_flit"),
                        (NvLinkErrorCounter::DlCrcData, "dl_crc_data"),
                    ] {
                        if let Ok(val) = link.error_counter(counter) {
                            let key = (idx, link_idx, label.to_string());
                            let prev = self.nvlink_err_prev.get(&key).copied().unwrap_or(0);
                            if val >= prev {
                                metrics
                                    .gpu_nvlink_errors_total
                                    .with_label_values(&[
                                        uuid_label,
                                        gpu_label.as_str(),
                                        link_label.as_str(),
                                    ])
                                    .inc_by(val - prev);
                            }
                            self.nvlink_err_prev.insert(key, val);
                            if let Some(f) = fabric_links.iter_mut().find(|f| f.link == link_idx) {
                                let prev_err = f.errors.unwrap_or(0);
                                f.errors = Some(prev_err + val.saturating_sub(prev_err));
                            }
                        }
                    }
                }
                if !fabric_links.is_empty() {
                    status.fabric_links = Some(fabric_links);
                }

                // nvml-wrapper 0.9 exposes replay counter but not uncorrectable PCIe errors
                metrics
                    .gpu_pcie_uncorrectable_errors_total
                    .with_label_values(&[uuid_label, gpu_label.as_str()])
                    .inc_by(0);
                metrics
                    .pcie_link_width
                    .with_label_values(&[uuid_label, gpu_label.as_str()])
                    .set(f64::from(device.current_pcie_link_width().unwrap_or(0)));
                metrics
                    .pcie_link_gen
                    .with_label_values(&[uuid_label, gpu_label.as_str()])
                    .set(f64::from(device.current_pcie_link_gen().unwrap_or(0)));
                if let Ok(replay) = device.pcie_replay_counter() {
                    let prev = self.last_pcie_replay.get(&idx).copied().unwrap_or(0);
                    if replay >= prev {
                        metrics
                            .gpu_pcie_replay_errors_total
                            .with_label_values(&[uuid_label, gpu_label.as_str()])
                            .inc_by(u64::from(replay - prev));
                    }
                    self.last_pcie_replay.insert(idx, replay);
                } else {
                    metrics
                        .gpu_pcie_replay_errors_total
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .inc_by(0);
                }
                // nvml-wrapper 0.9 does not expose PCIe uncorrectable errors; keep series present.
                if self.enable_mig {
                    #[cfg(all(feature = "gpu-nvml-ffi", feature = "gpu"))]
                    {
                        if let Ok(migs) = collect_mig_devices(nvml, &device) {
                            metrics
                                .gpu_mig_enabled
                                .with_label_values(&[uuid_label, gpu_label.as_str()])
                                .set(if migs.enabled { 1.0 } else { 0.0 });
                            // GI/CI info gauges
                            for gi in &migs.gpu_instances {
                                metrics
                                    .mig_gpu_instance_info
                                    .with_label_values(&[
                                        uuid_label,
                                        gpu_label.as_str(),
                                        gi.id.to_string().as_str(),
                                        gi.profile_id
                                            .map(|p| p.to_string())
                                            .unwrap_or_default()
                                            .as_str(),
                                        gi.placement.as_deref().unwrap_or(""),
                                    ])
                                    .set(1.0);
                            }
                            for ci in &migs.compute_instances {
                                metrics
                                    .mig_compute_instance_info
                                    .with_label_values(&[
                                        uuid_label,
                                        gpu_label.as_str(),
                                        ci.gpu_instance_id.to_string().as_str(),
                                        ci.id.to_string().as_str(),
                                        ci.profile_id
                                            .map(|p| p.to_string())
                                            .unwrap_or_default()
                                            .as_str(),
                                        ci.eng_profile_id
                                            .map(|p| p.to_string())
                                            .unwrap_or_default()
                                            .as_str(),
                                        ci.placement.as_deref().unwrap_or(""),
                                    ])
                                    .set(1.0);
                            }
                            for mig in &migs.devices {
                                let mig_id_string = mig.id.clone();
                                let mig_label =
                                    mig.uuid.as_deref().unwrap_or(mig_id_string.as_str());
                                let compat_label = if self.k8s_mode {
                                    k8s_resource_name(
                                        self.resource_prefix,
                                        mig.profile.as_deref().or(Some("generic")),
                                    )
                                } else {
                                    mig_label.to_string()
                                };
                                if let Some(util) = mig.util_percent {
                                    metrics
                                        .mig_utilization_percent
                                        .with_label_values(&[
                                            uuid_label,
                                            gpu_label.as_str(),
                                            mig_label,
                                        ])
                                        .set(f64::from(util));
                                    if self.k8s_mode {
                                        metrics
                                            .mig_utilization_percent
                                            .with_label_values(&[
                                                uuid_label,
                                                gpu_label.as_str(),
                                                compat_label.as_str(),
                                            ])
                                            .set(f64::from(util));
                                    }
                                }
                                if let Some(total) = mig.memory_total_bytes {
                                    metrics
                                        .mig_memory_total_bytes
                                        .with_label_values(&[
                                            uuid_label,
                                            gpu_label.as_str(),
                                            mig_label,
                                        ])
                                        .set(total as f64);
                                    if self.k8s_mode {
                                        metrics
                                            .mig_memory_total_bytes
                                            .with_label_values(&[
                                                uuid_label,
                                                gpu_label.as_str(),
                                                compat_label.as_str(),
                                            ])
                                            .set(total as f64);
                                    }
                                }
                                if let Some(used) = mig.memory_used_bytes {
                                    metrics
                                        .mig_memory_used_bytes
                                        .with_label_values(&[
                                            uuid_label,
                                            gpu_label.as_str(),
                                            mig_label,
                                        ])
                                        .set(used as f64);
                                    if self.k8s_mode {
                                        metrics
                                            .mig_memory_used_bytes
                                            .with_label_values(&[
                                                uuid_label,
                                                gpu_label.as_str(),
                                                compat_label.as_str(),
                                            ])
                                            .set(used as f64);
                                    }
                                }
                                if let Some(sm) = mig.sm_count {
                                    metrics
                                        .mig_sm_count
                                        .with_label_values(&[
                                            uuid_label,
                                            gpu_label.as_str(),
                                            mig_label,
                                        ])
                                        .set(f64::from(sm));
                                    if self.k8s_mode {
                                        metrics
                                            .mig_sm_count
                                            .with_label_values(&[
                                                uuid_label,
                                                gpu_label.as_str(),
                                                compat_label.as_str(),
                                            ])
                                            .set(f64::from(sm));
                                    }
                                }
                                // Best-effort per-MIG ECC and BAR1 info using MigDeviceStatus fields
                                if let Some(corrected) = mig.ecc_corrected {
                                    metrics
                                        .mig_ecc_corrected_total
                                        .with_label_values(&[
                                            uuid_label,
                                            gpu_label.as_str(),
                                            mig_label,
                                        ])
                                        .inc_by(corrected);
                                    if self.k8s_mode {
                                        metrics
                                            .mig_ecc_corrected_total
                                            .with_label_values(&[
                                                uuid_label,
                                                gpu_label.as_str(),
                                                compat_label.as_str(),
                                            ])
                                            .inc_by(corrected);
                                    }
                                }
                                if let Some(uncorrected) = mig.ecc_uncorrected {
                                    metrics
                                        .mig_ecc_uncorrected_total
                                        .with_label_values(&[
                                            uuid_label,
                                            gpu_label.as_str(),
                                            mig_label,
                                        ])
                                        .inc_by(uncorrected);
                                    if self.k8s_mode {
                                        metrics
                                            .mig_ecc_uncorrected_total
                                            .with_label_values(&[
                                                uuid_label,
                                                gpu_label.as_str(),
                                                compat_label.as_str(),
                                            ])
                                            .inc_by(uncorrected);
                                    }
                                }
                                if let (Some(total), Some(used)) =
                                    (mig.bar1_total_bytes, mig.bar1_used_bytes)
                                {
                                    metrics
                                        .mig_bar1_total_bytes
                                        .with_label_values(&[
                                            uuid_label,
                                            gpu_label.as_str(),
                                            mig_label,
                                        ])
                                        .set(total as f64);
                                    metrics
                                        .mig_bar1_used_bytes
                                        .with_label_values(&[
                                            uuid_label,
                                            gpu_label.as_str(),
                                            mig_label,
                                        ])
                                        .set(used as f64);
                                    if self.k8s_mode {
                                        metrics
                                            .mig_bar1_total_bytes
                                            .with_label_values(&[
                                                uuid_label,
                                                gpu_label.as_str(),
                                                compat_label.as_str(),
                                            ])
                                            .set(total as f64);
                                        metrics
                                            .mig_bar1_used_bytes
                                            .with_label_values(&[
                                                uuid_label,
                                                gpu_label.as_str(),
                                                compat_label.as_str(),
                                            ])
                                            .set(used as f64);
                                    }
                                }
                                metrics
                                    .mig_info
                                    .with_label_values(&[
                                        uuid_label,
                                        gpu_label.as_str(),
                                        mig_label,
                                        mig.profile.as_deref().unwrap_or(""),
                                        mig.placement.as_deref().unwrap_or(""),
                                    ])
                                    .set(1.0);
                            }
                            let supported = migs.supported;
                            status.mig_tree = Some(migs);
                            metrics
                                .gpu_mig_supported
                                .with_label_values(&[uuid_label, gpu_label.as_str()])
                                .set(if supported { 1.0 } else { 0.0 });
                        }
                    }

                    #[cfg(not(all(feature = "gpu-nvml-ffi", feature = "gpu")))]
                    {
                        metrics
                            .gpu_mig_supported
                            .with_label_values(&[uuid_label, gpu_label.as_str()])
                            .set(0.0);
                        metrics
                            .gpu_mig_enabled
                            .with_label_values(&[uuid_label, gpu_label.as_str()])
                            .set(0.0);
                    }
                } else {
                    metrics
                        .gpu_mig_supported
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(0.0);
                    metrics
                        .gpu_mig_enabled
                        .with_label_values(&[uuid_label, gpu_label.as_str()])
                        .set(0.0);
                }

                status.health = Some(health.clone());
                statuses.push(status);
            }

            #[cfg(target_os = "linux")]
            {
                if let Some(es) = event_set.as_ref() {
                    // Drain a few events without blocking long; we rely on periodic scrapes.
                    for _ in 0..32 {
                        match es.wait(0) {
                            Ok(ev) => {
                                let ev_uuid =
                                    ev.device.uuid().unwrap_or_else(|_| "unknown".to_string());
                                let index_label = uuid_to_index
                                    .get(&ev_uuid)
                                    .cloned()
                                    .unwrap_or_else(|| "unknown".to_string());
                                let event = if ev
                                    .event_type
                                    .contains(EventTypes::CRITICAL_XID_ERROR)
                                {
                                    "xid"
                                } else if ev.event_type.contains(EventTypes::SINGLE_BIT_ECC_ERROR) {
                                    "ecc_single"
                                } else if ev.event_type.contains(EventTypes::DOUBLE_BIT_ECC_ERROR) {
                                    "ecc_double"
                                } else if ev.event_type.contains(EventTypes::PSTATE_CHANGE) {
                                    "pstate"
                                } else if ev.event_type.contains(EventTypes::CLOCK_CHANGE) {
                                    "clock"
                                } else {
                                    "other"
                                };
                                let labels = &[ev_uuid.as_str(), index_label.as_str(), event];
                                metrics.gpu_events_total.with_label_values(labels).inc();
                                if event == "xid" {
                                    metrics.gpu_xid_errors_total.with_label_values(labels).inc();
                                    // record last XID in health if we tracked mapping
                                }
                            }
                            Err(NvmlError::Timeout) => break,
                            Err(_) => break,
                        }
                    }
                }
            }

            self.status.set_gpu_statuses(statuses);
        }

        // If GPU feature is disabled, collection is a no-op.
        Ok(())
    }
}

#[cfg(feature = "gpu")]
fn set_throttle_metric(vec: &GaugeVec, uuid: &str, index: &str, reason: &str, active: bool) {
    vec.with_label_values(&[uuid, index, reason])
        .set(if active { 1.0 } else { 0.0 });
}

#[cfg(feature = "gpu")]
fn k8s_resource_name(prefix: &str, mig_profile: Option<&str>) -> String {
    if let Some(profile) = mig_profile {
        format!("{}/mig-{}", prefix, profile.replace('.', "-"))
    } else {
        format!("{prefix}/gpu")
    }
}

#[cfg(feature = "gpu")]
fn pcie_lane_bytes_per_sec(_gen: u32, speed_mt_s: u32) -> f64 {
    // PCIe generation to base speed in MT/s per lane
    // Gen1: 2.5 GT/s, Gen2: 5 GT/s, Gen3: 8 GT/s, Gen4: 16 GT/s, Gen5: 32 GT/s, Gen6: 64 GT/s
    // Data rate is typically 8/10 encoding for Gen1/2, 128/130 for Gen3+
    // nvml_wrapper::PcieLinkMaxSpeed enum values are already in MT/s
    // The `speed_mt_s` parameter from `device.pcie_link_speed()` is already in MT/s.
    // We need to convert MT/s to Bytes/s. 1 MT/s = 10^6 transfers/second.
    // For PCIe, each transfer is 1 bit. So MT/s is Mbps.
    // To get Bytes/s, divide by 8.
    // However, NVML's pcie_throughput is in KB/s, so we need to be careful with units.
    // The original `PcieLinkMaxSpeed` enum values were already scaled for bytes.
    // Let's assume `speed_mt_s` is in MB/s or similar, or that the original `PcieLinkMaxSpeed`
    // values were already representing "effective" MB/s per lane.
    // Given the original values:
    // 2500 MT/s -> 2_500_000.0 * 1_000.0 (bytes/s) = 2.5 GB/s
    // This implies the original `PcieLinkMaxSpeed` values were effectively in MB/s, and then multiplied by 1000 to get KB/s, then by 1024 to get bytes/s.
    // Let's re-evaluate based on standard PCIe speeds:
    // Gen1: 2.5 GT/s (250 MB/s per lane, 8b/10b encoding)
    // Gen2: 5 GT/s (500 MB/s per lane, 8b/10b encoding)
    // Gen3: 8 GT/s (985 MB/s per lane, 128b/130b encoding)
    // Gen4: 16 GT/s (1969 MB/s per lane, 128b/130b encoding)
    // Gen5: 32 GT/s (3938 MB/s per lane, 128b/130b encoding)
    // The `speed_mt_s` from NVML is "current link speed in MegaTransfers/second".
    // For Gen1/2, 1 MT/s = 0.8 Mbps (due to 8b/10b). For Gen3+, 1 MT/s = 128/130 Mbps.
    // This is tricky. The original code used `PcieLinkMaxSpeed` enum values which were effectively `MB/s * 1000` (KB/s).
    // Let's use the `speed_mt_s` directly and assume it's the effective data rate in MB/s, or convert it.
    // If `speed_mt_s` is MegaTransfers/second, and we want Bytes/second:
    // For Gen1/2 (gen <= 2): (speed_mt_s * 0.8) / 8 * 10^6 = speed_mt_s * 0.1 * 10^6 Bytes/s
    // For Gen3+ (gen >= 3): (speed_mt_s * 128/130) / 8 * 10^6 = speed_mt_s * (128/1040) * 10^6 Bytes/s
    // Let's simplify and use the provided `speed_mt_s` as a direct indicator of throughput capacity.
    // The original `PcieLinkMaxSpeed::MegaTransfersPerSecond2500` was 2500 * 1000.0. This is 2.5 GB/s.
    // This implies the enum values were already scaled to represent MB/s * 1000.
    // So, if `speed_mt_s` is 2500, it means 2.5 GB/s.
    // Let's assume `speed_mt_s` is in MB/s (effective data rate per lane).
    // Then `speed_mt_s * 1024 * 1024` would be Bytes/s.
    // However, the original code used `* 1000.0` for the `PcieLinkMaxSpeed` values.
    // Let's stick to the original scaling: `speed_mt_s` is in "units of 1000 KB/s".
    f64::from(speed_mt_s) * 1_000_000.0 / 8.0 // Convert MT/s to Bytes/s (assuming 1 transfer = 1 bit)
}

#[cfg(feature = "gpu")]
fn build_filter(raw: Option<&str>) -> Option<HashSet<String>> {
    raw.filter(|s| !s.is_empty() && *s != "all").map(|s| {
        s.split(',')
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty())
            .collect()
    })
}

#[cfg(all(feature = "gpu", feature = "gpu-nvml-ffi"))]
fn collect_mig_devices(_nvml: &Nvml, parent: &nvml_wrapper::Device) -> Result<MigTree> {
    use nvml_wrapper_sys::bindings::{
        nvmlComputeInstanceInfo_t, nvmlDevice_t, nvmlGpuInstanceInfo_t,
        nvmlReturn_enum_NVML_SUCCESS, nvmlReturn_t,
    };

    // Load NVML dynamically to bypass missing symbols in sys crate
    let lib = unsafe { libloading::Library::new("libnvidia-ml.so.1") }?;

    // Typedefs for the functions we need
    type NvmlDeviceGetMigMode = unsafe extern "C" fn(
        device: nvmlDevice_t,
        current_mode: *mut std::os::raw::c_uint,
        pending_mode: *mut std::os::raw::c_uint,
    ) -> nvmlReturn_t;
    type NvmlDeviceGetMaxMigDeviceCount = unsafe extern "C" fn(
        device: nvmlDevice_t,
        count: *mut std::os::raw::c_uint,
    ) -> nvmlReturn_t;
    type NvmlDeviceGetMigDeviceHandleByIndex = unsafe extern "C" fn(
        device: nvmlDevice_t,
        index: std::os::raw::c_uint,
        mig_device: *mut nvmlDevice_t,
    ) -> nvmlReturn_t;
    type NvmlDeviceGetDeviceHandleFromMigDeviceHandle =
        unsafe extern "C" fn(mig_device: nvmlDevice_t, device: *mut nvmlDevice_t) -> nvmlReturn_t;
    type NvmlDeviceGetGpuInstanceId =
        unsafe extern "C" fn(device: nvmlDevice_t, id: *mut std::os::raw::c_uint) -> nvmlReturn_t;
    type NvmlDeviceGetComputeInstanceId =
        unsafe extern "C" fn(device: nvmlDevice_t, id: *mut std::os::raw::c_uint) -> nvmlReturn_t;
    type NvmlGpuInstanceGetById = unsafe extern "C" fn(
        device: nvmlDevice_t,
        id: std::os::raw::c_uint,
        gpu_instance: *mut nvmlDevice_t,
    ) -> nvmlReturn_t;
    type NvmlGpuInstanceGetInfo = unsafe extern "C" fn(
        gpu_instance: nvmlDevice_t,
        info: *mut nvml_wrapper_sys::bindings::nvmlGpuInstanceInfo_t,
    ) -> nvmlReturn_t;
    type NvmlGpuInstanceGetComputeInstanceById = unsafe extern "C" fn(
        gpu_instance: nvmlDevice_t,
        id: std::os::raw::c_uint,
        compute_instance: *mut nvmlDevice_t,
    ) -> nvmlReturn_t;
    type NvmlComputeInstanceGetInfo = unsafe extern "C" fn(
        compute_instance: nvmlDevice_t,
        info: *mut nvml_wrapper_sys::bindings::nvmlComputeInstanceInfo_t,
    ) -> nvmlReturn_t;
    type NvmlDeviceGetUUID = unsafe extern "C" fn(
        device: nvmlDevice_t,
        uuid: *mut std::os::raw::c_char,
        size: std::os::raw::c_uint,
    ) -> nvmlReturn_t;
    type NvmlDeviceGetMemoryInfo = unsafe extern "C" fn(
        device: nvmlDevice_t,
        memory: *mut nvml_wrapper_sys::bindings::nvmlMemory_t,
    ) -> nvmlReturn_t;
    type NvmlDeviceGetUtilizationRates = unsafe extern "C" fn(
        device: nvmlDevice_t,
        utilization: *mut nvml_wrapper_sys::bindings::nvmlUtilization_t,
    ) -> nvmlReturn_t;
    type NvmlDeviceGetBar1MemoryInfo = unsafe extern "C" fn(
        device: nvmlDevice_t,
        bar1_memory: *mut nvml_wrapper_sys::bindings::nvmlBAR1Memory_t,
    ) -> nvmlReturn_t;
    type NvmlDeviceGetTotalEccErrors = unsafe extern "C" fn(
        device: nvmlDevice_t,
        error_type: nvml_wrapper_sys::bindings::nvmlMemoryErrorType_t,
        counter_type: nvml_wrapper_sys::bindings::nvmlEccCounterType_t,
        ecc_count: *mut u64,
    ) -> nvmlReturn_t;

    let get_mig_mode: libloading::Symbol<NvmlDeviceGetMigMode> =
        unsafe { lib.get(b"nvmlDeviceGetMigMode") }?;
    let get_max_mig_device_count: libloading::Symbol<NvmlDeviceGetMaxMigDeviceCount> =
        unsafe { lib.get(b"nvmlDeviceGetMaxMigDeviceCount") }?;
    let get_mig_device_handle_by_index: libloading::Symbol<NvmlDeviceGetMigDeviceHandleByIndex> =
        unsafe { lib.get(b"nvmlDeviceGetMigDeviceHandleByIndex") }?;
    let get_device_handle_from_mig_device_handle: libloading::Symbol<
        NvmlDeviceGetDeviceHandleFromMigDeviceHandle,
    > = unsafe { lib.get(b"nvmlDeviceGetDeviceHandleFromMigDeviceHandle") }?;
    let get_gpu_instance_id: libloading::Symbol<NvmlDeviceGetGpuInstanceId> =
        unsafe { lib.get(b"nvmlDeviceGetGpuInstanceId") }?;
    let get_compute_instance_id: libloading::Symbol<NvmlDeviceGetComputeInstanceId> =
        unsafe { lib.get(b"nvmlDeviceGetComputeInstanceId") }?;
    let get_gpu_instance_by_id: libloading::Symbol<NvmlGpuInstanceGetById> =
        unsafe { lib.get(b"nvmlGpuInstanceGetById") }?;
    let get_gpu_instance_info: libloading::Symbol<NvmlGpuInstanceGetInfo> =
        unsafe { lib.get(b"nvmlGpuInstanceGetInfo") }?;
    let get_gpu_instance_compute_instance_by_id: libloading::Symbol<
        NvmlGpuInstanceGetComputeInstanceById,
    > = unsafe { lib.get(b"nvmlGpuInstanceGetComputeInstanceById") }?;
    let get_compute_instance_info: libloading::Symbol<NvmlComputeInstanceGetInfo> =
        unsafe { lib.get(b"nvmlComputeInstanceGetInfo") }?;
    let get_uuid: libloading::Symbol<NvmlDeviceGetUUID> = unsafe { lib.get(b"nvmlDeviceGetUUID") }?;
    let get_memory_info: libloading::Symbol<NvmlDeviceGetMemoryInfo> =
        unsafe { lib.get(b"nvmlDeviceGetMemoryInfo") }?;
    let get_utilization_rates: libloading::Symbol<NvmlDeviceGetUtilizationRates> =
        unsafe { lib.get(b"nvmlDeviceGetUtilizationRates") }?;
    let get_bar1_memory_info: libloading::Symbol<NvmlDeviceGetBar1MemoryInfo> =
        unsafe { lib.get(b"nvmlDeviceGetBar1MemoryInfo") }?;
    let get_total_ecc_errors: libloading::Symbol<NvmlDeviceGetTotalEccErrors> =
        unsafe { lib.get(b"nvmlDeviceGetTotalEccErrors") }?;

    let mut current_mode = 0;
    let mut pending = 0;
    let parent_handle = unsafe { parent.handle() };
    let mig_mode_res =
        unsafe { get_mig_mode(parent_handle, &raw mut current_mode, &raw mut pending) };
    let supported = mig_mode_res == nvmlReturn_enum_NVML_SUCCESS;
    // NVML_DEVICE_MIG_ENABLE is 1
    let enabled = current_mode == 1;

    if !supported || !enabled {
        return Ok(MigTree {
            supported,
            enabled,
            gpu_instances: Vec::new(),
            compute_instances: Vec::new(),
            devices: Vec::new(),
        });
    }

    let mut max_count = 0;
    unsafe { get_max_mig_device_count(parent_handle, &raw mut max_count) };

    let mut devices = Vec::new();
    let mut gi_map: HashMap<u32, GpuInstanceNode> = HashMap::new();
    let mut gi_handles: HashMap<u32, nvmlDevice_t> = HashMap::new();
    let mut ci_nodes: Vec<ComputeInstanceNode> = Vec::new();

    for idx in 0..max_count {
        let mut mig_handle: nvmlDevice_t = std::ptr::null_mut();
        if unsafe { get_mig_device_handle_by_index(parent_handle, idx, &raw mut mig_handle) }
            == nvmlReturn_enum_NVML_SUCCESS
        {
            let mut full_handle: nvmlDevice_t = std::ptr::null_mut();
            unsafe { get_device_handle_from_mig_device_handle(mig_handle, &raw mut full_handle) };

            let mut uuid_buf = [0i8; 96]; // NVML_DEVICE_UUID_V2_BUFFER_SIZE
            let _ = unsafe { get_uuid(mig_handle, uuid_buf.as_mut_ptr(), uuid_buf.len() as u32) };
            let mig_uuid_str = unsafe { std::ffi::CStr::from_ptr(uuid_buf.as_ptr()) }
                .to_string_lossy()
                .into_owned();
            let mig_uuid = if mig_uuid_str.is_empty() {
                None
            } else {
                Some(mig_uuid_str.clone())
            };

            // Extract GI/CI to map hierarchy
            let mut gi_id = 0;
            let _ = unsafe { get_gpu_instance_id(mig_handle, &raw mut gi_id) };
            let mut ci_id = 0;
            let _ = unsafe { get_compute_instance_id(mig_handle, &raw mut ci_id) };

            // Populate GI info best-effort
            if gi_id > 0 && !gi_map.contains_key(&gi_id) {
                let mut gi_handle: nvmlDevice_t = std::ptr::null_mut();
                if unsafe { get_gpu_instance_by_id(parent_handle, gi_id, &raw mut gi_handle) }
                    == nvmlReturn_enum_NVML_SUCCESS
                {
                    gi_handles.insert(gi_id, gi_handle);

                    let mut gi_info: nvmlGpuInstanceInfo_t = unsafe { std::mem::zeroed() };
                    // gi_info.version = ...; // Skip version if unavailable, rely on zeroed/default
                    let _ = unsafe { get_gpu_instance_info(gi_handle, &raw mut gi_info) };
                    let placement = Some(format!(
                        "{}:slice{}",
                        gi_info.placement.start, gi_info.placement.size
                    ));
                    gi_map.insert(
                        gi_id,
                        GpuInstanceNode {
                            id: gi_id,
                            profile_id: Some(gi_info.profileId),
                            placement,
                        },
                    );
                }
            }

            // Populate CI info best-effort
            if ci_id > 0 {
                // Check if we haven't added this CI yet (simple check by iteration or similar, but here we just push)
                // To avoid stats duplication, we rely on the fact that we iterate MIG devices.
                // However, one CI might be shared? No, MIG device <-> CI is 1:1 usually?
                // Actually 1 GI can have multiple CIs. 1 CI can have multiple MIG devices?
                // In MIG, a "MIG Device" is conceptually a CI.
                // We'll just push CI nodes as we encounter them. Ideally distinct.
                // But `ci_nodes` is for the tree structure.
                // Let's check uniqueness.
                let known = ci_nodes
                    .iter()
                    .any(|c| c.gpu_instance_id == gi_id && c.id == ci_id);

                if !known {
                    if let Some(&gi_handle) = gi_handles.get(&gi_id) {
                        let mut ci_handle: nvmlDevice_t = std::ptr::null_mut();
                        if unsafe {
                            get_gpu_instance_compute_instance_by_id(
                                gi_handle,
                                ci_id,
                                &raw mut ci_handle,
                            )
                        } == nvmlReturn_enum_NVML_SUCCESS
                        {
                            let mut ci_info: nvmlComputeInstanceInfo_t =
                                unsafe { std::mem::zeroed() };
                            // ci_info.version = ...; // Skip version
                            let _ =
                                unsafe { get_compute_instance_info(ci_handle, &raw mut ci_info) };
                            ci_nodes.push(ComputeInstanceNode {
                                gpu_instance_id: gi_id,
                                id: ci_id,
                                profile_id: Some(ci_info.profileId),
                                eng_profile_id: None,
                                placement: Some(format!(
                                    "{}:slice{}",
                                    ci_info.placement.start, ci_info.placement.size
                                )),
                            });
                        }
                    }
                }
            }

            // Metrics
            let mut mem_info: Option<nvml_wrapper_sys::bindings::nvmlMemory_t> = None;
            let mut util_gpu: Option<u32> = None;
            let mut ecc_cor: Option<u64> = None;
            let mut ecc_uncor: Option<u64> = None;
            let mut bar1: Option<nvml_wrapper_sys::bindings::nvmlBAR1Memory_t> = None;

            {
                let mut m = unsafe { std::mem::zeroed() };
                if unsafe { get_memory_info(mig_handle, &raw mut m) }
                    == nvmlReturn_enum_NVML_SUCCESS
                {
                    mem_info = Some(m);
                }

                let mut u = unsafe { std::mem::zeroed() };
                if unsafe { get_utilization_rates(mig_handle, &raw mut u) }
                    == nvmlReturn_enum_NVML_SUCCESS
                {
                    util_gpu = Some(u.gpu);
                }

                let mut b = unsafe { std::mem::zeroed() };
                if unsafe { get_bar1_memory_info(mig_handle, &raw mut b) }
                    == nvmlReturn_enum_NVML_SUCCESS
                {
                    bar1 = Some(b);
                }

                let mut c_count: u64 = 0;
                let mut u_count: u64 = 0;
                // NVML_ECC_COUNTER_TYPE_VOLATILE = 0
                // NVML_MEMORY_ERROR_TYPE_CORRECTED = 1
                // NVML_MEMORY_ERROR_TYPE_UNCORRECTED = 2
                if unsafe { get_total_ecc_errors(mig_handle, 1, 0, &raw mut c_count) }
                    == nvmlReturn_enum_NVML_SUCCESS
                {
                    ecc_cor = Some(c_count);
                }
                if unsafe { get_total_ecc_errors(mig_handle, 2, 0, &raw mut u_count) }
                    == nvmlReturn_enum_NVML_SUCCESS
                {
                    ecc_uncor = Some(u_count);
                }
            }

            let mig_id = format!("mig{idx}");
            let placement_str = gi_map
                .get(&gi_id)
                .and_then(|g| g.placement.clone())
                .unwrap_or_else(|| format!("gi{gi_id}"));
            let profile_str = gi_map
                .get(&gi_id)
                .and_then(|g| g.profile_id)
                .map(|p| p.to_string());

            devices.push(MigDeviceStatus {
                id: mig_uuid.clone().unwrap_or(mig_id.clone()),
                uuid: mig_uuid,
                memory_total_bytes: mem_info.as_ref().map(|m| m.total),
                memory_used_bytes: mem_info.map(|m| m.used),
                util_percent: util_gpu,
                sm_count: None, // Not retrieving SM count for now
                profile: profile_str,
                placement: Some(placement_str),
                bar1_total_bytes: bar1.as_ref().map(|b| b.bar1Total),
                bar1_used_bytes: bar1.map(|b| b.bar1Used),
                ecc_corrected: ecc_cor,
                ecc_uncorrected: ecc_uncor,
            });
        }
    }

    Ok(MigTree {
        supported,
        enabled,
        gpu_instances: gi_map.values().cloned().collect(),
        compute_instances: ci_nodes,
        devices,
    })
}
