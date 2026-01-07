// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use async_trait::async_trait;
use std::fs;
use sysinfo::{CpuExt, CpuRefreshKind, RefreshKind, System, SystemExt};

struct CpuStat {
    user: u64,
    nice: u64,
    system: u64,
    idle: u64,
    iowait: u64,
    irq: u64,
    softirq: u64,
    steal: u64,
    interrupts: u64,
    ctxt: u64,
}

use crate::collectors::Collector;
use crate::metrics::MetricsRegistry;
use crate::state::StatusState;

pub struct CpuCollector {
    system: System,
    status: StatusState,
    prev: Option<CpuStat>,
    ticks_per_sec: f64,
}

impl CpuCollector {
    pub fn new(status: StatusState) -> Self {
        // Only keep CPU-related refresh data to minimize overhead.
        let refresh = RefreshKind::new().with_cpu(CpuRefreshKind::everything());
        let system = System::new_with_specifics(refresh);
        let tps = unsafe { libc::sysconf(libc::_SC_CLK_TCK) } as f64;
        Self {
            system,
            status,
            prev: None,
            ticks_per_sec: if tps > 0.0 { tps } else { 100.0 },
        }
    }
}

#[async_trait]
impl Collector for CpuCollector {
    fn name(&self) -> &'static str {
        "cpu"
    }

    async fn collect(&mut self, metrics: &MetricsRegistry) -> anyhow::Result<()> {
        self.system.refresh_cpu();
        let la = self.system.load_average();
        let load = la.one;
        metrics.cpu_load_avg_1m.set(load);
        metrics.cpu_load_avg_5m.set(la.five);
        metrics.cpu_load_avg_15m.set(la.fifteen);

        let cores = self.system.cpus().len() as u64;
        let avg_util = if cores > 0 {
            let total: f32 = self
                .system
                .cpus()
                .iter()
                .map(sysinfo::CpuExt::cpu_usage)
                .sum();
            Some(f64::from(total) / (cores as f64))
        } else {
            None
        };

        let uptime_seconds = Some(self.system.uptime());
        self.status.set_cpu_summary(
            Some(cores),
            avg_util,
            load,
            Some(la.five),
            Some(la.fifteen),
            uptime_seconds,
        );

        for (idx, cpu) in self.system.cpus().iter().enumerate() {
            let label = idx.to_string();
            metrics
                .cpu_usage_percent
                .with_label_values(&[label.as_str()])
                .set(f64::from(cpu.cpu_usage()));
        }

        let stat = tokio::task::spawn_blocking(read_proc_stat)
            .await
            .ok()
            .flatten();
        if let Some(stat) = stat {
            if let Some(prev) = &self.prev {
                let inc = |state: &str, curr: u64, prev: u64| {
                    let delta = curr.saturating_sub(prev);
                    metrics
                        .cpu_time_seconds_total
                        .with_label_values(&[state])
                        .inc_by((delta as f64) / self.ticks_per_sec);
                };
                inc("user", stat.user, prev.user);
                inc("nice", stat.nice, prev.nice);
                inc("system", stat.system, prev.system);
                inc("idle", stat.idle, prev.idle);
                inc("iowait", stat.iowait, prev.iowait);
                inc("irq", stat.irq, prev.irq);
                inc("softirq", stat.softirq, prev.softirq);
                inc("steal", stat.steal, prev.steal);
                let dint = stat.interrupts.saturating_sub(prev.interrupts);
                let dctxt = stat.ctxt.saturating_sub(prev.ctxt);
                metrics.cpu_interrupts_total.inc_by(dint);
                metrics.cpu_context_switches_total.inc_by(dctxt);
            }
            self.prev = Some(stat);
        }

        Ok(())
    }
}

fn read_proc_stat() -> Option<CpuStat> {
    let s = fs::read_to_string("/proc/stat").ok()?;
    let mut user = 0;
    let mut nice = 0;
    let mut system = 0;
    let mut idle = 0;
    let mut iowait = 0;
    let mut irq = 0;
    let mut softirq = 0;
    let mut steal = 0;
    let mut interrupts = 0;
    let mut ctxt = 0;
    for line in s.lines() {
        if line.starts_with("cpu ") {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 8 {
                user = parts[1].parse().ok()?;
                nice = parts[2].parse().ok()?;
                system = parts[3].parse().ok()?;
                idle = parts[4].parse().ok()?;
                iowait = parts[5].parse().ok()?;
                irq = parts[6].parse().ok()?;
                softirq = parts[7].parse().ok()?;
                if parts.len() > 8 {
                    steal = parts[8].parse().ok()?;
                }
            }
        } else if line.starts_with("intr ") {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 {
                interrupts = parts[1].parse().ok()?;
            }
        } else if line.starts_with("ctxt ") {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 2 {
                ctxt = parts[1].parse().ok()?;
            }
        }
    }
    Some(CpuStat {
        user,
        nice,
        system,
        idle,
        iowait,
        irq,
        softirq,
        steal,
        interrupts,
        ctxt,
    })
}
