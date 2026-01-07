// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2025 Estimatedstocks AB
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::{Context, Result};
use prometheus::proto::{Metric, MetricFamily, MetricType};
use serde::{Deserialize, Serialize};
use tokio::fs::{self, File, OpenOptions};
use tokio::io::{AsyncWriteExt, BufWriter};
use tokio::sync::Mutex;
use tracing::debug;

use crate::config::AgentConfig;
use crate::metrics::MetricsRegistry;

const BLOCK_DURATION: Duration = Duration::from_secs(2 * 60 * 60); // 2h blocks
const FLUSH_INTERVAL: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Sample {
    pub metric: String,
    pub labels: HashMap<String, String>,
    pub ts_ms: i64,
    pub value: f64,
}

#[derive(Debug, Clone)]
pub struct LocalTsdbConfig {
    pub path: PathBuf,
    pub retention_hours: u64,
    pub max_disk_mb: u64,
}

impl From<&AgentConfig> for LocalTsdbConfig {
    fn from(value: &AgentConfig) -> Self {
        Self {
            path: PathBuf::from(value.local_tsdb_path.clone()),
            retention_hours: value.local_tsdb_retention_hours,
            max_disk_mb: value.local_tsdb_max_disk_mb,
        }
    }
}

#[derive(Debug, Serialize, Deserialize, Default, Clone)]
struct BlockMeta {
    start_ms: i64,
    end_ms: i64,
    samples: u64,
    metric_counts: HashMap<String, u64>,
    label_hash_counts: HashMap<u64, u64>,
}

#[derive(Debug)]
struct BlockWriter {
    meta: BlockMeta,
    dir: PathBuf,
    _samples_path: PathBuf,
    writer: BufWriter<File>,
    last_flush_ms: i64,
}

impl BlockWriter {
    async fn create(root: &Path, start_ms: i64, end_ms: i64) -> Result<Self> {
        let dir = root.join(format!("{start_ms}-{end_ms}"));
        fs::create_dir_all(&dir)
            .await
            .with_context(|| format!("creating block dir {}", dir.display()))?;
        let samples_path = dir.join("samples.jsonl");
        let file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&samples_path)
            .await
            .with_context(|| format!("opening samples file {}", samples_path.display()))?;
        Ok(Self {
            meta: BlockMeta {
                start_ms,
                end_ms,
                samples: 0,
                metric_counts: HashMap::new(),
                label_hash_counts: HashMap::new(),
            },
            dir,
            _samples_path: samples_path,
            writer: BufWriter::new(file),
            last_flush_ms: start_ms,
        })
    }

    async fn write_sample(&mut self, sample: &Sample) -> Result<()> {
        let line = serde_json::to_string(sample)?;
        self.writer
            .write_all(line.as_bytes())
            .await
            .context("writing sample")?;
        self.writer.write_all(b"\n").await?;
        self.meta.samples += 1;
        *self
            .meta
            .metric_counts
            .entry(sample.metric.clone())
            .or_default() += 1;
        let hash = labels_hash(&sample.labels);
        *self.meta.label_hash_counts.entry(hash).or_default() += 1;
        self.flush_if_needed(sample.ts_ms).await?;
        Ok(())
    }

    async fn finish(mut self) -> Result<()> {
        self.writer.flush().await?;
        self.persist_index_files().await?;
        Ok(())
    }

    async fn flush_if_needed(&mut self, ts_ms: i64) -> Result<()> {
        if ts_ms - self.last_flush_ms >= FLUSH_INTERVAL.as_millis() as i64 {
            self.writer.flush().await?;
            self.last_flush_ms = ts_ms;
        }
        Ok(())
    }

    async fn persist_index_files(&self) -> Result<()> {
        let meta_path = self.dir.join("meta.json");
        let index_path = self.dir.join("index.json");
        let meta_bytes = serde_json::to_vec_pretty(&self.meta)?;
        fs::write(&meta_path, &meta_bytes)
            .await
            .with_context(|| format!("writing meta {}", meta_path.display()))?;
        fs::write(&index_path, &meta_bytes)
            .await
            .with_context(|| format!("writing index {}", index_path.display()))?;
        Ok(())
    }
}

#[derive(Debug)]
pub struct LocalTsdb {
    config: LocalTsdbConfig,
    block_duration_ms: i64,
    current: Mutex<Option<BlockWriter>>,
}

impl LocalTsdb {
    pub fn new(config: LocalTsdbConfig) -> Result<Self> {
        std::fs::create_dir_all(&config.path)
            .with_context(|| format!("creating TSDB path {}", config.path.display()))?;
        Ok(Self {
            config,
            block_duration_ms: BLOCK_DURATION.as_millis() as i64,
            current: Mutex::new(None),
        })
    }

    pub async fn write_samples(&self, samples: &[Sample]) -> Result<()> {
        if samples.is_empty() {
            return Ok(());
        }
        let _ = chrono::Utc::now().timestamp_millis();

        let mut guard = self.current.lock().await;
        for sample in samples {
            self.ensure_block_for_ts(sample.ts_ms, &mut guard).await?;
            if let Some(writer) = guard.as_mut() {
                writer.write_sample(sample).await?;
            }
        }
        Ok(())
    }

    async fn ensure_block_for_ts(
        &self,
        ts_ms: i64,
        current: &mut Option<BlockWriter>,
    ) -> Result<()> {
        let window_start = (ts_ms / self.block_duration_ms) * self.block_duration_ms;
        let window_end = window_start + self.block_duration_ms;
        let needs_new = match current {
            Some(w) => ts_ms < w.meta.start_ms || ts_ms >= w.meta.end_ms,
            None => true,
        };
        if needs_new {
            if let Some(writer) = current.take() {
                writer.finish().await?;
            }
            let writer = BlockWriter::create(&self.config.path, window_start, window_end).await?;
            *current = Some(writer);
        }
        Ok(())
    }

    async fn prune(&self, now_ms: i64) -> Result<()> {
        let retention_ms = (self.config.retention_hours as i64) * 60 * 60 * 1000;
        let max_bytes = self.config.max_disk_mb as i64 * 1024 * 1024;

        let mut blocks = self.list_blocks().await?;
        // Retention-based pruning
        for blk in blocks.iter().filter(|b| b.end_ms < now_ms - retention_ms) {
            debug!("pruning expired block {}", blk.dir.display());
            let _ = fs::remove_dir_all(&blk.dir).await;
        }
        // Re-scan after retention deletion
        blocks = self.list_blocks().await?;
        let mut total_bytes: i64 = blocks.iter().map(|b| b.size_bytes as i64).sum();
        if total_bytes > max_bytes {
            let mut sorted = blocks;
            sorted.sort_by_key(|b| b.start_ms);
            for blk in sorted {
                if total_bytes <= max_bytes {
                    break;
                }
                debug!("pruning block {} to enforce disk budget", blk.dir.display());
                if fs::remove_dir_all(&blk.dir).await.is_ok() {
                    total_bytes -= blk.size_bytes as i64;
                }
            }
        }
        Ok(())
    }

    async fn list_blocks(&self) -> Result<Vec<BlockInfo>> {
        let mut entries = fs::read_dir(&self.config.path)
            .await
            .with_context(|| format!("reading {}", self.config.path.display()))?;
        let mut out = Vec::new();
        while let Some(entry) = entries.next_entry().await? {
            let file_type = entry.file_type().await?;
            if !file_type.is_dir() {
                continue;
            }
            let name = entry.file_name();
            let name = name.to_string_lossy();
            let parts: Vec<&str> = name.split('-').collect();
            if parts.len() != 2 {
                continue;
            }
            let start_ms: i64 = match parts[0].parse() {
                Ok(v) => v,
                Err(_) => continue,
            };
            let end_ms: i64 = match parts[1].parse() {
                Ok(v) => v,
                Err(_) => continue,
            };
            let dir = entry.path();
            let size_bytes = tokio::task::spawn_blocking({
                let dir_cloned = dir.clone();
                move || dir_size_bytes(&dir_cloned)
            })
            .await
            .ok()
            .flatten()
            .unwrap_or(0);
            let index = read_block_index(&dir).await.ok();
            out.push(BlockInfo {
                dir,
                start_ms,
                end_ms,
                size_bytes,
                index,
            });
        }
        Ok(out)
    }

    pub async fn flush_current(&self) -> Result<()> {
        let mut guard = self.current.lock().await;
        if let Some(writer) = guard.take() {
            writer.finish().await?;
        }
        Ok(())
    }

    pub async fn snapshot_current(&self) -> Result<()> {
        let mut guard = self.current.lock().await;
        if let Some(writer) = guard.as_mut() {
            writer.writer.flush().await?;
            let _ = writer.persist_index_files().await;
        }
        Ok(())
    }

    pub async fn export_lines(
        &self,
        from_ms: Option<i64>,
        to_ms: Option<i64>,
        metrics: Option<&Vec<String>>,
    ) -> Result<Vec<String>> {
        // Flush buffered data and write index/metadata for the current block,
        // but keep the writer open so we don't reset metadata within the same window.
        let _ = self.snapshot_current().await;
        let blocks = self.list_blocks().await?;
        let mut out = Vec::new();
        for blk in blocks
            .into_iter()
            .filter(|b| overlaps(b.start_ms, b.end_ms, from_ms, to_ms))
        {
            if let (Some(filters), Some(idx)) = (metrics, &blk.index) {
                if !metrics_match_index(filters, idx) {
                    continue;
                }
            }
            let samples_path = blk.dir.join("samples.jsonl");
            let Ok(content) = fs::read_to_string(&samples_path).await else {
                continue;
            };
            for line in content.lines() {
                if line.trim().is_empty() {
                    continue;
                }
                if let Ok(sample) = serde_json::from_str::<Sample>(line) {
                    if !timestamp_in_range(sample.ts_ms, from_ms, to_ms) {
                        continue;
                    }
                    if let Some(filters) = metrics {
                        if !matches_metric(&sample.metric, filters) {
                            continue;
                        }
                    }
                    out.push(format_export_line(&sample));
                }
            }
        }
        Ok(out)
    }
}

impl LocalTsdb {
    pub fn spawn_pruner(
        self: std::sync::Arc<Self>,
        interval: Duration,
    ) -> tokio::task::JoinHandle<()> {
        tokio::spawn(async move {
            let mut ticker = tokio::time::interval(interval);
            loop {
                ticker.tick().await;
                let now_ms = chrono::Utc::now().timestamp_millis();
                let _ = self.prune(now_ms).await;
            }
        })
    }
}

struct BlockInfo {
    dir: PathBuf,
    start_ms: i64,
    end_ms: i64,
    size_bytes: u64,
    index: Option<BlockMeta>,
}

async fn read_block_index(dir: &Path) -> Result<BlockMeta> {
    let index_path = dir.join("index.json");
    let bytes = fs::read(&index_path)
        .await
        .with_context(|| format!("reading index {}", index_path.display()))?;
    let meta: BlockMeta = serde_json::from_slice(&bytes)?;
    Ok(meta)
}

fn overlaps(start: i64, end: i64, from: Option<i64>, to: Option<i64>) -> bool {
    let after_from = from.is_none_or(|f| end >= f);
    let before_to = to.is_none_or(|t| start <= t);
    after_from && before_to
}

fn timestamp_in_range(ts: i64, from: Option<i64>, to: Option<i64>) -> bool {
    let gte_from = from.is_none_or(|f| ts >= f);
    let lte_to = to.is_none_or(|t| ts <= t);
    gte_from && lte_to
}

fn metrics_match_index(filters: &[String], idx: &BlockMeta) -> bool {
    filters.iter().any(|f| {
        if f.ends_with('*') {
            let prefix = f.trim_end_matches('*');
            idx.metric_counts.keys().any(|m| m.starts_with(prefix))
        } else {
            idx.metric_counts.contains_key(f)
        }
    })
}

fn matches_metric(metric: &str, filters: &[String]) -> bool {
    filters.iter().any(|f| {
        if f.ends_with('*') {
            metric.starts_with(f.trim_end_matches('*'))
        } else {
            metric == f
        }
    })
}

fn format_export_line(sample: &Sample) -> String {
    let mut labels: Vec<_> = sample.labels.iter().collect();
    labels.sort_by_key(|(k, _)| *k);
    let labels_str = if labels.is_empty() {
        String::new()
    } else {
        let parts: Vec<String> = labels
            .into_iter()
            .map(|(k, v)| format!(r#"{k}="{v}""#))
            .collect();
        format!("{{{}}}", parts.join(","))
    };
    format!(
        "{}{} {} {:.6}",
        sample.metric, labels_str, sample.ts_ms, sample.value
    )
}

fn labels_hash(labels: &HashMap<String, String>) -> u64 {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};

    let mut pairs: Vec<_> = labels.iter().collect();
    pairs.sort_by_key(|(k, _)| *k);
    let mut hasher = DefaultHasher::new();
    for (k, v) in pairs {
        k.hash(&mut hasher);
        v.hash(&mut hasher);
    }
    hasher.finish()
}

fn dir_size_bytes(path: &Path) -> Option<u64> {
    let mut size = 0u64;
    let mut stack = vec![path.to_path_buf()];
    while let Some(p) = stack.pop() {
        if let Ok(meta) = std::fs::metadata(&p) {
            if meta.is_file() {
                size = size.saturating_add(meta.len());
            } else if meta.is_dir() {
                if let Ok(read) = std::fs::read_dir(&p) {
                    for entry in read.flatten() {
                        stack.push(entry.path());
                    }
                }
            }
        }
    }
    Some(size)
}

#[must_use]
pub fn samples_from_registry(registry: &MetricsRegistry, fallback_ts_ms: i64) -> Vec<Sample> {
    let families = registry.gather_families();
    let mut out = Vec::new();
    for fam in families {
        match fam.get_field_type() {
            MetricType::GAUGE | MetricType::COUNTER | MetricType::UNTYPED => {
                for metric in fam.get_metric() {
                    if let Some(sample) = sample_from_metric(&fam, metric, fallback_ts_ms) {
                        out.push(sample);
                    }
                }
            }
            _ => {
                // Histograms/summaries not persisted in v1 on-agent buffer
                continue;
            }
        }
    }
    out
}

fn sample_from_metric(fam: &MetricFamily, metric: &Metric, fallback_ts_ms: i64) -> Option<Sample> {
    let metric_name = fam.get_name().to_string();
    let ts_ms = if metric.get_timestamp_ms() > 0 {
        metric.get_timestamp_ms()
    } else {
        fallback_ts_ms
    };
    let labels = metric
        .get_label()
        .iter()
        .map(|lp| (lp.get_name().to_string(), lp.get_value().to_string()))
        .collect::<HashMap<_, _>>();

    let value = match fam.get_field_type() {
        MetricType::GAUGE => metric.get_gauge().get_value(),
        MetricType::COUNTER => metric.get_counter().get_value(),
        MetricType::UNTYPED => metric.get_untyped().get_value(),
        _ => return None,
    };

    Some(Sample {
        metric: metric_name,
        labels,
        ts_ms,
        value,
    })
}
