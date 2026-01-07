// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, Instant};

use async_trait::async_trait;
use sysinfo::{ComponentExt, System, SystemExt};
use tracing::{debug, warn};

use crate::collectors::Collector;
use crate::metrics::MetricsRegistry;
use crate::state::{StatusState, TemperatureReading};

struct RaplZone {
    name: String,
    energy_path: PathBuf,
    max_range_path: PathBuf,
    last_energy_uj: Option<u64>,
    last_ts: Option<Instant>,
}

impl RaplZone {
    fn read_energy(&self) -> Option<u64> {
        fs::read_to_string(&self.energy_path)
            .ok()
            .and_then(|s| s.trim().parse::<u64>().ok())
    }

    fn read_max_range(&self) -> Option<u64> {
        fs::read_to_string(&self.max_range_path)
            .ok()
            .and_then(|s| s.trim().parse::<u64>().ok())
    }
}

pub struct PowerCollector {
    rapl_zones: Vec<RaplZone>,
    node_power_candidates: Vec<PathBuf>,
    warned_rapl: bool,
    warned_node: bool,
    status: StatusState,
    sysinfo: System,
    envelope_watts: Option<f64>,
    last_node_power_watts: Option<f64>,
    last_node_ts: Option<Instant>,
}

impl PowerCollector {
    pub fn new(status: StatusState, envelope_watts: Option<f64>) -> Self {
        let rapl_zones = discover_rapl();
        let node_power_candidates = discover_node_power();
        Self {
            rapl_zones,
            node_power_candidates,
            warned_rapl: false,
            warned_node: false,
            status,
            sysinfo: System::new_all(),
            envelope_watts,
            last_node_power_watts: None,
            last_node_ts: None,
        }
    }
}

#[async_trait]
impl Collector for PowerCollector {
    fn name(&self) -> &'static str {
        "power"
    }

    async fn collect(&mut self, metrics: &MetricsRegistry) -> anyhow::Result<()> {
        collect_rapl(self, metrics);
        collect_node_power(self, metrics);
        collect_cpu_temps(self, metrics);
        Ok(())
    }
}

fn discover_rapl() -> Vec<RaplZone> {
    let mut zones = Vec::new();
    let base = Path::new("/sys/class/powercap");
    if let Ok(entries) = fs::read_dir(base) {
        for entry in entries.flatten() {
            let path = entry.path();
            let name_file = path.join("name");
            let energy_path = path.join("energy_uj");
            let max_range_path = path.join("max_energy_range_uj");
            if energy_path.exists() && max_range_path.exists() {
                let name = fs::read_to_string(&name_file)
                    .unwrap_or_else(|_| entry.file_name().to_string_lossy().into_owned());
                zones.push(RaplZone {
                    name: name.trim().to_string(),
                    energy_path,
                    max_range_path,
                    last_energy_uj: None,
                    last_ts: None,
                });
            }
        }
    }
    zones
}

fn collect_rapl(collector: &mut PowerCollector, metrics: &MetricsRegistry) {
    if collector.rapl_zones.is_empty() && !collector.warned_rapl {
        debug!("No RAPL zones found under /sys/class/powercap");
        collector.warned_rapl = true;
    }

    for zone in &mut collector.rapl_zones {
        let Some(energy) = zone.read_energy() else {
            if !collector.warned_rapl {
                warn!(
                    "Failed reading RAPL energy from {}",
                    zone.energy_path.display()
                );
            }
            continue;
        };
        let now = Instant::now();
        if let (Some(prev_energy), Some(prev_ts)) = (zone.last_energy_uj, zone.last_ts) {
            let dt = now.duration_since(prev_ts);
            if let Some(watts) =
                calculate_power_watts(energy, prev_energy, dt, zone.read_max_range())
            {
                metrics
                    .cpu_package_power_watts
                    .with_label_values(&[zone.name.as_str()])
                    .set(watts);
                collector
                    .status
                    .set_cpu_package_power(zone.name.clone(), watts);
                if zone.name.to_lowercase().contains("core") {
                    metrics
                        .cpu_core_power_watts
                        .with_label_values(&[zone.name.as_str()])
                        .set(watts);
                }
                let delta_joules = watts * dt.as_secs_f64();
                metrics
                    .cpu_package_energy_joules_total
                    .with_label_values(&[zone.name.as_str()])
                    .inc_by(delta_joules.floor() as u64);
            }
        }

        zone.last_energy_uj = Some(energy);
        zone.last_ts = Some(now);
    }
}

fn calculate_power_watts(
    current_uj: u64,
    previous_uj: u64,
    dt: Duration,
    max_range_uj: Option<u64>,
) -> Option<f64> {
    if dt.is_zero() {
        return None;
    }
    let delta = if current_uj >= previous_uj {
        current_uj - previous_uj
    } else {
        // Wrap-around; use max range to correct if available.
        max_range_uj
            .and_then(|range| range.checked_sub(previous_uj - current_uj))
            .unwrap_or(0)
    };
    let joules = delta as f64 / 1_000_000.0;
    Some(joules / dt.as_secs_f64())
}

fn discover_node_power() -> Vec<PathBuf> {
    let mut candidates = Vec::new();
    let hwmon_root = Path::new("/sys/class/hwmon");
    if let Ok(hwmons) = fs::read_dir(hwmon_root) {
        for hwmon in hwmons.flatten() {
            let path = hwmon.path();
            if let Ok(entries) = fs::read_dir(&path) {
                for entry in entries.flatten() {
                    let fname = entry.file_name().to_string_lossy().into_owned();
                    if fname.starts_with("power") && fname.ends_with("_input") {
                        candidates.push(entry.path());
                    }
                }
            }
        }
    }
    candidates
}

fn read_ipmi_node_power() -> Option<f64> {
    let output = Command::new("ipmitool")
        .args(["sdr", "type", "Power"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    for line in stdout.lines() {
        let mut watts: Option<f64> = None;
        for token in line.split_whitespace() {
            if let Some(stripped) = token.strip_suffix('W') {
                watts = stripped.trim().parse::<f64>().ok();
            }
        }
        if let Some(v) = watts {
            return Some(v);
        }
    }
    None
}

fn collect_node_power(collector: &mut PowerCollector, metrics: &MetricsRegistry) {
    if collector.envelope_watts.is_some() {
        metrics.node_power_envelope_exceeded.set(0.0);
    }
    if let Some(ipmi_watts) = read_ipmi_node_power() {
        metrics.node_power_watts.set(ipmi_watts);
        collector.status.set_node_power(ipmi_watts);
        let now = Instant::now();
        if let (Some(prev_watts), Some(prev_ts)) =
            (collector.last_node_power_watts, collector.last_node_ts)
        {
            let dt = now.duration_since(prev_ts).as_secs_f64();
            if dt > 0.0 {
                let energy_j = prev_watts * dt;
                metrics
                    .node_energy_joules_total
                    .inc_by(energy_j.floor() as u64);
            }
        }
        collector.last_node_power_watts = Some(ipmi_watts);
        collector.last_node_ts = Some(now);
        if let Some(envelope) = collector.envelope_watts {
            let exceeded = if ipmi_watts > envelope { 1.0 } else { 0.0 };
            metrics.node_power_envelope_exceeded.set(exceeded);
        }
        return;
    }

    for candidate in &collector.node_power_candidates {
        if let Ok(val) = fs::read_to_string(candidate) {
            if let Ok(microwatts) = val.trim().parse::<u64>() {
                let watts = microwatts as f64 / 1_000_000.0;
                metrics.node_power_watts.set(watts);
                collector.status.set_node_power(watts);
                let now = Instant::now();
                if let (Some(prev_watts), Some(prev_ts)) =
                    (collector.last_node_power_watts, collector.last_node_ts)
                {
                    let dt = now.duration_since(prev_ts).as_secs_f64();
                    if dt > 0.0 {
                        let energy_j = prev_watts * dt;
                        metrics
                            .node_energy_joules_total
                            .inc_by(energy_j.floor() as u64);
                    }
                }
                collector.last_node_power_watts = Some(watts);
                collector.last_node_ts = Some(now);
                if let Some(envelope) = collector.envelope_watts {
                    let exceeded = if watts > envelope { 1.0 } else { 0.0 };
                    metrics.node_power_envelope_exceeded.set(exceeded);
                }
                return;
            }
        }
    }

    if !collector.warned_node && !collector.node_power_candidates.is_empty() {
        warn!("Failed to read node power from hwmon candidates");
        collector.warned_node = true;
    }
}

fn collect_cpu_temps(collector: &mut PowerCollector, metrics: &MetricsRegistry) {
    collector.sysinfo.refresh_components();
    let mut readings = Vec::new();
    for c in collector.sysinfo.components() {
        let name = c.label().to_string();
        let temp = f64::from(c.temperature());
        metrics
            .cpu_temperature_celsius
            .with_label_values(&[name.as_str()])
            .set(temp);
        readings.push(TemperatureReading {
            sensor: name,
            celsius: temp,
        });
    }
    collector.status.set_cpu_temperatures(readings);
}
