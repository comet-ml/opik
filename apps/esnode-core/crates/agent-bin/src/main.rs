// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
mod client;
mod console;

use std::{
    fs,
    path::{Path, PathBuf},
    time::Duration,
};

use agent_core::{Agent, AgentConfig, ConfigOverrides, LogLevel};
use anyhow::{anyhow, bail, Context, Result};
use clap::{Parser, Subcommand, ValueEnum};
use client::AgentClient;
use console::{run_console, AgentMode, ManagedMetadata};
use tracing_subscriber::{fmt, EnvFilter};

#[derive(Parser, Debug)]
#[command(name = "esnode-core", about = "GPU-aware host metrics exporter")]
struct Cli {
    /// Optional path to configuration file (TOML). Also read from `ESNODE_CONFIG`.
    #[arg(long, env = "ESNODE_CONFIG")]
    config: Option<PathBuf>,

    /// Disable ANSI colors (applies to TUI + non-interactive output).
    #[arg(long)]
    no_color: bool,

    /// Address for HTTP listener, e.g. 0.0.0.0:9100
    #[arg(long, env = "ESNODE_LISTEN_ADDRESS")]
    listen_address: Option<String>,

    /// Scrape interval (e.g. 5s, 1m)
    #[arg(long, env = "ESNODE_SCRAPE_INTERVAL")]
    scrape_interval: Option<String>,

    /// Enable or disable CPU collector
    #[arg(long, env = "ESNODE_ENABLE_CPU")]
    enable_cpu: Option<bool>,

    /// Enable or disable memory collector
    #[arg(long, env = "ESNODE_ENABLE_MEMORY")]
    enable_memory: Option<bool>,

    /// Enable or disable disk collector
    #[arg(long, env = "ESNODE_ENABLE_DISK")]
    enable_disk: Option<bool>,

    /// Enable or disable network collector
    #[arg(long, env = "ESNODE_ENABLE_NETWORK")]
    enable_network: Option<bool>,

    /// Enable or disable GPU collector
    #[arg(long, env = "ESNODE_ENABLE_GPU")]
    enable_gpu: Option<bool>,

    /// Enable or disable AMD GPU collector (`ROCm`)
    #[arg(long, env = "ESNODE_ENABLE_GPU_AMD")]
    enable_gpu_amd: Option<bool>,

    /// Enable or disable MIG telemetry (requires GPU feature; guarded).
    #[arg(long, env = "ESNODE_ENABLE_GPU_MIG")]
    enable_gpu_mig: Option<bool>,

    /// Enable or disable GPU event polling (XID/ECC/retire/throttle).
    #[arg(long, env = "ESNODE_ENABLE_GPU_EVENTS")]
    enable_gpu_events: Option<bool>,

    /// Optional filter for visible GPUs (UUIDs or indices, comma separated).
    #[arg(long, env = "ESNODE_GPU_VISIBLE_DEVICES")]
    gpu_visible_devices: Option<String>,

    /// Optional filter for GPUs where MIG can be managed (UUIDs or indices).
    #[arg(long, env = "ESNODE_MIG_CONFIG_DEVICES")]
    mig_config_devices: Option<String>,

    /// Enable Kubernetes-compatible resource/label naming.
    #[arg(long, env = "ESNODE_K8S_MODE")]
    k8s_mode: Option<bool>,

    /// Enable or disable power collector (CPU/package/hwmon/BMC if available)
    #[arg(long, env = "ESNODE_ENABLE_POWER")]
    enable_power: Option<bool>,

    /// Optional node power envelope in watts (for breach flag)
    #[arg(long, env = "ESNODE_NODE_POWER_ENVELOPE_WATTS")]
    node_power_envelope_watts: Option<f64>,

    /// Enable lightweight on-agent TSDB buffer (JSONL-backed).
    #[arg(long, env = "ESNODE_ENABLE_LOCAL_TSDB")]
    enable_local_tsdb: Option<bool>,

    /// Filesystem path for the agent TSDB (when enabled).
    #[arg(long, env = "ESNODE_LOCAL_TSDB_PATH")]
    local_tsdb_path: Option<String>,

    /// Retention window for the on-agent TSDB (hours).
    #[arg(long, env = "ESNODE_LOCAL_TSDB_RETENTION_HOURS")]
    local_tsdb_retention_hours: Option<u64>,

    /// Maximum disk budget for the on-agent TSDB (MB).
    #[arg(long, env = "ESNODE_LOCAL_TSDB_MAX_DISK_MB")]
    local_tsdb_max_disk_mb: Option<u64>,

    /// If set, indicates this agent is managed by an ESNODE-Pulse (read-only console).
    #[arg(long, env = "ESNODE_MANAGED_SERVER")]
    managed_server: Option<String>,

    /// Optional cluster ID when managed by server.
    #[arg(long, env = "ESNODE_MANAGED_CLUSTER_ID")]
    managed_cluster_id: Option<String>,

    /// Optional node ID when managed by server.
    #[arg(long, env = "ESNODE_MANAGED_NODE_ID")]
    managed_node_id: Option<String>,

    /// Enable ESNODE-Orchestrator (Autonomous features)
    #[arg(long, env = "ESNODE_ENABLE_ORCHESTRATOR")]
    pub enable_orchestrator: Option<bool>,

    /// Enable App/Model Awareness collector
    #[arg(long, env = "ESNODE_ENABLE_APP")]
    pub enable_app: Option<bool>,

    /// URL for application metrics (e.g. <http://localhost:8000/metrics>)
    #[arg(long, env = "ESNODE_APP_METRICS_URL")]
    pub app_metrics_url: Option<String>,

    /// Log level (error, warn, info, debug, trace)
    #[arg(long, env = "ESNODE_LOG_LEVEL")]
    log_level: Option<String>,

    #[command(subcommand)]
    command: Option<Command>,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Default daemon mode: run the agent and expose /metrics.
    Daemon,
    /// One-shot node summary (CPU/mem/GPU/power).
    Status,
    /// Dump metrics snapshot in human-readable form.
    Metrics {
        #[arg(value_enum, default_value_t = MetricsProfile::Basic)]
        profile: MetricsProfile,
    },
    /// Enable a metric set and persist it to config.
    EnableMetricSet {
        #[arg(value_enum)]
        set: MetricSet,
    },
    /// Disable a metric set and persist it to config.
    DisableMetricSet {
        #[arg(value_enum)]
        set: MetricSet,
    },
    /// List metric profiles and what they include.
    Profiles,
    /// Run quick self-check for GPU API, permissions, filesystem, etc.
    Diagnostics,
    /// Launch the AS/400-inspired console UI.
    Cli,
    /// View or modify agent config.
    Config {
        #[command(subcommand)]
        action: ConfigCommand,
    },
    /// Server control-plane commands.
    Server {
        #[command(subcommand)]
        action: ServerCommand,
    },
}

#[derive(Debug, Subcommand)]
enum ConfigCommand {
    Show,
    Set {
        /// Key-value pair (key=value) to persist into esnode.toml.
        key_value: String,
    },
}

#[derive(Copy, Clone, Debug, ValueEnum)]
enum MetricsProfile {
    Basic,
    Full,
    GpuOnly,
    PowerOnly,
}

#[derive(Debug, Subcommand)]
enum ServerCommand {
    /// Connect this agent to an ESNODE-Pulse and persist config.
    Connect {
        #[arg(long)]
        address: String,
        #[arg(long)]
        _token: Option<String>,
    },
    /// Disconnect from an ESNODE-Pulse (return to standalone).
    Disconnect,
    /// Show connection status.
    Status,
}

#[derive(Copy, Clone, Debug, ValueEnum)]
enum MetricSet {
    Host,
    Gpu,
    Power,
    Mcp,
    App,
    All,
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();
    let config_path = resolve_config_path(&cli);
    let mut config = AgentConfig::default();

    if config_path.exists() {
        let file_overrides = load_config_file(&config_path)
            .with_context(|| format!("failed to read config file {}", config_path.display()))?;
        config.apply_overrides(file_overrides);
    }

    let cli_overrides = cli_to_overrides(&cli)?;
    config.apply_overrides(cli_overrides);

    match cli.command.as_ref().unwrap_or(&Command::Daemon) {
        Command::Daemon => {
            init_tracing(&config);
            tracing::info!("Starting ESNODE-Core with config: {:?}", config);
            let agent = Agent::new(config)?;
            agent.run().await
        }
        Command::Status => {
            let client = AgentClient::new(&config.listen_address);
            command_status(&client, cli.no_color)
        }
        Command::Metrics { profile } => {
            let client = AgentClient::new(&config.listen_address);
            command_metrics(&client, *profile)
        }
        Command::EnableMetricSet { set } => command_toggle_metric_set(&config_path, *set, true),
        Command::DisableMetricSet { set } => command_toggle_metric_set(&config_path, *set, false),
        Command::Profiles => {
            command_profiles();
            Ok(())
        }
        Command::Diagnostics => {
            let client = AgentClient::new(&config.listen_address);
            command_diagnostics(&client)
        }
        Command::Cli => {
            let client = AgentClient::new(&config.listen_address);
            let mode = agent_mode(&config);
            run_console(
                &client,
                cli.no_color,
                mode,
                config_path.clone(),
                config.clone(),
            )
        }
        Command::Server { action } => {
            match action {
                ServerCommand::Connect { address, _token } => {
                    command_server_connect(&config_path, &config, address, None)?;
                }
                ServerCommand::Disconnect => command_server_disconnect(&config_path, &config)?,
                ServerCommand::Status => command_server_status(&config),
            }
            Ok(())
        }
        Command::Config { action } => match action {
            ConfigCommand::Show => command_config_show(&config_path, &config),
            ConfigCommand::Set { key_value } => command_config_set(&config_path, key_value),
        },
    }
}

fn resolve_config_path(cli: &Cli) -> PathBuf {
    if let Some(path) = &cli.config {
        path.clone()
    } else {
        PathBuf::from("esnode.toml")
    }
}

fn load_config_file(path: &Path) -> Result<ConfigOverrides> {
    let contents = fs::read_to_string(path)?;
    let overrides: ConfigOverrides = toml::from_str(&contents)?;
    Ok(overrides)
}

fn cli_to_overrides(cli: &Cli) -> Result<ConfigOverrides> {
    let orchestrator = if cli.enable_orchestrator.unwrap_or(false) {
        Some(esnode_orchestrator::OrchestratorConfig {
            enabled: true,
            ..Default::default()
        })
    } else {
        None
    };

    Ok(ConfigOverrides {
        listen_address: cli.listen_address.clone(),
        scrape_interval: parse_duration(cli.scrape_interval.as_deref())?,
        enable_cpu: cli.enable_cpu,
        enable_memory: cli.enable_memory,
        enable_disk: cli.enable_disk,
        enable_network: cli.enable_network,
        enable_gpu: cli.enable_gpu,
        enable_gpu_amd: cli.enable_gpu_amd,
        enable_gpu_mig: cli.enable_gpu_mig,
        enable_gpu_events: cli.enable_gpu_events,
        gpu_visible_devices: cli.gpu_visible_devices.clone().map(Some),
        mig_config_devices: cli.mig_config_devices.clone().map(Some),
        k8s_mode: cli.k8s_mode,
        enable_power: cli.enable_power,
        enable_mcp: None,
        enable_app: cli.enable_app,
        app_metrics_url: cli.app_metrics_url.clone(),
        enable_rack_thermals: None,
        managed_server: cli.managed_server.clone().map(Some),
        managed_cluster_id: cli.managed_cluster_id.clone().map(Some),
        managed_node_id: cli.managed_node_id.clone().map(Some),
        managed_join_token: None,
        managed_last_contact_unix_ms: None,
        node_power_envelope_watts: cli.node_power_envelope_watts,
        enable_local_tsdb: cli.enable_local_tsdb,
        local_tsdb_path: cli.local_tsdb_path.clone(),
        local_tsdb_retention_hours: cli.local_tsdb_retention_hours,
        local_tsdb_max_disk_mb: cli.local_tsdb_max_disk_mb,
        log_level: parse_log_level(cli.log_level.as_deref())?,
        orchestrator,
    })
}

fn parse_duration(input: Option<&str>) -> Result<Option<Duration>> {
    if let Some(value) = input {
        let duration = humantime::parse_duration(value)?;
        Ok(Some(duration))
    } else {
        Ok(None)
    }
}

fn parse_log_level(input: Option<&str>) -> Result<Option<LogLevel>> {
    if let Some(level) = input {
        let parsed = match level.to_ascii_lowercase().as_str() {
            "error" => LogLevel::Error,
            "warn" | "warning" => LogLevel::Warn,
            "info" => LogLevel::Info,
            "debug" => LogLevel::Debug,
            "trace" => LogLevel::Trace,
            other => bail!("unknown log level {other}"),
        };
        Ok(Some(parsed))
    } else {
        Ok(None)
    }
}

fn init_tracing(config: &AgentConfig) {
    let env_filter =
        EnvFilter::from_default_env().add_directive(config.log_level.as_tracing().into());
    let subscriber = fmt().with_env_filter(env_filter).finish();
    let _ = tracing::subscriber::set_global_default(subscriber);
}

fn command_status(client: &AgentClient, no_color: bool) -> Result<()> {
    use std::fmt::Write;
    let _ = no_color;
    let snapshot = client.fetch_status()?;
    let mut out = String::new();
    out.push_str("Node status\n");
    out.push_str("ESNODE status (basic profile)\n");
    writeln!(
        &mut out,
        "  Healthy: {}",
        if snapshot.healthy { "yes" } else { "no" }
    )?;
    writeln!(&mut out, "  Load 1m: {:.2}", snapshot.load_avg_1m)?;
    writeln!(
        &mut out,
        "  Node power: {}",
        snapshot
            .node_power_watts
            .map_or_else(|| "n/a".to_string(), |v| format!("{v:.1} W"))
    )?;
    writeln!(&mut out, "  GPUs: {} detected", snapshot.gpus.len())?;
    let avg_util = average(
        &snapshot
            .gpus
            .iter()
            .filter_map(|g| g.util_percent)
            .collect::<Vec<_>>(),
    );
    let avg_power = average(
        &snapshot
            .gpus
            .iter()
            .filter_map(|g| g.power_watts)
            .collect::<Vec<_>>(),
    );
    writeln!(&mut out, "    Avg util: {:.1}%", avg_util.unwrap_or(0.0))?;
    writeln!(&mut out, "    Avg power: {:.1} W", avg_power.unwrap_or(0.0))?;
    if !snapshot.last_errors.is_empty() {
        out.push_str("  Recent errors:\n");
        for err in &snapshot.last_errors {
            writeln!(
                &mut out,
                "    [{}] {} ({})",
                err.collector, err.message, err.unix_ms
            )?;
        }
    }
    print!("{out}");
    Ok(())
}

fn average(values: &[f64]) -> Option<f64> {
    if values.is_empty() {
        None
    } else {
        Some(values.iter().sum::<f64>() / values.len() as f64)
    }
}

fn command_metrics(client: &AgentClient, profile: MetricsProfile) -> Result<()> {
    println!("ESNODE metrics snapshot (profile: {profile:?})");
    match client.fetch_metrics_text() {
        Ok(body) => {
            println!("{body}");
            Ok(())
        }
        Err(err) => bail!("unable to fetch /metrics: {err}"),
    }
}

fn command_profiles() {
    println!("Available profiles:");
    println!("  basic     -> host + gpu core + power");
    println!("  full      -> everything (host, gpu, power, mcp, app)");
    println!("  gpu-only  -> GPU core + power only");
    println!("  power-only-> host power + gpu power");
}

fn command_toggle_metric_set(path: &Path, set: MetricSet, enable: bool) -> Result<()> {
    let mut config = AgentConfig::default();
    if path.exists() {
        let file_overrides = load_config_file(path)?;
        config.apply_overrides(file_overrides);
    }
    ensure_local_control(&config)?;

    match set {
        MetricSet::Host => {
            config.enable_cpu = enable;
            config.enable_memory = enable;
            config.enable_disk = enable;
            config.enable_network = enable;
        }
        MetricSet::Gpu => {
            config.enable_gpu = enable;
        }
        MetricSet::Power => {
            config.enable_power = enable;
        }
        MetricSet::Mcp => {
            config.enable_mcp = enable;
        }
        MetricSet::App => {
            config.enable_app = enable;
        }
        MetricSet::All => {
            config.enable_cpu = enable;
            config.enable_memory = enable;
            config.enable_disk = enable;
            config.enable_network = enable;
            config.enable_gpu = enable;
            config.enable_power = enable;
            config.enable_mcp = enable;
            config.enable_app = enable;
            config.enable_rack_thermals = enable;
        }
    }

    persist_config(path, &config)?;
    println!(
        "{} metric set {:?} in {}",
        if enable { "Enabled" } else { "Disabled" },
        set,
        path.display()
    );
    Ok(())
}

fn persist_config(path: &Path, config: &AgentConfig) -> Result<()> {
    let contents = toml::to_string_pretty(config)?;
    fs::write(path, contents)?;
    Ok(())
}

fn command_diagnostics(client: &AgentClient) -> Result<()> {
    println!("Running ESNODE diagnostics...");
    match client.fetch_status() {
        Ok(status) => {
            println!("  Agent reachable at {}", client.base_url());
            println!(
                "  GPU status entries: {} ({} recent errors)",
                status.gpus.len(),
                status.last_errors.len()
            );
            println!(
                "  Node power: {}",
                status
                    .node_power_watts
                    .map_or_else(|| "n/a".to_string(), |v| format!("{v:.1} W"))
            );
            Ok(())
        }
        Err(err) => bail!("agent not reachable: {err}"),
    }
}

fn command_config_show(path: &Path, effective: &AgentConfig) -> Result<()> {
    println!("Config path: {}", path.display());
    println!("{}", toml::to_string_pretty(effective)?);
    Ok(())
}

fn command_config_set(path: &Path, pair: &str) -> Result<()> {
    let (key, value) = pair
        .split_once('=')
        .ok_or_else(|| anyhow!("use key=value syntax"))?;
    let mut config = AgentConfig::default();
    if path.exists() {
        let file_overrides = load_config_file(path)?;
        config.apply_overrides(file_overrides);
    }
    ensure_local_control(&config)?;
    apply_config_kv(&mut config, key, value)?;
    persist_config(path, &config)?;
    println!("Updated {} in {}", key, path.display());
    Ok(())
}

fn apply_config_kv(config: &mut AgentConfig, key: &str, val: &str) -> Result<()> {
    match key {
        "listen_address" => config.listen_address = val.to_string(),
        "scrape_interval" => config.scrape_interval = parse_duration(Some(val))?.unwrap(),
        "enable_cpu" => config.enable_cpu = val.parse()?,
        "enable_memory" => config.enable_memory = val.parse()?,
        "enable_disk" => config.enable_disk = val.parse()?,
        "enable_network" => config.enable_network = val.parse()?,
        "enable_gpu" => config.enable_gpu = val.parse()?,
        "enable_power" => config.enable_power = val.parse()?,
        "enable_mcp" => config.enable_mcp = val.parse()?,
        "enable_app" => config.enable_app = val.parse()?,
        "enable_rack_thermals" => config.enable_rack_thermals = val.parse()?,
        "managed_server" => config.managed_server = Some(val.to_string()),
        "managed_cluster_id" => config.managed_cluster_id = Some(val.to_string()),
        "managed_node_id" => config.managed_node_id = Some(val.to_string()),
        "managed_join_token" => config.managed_join_token = Some(val.to_string()),
        "managed_last_contact_unix_ms" => config.managed_last_contact_unix_ms = Some(val.parse()?),
        "node_power_envelope_watts" => config.node_power_envelope_watts = Some(val.parse()?),
        "log_level" => config.log_level = parse_log_level(Some(val))?.unwrap(),
        other => bail!("unknown config key {other}"),
    }
    Ok(())
}

fn ensure_local_control(config: &AgentConfig) -> Result<()> {
    if let Some(server) = &config.managed_server {
        bail!("This node is managed by ESNODE-Pulse ({server}); local control is disabled");
    }
    Ok(())
}

fn agent_mode(config: &AgentConfig) -> AgentMode {
    config
        .managed_server
        .as_ref()
        .map_or(AgentMode::Standalone, |srv| {
            AgentMode::Managed(ManagedMetadata {
                server: Some(srv.clone()),
                cluster_id: config.managed_cluster_id.clone(),
                node_id: config.managed_node_id.clone(),
                last_contact_unix_ms: config.managed_last_contact_unix_ms,
                state: if config.managed_last_contact_unix_ms.is_some() {
                    "CONNECTED".to_string()
                } else {
                    "DEGRADED".to_string()
                },
            })
        })
}

fn command_server_connect(
    path: &Path,
    current: &AgentConfig,
    address: &str,
    token: Option<String>,
) -> Result<()> {
    let mut config = current.clone();
    config.managed_server = Some(address.to_string());
    config.managed_join_token.clone_from(&token);
    if config.managed_node_id.is_none() {
        config.managed_node_id = Some(default_node_id());
    }
    if config.managed_cluster_id.is_none() {
        config.managed_cluster_id = Some("unknown-cluster".to_string());
    }
    config.managed_last_contact_unix_ms = Some(chrono::Utc::now().timestamp_millis() as u64);
    persist_config(path, &config)?;
    println!("Connected to ESNODE-Pulse at {address}");
    if let Some(_tok) = token {
        println!("Join token persisted");
    }
    Ok(())
}

fn command_server_disconnect(path: &Path, current: &AgentConfig) -> Result<()> {
    let mut config = current.clone();
    config.managed_server = None;
    config.managed_cluster_id = None;
    config.managed_node_id = None;
    config.managed_join_token = None;
    config.managed_last_contact_unix_ms = None;
    persist_config(path, &config)?;
    println!("Disconnected from ESNODE-Pulse; standalone mode restored");
    Ok(())
}

fn command_server_status(config: &AgentConfig) {
    if let Some(server) = &config.managed_server {
        let degraded = config.managed_last_contact_unix_ms.is_none();
        println!("Managed by ESNODE-Pulse");
        println!("  Server: {server}");
        println!(
            "  Cluster ID: {}",
            config
                .managed_cluster_id
                .clone()
                .unwrap_or_else(|| "-".to_string())
        );
        println!(
            "  Node ID: {}",
            config
                .managed_node_id
                .clone()
                .unwrap_or_else(default_node_id)
        );
        if let Some(last) = config.managed_last_contact_unix_ms {
            println!("  Last contact (unix ms): {last}");
        } else {
            println!("  Last contact: unknown (degraded)");
        }
        println!(
            "  State: {}",
            if degraded { "DEGRADED" } else { "CONNECTED" }
        );
    } else {
        println!("Not connected to any ESNODE-Pulse (standalone)");
    }
}

fn default_node_id() -> String {
    std::env::var("HOSTNAME").unwrap_or_else(|_| "node-unknown".to_string())
}

#[cfg(test)]
mod tests {
    use super::{cli_to_overrides, Cli, Command, MetricSet, MetricsProfile};
    use clap::Parser;

    #[test]
    fn cli_parses_status_command() {
        let cli = Cli::parse_from(["esnode-core", "status"]);
        assert!(matches!(cli.command, Some(Command::Status)));
    }

    #[test]
    fn cli_parses_metrics_command_with_profile() {
        let cli = Cli::parse_from(["esnode-core", "metrics", "full"]);
        match cli.command {
            Some(Command::Metrics { profile }) => assert!(matches!(profile, MetricsProfile::Full)),
            other => panic!("unexpected {other:?}"),
        }
    }

    #[test]
    fn cli_overrides_enable_flags() {
        let cli = Cli::parse_from([
            "esnode-core",
            "--enable-cpu",
            "false",
            "--enable-network",
            "false",
            "enable-metric-set",
            "gpu",
        ]);
        let overrides = cli_to_overrides(&cli).unwrap();
        assert_eq!(overrides.enable_cpu, Some(false));
        assert_eq!(overrides.enable_network, Some(false));
        match cli.command {
            Some(Command::EnableMetricSet { set }) => assert!(matches!(set, MetricSet::Gpu)),
            _ => panic!("expected enable-metric-set"),
        }
    }
}
