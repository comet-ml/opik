// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use async_trait::async_trait;
use std::fs;
use sysinfo::{RefreshKind, System, SystemExt};

use crate::collectors::Collector;
use crate::metrics::MetricsRegistry;
use crate::state::StatusState;

pub struct MemoryCollector {
    system: System,
    prev_pg_in_bytes: Option<u64>,
    prev_pg_out_bytes: Option<u64>,
    status: StatusState,
    last_swap_spike: bool,
}

impl MemoryCollector {
    pub fn new(status: StatusState) -> Self {
        let system = System::new_with_specifics(RefreshKind::new());
        Self {
            system,
            prev_pg_in_bytes: None,
            prev_pg_out_bytes: None,
            status,
            last_swap_spike: false,
        }
    }
}

#[async_trait]
impl Collector for MemoryCollector {
    fn name(&self) -> &'static str {
        "memory"
    }

    async fn collect(&mut self, metrics: &MetricsRegistry) -> anyhow::Result<()> {
        self.system.refresh_memory();
        // sysinfo reports memory in kibibytes; convert to bytes.
        let to_bytes = |kb: u64| kb.saturating_mul(1024);
        let total = to_bytes(self.system.total_memory());
        let used = to_bytes(self.system.used_memory());
        let free = to_bytes(self.system.free_memory());

        metrics.memory_total_bytes.set(total as f64);
        metrics.memory_used_bytes.set(used as f64);
        metrics.memory_free_bytes.set(free as f64);
        let mut swap_used: Option<u64> = None;

        if let Some(mi) = read_meminfo() {
            if let Some(v) = mi.get("MemAvailable") {
                metrics.memory_available_bytes.set((*v as f64) * 1024.0);
            }
            if let Some(v) = mi.get("Buffers") {
                metrics.memory_buffers_bytes.set((*v as f64) * 1024.0);
            }
            if let Some(v) = mi.get("Cached") {
                metrics.memory_cached_bytes.set((*v as f64) * 1024.0);
            }
            if let Some(v) = mi.get("SwapTotal") {
                metrics.swap_total_bytes.set((*v as f64) * 1024.0);
            }
            if let Some(v) = mi.get("SwapFree") {
                metrics.swap_free_bytes.set((*v as f64) * 1024.0);
                if let Some(t) = mi.get("SwapTotal") {
                    let used_b = t.saturating_sub(*v).saturating_mul(1024);
                    metrics.swap_used_bytes.set(used_b as f64);
                    swap_used = Some(used_b);
                }
            }
        }
        self.status
            .set_memory_summary(Some(total), Some(used), Some(free), swap_used);

        if let Some((pg_in_kb, pg_out_kb)) = read_vmstat_pg() {
            let in_b = pg_in_kb.saturating_mul(1024);
            let out_b = pg_out_kb.saturating_mul(1024);
            let mut spike = false;
            if let Some(prev) = self.prev_pg_in_bytes {
                let delta = in_b.saturating_sub(prev);
                metrics.page_in_bytes_total.inc_by(delta);
                if delta > 10 * 1024 * 1024 {
                    spike = true;
                }
            }
            if let Some(prev) = self.prev_pg_out_bytes {
                let delta = out_b.saturating_sub(prev);
                metrics.page_out_bytes_total.inc_by(delta);
                if delta > 10 * 1024 * 1024 {
                    spike = true;
                }
            }
            self.prev_pg_in_bytes = Some(in_b);
            self.prev_pg_out_bytes = Some(out_b);
            metrics
                .swap_degradation_spike
                .set(if spike { 1.0 } else { 0.0 });
            self.last_swap_spike = spike;
            self.status.set_swap_degraded(spike);
        }

        Ok(())
    }
}

fn read_meminfo() -> Option<std::collections::HashMap<String, u64>> {
    let s = fs::read_to_string("/proc/meminfo").ok()?;
    let mut map = std::collections::HashMap::new();
    for line in s.lines() {
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() >= 2 {
            let key = parts[0].trim_end_matches(':').to_string();
            if let Ok(val) = parts[1].parse::<u64>() {
                map.insert(key, val);
            }
        }
    }
    Some(map)
}

fn read_vmstat_pg() -> Option<(u64, u64)> {
    let s = fs::read_to_string("/proc/vmstat").ok()?;
    let mut in_kb = 0;
    let mut out_kb = 0;
    for line in s.lines() {
        if line.starts_with("pgpgin ") {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 {
                in_kb = parts[1].parse().ok()?;
            }
        } else if line.starts_with("pgpgout ") {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 {
                out_kb = parts[1].parse().ok()?;
            }
        }
    }
    Some((in_kb, out_kb))
}
