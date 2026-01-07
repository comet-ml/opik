// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use std::time::Duration;

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum LogLevel {
    Error,
    Warn,
    #[default]
    Info,
    Debug,
    Trace,
}

impl LogLevel {
    #[must_use]
    pub const fn as_tracing(&self) -> tracing::Level {
        match self {
            Self::Error => tracing::Level::ERROR,
            Self::Warn => tracing::Level::WARN,
            Self::Info => tracing::Level::INFO,
            Self::Debug => tracing::Level::DEBUG,
            Self::Trace => tracing::Level::TRACE,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentConfig {
    pub listen_address: String,
    #[serde(with = "humantime_serde", alias = "scrape_interval_seconds")]
    pub scrape_interval: Duration,
    pub enable_cpu: bool,
    pub enable_memory: bool,
    pub enable_disk: bool,
    pub enable_network: bool,
    pub enable_gpu: bool,
    #[serde(default)]
    pub enable_gpu_amd: bool,
    pub enable_power: bool,
    #[serde(default)]
    pub enable_gpu_mig: bool,
    #[serde(default)]
    pub enable_gpu_events: bool,
    #[serde(default)]
    pub gpu_visible_devices: Option<String>,
    #[serde(default)]
    pub mig_config_devices: Option<String>,
    #[serde(default)]
    pub k8s_mode: bool,
    #[serde(default)]
    pub enable_mcp: bool,
    #[serde(default)]
    pub enable_app: bool,
    #[serde(default)]
    pub enable_rack_thermals: bool,
    #[serde(default)]
    pub managed_server: Option<String>,
    #[serde(default)]
    pub managed_cluster_id: Option<String>,
    #[serde(default)]
    pub managed_node_id: Option<String>,
    #[serde(default)]
    pub managed_join_token: Option<String>,
    #[serde(default)]
    pub managed_last_contact_unix_ms: Option<u64>,
    #[serde(default)]
    pub node_power_envelope_watts: Option<f64>,
    #[serde(default)]
    pub enable_local_tsdb: bool,
    #[serde(default = "default_local_tsdb_path")]
    pub local_tsdb_path: String,
    #[serde(default = "default_local_tsdb_retention_hours")]
    pub local_tsdb_retention_hours: u64,
    #[serde(default = "default_local_tsdb_max_disk_mb")]
    pub local_tsdb_max_disk_mb: u64,
    pub log_level: LogLevel,
    #[serde(default)]
    pub orchestrator: Option<esnode_orchestrator::OrchestratorConfig>,
    #[serde(default)]
    pub app_metrics_url: String,
}

impl Default for AgentConfig {
    fn default() -> Self {
        Self {
            listen_address: "0.0.0.0:9100".to_string(),
            scrape_interval: Duration::from_secs(5),
            enable_cpu: true,
            enable_memory: true,
            enable_disk: true,
            enable_network: true,
            enable_gpu: true,
            enable_gpu_amd: false,
            enable_power: true,
            enable_gpu_mig: false,
            enable_gpu_events: false,
            gpu_visible_devices: None,
            mig_config_devices: None,
            k8s_mode: false,
            enable_mcp: false,
            enable_app: false,
            enable_rack_thermals: false,
            managed_server: None,
            managed_cluster_id: None,
            managed_node_id: None,
            managed_join_token: None,
            managed_last_contact_unix_ms: None,
            node_power_envelope_watts: None,
            enable_local_tsdb: true,
            local_tsdb_path: default_local_tsdb_path(),
            local_tsdb_retention_hours: default_local_tsdb_retention_hours(),
            local_tsdb_max_disk_mb: default_local_tsdb_max_disk_mb(),
            log_level: LogLevel::Info,
            orchestrator: None,
            app_metrics_url: "http://127.0.0.1:8000/metrics".to_string(),
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ConfigOverrides {
    pub listen_address: Option<String>,
    #[serde(with = "humantime_serde::option", alias = "scrape_interval_seconds")]
    pub scrape_interval: Option<Duration>,
    pub enable_cpu: Option<bool>,
    pub enable_memory: Option<bool>,
    pub enable_disk: Option<bool>,
    pub enable_network: Option<bool>,
    pub enable_gpu: Option<bool>,
    pub enable_gpu_amd: Option<bool>,
    pub enable_power: Option<bool>,
    #[serde(default)]
    pub enable_gpu_mig: Option<bool>,
    #[serde(default)]
    pub enable_gpu_events: Option<bool>,
    #[serde(default)]
    pub gpu_visible_devices: Option<Option<String>>,
    #[serde(default)]
    pub mig_config_devices: Option<Option<String>>,
    #[serde(default)]
    pub k8s_mode: Option<bool>,
    #[serde(default)]
    pub enable_mcp: Option<bool>,
    #[serde(default)]
    pub enable_app: Option<bool>,
    #[serde(default)]
    pub enable_rack_thermals: Option<bool>,
    #[serde(default)]
    pub managed_server: Option<Option<String>>,
    #[serde(default)]
    pub managed_cluster_id: Option<Option<String>>,
    #[serde(default)]
    pub managed_node_id: Option<Option<String>>,
    #[serde(default)]
    pub managed_join_token: Option<Option<String>>,
    #[serde(default)]
    pub managed_last_contact_unix_ms: Option<Option<u64>>,
    #[serde(default)]
    pub node_power_envelope_watts: Option<f64>,
    #[serde(default)]
    pub enable_local_tsdb: Option<bool>,
    #[serde(default)]
    pub local_tsdb_path: Option<String>,
    #[serde(default)]
    pub local_tsdb_retention_hours: Option<u64>,
    #[serde(default)]
    pub local_tsdb_max_disk_mb: Option<u64>,
    pub log_level: Option<LogLevel>,
    #[serde(default)]
    pub orchestrator: Option<esnode_orchestrator::OrchestratorConfig>,
    pub app_metrics_url: Option<String>,
}

impl AgentConfig {
    pub fn apply_overrides(&mut self, overrides: ConfigOverrides) {
        if let Some(listen_address) = overrides.listen_address {
            self.listen_address = listen_address;
        }
        if let Some(orch) = overrides.orchestrator {
            self.orchestrator = Some(orch);
        }
        if let Some(scrape_interval) = overrides.scrape_interval {
            self.scrape_interval = scrape_interval;
        }
        if let Some(enable_cpu) = overrides.enable_cpu {
            self.enable_cpu = enable_cpu;
        }
        if let Some(enable_memory) = overrides.enable_memory {
            self.enable_memory = enable_memory;
        }
        if let Some(enable_disk) = overrides.enable_disk {
            self.enable_disk = enable_disk;
        }
        if let Some(enable_network) = overrides.enable_network {
            self.enable_network = enable_network;
        }
        if let Some(enable_gpu) = overrides.enable_gpu {
            self.enable_gpu = enable_gpu;
        }
        if let Some(enable_gpu_amd) = overrides.enable_gpu_amd {
            self.enable_gpu_amd = enable_gpu_amd;
        }
        if let Some(enable_power) = overrides.enable_power {
            self.enable_power = enable_power;
        }
        if let Some(enable_gpu_mig) = overrides.enable_gpu_mig {
            self.enable_gpu_mig = enable_gpu_mig;
        }
        if let Some(enable_gpu_events) = overrides.enable_gpu_events {
            self.enable_gpu_events = enable_gpu_events;
        }
        if let Some(gpu_visible_devices) = overrides.gpu_visible_devices {
            self.gpu_visible_devices = gpu_visible_devices;
        }
        if let Some(mig_config_devices) = overrides.mig_config_devices {
            self.mig_config_devices = mig_config_devices;
        }
        if let Some(k8s_mode) = overrides.k8s_mode {
            self.k8s_mode = k8s_mode;
        }
        if let Some(enable_mcp) = overrides.enable_mcp {
            self.enable_mcp = enable_mcp;
        }
        if let Some(enable_app) = overrides.enable_app {
            self.enable_app = enable_app;
        }
        if let Some(enable_rack_thermals) = overrides.enable_rack_thermals {
            self.enable_rack_thermals = enable_rack_thermals;
        }
        if let Some(managed_server) = overrides.managed_server {
            self.managed_server = managed_server;
        }
        if let Some(managed_cluster_id) = overrides.managed_cluster_id {
            self.managed_cluster_id = managed_cluster_id;
        }
        if let Some(managed_node_id) = overrides.managed_node_id {
            self.managed_node_id = managed_node_id;
        }
        if let Some(managed_join_token) = overrides.managed_join_token {
            self.managed_join_token = managed_join_token;
        }
        if let Some(last_contact) = overrides.managed_last_contact_unix_ms {
            self.managed_last_contact_unix_ms = last_contact;
        }
        if let Some(envelope) = overrides.node_power_envelope_watts {
            self.node_power_envelope_watts = Some(envelope);
        }
        if let Some(enable_local_tsdb) = overrides.enable_local_tsdb {
            self.enable_local_tsdb = enable_local_tsdb;
        }
        if let Some(local_tsdb_path) = overrides.local_tsdb_path {
            self.local_tsdb_path = local_tsdb_path;
        }
        if let Some(retention) = overrides.local_tsdb_retention_hours {
            self.local_tsdb_retention_hours = retention;
        }
        if let Some(max_mb) = overrides.local_tsdb_max_disk_mb {
            self.local_tsdb_max_disk_mb = max_mb;
        }
        if let Some(log_level) = overrides.log_level {
            self.log_level = log_level;
        }
        if let Some(url) = overrides.app_metrics_url {
            self.app_metrics_url = url;
        }
    }
}

fn default_local_tsdb_path() -> String {
    if let Ok(path) = std::env::var("XDG_DATA_HOME") {
        format!("{path}/esnode/tsdb")
    } else if let Ok(home) = std::env::var("HOME") {
        format!("{home}/.local/share/esnode/tsdb")
    } else {
        "/var/lib/esnode/tsdb".to_string()
    }
}

const fn default_local_tsdb_retention_hours() -> u64 {
    48
}

const fn default_local_tsdb_max_disk_mb() -> u64 {
    2048
}
