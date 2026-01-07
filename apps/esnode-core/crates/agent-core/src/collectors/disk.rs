// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use async_trait::async_trait;
use std::collections::HashMap;
use std::fs;
use sysinfo::{DiskExt, RefreshKind, System, SystemExt};

use crate::collectors::Collector;
use crate::metrics::MetricsRegistry;
use crate::state::StatusState;

pub struct DiskCollector {
    system: System,
    previous: HashMap<String, DiskIo>,
    status: StatusState,
    prev_instant: Option<std::time::Instant>,
}

impl DiskCollector {
    pub fn new(status: StatusState) -> Self {
        let system = System::new_with_specifics(RefreshKind::new());
        Self {
            system,
            previous: HashMap::new(),
            status,
            prev_instant: None,
        }
    }
}

#[async_trait]
impl Collector for DiskCollector {
    fn name(&self) -> &'static str {
        "disk"
    }

    async fn collect(&mut self, metrics: &MetricsRegistry) -> anyhow::Result<()> {
        let now = std::time::Instant::now();
        let dt = self
            .prev_instant
            .map_or(0.0, |p| now.saturating_duration_since(p).as_secs_f64());
        self.prev_instant = Some(now);

        self.system.refresh_disks_list();
        self.system.refresh_disks();

        let mut root_total: Option<u64> = None;
        let mut root_used: Option<u64> = None;

        for disk in self.system.disks() {
            let mount = disk.mount_point().to_string_lossy().into_owned();
            let total = disk.total_space();
            let available = disk.available_space();
            let used = total.saturating_sub(available);
            let free = available;

            metrics
                .disk_total_bytes
                .with_label_values(&[mount.as_str()])
                .set(total as f64);
            metrics
                .disk_used_bytes
                .with_label_values(&[mount.as_str()])
                .set(used as f64);
            metrics
                .disk_free_bytes
                .with_label_values(&[mount.as_str()])
                .set(free as f64);

            if mount == "/" {
                root_total = Some(total);
                root_used = Some(used);
            }
        }

        let mut root_io_ms_delta: Option<u64> = None;

        if let Some(map) = read_diskstats() {
            for (dev, io) in &map {
                let prev = self.previous.get(dev).cloned().unwrap_or_default();
                let rd_ops_delta = io.reads_completed.saturating_sub(prev.reads_completed);
                let wr_ops_delta = io.writes_completed.saturating_sub(prev.writes_completed);
                let rd_bytes_delta = io
                    .sectors_read
                    .saturating_sub(prev.sectors_read)
                    .saturating_mul(512);
                let wr_bytes_delta = io
                    .sectors_written
                    .saturating_sub(prev.sectors_written)
                    .saturating_mul(512);
                let io_ms_delta = io.io_time_ms.saturating_sub(prev.io_time_ms);
                let total_ops = rd_ops_delta.saturating_add(wr_ops_delta);
                let avg_latency_ms = if total_ops > 0 {
                    (io_ms_delta as f64) / (total_ops as f64)
                } else {
                    0.0
                };
                let latency_degraded = avg_latency_ms > 50.0;
                let busy_pct = if dt > 0.0 {
                    (io_ms_delta as f64 / (dt * 1000.0)).min(1.0)
                } else {
                    0.0
                };

                metrics
                    .disk_read_ops_total
                    .with_label_values(&[dev.as_str()])
                    .inc_by(rd_ops_delta);
                metrics
                    .disk_write_ops_total
                    .with_label_values(&[dev.as_str()])
                    .inc_by(wr_ops_delta);
                metrics
                    .disk_read_bytes_total
                    .with_label_values(&[dev.as_str()])
                    .inc_by(rd_bytes_delta);
                metrics
                    .disk_written_bytes_total
                    .with_label_values(&[dev.as_str()])
                    .inc_by(wr_bytes_delta);
                metrics
                    .disk_io_time_ms_total
                    .with_label_values(&[dev.as_str()])
                    .inc_by(io_ms_delta);
                metrics
                    .disk_degradation_busy
                    .with_label_values(&[dev.as_str()])
                    .set(if busy_pct > 0.8 { 1.0 } else { 0.0 });
                metrics
                    .disk_io_avg_latency_ms
                    .with_label_values(&[dev.as_str()])
                    .set(avg_latency_ms);
                metrics
                    .disk_degradation_latency
                    .with_label_values(&[dev.as_str()])
                    .set(if latency_degraded { 1.0 } else { 0.0 });

                if dt > 0.0
                    && root_io_ms_delta.is_none()
                    && (dev.starts_with("sd")
                        || dev.starts_with("nvme")
                        || dev.starts_with("vd")
                        || dev.starts_with("xvd"))
                {
                    root_io_ms_delta = Some(io_ms_delta);
                }

                self.previous.insert(dev.clone(), io.clone());
            }
        }

        self.status
            .set_disk_summary(root_total, root_used, root_io_ms_delta);
        // Set coarse degradation flag if any device was busy >80% this interval.
        let any_busy = self.previous.keys().any(|dev| {
            metrics
                .disk_degradation_busy
                .with_label_values(&[dev])
                .get()
                > 0.0
                || metrics
                    .disk_degradation_latency
                    .with_label_values(&[dev])
                    .get()
                    > 0.0
        });
        self.status.set_disk_degraded(any_busy);
        Ok(())
    }
}

#[derive(Default, Clone)]
struct DiskIo {
    reads_completed: u64,
    sectors_read: u64,
    writes_completed: u64,
    sectors_written: u64,
    io_time_ms: u64,
}

fn read_diskstats() -> Option<HashMap<String, DiskIo>> {
    let s = fs::read_to_string("/proc/diskstats").ok()?;
    let mut map = HashMap::new();
    for line in s.lines() {
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() >= 14 {
            let dev = parts[2].to_string();
            let reads_completed = parts[3].parse().unwrap_or(0);
            let sectors_read = parts[5].parse().unwrap_or(0);
            let writes_completed = parts[7].parse().unwrap_or(0);
            let sectors_written = parts[9].parse().unwrap_or(0);
            let io_time_ms = parts[12].parse().unwrap_or(0);
            map.insert(
                dev,
                DiskIo {
                    reads_completed,
                    sectors_read,
                    writes_completed,
                    sectors_written,
                    io_time_ms,
                },
            );
        }
    }
    Some(map)
}
