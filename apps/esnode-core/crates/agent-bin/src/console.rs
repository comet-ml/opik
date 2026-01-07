// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use std::{
    fs,
    io::{stdout, Stdout},
    path::PathBuf,
    time::{Duration, Instant},
};

use agent_core::state::{GpuStatus, StatusSnapshot};
use anyhow::{Context, Result};
use chrono::Utc;
use crossterm::{
    cursor,
    event::{self, Event, KeyCode, KeyEventKind},
    execute,
    terminal::{disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen},
};
use ratatui::{
    backend::CrosstermBackend,
    layout::{Alignment, Rect},
    style::{Color, Modifier, Style},
    text::Line,
    widgets::{Block, Borders, Paragraph, Wrap},
    Terminal,
};

use crate::client::AgentClient;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Screen {
    MainMenu,
    NodeOverview,
    GpuPower,
    NetworkDisk,
    Efficiency,
    MetricsProfiles,
    AgentStatus,
    ConnectServer,

}

#[derive(Clone, Debug)]
pub struct ManagedMetadata {
    pub server: Option<String>,
    pub cluster_id: Option<String>,
    pub node_id: Option<String>,
    pub last_contact_unix_ms: Option<u64>,
    pub state: String,
}

#[derive(Clone, Debug)]
pub enum AgentMode {
    Standalone,
    Managed(ManagedMetadata),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum ConnectField {
    Server,
    Token,
}

struct AppState {
    screen: Screen,
    last_status: Option<StatusSnapshot>,

    message: Option<String>,
    no_color: bool,
    should_exit: bool,
    mode: AgentMode,
    config_path: PathBuf,
    config: agent_core::AgentConfig,
    connect_active: ConnectField,
    connect_server_input: String,
    connect_token_input: String,
}

impl AppState {
    fn new(
        no_color: bool,
        mode: AgentMode,
        config_path: PathBuf,
        config: agent_core::AgentConfig,
    ) -> Self {
        Self {
            screen: Screen::MainMenu,
            last_status: None,

            message: None,
            no_color,
            should_exit: false,
            mode,
            config_path,
            config: config.clone(),
            connect_active: ConnectField::Server,
            connect_server_input: config.managed_server.clone().unwrap_or_default(),
            connect_token_input: config.managed_join_token.unwrap_or_default(),
        }
    }

    fn set_status(&mut self, status: Option<StatusSnapshot>) {
        self.last_status = status;
    }

    fn set_screen(&mut self, screen: Screen) {
        self.screen = screen;
        self.message = None;
    }

    fn back(&mut self) {
        if self.screen == Screen::MainMenu {
            self.should_exit = true;
        } else {
            self.screen = Screen::MainMenu;
        }
    }
}

pub fn run_console(
    client: &AgentClient,
    no_color: bool,
    mode: AgentMode,
    config_path: PathBuf,
    config: agent_core::AgentConfig,
) -> Result<()> {
    let stdout = prepare_terminal()?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;
    terminal.clear()?;
    terminal.show_cursor()?;

    let mut state = AppState::new(no_color, mode, config_path, config);
    refresh_status(&mut state, client);
    let mut last_refresh = Instant::now();

    loop {
        terminal.draw(|f| render(f, &state))?;

        if state.should_exit {
            break;
        }

        let timeout = Duration::from_millis(200);
        if event::poll(timeout)? {
            if let Event::Key(key) = event::read()? {
                // Some terminals/tmux combos report keys as Repeat or Unknown (and occasionally
                // only emit Release); treat everything except explicit Release as a press.
                if !matches!(key.kind, KeyEventKind::Release) {
                    let mut refresh_now = handle_key(key.code, &mut state);
                    if state.should_exit {
                        break;
                    }
                    match key.code {
                        KeyCode::Char('1') if state.screen == Screen::MainMenu => {
                            state.set_screen(Screen::NodeOverview);
                            refresh_now = true;
                        }
                        KeyCode::Char('2') if state.screen == Screen::MainMenu => {
                            state.set_screen(Screen::GpuPower);
                            refresh_now = true;
                        }
                        KeyCode::Char('3') if state.screen == Screen::MainMenu => {
                            state.set_screen(Screen::NetworkDisk);
                            refresh_now = true;
                        }
                        KeyCode::Char('4') if state.screen == Screen::MainMenu => {
                            state.set_screen(Screen::Efficiency);
                            refresh_now = true;
                        }
                        KeyCode::Char('5') if state.screen == Screen::MainMenu => {
                            state.set_screen(Screen::MetricsProfiles);
                            refresh_now = true;
                        }
                        KeyCode::Char('6') if state.screen == Screen::MainMenu => {
                            state.set_screen(Screen::AgentStatus);
                            refresh_now = true;
                        }
                        KeyCode::Char('7') if state.screen == Screen::MainMenu => {
                            state.set_screen(Screen::ConnectServer);
                            refresh_now = true;
                        }

                        _ => {}
                    }
                    if refresh_now {
                        refresh_status(&mut state, client);
                    }
                }
            }
        }

        if last_refresh.elapsed() > Duration::from_secs(5) {
            refresh_status(&mut state, client);
            last_refresh = Instant::now();
        }
    }

    restore_terminal()?;
    Ok(())
}

fn refresh_status(state: &mut AppState, client: &AgentClient) {
    match client.fetch_status() {
        Ok(snapshot) => {
            state.message = None;
            state.set_status(Some(snapshot));
        }
        Err(err) => {
            state.message = Some(format!(
                "Unable to reach agent at {}: {err}",
                client.base_url()
            ));
            state.set_status(None);
        }
    }

}

fn prepare_terminal() -> Result<Stdout> {
    enable_raw_mode().context("enabling raw mode")?;
    let mut stdout = stdout();
    execute!(stdout, EnterAlternateScreen, cursor::Show).context("preparing terminal")?;
    Ok(stdout)
}

fn restore_terminal() -> Result<()> {
    let mut stdout = stdout();
    execute!(stdout, LeaveAlternateScreen, cursor::Show).context("restoring terminal")?;
    disable_raw_mode().context("disabling raw mode")
}

fn render(frame: &mut ratatui::Frame, state: &AppState) {
    // Use full terminal area instead of a fixed 80x24 window so the console scales
    // with the current terminal size.
    let area = frame.size();
    if let AgentMode::Managed(_) = state.mode {
        render_managed(frame, area, state);
        return;
    }
    match state.screen {
        Screen::MainMenu => render_main_menu(frame, area, state),
        Screen::NodeOverview => render_node_overview(frame, area, state),
        Screen::GpuPower => render_gpu_power(frame, area, state),
        Screen::NetworkDisk => render_network_disk(frame, area, state),
        Screen::Efficiency => render_efficiency(frame, area, state),
        Screen::MetricsProfiles => render_metric_profiles(frame, area, state),
        Screen::AgentStatus => render_agent_status(frame, area, state),
        Screen::ConnectServer => render_connect_server(frame, area, state),

    }
}

fn render_main_menu(frame: &mut ratatui::Frame, area: Rect, state: &AppState) {
    let mode_line = match &state.mode {
        AgentMode::Standalone => "STANDALONE".to_string(),
        AgentMode::Managed(_) => "MANAGED".to_string(),
    };
    let server_line = match &state.mode {
        AgentMode::Standalone => "(not connected)".to_string(),
        AgentMode::Managed(meta) => meta
            .server
            .clone()
            .unwrap_or_else(|| "(unknown)".to_string()),
    };
    let text = vec![
        Line::from("                          ESNODE – CORE CONSOLE                         N01"),
        Line::from("                        Estimatedstocks AB – ESNODE-Core                "),
        Line::from(""),
        Line::from(format!(
            "   Core Mode  . . . . . . . . . . . . . . . :  {mode_line}"
        )),
        Line::from(format!(
            "   Server (Pulse)  . . . . . . . . . . . .  :  {server_line}"
        )),
        Line::from(""),
        Line::from("   Select one of the following options and press Enter:"),
        Line::from(""),
        Line::from("     1. ESNODE Overview          (CPU / Memory / Load)"),
        Line::from("     2. GPU & Power              (GPU, VRAM, watts, thermals)"),
        Line::from("     3. Network & Disk           (I/O, bandwidth, latency)"),
        Line::from("     4. Efficiency & MCP Signals (tokens-per-watt, routing scores)"),
        Line::from("     5. Metrics Profiles         (enable/disable metric sets)"),
        Line::from("     6. AgentStatus & Logs      (health, errors, config)"),
        Line::from("     7. Connect to ESNODE-Pulse (attach this ESNODE to a cluster)"),

        Line::from(""),
        Line::from("     Selection . . . . . . . . . . . . . . . . . .  __"),
        Line::from(""),
        Line::from(""),
        Line::from(" F3=Exit   F5=Refresh   F9=Node Info   F10=Help   F12=Cancel"),
    ];
    let mut block = Block::default().borders(Borders::ALL);
    if !state.no_color {
        block = block.border_style(primary_style(state));
    }
    let paragraph = Paragraph::new(text)
        .alignment(Alignment::Left)
        .style(primary_style(state))
        .block(block)
        .wrap(Wrap { trim: false });
    frame.render_widget(paragraph, area);
    // Place a visible cursor on the selection line so users can see the active input spot.
    let selection_row = area.y.saturating_add(16);
    let selection_col = area.x.saturating_add(50);
    frame.set_cursor(selection_col, selection_row);
    if let Some(msg) = &state.message {
        render_message(frame, area, msg, state);
    }
}

fn render_node_overview(frame: &mut ratatui::Frame, area: Rect, state: &AppState) {
    if state.last_status.is_none() {
        render_placeholder(
            frame,
            area,
            state,
            "Waiting for metrics from esnode-core daemon...",
        );
        return;
    }
    let summary = NodeSummary::from_status(state);
    let text = vec![
        Line::from("                            ESNODE – NODE OVERVIEW                        N01"),
        Line::from(format!(
            " Node: {node:<18} Region: {region:<16} Uptime: {uptime:<12}",
            node = summary.node_name,
            region = summary.region,
            uptime = summary.uptime
        )),
        Line::from(""),
        Line::from(format!(
            "   CPU:   {cores:<8} Load(1/5/15):  {l1:<4} {l5:<4} {l15:<4}     Util:  {util:>6}",
            cores = summary.cores,
            l1 = summary.load_1,
            l5 = summary.load_5,
            l15 = summary.load_15,
            util = summary.cpu_util
        )),
        Line::from(format!(
            "   Mem:   {mem_total:<9} Used:  {mem_used:<10} Free:  {mem_free:<10} Swap Used:  {swap_used}",
            mem_total = summary.mem_total,
            mem_used = summary.mem_used,
            mem_free = summary.mem_free,
            swap_used = summary.swap_used
        )),
        Line::from(format!(
            "   Disk:  /           Used:  {disk_used:<12} IO Latency:  {disk_lat}",
            disk_used = summary.disk_used,
            disk_lat = summary.disk_latency
        )),
        Line::from(format!(
            "   Net:   eth0        Rx:  {net_rx:<8}   Tx:  {net_tx:<8}   Drops:  {net_drop}",
            net_rx = summary.net_rx,
            net_tx = summary.net_tx,
            net_drop = summary.net_drop
        )),
        Line::from(format!(
            "   Health Flags: Disk={}  Net={}  Swap={}  Score={}",
            if summary.disk_degraded { "DEGRADED" } else { "OK" },
            if summary.network_degraded { "DEGRADED" } else { "OK" },
            if summary.swap_degraded { "DEGRADED" } else { "OK" },
            summary.degradation_score
        )),
        Line::from(""),
        Line::from(format!(
            "   Power: Node Draw:  {power_draw:<8}   Limit:  {power_limit:<8}   Spikes (24h):  {spikes}",
            power_draw = summary.node_power,
            power_limit = summary.node_limit,
            spikes = summary.spikes
        )),
        Line::from(format!(
            "   Therm: Inlet:  {inlet:<6} Exhaust:  {exhaust:<6}      CPU Hotspot:  {hotspot}",
            inlet = summary.therm_inlet,
            exhaust = summary.therm_exhaust,
            hotspot = summary.therm_hotspot
        )),
        Line::from(""),
        Line::from(format!(
            "   GPUs:  {count:<2} detected     Total VRAM:  {vram:<6}",
            count = summary.gpu_count,
            vram = summary.total_vram,
        )),
        Line::from(format!(
            "          Avg Util:  {util:<6}     Avg Power:  {gpu_power:<8}     Tokens/Watt:  {tokens}",
            util = summary.avg_gpu_util,
            gpu_power = summary.avg_gpu_power,
            tokens = summary.tokens_per_watt
        )),
        Line::from(""),
        Line::from(" F3=Exit   F5=Refresh   F9=GPU Detail   F10=Metrics Profile   F12=Menu"),
    ];

    let mut block = Block::default().borders(Borders::ALL);
    if !state.no_color {
        block = block.border_style(primary_style(state));
    }
    let paragraph = Paragraph::new(text)
        .style(primary_style(state))
        .alignment(Alignment::Left)
        .block(block)
        .wrap(Wrap { trim: false });
    frame.render_widget(paragraph, area);
    if let Some(msg) = &state.message {
        render_message(frame, area, msg, state);
    }
}

fn render_gpu_power(frame: &mut ratatui::Frame, area: Rect, state: &AppState) {
    if state.last_status.is_none() {
        render_placeholder(
            frame,
            area,
            state,
            "Waiting for GPU/power data from esnode-core daemon...",
        );
        return;
    }
    let lines = build_gpu_table(state.last_status.as_ref());
    let text = vec![
        Line::from("                          ESNODE – GPU & POWER STATUS                    N01"),
        Line::from(""),
    ]
    .into_iter()
    .chain(lines)
    .chain(vec![
        Line::from(""),
        Line::from("    Option . . . . . . . . . . . . . .  __   (1=GPU Detail, 2=Power Spikes, 3=KV Cache)"),
        Line::from(""),
        Line::from(""),
        Line::from(" F3=Exit   F5=Refresh   F9=Power Spikes   F11=More Fields   F12=Back"),
    ])
    .collect::<Vec<_>>();

    let mut block = Block::default().borders(Borders::ALL);
    if !state.no_color {
        block = block.border_style(primary_style(state));
    }
    let paragraph = Paragraph::new(text)
        .style(primary_style(state))
        .alignment(Alignment::Left)
        .block(block)
        .wrap(Wrap { trim: false });
    frame.render_widget(paragraph, area);
    if let Some(msg) = &state.message {
        render_message(frame, area, msg, state);
    }
}

fn render_network_disk(frame: &mut ratatui::Frame, area: Rect, state: &AppState) {
    if state.last_status.is_none() {
        render_placeholder(
            frame,
            area,
            state,
            "Waiting for network/disk data from esnode-core daemon...",
        );
        return;
    }
    let status = state.last_status.as_ref().unwrap();
    let nic = status
        .primary_nic
        .clone()
        .unwrap_or_else(|| "n/a".to_string());
    let rx = status.net_rx_bytes_per_sec.map_or_else(
        || "n/a".to_string(),
        |b| format!("{}/s", human_bytes(b as u64)),
    );
    let tx = status.net_tx_bytes_per_sec.map_or_else(
        || "n/a".to_string(),
        |b| format!("{}/s", human_bytes(b as u64)),
    );
    let drops = status
        .net_drops_per_sec
        .map_or_else(|| "0".to_string(), |d| format!("{d:.1}/s"));
    let disk_used = match (status.disk_root_used_bytes, status.disk_root_total_bytes) {
        (Some(used), Some(total)) => format!("{} / {}", human_bytes(used), human_bytes(total)),
        _ => "n/a".to_string(),
    };
    let disk_io = status
        .disk_root_io_time_ms
        .map_or_else(|| "n/a".to_string(), |v| format!("{v} ms"));
    let degradation = format!(
        "Disk: {}   Net: {}   Swap: {}   Score: {}",
        if status.disk_degraded {
            "DEGRADED"
        } else {
            "OK"
        },
        if status.network_degraded {
            "DEGRADED"
        } else {
            "OK"
        },
        if status.swap_degraded {
            "DEGRADED"
        } else {
            "OK"
        },
        status.degradation_score
    );

    let text = vec![
        Line::from("                        ESNODE – NETWORK & DISK STATUS                   N01"),
        Line::from(""),
        Line::from(" Network:"),
        Line::from("   IF     Rx/s             Tx/s             Drops/s"),
        Line::from(format!("   {nic:<6}{rx:<17}{tx:<17}{drops}")),
        Line::from(""),
        Line::from(" Disks:"),
        Line::from("   Mount   Used / Total                 IO Time"),
        Line::from(format!("   /       {disk_used:<26}{disk_io}")),
        Line::from(""),
        Line::from(format!("   Degradation Flags:  {degradation}")),
        Line::from(""),
        Line::from(""),
        Line::from(" F3=Exit   F5=Refresh   F9=I/O Detail   F12=Back"),
    ];
    let mut block = Block::default().borders(Borders::ALL);
    if !state.no_color {
        block = block.border_style(primary_style(state));
    }
    let paragraph = Paragraph::new(text)
        .style(primary_style(state))
        .block(block)
        .wrap(Wrap { trim: false });
    frame.render_widget(paragraph, area);
}

fn render_efficiency(frame: &mut ratatui::Frame, area: Rect, state: &AppState) {
    if state.last_status.is_none() {
        render_placeholder(
            frame,
            area,
            state,
            "Waiting for efficiency metrics from esnode-core daemon...",
        );
        return;
    }
    let summary = NodeSummary::from_status(state);
    let text = vec![
        Line::from("                     ESNODE – EFFICIENCY & MCP SIGNALS                   N01"),
        Line::from(""),
        Line::from("   Efficiency Snapshot:"),
        Line::from(format!(
            "     Tokens per Joule . . . . . . . . . . . . . . . . :  {}",
            summary.tokens_per_joule
        )),
        Line::from(format!(
            "     Tokens per Watt-second  . . . . . . . . . . . . :  {}",
            summary.tokens_per_watt
        )),
        Line::from(format!(
            "     Node power draw . . . . . . . . . . . . . . . . :  {}",
            summary.node_power
        )),
        Line::from(format!(
            "     Avg GPU util / power . . . . . . . . . . . . . .:  {} / {}",
            summary.avg_gpu_util, summary.avg_gpu_power
        )),
        Line::from(format!(
            "     CPU util (approx)  . . . . . . . . . . . . . . .:  {}",
            summary.cpu_util
        )),
        Line::from(""),
        Line::from("   Routing / Scheduling Scores:"),
        Line::from("     Best-fit GPU score  . . . . . . . . . . . . . . :  n/a"),
        Line::from("     Energy cost score . . . . . . . . . . . . . . . :  n/a"),
        Line::from("     Thermal risk score  . . . . . . . . . . . . . . :  n/a"),
        Line::from("     Memory pressure score . . . . . . . . . . . . . :  n/a"),
        Line::from("     Cache freshness score . . . . . . . . . . . . . :  n/a"),
        Line::from(""),
        Line::from("   Batch & Queue:"),
        Line::from("     Batch capacity free (%)  . . . . . . . . . . . . :  n/a"),
        Line::from("     KV cache free bytes  . . . . . . . . . . . . . . :  n/a"),
        Line::from("     Inference queue length  . . . . . . . . . . . . :  n/a"),
        Line::from("     Speculative ready flag . . . . . . . . . . . . . :  n/a"),
        Line::from(""),
        Line::from(""),
        Line::from(" F3=Exit   F5=Refresh   F9=Explain Scores   F12=Back"),
    ];
    let mut block = Block::default().borders(Borders::ALL);
    if !state.no_color {
        block = block.border_style(primary_style(state));
    }
    let paragraph = Paragraph::new(text)
        .style(primary_style(state))
        .block(block)
        .wrap(Wrap { trim: false });
    frame.render_widget(paragraph, area);
}

fn render_metric_profiles(frame: &mut ratatui::Frame, area: Rect, state: &AppState) {
    let summary = MetricToggleState::from_config(&state.config, state.last_status.as_ref());
    let text = vec![
        Line::from("                         ESNODE – METRICS PROFILES                      N01"),
        Line::from(""),
        Line::from("   Current Metrics Sets (Y=enabled, N=disabled):"),
        Line::from(""),
        Line::from(format!(
            "     Host / Node (CPU, mem, disk, net) . . . . . . . [{}]",
            summary.host
        )),
        Line::from(format!(
            "     GPU Core (util, VRAM, temp) . . . . . . . . . . [{}]",
            summary.gpu_core
        )),
        Line::from(format!(
            "     GPU Power & Energy  . . . . . . . . . . . . . . [{}]",
            summary.gpu_power
        )),
        Line::from(format!(
            "     MCP Efficiency & Routing . . . . . . . . . . . .[{}]",
            summary.mcp
        )),
        Line::from(format!(
            "     Application / HTTP Metrics . . . . . . . . . . .[{}]",
            summary.app
        )),
        Line::from(format!(
            "     Rack / Room Thermals (BMC/IPMI) . . . . . . . . [{}]",
            summary.rack
        )),
        Line::from(""),
        Line::from("   Option:"),
        Line::from("     1=Toggle Host/Node"),
        Line::from("     2=Toggle GPU Core"),
        Line::from("     3=Toggle GPU Power/Energy"),
        Line::from("     4=Toggle MCP Metrics"),
        Line::from("     5=Toggle Application Metrics"),
        Line::from("     6=Toggle Rack/Room Thermals"),
        Line::from(""),
        Line::from("   Selection . . . . . . . . . . . . . . . . . . . . __"),
        Line::from(""),
        Line::from(" F3=Exit   F5=Refresh   F10=Save Now   F12=Back"),
    ];
    let mut block = Block::default().borders(Borders::ALL);
    if !state.no_color {
        block = block.border_style(primary_style(state));
    }
    let paragraph = Paragraph::new(text)
        .style(primary_style(state))
        .block(block)
        .wrap(Wrap { trim: false });
    frame.render_widget(paragraph, area);
}

fn render_agent_status(frame: &mut ratatui::Frame, area: Rect, state: &AppState) {
    if state.last_status.is_none() {
        render_placeholder(
            frame,
            area,
            state,
            "Waiting for agent status from esnode-core daemon...",
        );
        return;
    }
    let errors = state
        .last_status
        .as_ref()
        .map(|s| s.last_errors.clone())
        .unwrap_or_default();
    let mut lines = vec![
        Line::from("                       ESNODE – AGENT STATUS & LOGS                     N01"),
        Line::from(""),
        Line::from("   Agent Status:"),
        Line::from(format!(
            "     Running . . . . . . . . . . . . . . . . . . . . :  {}",
            state
                .last_status
                .as_ref()
                .map_or("UNKNOWN", |s| if s.healthy { "YES" } else { "WARN" })
        )),
        Line::from(format!(
            "     Last scrape (unix ms) . . . . . . . . . . . . . :  {}",
            state
                .last_status
                .as_ref().map_or_else(|| "n/a".to_string(), |s| s.last_scrape_unix_ms.to_string())
        )),
        Line::from(format!(
            "     Node power (W) . . . . . . . . . . . . . . . . .:  {}",
            state
                .last_status
                .as_ref()
                .and_then(|s| s.node_power_watts).map_or_else(|| "n/a".to_string(), |v| format!("{v:.1}"))
        )),
        Line::from(format!(
            "     Degradation flags . . . . . . . . . . . . . . . .:  disk={} net={} swap={} score={}",
            state
                .last_status
                .as_ref()
                .map_or("n/a", |s| if s.disk_degraded { "DEG" } else { "OK" }),
            state
                .last_status
                .as_ref()
                .map_or("n/a", |s| if s.network_degraded { "DEG" } else { "OK" }),
            state
                .last_status
                .as_ref()
                .map_or("n/a", |s| if s.swap_degraded { "DEG" } else { "OK" }),
            state
                .last_status
                .as_ref().map_or_else(|| "n/a".to_string(), |s| s.degradation_score.to_string())
        )),
        Line::from(""),
        Line::from("   Recent Errors (last 10):"),
    ];

    if errors.is_empty() {
        lines.push(Line::from("     none"));
    } else {
        for (idx, err) in errors.iter().enumerate() {
            lines.push(Line::from(format!(
                "     {}. [{}] {} (unix_ms={})",
                idx + 1,
                err.collector,
                err.message,
                err.unix_ms
            )));
        }
    }

    lines.extend_from_slice(&[
        Line::from(""),
        Line::from("   Option:"),
        Line::from("     1=View full log (last 100 lines)"),
        Line::from("     2=Export diagnostics snapshot"),
        Line::from("     3=Show config"),
        Line::from(""),
        Line::from("   Selection . . . . . . . . . . . . . . . . . . . . __"),
        Line::from(""),
        Line::from(""),
        Line::from(" F3=Exit   F5=Refresh   F9=Diagnostics   F12=Back"),
    ]);

    let mut block = Block::default().borders(Borders::ALL);
    if !state.no_color {
        block = block.border_style(primary_style(state));
    }
    let paragraph = Paragraph::new(lines)
        .style(primary_style(state))
        .block(block)
        .wrap(Wrap { trim: false });
    frame.render_widget(paragraph, area);
}

fn render_connect_server(frame: &mut ratatui::Frame, area: Rect, state: &AppState) {
    let server_prefix = "   Server address (host:port)  . . . . . . . . . . . . .  ";
    let token_prefix = "   Join token (optional)  . . . . . . . . . . . . . . . .  ";
    let server_line = format!("{server_prefix}{:<30}", state.connect_server_input);
    let token_line = format!("{token_prefix}{:<30}", state.connect_token_input);
    let lines = vec![
        Line::from("                    ESNODE – CONNECT TO ESNODE-SERVER                    N02"),
        Line::from(""),
        Line::from("   This node is currently running in STANDALONE mode."),
        Line::from("   To enroll it into a managed cluster, enter the ESNODE-Pulse details."),
        Line::from(""),
        Line::from(server_line),
        Line::from(token_line),
        Line::from(""),
        Line::from("   After connection:"),
        Line::from("     - Local tuning via this console will be disabled."),
        Line::from("     - Monitoring, alerts and throttling will be controlled centrally"),
        Line::from("       from the ESNODE-Pulse."),
        Line::from("     - Local /metrics endpoint and Prometheus output remain active."),
        Line::from(""),
        Line::from("   Option:"),
        Line::from("     1=Connect Now    2=Test Connection    3=Cancel"),
        Line::from(""),
        Line::from("   Selection . . . . . . . . . . . . . . . . . . . . . __"),
        Line::from(""),
        Line::from(
            "                                                                                 ",
        ),
        Line::from(" F3=Exit   F5=Refresh   F10=Help   F12=Back"),
    ];
    let mut block = Block::default().borders(Borders::ALL);
    if !state.no_color {
        block = block.border_style(primary_style(state));
    }
    let paragraph = Paragraph::new(lines)
        .style(primary_style(state))
        .block(block)
        .wrap(Wrap { trim: false });
    frame.render_widget(paragraph, area);

    // Place cursor on active input
    let (cursor_row, cursor_col) = match state.connect_active {
        ConnectField::Server => (
            area.y + 5,
            area.x + server_prefix.len() as u16 + state.connect_server_input.len() as u16,
        ),
        ConnectField::Token => (
            area.y + 6,
            area.x + token_prefix.len() as u16 + state.connect_token_input.len() as u16,
        ),
    };
    frame.set_cursor(cursor_col, cursor_row);
}



fn handle_key(code: KeyCode, state: &mut AppState) -> bool {
    if let AgentMode::Managed(_) = state.mode {
        if state.screen != Screen::ConnectServer {
            match code {
                KeyCode::Esc | KeyCode::F(3 | 12) | KeyCode::Char('q') => {
                    state.should_exit = true;
                }
                KeyCode::F(5) => return true,
                _ => {}
            }
            return false;
        }
    }
    if state.screen == Screen::ConnectServer {
        if let Some(refresh) = handle_connect_key(code, state) {
            return refresh;
        }
    }
    match code {
        KeyCode::Esc | KeyCode::F(12) => state.back(),
        KeyCode::F(3) | KeyCode::Char('q') => state.should_exit = true,
        KeyCode::F(5) => return true,
        KeyCode::F(9) => {
            state.message = Some("Node info refreshed".to_string());
            return true;
        }
        KeyCode::F(10) => {
            state.message =
                Some("Use number keys 1-7, F3=Exit, F5/F9=Refresh, F12=Menu".to_string());
        }
        KeyCode::Char(k @ '1'..='3') if state.screen == Screen::AgentStatus => {
            handle_agent_status_action(k, state);
        }
        KeyCode::Char(k @ '1'..='6') if state.screen == Screen::MetricsProfiles => {
            toggle_metric_profile(k, state);
        }
        KeyCode::Left => {
            state.screen = Screen::MainMenu;
        }
        KeyCode::Right => {
            state.screen = Screen::NodeOverview;
        }
        _ => {}
    }
    false
}

fn handle_agent_status_action(key: char, state: &mut AppState) {
    match key {
        '1' => {
            state.message = Some(
                "View full log via: journalctl -u esnode-core -n 100 (or your log file)"
                    .to_string(),
            );
        }
        '2' => {
            state.message = Some(
                "Export diagnostics via CLI: esnode-core diagnostics > diagnostics.txt".to_string(),
            );
        }
        '3' => {
            state.message = Some(format!(
                "Config path: {}; use CLI 'esnode-core config show'",
                state.config_path.to_string_lossy()
            ));
        }
        _ => {}
    }
}

fn toggle_metric_profile(key: char, state: &mut AppState) {
    // Flip config booleans and persist to the same config file used by the CLI.
    let mut message = String::new();
    let mut changed = false;
    match key {
        // Host / node bundle
        '1' => {
            let enable = !(state.config.enable_cpu
                && state.config.enable_memory
                && state.config.enable_disk
                && state.config.enable_network);
            state.config.enable_cpu = enable;
            state.config.enable_memory = enable;
            state.config.enable_disk = enable;
            state.config.enable_network = enable;
            message = format!(
                "{} host/node metrics (CPU/mem/disk/net)",
                if enable { "Enabled" } else { "Disabled" }
            );
            changed = true;
        }
        // GPU core
        '2' => {
            state.config.enable_gpu = !state.config.enable_gpu;
            message = format!(
                "{} GPU core metrics",
                if state.config.enable_gpu {
                    "Enabled"
                } else {
                    "Disabled"
                }
            );
            changed = true;
        }
        // GPU power/energy
        '3' => {
            state.config.enable_power = !state.config.enable_power;
            message = format!(
                "{} GPU power metrics",
                if state.config.enable_power {
                    "Enabled"
                } else {
                    "Disabled"
                }
            );
            changed = true;
        }
        // MCP signals
        '4' => {
            state.config.enable_mcp = !state.config.enable_mcp;
            message = format!(
                "{} MCP metrics",
                if state.config.enable_mcp {
                    "Enabled"
                } else {
                    "Disabled"
                }
            );
            changed = true;
        }
        // Application metrics
        '5' => {
            state.config.enable_app = !state.config.enable_app;
            message = format!(
                "{} application metrics",
                if state.config.enable_app {
                    "Enabled"
                } else {
                    "Disabled"
                }
            );
            changed = true;
        }
        // Rack / room thermals
        '6' => {
            state.config.enable_rack_thermals = !state.config.enable_rack_thermals;
            message = format!(
                "{} rack/room thermals",
                if state.config.enable_rack_thermals {
                    "Enabled"
                } else {
                    "Disabled"
                }
            );
            changed = true;
        }
        _ => {}
    }

    if !changed {
        return;
    }

    if let Err(err) = persist_console_config(&state.config_path, &state.config) {
        state.message = Some(format!("Failed to save metrics profile: {err}"));
    } else {
        state.message = Some(message);
    }
}

fn persist_console_config(path: &PathBuf, config: &agent_core::AgentConfig) -> Result<()> {
    let contents = toml::to_string_pretty(config)?;
    fs::write(path, contents).context("writing config file")?;
    Ok(())
}

fn handle_connect_key(code: KeyCode, state: &mut AppState) -> Option<bool> {
    match code {
        KeyCode::Enter => {
            if state.connect_server_input.trim().is_empty() {
                state.message =
                    Some("Enter server address (host:port) before connecting".to_string());
            } else {
                perform_connect(state);
            }
            return Some(false);
        }
        KeyCode::Tab => {
            state.connect_active = match state.connect_active {
                ConnectField::Server => ConnectField::Token,
                ConnectField::Token => ConnectField::Server,
            };
            return Some(false);
        }
        KeyCode::Backspace => {
            match state.connect_active {
                ConnectField::Server => {
                    state.connect_server_input.pop();
                }
                ConnectField::Token => {
                    state.connect_token_input.pop();
                }
            }
            return Some(false);
        }
        KeyCode::Char(c) => {
            // Basic printable guard; allow spaces as part of token.
            if !c.is_control() {
                match state.connect_active {
                    ConnectField::Server => state.connect_server_input.push(c),
                    ConnectField::Token => state.connect_token_input.push(c),
                }
            }
            return Some(false);
        }
        _ => {}
    }
    None
}

fn perform_connect(state: &mut AppState) {
    let server = state.connect_server_input.trim().to_string();
    let token = state.connect_token_input.trim().to_string();

    state.config.managed_server = Some(server.clone());
    state.config.managed_join_token = if token.is_empty() {
        None
    } else {
        Some(token.clone())
    };
    if state.config.managed_node_id.is_none() {
        state.config.managed_node_id = Some("local-node".to_string());
    }
    if state.config.managed_cluster_id.is_none() {
        state.config.managed_cluster_id = Some("unknown-cluster".to_string());
    }
    state.config.managed_last_contact_unix_ms = Some(Utc::now().timestamp_millis() as u64);

    if let Err(err) = persist_console_config(&state.config_path, &state.config) {
        state.message = Some(format!("Failed to save connection: {err}"));
        return;
    }

    state.message = Some(format!(
        "Saved ESNODE-Pulse server {}, token {}",
        server,
        if token.is_empty() { "(none)" } else { "(set)" }
    ));
}

fn primary_style(state: &AppState) -> Style {
    if state.no_color {
        Style::default()
    } else {
        Style::default()
            .fg(Color::Green)
            .bg(Color::Black)
            .add_modifier(Modifier::BOLD)
    }
}

fn render_message(frame: &mut ratatui::Frame, area: Rect, message: &str, state: &AppState) {
    let area = Rect {
        x: area.x + 2,
        y: area.y + area.height.saturating_sub(3),
        width: area.width.saturating_sub(4),
        height: 3,
    };
    let mut block = Block::default().borders(Borders::ALL).title("Info");
    if !state.no_color {
        block = block.border_style(Style::default().fg(Color::Yellow));
    }
    let paragraph = Paragraph::new(message.to_string())
        .alignment(Alignment::Left)
        .style(primary_style(state))
        .block(block);
    frame.render_widget(paragraph, area);
}

fn render_placeholder(frame: &mut ratatui::Frame, area: Rect, state: &AppState, msg: &str) {
    let mut block = Block::default()
        .borders(Borders::ALL)
        .title("Awaiting Data");
    if !state.no_color {
        block = block.border_style(Style::default().fg(Color::Yellow));
    }
    let lines = vec![
        Line::from(msg.to_string()),
        Line::from(""),
        Line::from("Ensure esnode-core daemon is running and reachable, then press F5."),
    ];
    let paragraph = Paragraph::new(lines)
        .style(primary_style(state))
        .alignment(Alignment::Left)
        .block(block)
        .wrap(Wrap { trim: false });
    frame.render_widget(paragraph, area);
}

#[cfg(test)]
mod tests {
    use agent_core::state::{GpuStatus, StatusSnapshot};
    use ratatui::backend::TestBackend;
    use ratatui::Terminal;

    use super::{AgentMode, AppState, MetricToggleState, NodeSummary};
    use agent_core::state::GpuVendor;

    fn sample_status() -> StatusSnapshot {
        StatusSnapshot {
            healthy: true,
            load_avg_1m: 1.5,
            load_avg_5m: Some(1.0),
            load_avg_15m: Some(0.5),
            uptime_seconds: Some(3600),
            last_scrape_unix_ms: 123,
            last_errors: vec![],
            node_power_watts: Some(220.0),
            cpu_package_power_watts: vec![],
            cpu_temperatures: vec![],
            gpus: vec![GpuStatus {
                uuid: Some("GPU-TEST".to_string()),
                gpu: "0".to_string(),
                vendor: Some(GpuVendor::Nvidia),
                capabilities: None,
                identity: None,
                topo: None,
                health: None,
                nvlink: None,
                fabric_links: None,
                mig_tree: None,
                temperature_celsius: Some(70.0),
                power_watts: Some(250.0),
                util_percent: Some(75.0),
                memory_total_bytes: Some(24.0 * 1024.0 * 1024.0 * 1024.0),
                memory_used_bytes: Some(12.0 * 1024.0 * 1024.0 * 1024.0),
                fan_percent: Some(30.0),
                clock_sm_mhz: None,
                clock_mem_mhz: None,
                thermal_throttle: false,
                power_throttle: false,
            }],
            cpu_cores: Some(16),
            cpu_util_percent: Some(55.0),
            mem_total_bytes: Some(32 * 1024 * 1024 * 1024),
            mem_used_bytes: Some(16 * 1024 * 1024 * 1024),
            mem_free_bytes: Some(10 * 1024 * 1024 * 1024),
            swap_used_bytes: Some(0),
            disk_root_total_bytes: Some(500 * 1024 * 1024 * 1024),
            disk_root_used_bytes: Some(200 * 1024 * 1024 * 1024),
            disk_root_io_time_ms: Some(12),
            primary_nic: Some("eth0".to_string()),
            net_rx_bytes_per_sec: Some(10_000.0),
            net_tx_bytes_per_sec: Some(5_000.0),
            net_drops_per_sec: Some(0.1),
            app_tokens_per_sec: None,
            app_tokens_per_watt: None,
            disk_degraded: false,
            network_degraded: false,
            swap_degraded: false,
            degradation_score: 0,
        }
    }

    #[test]
    fn node_summary_formats_core_fields() {
        let mut dummy_state = AppState::new(
            false,
            AgentMode::Standalone,
            std::path::PathBuf::from("/tmp/esnode.toml"),
            agent_core::AgentConfig::default(),
        );
        dummy_state.set_status(Some(sample_status()));

        let summary = NodeSummary::from_status(&dummy_state);

        assert_eq!(summary.cores, "16");
        assert_eq!(summary.cpu_util, "55 %");
        assert!(summary.mem_total.contains("GiB"));
        assert!(summary.disk_used.contains('/'));
        assert!(summary.net_rx.contains("eth0"));
        assert_eq!(summary.avg_gpu_util, "75 %");
        assert_eq!(summary.node_power, "220.0 W");
    }

    fn build_state_with_status() -> AppState {
        let mut state = AppState::new(
            false,
            AgentMode::Standalone,
            std::path::PathBuf::from("/tmp/esnode.toml"),
            agent_core::AgentConfig {
                ..Default::default()
            },
        );
        state.set_status(Some(sample_status()));
        state
    }

    #[test]
    fn render_core_screens_without_panic() {
        let mut state = build_state_with_status();

        for screen in [
            super::Screen::NodeOverview,
            super::Screen::NetworkDisk,
            super::Screen::AgentStatus,

        ] {
            state.screen = screen;
            let backend = TestBackend::new(120, 40);
            let mut terminal = Terminal::new(backend).expect("terminal");
            terminal
                .draw(|f| super::render(f, &state))
                .expect("render should succeed");
        }
    }

    #[test]
    fn metric_toggle_state_prefers_config() {
        let cfg = agent_core::AgentConfig {
            enable_cpu: false,
            enable_memory: false,
            enable_disk: false,
            enable_network: false,
            enable_gpu: true,
            enable_power: false,
            enable_mcp: true,
            enable_app: false,
            enable_rack_thermals: true,
            ..Default::default()
        };

        let toggles = MetricToggleState::from_config(&cfg, None);
        assert_eq!(toggles.host, 'N');
        assert_eq!(toggles.gpu_core, 'Y');
        assert_eq!(toggles.gpu_power, 'N');
        assert_eq!(toggles.mcp, 'Y');
        assert_eq!(toggles.app, 'N');
        assert_eq!(toggles.rack, 'Y');

        let toggles2 = MetricToggleState::from_config(&cfg, Some(&sample_status()));
        assert_eq!(toggles2.host, 'Y');
    }
}

fn render_managed(frame: &mut ratatui::Frame, area: Rect, state: &AppState) {
    let meta = match &state.mode {
        AgentMode::Managed(m) => Some(m),
        AgentMode::Standalone => None,
    };
    let lines = vec![
        Line::from("                     ESNODE-AGENT – MANAGED BY ESNODE-SERVER             N01"),
        Line::from(""),
        Line::from(format!(
            "   Node Mode  . . . . . . . . . . . . . . . :  {}",
            meta.map_or("UNKNOWN", |_| "MANAGED")
        )),
        Line::from(format!(
            "   Node ID  . . . . . . . . . . . . . . . . :  {}",
            meta.and_then(|m| m.node_id.clone())
                .unwrap_or_else(|| "unknown".to_string())
        )),
        Line::from(format!(
            "   Cluster ID  . . . . . . . . . . . . . .  :  {}",
            meta.and_then(|m| m.cluster_id.clone())
                .unwrap_or_else(|| "unknown".to_string())
        )),
        Line::from(""),
        Line::from("   ESNODE-Pulse:"),
        Line::from(format!(
            "     Address . . . . . . . . . . . . . . .  :  {}",
            meta.and_then(|m| m.server.clone())
                .unwrap_or_else(|| "unknown".to_string())
        )),
        Line::from(format!(
            "     Last contact (UTC) . . . . . . . . . . :  {}",
            meta.and_then(|m| m.last_contact_unix_ms)
                .map_or_else(|| "unknown".to_string(), |ms| format!("{ms}"))
        )),
        Line::from(format!(
            "     Connection state  . . . . . . . . . .  :  {}",
            meta.map_or_else(|| "DEGRADED".to_string(), |m| m.state.clone())
        )),
        Line::from(""),
        Line::from("   Local Monitoring:"),
        Line::from("     Prometheus endpoint (/metrics)  . . . .:  ENABLED"),
        Line::from("     OTLP / JSON / file sinks  . . . . . .  :  ENABLED (per config)"),
        Line::from(""),
        Line::from("   Local control of metrics profiles, alerts, and throttling"),
        Line::from("   is disabled while this node is managed by ESNODE-Pulse."),
        Line::from(""),
        Line::from("   To change policies, please use the ESNODE-Pulse console:"),
        Line::from(""),
        Line::from("     $ esnode-pulse cli   (on the master/server host)"),
        Line::from(""),
        Line::from(""),
        Line::from(" F3=Exit   F5=Refresh   F12=Cancel"),
    ];

    let mut block = Block::default().borders(Borders::ALL);
    if !state.no_color {
        block = block.border_style(primary_style(state));
    }
    let paragraph = Paragraph::new(lines)
        .style(primary_style(state))
        .block(block)
        .wrap(Wrap { trim: false });
    frame.render_widget(paragraph, area);
}

fn build_gpu_table(status: Option<&StatusSnapshot>) -> Vec<Line<'static>> {
    let mut lines = vec![
        Line::from(
            " GPU  User  Util%  VRAM Used / Total      Power(W)  Temp°C  Throt%  ECC  Notes",
        ),
        Line::from(
            " ---- ----- -----  --------------------- --------- ------- ------- ----  -----",
        ),
    ];

    match status {
        Some(status) if !status.gpus.is_empty() => {
            for (idx, gpu) in status.gpus.iter().enumerate() {
                lines.push(Line::from(format!(
                    " {idx:<4}{user:<6}{util:<6}{mem:<23}{power:<10}{temp:<8}{throt:<8}{ecc:<5}{notes}",
                    user = gpu_owner(gpu),
                    util = gpu
                        .util_percent.map_or_else(|| "  n/a".to_string(), |v| format!("{v:>5.1}")),
                    mem = format!(
                        "{} / {}",
                        format_bytes(gpu.memory_used_bytes),
                        format_bytes(gpu.memory_total_bytes)
                    ),
                    power = gpu
                        .power_watts.map_or_else(|| "n/a      ".to_string(), |v| format!("{v:<9.0}")),
                    temp = gpu
                        .temperature_celsius.map_or_else(|| "n/a    ".to_string(), |v| format!("{v:<7.0}")),
                    throt = format!(
                        "{:.1}",
                        if gpu.power_throttle || gpu.thermal_throttle {
                            3.0
                        } else {
                            0.0
                        }
                    ),
                    ecc = 0,
                    notes = if gpu.thermal_throttle {
                        "HOT"
                    } else if gpu.power_throttle {
                        "THROTTLING"
                    } else {
                        "OK"
                    }
                )));
                if gpu.health.is_some() || gpu.mig_tree.is_some() {
                    let h = gpu_health_line(gpu);
                    if !h.is_empty() {
                        lines.push(Line::from(format!("      {h}")));
                    }
                    if let Some(tree) = gpu.mig_tree.as_ref() {
                        let mut mig_line = format!(
                            "MIG: {} / supported:{}",
                            if tree.enabled { "enabled" } else { "disabled" },
                            if tree.supported { "yes" } else { "no" }
                        );
                        if !tree.devices.is_empty() {
                            let mut descs: Vec<String> = Vec::new();
                            for d in tree.devices.iter().take(4) {
                                let mut parts = Vec::new();
                                if let Some(p) = d.profile.as_ref() {
                                    parts.push(p.clone());
                                }
                                if let Some(pl) = d.placement.as_ref() {
                                    parts.push(pl.clone());
                                }
                                descs.push(parts.join("@"));
                            }
                            if tree.devices.len() > 4 {
                                descs.push(format!("+{} more", tree.devices.len() - 4));
                            }
                            use std::fmt::Write;
                            let _ = write!(&mut mig_line, " | devices: {}", descs.join(", "));
                        }
                        lines.push(Line::from(format!("      {mig_line}")));
                    }
                }
            }
        }
        Some(_) => {
            lines.push(Line::from(
                "   GPU hardware not present or not supported on this node.",
            ));
        }
        None => {
            lines.push(Line::from("   no GPU data available (agent not reachable)"));
        }
    }

    lines.push(Line::from(""));
    let node_power = status
        .and_then(|s| s.node_power_watts)
        .map_or_else(|| "n/a".to_string(), |v| format!("{:.1} kW", v / 1000.0));
    lines.push(Line::from(format!(
        " Node Power: {node_power}   Tokens/Watt (last 5m): n/a    Energy/J (last 24h):  n/a",
    )));
    lines
}

fn format_bytes(value: Option<f64>) -> String {
    match value {
        Some(v) if v > 0.0 => format!("{:.0} GiB", v / 1024.0 / 1024.0 / 1024.0),
        _ => "n/a".to_string(),
    }
}

fn human_bytes(v: u64) -> String {
    const KB: f64 = 1024.0;
    const MB: f64 = KB * 1024.0;
    const GB: f64 = MB * 1024.0;
    const TB: f64 = GB * 1024.0;
    let f = v as f64;
    if f >= TB {
        format!("{:.1} TiB", f / TB)
    } else if f >= GB {
        format!("{:.1} GiB", f / GB)
    } else if f >= MB {
        format!("{:.1} MiB", f / MB)
    } else if f >= KB {
        format!("{:.0} KiB", f / KB)
    } else {
        format!("{v} B")
    }
}

fn format_duration(secs: u64) -> String {
    let days = secs / 86_400;
    let hours = (secs % 86_400) / 3600;
    let minutes = (secs % 3600) / 60;
    if days > 0 {
        format!("{days}d {hours}h {minutes}m")
    } else if hours > 0 {
        format!("{hours}h {minutes}m")
    } else {
        format!("{minutes}m")
    }
}

fn gpu_owner(gpu: &GpuStatus) -> String {
    gpu.fan_percent
        .map_or_else(|| "svc".to_string(), |v| format!("{v:>5.1}"))
}

fn gpu_health_line(gpu: &GpuStatus) -> String {
    if let Some(h) = gpu.health.as_ref() {
        let mut parts = Vec::new();
        if let Some(p) = h.pstate {
            parts.push(format!("pstate P{p}"));
        }
        if !h.throttle_reasons.is_empty() {
            parts.push(format!("throttle: {}", h.throttle_reasons.join(",")));
        }
        if let Some(mode) = h.ecc_mode.as_ref() {
            parts.push(format!("ECC {mode}"));
        }
        if let Some(r) = h.retired_pages {
            parts.push(format!("retired_pages {r}"));
        }
        if let Some(xid) = h.last_xid {
            parts.push(format!("last_xid {xid}"));
        }
        if let Some(enc) = h.encoder_util_percent {
            parts.push(format!("enc {enc:.0}%"));
        }
        if let Some(dec) = h.decoder_util_percent {
            parts.push(format!("dec {dec:.0}%"));
        }
        if let Some(cp) = h.copy_util_percent {
            parts.push(format!("copy {cp:.0}%"));
        }
        if let Some(bar1) = h.bar1_used_bytes {
            let total = h.bar1_total_bytes.unwrap_or(0);
            parts.push(format!(
                "BAR1 {} / {}",
                human_bytes(bar1),
                human_bytes(total)
            ));
        }
        parts.join(" | ")
    } else {
        String::new()
    }
}

#[derive(Default)]
struct NodeSummary {
    node_name: String,
    region: String,
    uptime: String,
    cores: String,
    load_1: String,
    load_5: String,
    load_15: String,
    cpu_util: String,
    mem_total: String,
    mem_used: String,
    mem_free: String,
    swap_used: String,
    disk_used: String,
    disk_latency: String,
    net_rx: String,
    net_tx: String,
    net_drop: String,
    node_power: String,
    node_limit: String,
    spikes: String,
    therm_inlet: String,
    therm_exhaust: String,
    therm_hotspot: String,
    gpu_count: usize,
    total_vram: String,
    avg_gpu_util: String,
    avg_gpu_power: String,
    tokens_per_watt: String,
    tokens_per_joule: String,
    disk_degraded: bool,
    network_degraded: bool,
    swap_degraded: bool,
    degradation_score: u64,
}

impl NodeSummary {
    fn from_status(state: &AppState) -> Self {
        let mut summary = Self {
            node_name: "gpu-node-01".to_string(),
            region: "local".to_string(),
            uptime: "n/a".to_string(),
            cores: "n/a".to_string(),
            load_1: "n/a".to_string(),
            load_5: "n/a".to_string(),
            load_15: "n/a".to_string(),
            cpu_util: "n/a".to_string(),
            mem_total: "n/a".to_string(),
            mem_used: "n/a".to_string(),
            mem_free: "n/a".to_string(),
            swap_used: "n/a".to_string(),
            disk_used: "n/a".to_string(),
            disk_latency: "n/a".to_string(),
            net_rx: "n/a".to_string(),
            net_tx: "n/a".to_string(),
            net_drop: "0".to_string(),
            node_power: "n/a".to_string(),
            node_limit: "n/a".to_string(),
            spikes: "n/a".to_string(),
            therm_inlet: "n/a".to_string(),
            therm_exhaust: "n/a".to_string(),
            therm_hotspot: "n/a".to_string(),
            gpu_count: 0,
            total_vram: "0 GiB".to_string(),
            avg_gpu_util: "n/a".to_string(),
            avg_gpu_power: "n/a".to_string(),
            tokens_per_watt: "n/a".to_string(),
            tokens_per_joule: "n/a".to_string(),
            disk_degraded: false,
            network_degraded: false,
            swap_degraded: false,
            degradation_score: 0,
        };

        if let Some(status) = state.last_status.as_ref() {
            summary.load_1 = format!("{:.1}", status.load_avg_1m);
            if let Some(l5) = status.load_avg_5m {
                summary.load_5 = format!("{l5:.1}");
            }
            if let Some(l15) = status.load_avg_15m {
                summary.load_15 = format!("{l15:.1}");
            }
            if let Some(cores) = status.cpu_cores {
                summary.cores = format!("{cores}");
            }
            if let Some(util) = status.cpu_util_percent {
                summary.cpu_util = format!("{util:.0} %");
            }
            if let Some(uptime) = status.uptime_seconds {
                summary.uptime = format_duration(uptime);
            }
            if let (Some(total), Some(used), Some(free)) = (
                status.mem_total_bytes,
                status.mem_used_bytes,
                status.mem_free_bytes,
            ) {
                summary.mem_total = human_bytes(total);
                summary.mem_used = human_bytes(used);
                summary.mem_free = human_bytes(free);
            }
            if let Some(swap) = status.swap_used_bytes {
                summary.swap_used = human_bytes(swap);
            }
            if let (Some(total), Some(used)) =
                (status.disk_root_total_bytes, status.disk_root_used_bytes)
            {
                summary.disk_used = format!("{} / {}", human_bytes(used), human_bytes(total));
            }
            if let Some(io_ms) = status.disk_root_io_time_ms {
                summary.disk_latency = format!("{io_ms} ms");
            }
            if let Some(nic) = status.primary_nic.clone() {
                let rx = status.net_rx_bytes_per_sec.map_or_else(
                    || "n/a".to_string(),
                    |b| format!("{}/s", human_bytes(b as u64)),
                );
                let tx = status.net_tx_bytes_per_sec.map_or_else(
                    || "n/a".to_string(),
                    |b| format!("{}/s", human_bytes(b as u64)),
                );
                let drops = status
                    .net_drops_per_sec
                    .map_or_else(|| "0".to_string(), |d| format!("{d:.1}"));
                summary.net_rx = format!("{rx} ({nic})");
                summary.net_tx = tx;
                summary.net_drop = drops;
            }
            if let Some(power) = status.node_power_watts {
                summary.node_power = format!("{power:.1} W");
            } else {
                let cpu_pkg_avg: Option<f64> = {
                    let vals: Vec<f64> = status
                        .cpu_package_power_watts
                        .iter()
                        .map(|p| p.watts)
                        .collect();
                    if vals.is_empty() {
                        None
                    } else {
                        Some(vals.iter().sum::<f64>() / (vals.len() as f64))
                    }
                };
                let gpu_avg: Option<f64> = {
                    if status.gpus.is_empty() {
                        None
                    } else {
                        Some(
                            status
                                .gpus
                                .iter()
                                .filter_map(|g| g.power_watts)
                                .sum::<f64>()
                                / (status.gpus.len() as f64),
                        )
                    }
                };
                let approx = match (cpu_pkg_avg, gpu_avg) {
                    (Some(c), Some(g)) => Some(c + g),
                    (Some(c), None) => Some(c),
                    (None, Some(g)) => Some(g),
                    _ => None,
                };
                if let Some(v) = approx {
                    summary.node_power = format!("~{v:.1} W");
                }
            }

            if let Some(_tps) = status.app_tokens_per_sec {
                // We don't have a field for raw tokens/sec in NodeSummary yet,
                // but we use it for efficiency.
                if let Some(tpw) = status.app_tokens_per_watt {
                    summary.tokens_per_watt = format!("{tpw:.2}");
                    // Approximate Joule calc (Watts * 1s = Joules for that second)
                    // So Tokens/Joule is essentially same as Tokens/Watt if considering rate per second.
                    summary.tokens_per_joule = format!("{tpw:.2}");
                }
            }

            if !status.cpu_temperatures.is_empty() {
                let mut inlet = None;
                let mut exhaust = None;
                let mut hotspot = None;
                for t in &status.cpu_temperatures {
                    let name = t.sensor.to_lowercase();
                    if inlet.is_none() && (name.contains("inlet") || name.contains("ambient")) {
                        inlet = Some(t.celsius);
                    }
                    if exhaust.is_none() && name.contains("exhaust") {
                        exhaust = Some(t.celsius);
                    }
                    hotspot = Some(match hotspot {
                        Some(h) if h >= t.celsius => h,
                        _ => t.celsius,
                    });
                }
                if let Some(v) = inlet {
                    summary.therm_inlet = format!("{v:.0} C");
                }
                if let Some(v) = exhaust {
                    summary.therm_exhaust = format!("{v:.0} C");
                }
                if let Some(v) = hotspot {
                    summary.therm_hotspot = format!("{v:.0} C");
                }
            }
            if !status.gpus.is_empty() {
                summary.gpu_count = status.gpus.len();
                let total_vram_bytes: f64 = status
                    .gpus
                    .iter()
                    .filter_map(|g| g.memory_total_bytes)
                    .sum();
                if total_vram_bytes > 0.0 {
                    summary.total_vram =
                        format!("{:.0} GiB", total_vram_bytes / 1024.0 / 1024.0 / 1024.0);
                }
                let avg_util: f64 = status
                    .gpus
                    .iter()
                    .filter_map(|g| g.util_percent)
                    .sum::<f64>()
                    / (status.gpus.len() as f64);
                if avg_util > 0.0 {
                    summary.avg_gpu_util = format!("{avg_util:.0} %");
                }
                let avg_power: f64 = status
                    .gpus
                    .iter()
                    .filter_map(|g| g.power_watts)
                    .sum::<f64>()
                    / (status.gpus.len() as f64);
                if avg_power > 0.0 {
                    summary.avg_gpu_power = format!("{avg_power:.0} W/GPU");
                }
                summary.tokens_per_watt = "n/a".to_string();
            }
            if let Some(limit) = state.config.node_power_envelope_watts {
                summary.node_limit = format!("{limit:.0} W");
            }
            summary.disk_degraded = status.disk_degraded;
            summary.network_degraded = status.network_degraded;
            summary.swap_degraded = status.swap_degraded;
            summary.degradation_score = status.degradation_score;
            // We don't yet have real AI efficiency counters in the status payload; keep
            // tokens-per-* as n/a to avoid showing fictional numbers. When metrics are
            // added to StatusSnapshot, wire them here.
        }

        summary
    }
}

#[derive(Default)]
struct MetricToggleState {
    host: char,
    gpu_core: char,
    gpu_power: char,
    mcp: char,
    app: char,
    rack: char,
}

impl MetricToggleState {
    fn from_config(
        config: &agent_core::AgentConfig,
        status: Option<&StatusSnapshot>,
    ) -> Self {
        let mut toggles = Self {
            host: if config.enable_cpu
                && config.enable_memory
                && config.enable_disk
                && config.enable_network
            {
                'Y'
            } else {
                'N'
            },
            gpu_core: if config.enable_gpu { 'Y' } else { 'N' },
            gpu_power: if config.enable_power { 'Y' } else { 'N' },
            mcp: if config.enable_mcp { 'Y' } else { 'N' },
            app: if config.enable_app { 'Y' } else { 'N' },
            rack: if config.enable_rack_thermals {
                'Y'
            } else {
                'N'
            },
        };

        // If config and status disagree (e.g., metrics temporarily unavailable), prefer
        // the config but upgrade to 'Y' when recent data indicates it's actually on.
        if let Some(s) = status {
            if toggles.host == 'N'
                && (s.cpu_cores.is_some()
                    || s.mem_total_bytes.is_some()
                    || s.disk_root_total_bytes.is_some())
            {
                toggles.host = 'Y';
            }
            if toggles.gpu_core == 'N' && !s.gpus.is_empty() {
                toggles.gpu_core = 'Y';
            }
            if toggles.gpu_power == 'N'
                && (s.node_power_watts.is_some() || !s.cpu_package_power_watts.is_empty())
            {
                toggles.gpu_power = 'Y';
            }
        }
        toggles
    }
}
