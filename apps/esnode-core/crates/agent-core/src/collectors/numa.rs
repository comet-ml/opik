// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use async_trait::async_trait;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

use sysinfo::{CpuRefreshKind, RefreshKind, System, SystemExt};

use crate::collectors::Collector;
use crate::metrics::MetricsRegistry;

#[derive(Clone)]
struct NumaNode {
    id: String,
    meminfo_path: PathBuf,
    cpus: Vec<usize>,
    distance_path: Option<PathBuf>,
}

pub struct NumaCollector {
    nodes: Vec<NumaNode>,
    system: System,
}

impl NumaCollector {
    pub fn new() -> Self {
        let nodes = discover_nodes();
        // Keep CPU refresh data for per-core utilization.
        let system =
            System::new_with_specifics(RefreshKind::new().with_cpu(CpuRefreshKind::everything()));
        Self { nodes, system }
    }
}

#[async_trait]
impl Collector for NumaCollector {
    fn name(&self) -> &'static str {
        "numa"
    }

    async fn collect(&mut self, metrics: &MetricsRegistry) -> anyhow::Result<()> {
        self.system.refresh_cpu();
        for node in &self.nodes {
            let label = node.id.as_str();
            if let Some(meminfo) = read_meminfo(&node.meminfo_path) {
                let total = meminfo.get("MemTotal").copied().unwrap_or(0) * 1024;
                let free = meminfo.get("MemFree").copied().unwrap_or(0) * 1024;
                let used = total.saturating_sub(free);
                metrics
                    .numa_memory_total_bytes
                    .with_label_values(&[label])
                    .set(total as f64);
                metrics
                    .numa_memory_free_bytes
                    .with_label_values(&[label])
                    .set(free as f64);
                metrics
                    .numa_memory_used_bytes
                    .with_label_values(&[label])
                    .set(used as f64);
            }

            // Average CPU usage across the node's CPUs.
            let usages: Vec<f32> = node
                .cpus
                .iter()
                .filter_map(|idx| self.system.cpus().get(*idx).map(sysinfo::CpuExt::cpu_usage))
                .collect();
            if !usages.is_empty() {
                let sum: f32 = usages.iter().copied().sum();
                let avg = f64::from(sum) / usages.len() as f64;
                metrics
                    .numa_cpu_usage_percent
                    .with_label_values(&[label])
                    .set(avg);
            }

            // Page faults per NUMA domain: if not available, publish zero to keep the series visible.
            metrics
                .numa_page_faults_total
                .with_label_values(&[label])
                .inc_by(0);

            if let Some(dist_path) = &node.distance_path {
                if let Ok(contents) = fs::read_to_string(dist_path) {
                    for (idx, value) in contents.split_whitespace().enumerate() {
                        if let Ok(distance) = value.parse::<f64>() {
                            metrics
                                .numa_distance
                                .with_label_values(&[label, &idx.to_string()])
                                .set(distance);
                        }
                    }
                }
            }
        }
        Ok(())
    }
}

fn discover_nodes() -> Vec<NumaNode> {
    let base = Path::new("/sys/devices/system/node");
    let mut nodes = Vec::new();
    if let Ok(entries) = fs::read_dir(base) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if !name.starts_with("node") {
                continue;
            }
            let id_part = name.trim_start_matches("node");
            let meminfo_path = entry.path().join("meminfo");
            if !meminfo_path.exists() {
                continue;
            }
            let cpus = parse_cpulist(entry.path().join("cpulist"));
            let distance_path = {
                let p = entry.path().join("distance");
                p.exists().then_some(p)
            };
            nodes.push(NumaNode {
                id: id_part.to_string(),
                meminfo_path,
                cpus,
                distance_path,
            });
        }
    }
    nodes
}

fn parse_cpulist(path: PathBuf) -> Vec<usize> {
    let Ok(contents) = fs::read_to_string(path) else {
        return Vec::new();
    };
    let mut cpus = Vec::new();
    for part in contents.trim().split(',') {
        if let Some((start, end)) = part.split_once('-') {
            if let (Ok(s), Ok(e)) = (start.parse::<usize>(), end.parse::<usize>()) {
                for id in s..=e {
                    cpus.push(id);
                }
            }
        } else if let Ok(id) = part.parse::<usize>() {
            cpus.push(id);
        }
    }
    cpus
}

fn read_meminfo(path: &Path) -> Option<HashMap<String, u64>> {
    let contents = fs::read_to_string(path).ok()?;
    let mut map = HashMap::new();
    for line in contents.lines() {
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() >= 4 {
            // Format: "Node 0 MemTotal: 12345 kB"
            if let Ok(value) = parts[3].parse::<u64>() {
                map.insert(parts[2].trim_end_matches(':').to_string(), value);
            }
        }
    }
    Some(map)
}
