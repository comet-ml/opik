// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use std::collections::HashMap;
use std::fs;

use async_trait::async_trait;
use sysinfo::{NetworkExt, RefreshKind, System, SystemExt};

use crate::collectors::Collector;
use crate::metrics::MetricsRegistry;
use crate::state::StatusState;

#[derive(Default, Clone, Copy)]
struct NetworkSnapshot {
    rx: u64,
    tx: u64,
    rx_errors: u64,
    rx_packets: u64,
    tx_packets: u64,
    rx_dropped: u64,
    tx_dropped: u64,
}

pub struct NetworkCollector {
    system: System,
    previous: HashMap<String, NetworkSnapshot>,
    status: StatusState,
    prev_instant: Option<std::time::Instant>,
    prev_tcp_retrans: Option<u64>,
}

impl NetworkCollector {
    pub fn new(status: StatusState) -> Self {
        let system = System::new_with_specifics(RefreshKind::new());
        Self {
            system,
            previous: HashMap::new(),
            status,
            prev_instant: None,
            prev_tcp_retrans: None,
        }
    }
}

#[async_trait]
impl Collector for NetworkCollector {
    fn name(&self) -> &'static str {
        "network"
    }

    async fn collect(&mut self, metrics: &MetricsRegistry) -> anyhow::Result<()> {
        let now = std::time::Instant::now();
        let dt = self
            .prev_instant
            .map_or(0.0, |p| now.saturating_duration_since(p).as_secs_f64());
        self.prev_instant = Some(now);

        self.system.refresh_networks_list();
        self.system.refresh_networks();

        let mut best_iface: Option<(String, u64, u64, u64)> = None; // iface, rx_delta, tx_delta, drops

        for (iface, data) in self.system.networks() {
            let rx = data.total_received();
            let tx = data.total_transmitted();
            let rx_errors = data.total_errors_on_received();
            let prev = self.previous.entry(iface.clone()).or_default();

            let rx_delta = rx.saturating_sub(prev.rx);
            let tx_delta = tx.saturating_sub(prev.tx);
            let err_delta = rx_errors.saturating_sub(prev.rx_errors);

            metrics
                .network_rx_bytes_total
                .with_label_values(&[iface.as_str()])
                .inc_by(rx_delta);
            metrics
                .network_tx_bytes_total
                .with_label_values(&[iface.as_str()])
                .inc_by(tx_delta);
            metrics
                .network_rx_errors_total
                .with_label_values(&[iface.as_str()])
                .inc_by(err_delta);

            let mut snap = *prev;
            snap.rx = rx;
            snap.tx = tx;
            snap.rx_errors = rx_errors;
            self.previous.insert(iface.clone(), snap);

            if iface != "lo" {
                let score = rx_delta.saturating_add(tx_delta);
                if score > best_iface.as_ref().map_or(0, |b| b.1.saturating_add(b.2)) {
                    best_iface = Some((iface.clone(), rx_delta, tx_delta, err_delta));
                }
            }
        }

        let map = tokio::task::spawn_blocking(read_netdev)
            .await
            .ok()
            .flatten();
        if let Some(map) = map {
            for (iface, v) in &map {
                let prev = self.previous.entry(iface.clone()).or_default();
                let rxp = v.rx_packets.saturating_sub(prev.rx_packets);
                let txp = v.tx_packets.saturating_sub(prev.tx_packets);
                let rxd = v.rx_dropped.saturating_sub(prev.rx_dropped);
                let txd = v.tx_dropped.saturating_sub(prev.tx_dropped);

                metrics
                    .network_rx_packets_total
                    .with_label_values(&[iface.as_str()])
                    .inc_by(rxp);
                metrics
                    .network_tx_packets_total
                    .with_label_values(&[iface.as_str()])
                    .inc_by(txp);
                metrics
                    .network_rx_dropped_total
                    .with_label_values(&[iface.as_str()])
                    .inc_by(rxd);
                metrics
                    .network_tx_dropped_total
                    .with_label_values(&[iface.as_str()])
                    .inc_by(txd);
                let drop_flag = (rxd + txd) > 0;
                metrics
                    .network_degradation_drops
                    .with_label_values(&[iface.as_str()])
                    .set(if drop_flag { 1.0 } else { 0.0 });
                let degrade = (rxd + txd) > 0;
                metrics
                    .network_degradation_drops
                    .with_label_values(&[iface.as_str()])
                    .set(if degrade { 1.0 } else { 0.0 });

                let mut snap = *prev;
                snap.rx_packets = v.rx_packets;
                snap.tx_packets = v.tx_packets;
                snap.rx_dropped = v.rx_dropped;
                snap.tx_dropped = v.tx_dropped;
                self.previous.insert(iface.clone(), snap);
                if iface != "lo" {
                    if let Some((best, _, _, drops)) = best_iface.as_mut() {
                        if best == iface {
                            *drops = rxd.saturating_add(txd);
                        }
                    }
                }
            }
        }

        if let Some((iface, rx_delta, tx_delta, drops_delta)) = best_iface {
            let rx_per_s = if dt > 0.0 {
                Some(rx_delta as f64 / dt)
            } else {
                None
            };
            let tx_per_s = if dt > 0.0 {
                Some(tx_delta as f64 / dt)
            } else {
                None
            };
            let drops_per_s = if dt > 0.0 {
                Some(drops_delta as f64 / dt)
            } else {
                None
            };
            self.status
                .set_network_summary(Some(iface), rx_per_s, tx_per_s, drops_per_s);
        } else {
            self.status.set_network_summary(None, None, None, None);
        }
        // Mark degradation if any interface saw drops
        let any_drops = self
            .previous
            .iter()
            .any(|(_, snap)| snap.rx_dropped > 0 || snap.tx_dropped > 0);
        self.status.set_network_degraded(any_drops);

        // TCP retransmissions from /proc/net/netstat (TCPSegRetrans)
        if let Some(retrans_total) = read_tcp_retrans() {
            if let Some(prev) = self.prev_tcp_retrans {
                let delta = retrans_total.saturating_sub(prev);
                metrics.network_tcp_retrans_total.inc_by(delta);
                metrics
                    .network_degradation_retrans
                    .set(if delta > 0 { 1.0 } else { 0.0 });
                if delta > 0 {
                    self.status.set_network_degraded(true);
                }
            }
            self.prev_tcp_retrans = Some(retrans_total);
        }

        Ok(())
    }
}

#[derive(Default, Clone, Copy)]
struct NetDevVals {
    rx_packets: u64,
    tx_packets: u64,
    rx_dropped: u64,
    tx_dropped: u64,
}

fn read_netdev() -> Option<HashMap<String, NetDevVals>> {
    let s = fs::read_to_string("/proc/net/dev").ok()?;
    let mut map = HashMap::new();
    for line in s.lines().skip(2) {
        if let Some(pos) = line.find(':') {
            let iface = line[..pos].trim().to_string();
            let rest = line[pos + 1..].split_whitespace().collect::<Vec<&str>>();
            if rest.len() >= 16 {
                let rx_packets = rest[1].parse().unwrap_or(0);
                let rx_dropped = rest[3].parse().unwrap_or(0);
                let tx_packets = rest[9].parse().unwrap_or(0);
                let tx_dropped = rest[11].parse().unwrap_or(0);
                map.insert(
                    iface,
                    NetDevVals {
                        rx_packets,
                        tx_packets,
                        rx_dropped,
                        tx_dropped,
                    },
                );
            }
        }
    }
    Some(map)
}

fn read_tcp_retrans() -> Option<u64> {
    let s = fs::read_to_string("/proc/net/netstat").ok()?;
    let mut header: Option<String> = None;
    let mut values: Option<String> = None;
    for line in s.lines() {
        if line.starts_with("TcpExt:") {
            header = Some(line.to_string());
        } else if line.starts_with("TcpExt ") {
            values = Some(line.to_string());
        }
    }
    let header = header?;
    let values = values?;
    let keys: Vec<&str> = header.split_whitespace().collect();
    let vals: Vec<&str> = values.split_whitespace().collect();
    let idx = keys.iter().position(|k| *k == "TCPSegRetrans")?;
    vals.get(idx)?.parse().ok()
}
