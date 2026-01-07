// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2025 Estimatedstocks AB
use axum::{
    extract::{Json, State},
    http::StatusCode,
    routing::{get, post},
    Router,
};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, RwLock};

pub mod features;

/// ESNODE-Orchestrator Library
/// ---------------------------------------
///
/// Provides the `Orchestrator` struct and associated logic for:
/// 1. Task/Device scheduling (scoring).
/// 2. Autonomous features (zombie reaper, turbo mode, etc.).

// --- Data Models ---

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "lowercase")]
pub enum DeviceKind {
    Cpu,
    Gpu,
    Npu,
    MemoryAccel,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Device {
    pub id: String,
    pub kind: DeviceKind,
    pub peak_flops_tflops: f64,
    pub mem_gb: f64,
    pub power_watts_idle: f64,
    pub power_watts_max: f64,
    pub current_load: f64,
    #[serde(skip)]
    pub last_seen: u64,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum LatencyClass {
    Low,
    Medium,
    High,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Task {
    pub id: String,
    pub est_flops: f64,
    pub est_bytes: f64,
    pub latency_class: LatencyClass,
    pub preferred_kinds: Option<Vec<DeviceKind>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub devices: Vec<Device>,
    pub tasks: Vec<Task>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[allow(clippy::struct_excessive_bools)]
pub struct OrchestratorConfig {
    pub enabled: bool,
    /// Allow exposing orchestrator control API on non-loopback listeners.
    /// Default is false; agents bind to loopback-only for orchestrator routes unless explicitly allowed.
    #[serde(default)]
    pub allow_public: bool,
    /// Optional bearer token required for /orchestrator/* control API.
    /// When set, requests must include `Authorization: Bearer <token>`.
    #[serde(default)]
    pub token: Option<String>,
    // Compute Loop
    pub enable_zombie_reaper: bool,
    pub enable_turbo_mode: bool,
    pub enable_bin_packing: bool,
    pub enable_flash_preemption: bool,
    // Storage/Net Loop
    pub enable_dataset_prefetch: bool,
    pub enable_bandwidth_reserve: bool,
    pub enable_fs_cleanup: bool,
}

impl Default for OrchestratorConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            allow_public: false,
            token: None,
            enable_zombie_reaper: true,
            enable_turbo_mode: false,
            enable_bin_packing: false,
            enable_flash_preemption: false,
            enable_dataset_prefetch: false,
            enable_bandwidth_reserve: false,
            enable_fs_cleanup: false,
        }
    }
}

// --- Orchestrator Logic ---

pub struct Orchestrator {
    pub config: OrchestratorConfig,
    pub devices: HashMap<String, Device>,
    pub pending_tasks: VecDeque<Task>,
    pub alpha_perf: f64,
    pub beta_energy: f64,
    pub gamma_congestion: f64,
    pub delta_data: f64,
}

impl Orchestrator {
    #[must_use]
    pub fn new(initial_devices: Vec<Device>, config: OrchestratorConfig) -> Self {
        Self {
            config,
            devices: initial_devices
                .into_iter()
                .map(|d| (d.id.clone(), d))
                .collect(),
            pending_tasks: VecDeque::new(),
            alpha_perf: 1.0,
            beta_energy: 0.7,
            gamma_congestion: 0.5,
            delta_data: 0.3,
        }
    }

    fn perf_score(task: &Task, dev: &Device) -> f64 {
        let peak_flops = dev.peak_flops_tflops * 1e12_f64;
        let eff_flops = peak_flops * (1.0 - dev.current_load).max(0.1);
        let time_seconds = task.est_flops / eff_flops.max(1e-6);

        let weight = match task.latency_class {
            LatencyClass::High => 1.0,
            LatencyClass::Medium => 0.7,
            LatencyClass::Low => 0.4,
        };
        -time_seconds * weight
    }

    fn energy_score(task: &Task, dev: &Device) -> f64 {
        let peak_flops = dev.peak_flops_tflops * 1e12_f64;
        let eff_flops = peak_flops * (1.0 - dev.current_load).max(0.1);
        let time_seconds = task.est_flops / eff_flops.max(1e-6);
        let effective_load = (dev.current_load + 0.2).min(1.0);
        let power_watts = (dev.power_watts_max - dev.power_watts_idle)
            .mul_add(effective_load, dev.power_watts_idle);
        power_watts * time_seconds
    }

    fn congestion_penalty(dev: &Device) -> f64 {
        dev.current_load.powi(2)
    }

    fn data_movement_cost(task: &Task, _dev: &Device) -> f64 {
        task.est_bytes / 1e9_f64
    }

    fn device_allowed(task: &Task, dev: &Device) -> bool {
        task.preferred_kinds
            .as_ref()
            .is_none_or(|kinds| kinds.contains(&dev.kind))
    }

    #[must_use]
    pub fn pick_device_for_task(&self, task: &Task) -> Option<String> {
        let mut best_id: Option<String> = None;
        let mut best_score: f64 = f64::NEG_INFINITY;

        for (id, dev) in &self.devices {
            if !Self::device_allowed(task, dev) {
                continue;
            }
            // Skip overloaded devices
            if dev.current_load >= 0.95 {
                continue;
            }

            let perf = Self::perf_score(task, dev);
            let energy = Self::energy_score(task, dev);
            let congestion = Self::congestion_penalty(dev);
            let data_cost = Self::data_movement_cost(task, dev);

            let score = self.delta_data.mul_add(
                -data_cost,
                self.gamma_congestion.mul_add(
                    -congestion,
                    self.alpha_perf.mul_add(perf, -(self.beta_energy * energy)),
                ),
            );

            if score > best_score {
                best_score = score;
                best_id = Some(id.clone());
            }
        }
        best_id
    }

    pub fn register_assignment(&mut self, device_id: &str, task: &Task) {
        if let Some(dev) = self.devices.get_mut(device_id) {
            let peak_flops = dev.peak_flops_tflops * 1e12_f64;
            let load_increase = (task.est_flops / peak_flops).min(0.5);
            dev.current_load = (dev.current_load + load_increase).min(1.0);
            tracing::info!(
                "Configuration update: Assigned {} to {} (New Load: {:.1}%)",
                task.id,
                device_id,
                dev.current_load * 100.0
            );
        }
    }

    pub fn update_device(&mut self, dev: Device) {
        self.devices.insert(dev.id.clone(), dev);
    }

    pub fn tick(&mut self) {
        // Scheduler Tick
        let len = self.pending_tasks.len();
        if len > 0 {
            tracing::debug!("Tick: Checking {} pending tasks...", len);
            for _ in 0..len {
                if let Some(task) = self.pending_tasks.pop_front() {
                    if let Some(dev_id) = self.pick_device_for_task(&task) {
                        self.register_assignment(&dev_id, &task);
                    } else {
                        // Still cannot assign, push back
                        self.pending_tasks.push_back(task);
                    }
                }
            }
        }

        // Autonomous Ticks
        if self.config.enable_zombie_reaper {
            features::reaper::check_zombies(self);
        }
        if self.config.enable_turbo_mode {
            features::turbo::check_priorities(self);
        }
        if self.config.enable_bin_packing {
            features::packing::optimize_packing(self);
        }
        if self.config.enable_flash_preemption {
            features::preemption::check_preemption(self);
        }
        if self.config.enable_dataset_prefetch {
            features::prefetch::check_prefetch(self);
        }
        if self.config.enable_bandwidth_reserve {
            features::traffic::check_bandwidth(self);
        }
        if self.config.enable_fs_cleanup {
            features::cleanup::check_filesystem(self);
        }
    }
}

// --- App State & Handlers ---

#[derive(Clone)]
pub struct AppState {
    pub orchestrator: Arc<RwLock<Orchestrator>>,
    pub token: Option<String>,
}

#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct PubMetrics {
    pub device_count: usize,
    pub pending_tasks: usize,
    pub devices: Vec<Device>,
}

async fn heartbeat_handler(
    State(state): State<AppState>,
    headers: axum::http::HeaderMap,
    Json(device): Json<Device>,
) -> Result<Json<String>, StatusCode> {
    authorize(&headers, &state.token)?;
    let mut orch = state.orchestrator.write().unwrap();
    let id = device.id.clone();
    orch.update_device(device);
    tracing::info!(
        target: "audit",
        action = "orchestrator_heartbeat",
        device = %id,
        devices_total = orch.devices.len()
    );
    Ok(Json(format!("Device '{id}' registered/updated.")))
}

#[derive(Serialize)]
pub struct TaskSubmissionResponse {
    pub status: String,
    pub assigned_device: Option<String>,
}

async fn submit_task_handler(
    State(state): State<AppState>,
    headers: axum::http::HeaderMap,
    Json(task): Json<Task>,
) -> Result<Json<TaskSubmissionResponse>, StatusCode> {
    authorize(&headers, &state.token)?;
    let mut orch = state.orchestrator.write().unwrap();

    // Try to schedule immediately
    if let Some(dev_id) = orch.pick_device_for_task(&task) {
        orch.register_assignment(&dev_id, &task);
        tracing::info!(target: "audit", action = "orchestrator_task_assigned", task = %task.id, device = %dev_id);
        Ok(Json(TaskSubmissionResponse {
            status: "Assigned".to_string(),
            assigned_device: Some(dev_id),
        }))
    } else {
        orch.pending_tasks.push_back(task.clone());
        tracing::info!(target: "audit", action = "orchestrator_task_queued", task = %task.id, queue_len = orch.pending_tasks.len());
        Ok(Json(TaskSubmissionResponse {
            status: "Queued".to_string(),
            assigned_device: None,
        }))
    }
}

async fn metrics_handler(
    State(state): State<AppState>,
    headers: axum::http::HeaderMap,
) -> Result<Json<PubMetrics>, StatusCode> {
    authorize(&headers, &state.token)?;
    let orch = state.orchestrator.read().unwrap();
    let snapshot = PubMetrics {
        device_count: orch.devices.len(),
        pending_tasks: orch.pending_tasks.len(),
        devices: orch.devices.values().cloned().collect(),
    };
    tracing::info!(
        target: "audit",
        action = "orchestrator_metrics",
        device_count = snapshot.device_count,
        pending_tasks = snapshot.pending_tasks
    );
    Ok(Json(snapshot))
}

fn authorize(headers: &axum::http::HeaderMap, token: &Option<String>) -> Result<(), StatusCode> {
    if let Some(tok) = token {
        let expected = format!("Bearer {tok}");
        if let Some(h) = headers.get(axum::http::header::AUTHORIZATION) {
            if h.to_str().ok() == Some(expected.as_str()) {
                tracing::info!(target: "audit", action = "orchestrator_auth_ok", token_present = true);
                return Ok(());
            }
        }
        tracing::warn!(
            target: "audit",
            action = "orchestrator_auth_fail",
            token_present = headers.contains_key(axum::http::header::AUTHORIZATION)
        );
        return Err(StatusCode::UNAUTHORIZED);
    }
    Ok(())
}

// --- Public Integration API ---

pub fn routes(state: AppState) -> Router {
    Router::new()
        .route("/register", post(heartbeat_handler))
        .route("/submit", post(submit_task_handler))
        .route("/metrics", get(metrics_handler))
        .with_state(state)
}

pub async fn run_loop(state: AppState) {
    loop {
        tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
        let mut orch = state.orchestrator.write().unwrap();
        orch.tick();
    }
}
