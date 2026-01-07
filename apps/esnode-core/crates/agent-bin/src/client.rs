// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use agent_core::state::StatusSnapshot;
use anyhow::{anyhow, Context, Result};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;
use url::Url;

/// Lightweight HTTP client for talking to the local agent without external deps.
pub struct AgentClient {
    base_url: String,
}

impl AgentClient {
    pub fn new(listen_address: &str) -> Self {
        let normalized =
            if listen_address.starts_with("http://") || listen_address.starts_with("https://") {
                listen_address.to_string()
            } else {
                format!("http://{listen_address}")
            };
        Self {
            base_url: normalized.trim_end_matches('/').to_string(),
        }
    }

    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    pub fn fetch_status(&self) -> Result<StatusSnapshot> {
        let (status, body) = self.http_get("/status")?;
        if status == 404 {
            return Err(anyhow!("requesting /status: 404"));
        }
        let snapshot: StatusSnapshot =
            serde_json::from_str(&body).context("parsing status JSON")?;
        Ok(snapshot)
    }

    pub fn fetch_metrics_text(&self) -> Result<String> {
        let (status, body) = self.http_get("/metrics")?;
        if status == 404 {
            return Err(anyhow!("requesting /metrics: 404"));
        }
        Ok(body)
    }

    pub fn fetch_orchestrator_metrics(&self) -> Result<esnode_orchestrator::PubMetrics> {
        let (status, body) = self.http_get("/orchestrator/metrics")?;
        if status == 404 {
            return Ok(esnode_orchestrator::PubMetrics {
                device_count: 0,
                pending_tasks: 0,
                devices: vec![],
            });
        }
        let metrics: esnode_orchestrator::PubMetrics =
            serde_json::from_str(&body).context("parsing orchestrator metrics")?;
        Ok(metrics)
    }

    fn http_get(&self, path: &str) -> Result<(u16, String)> {
        let url = Url::parse(&format!("{}{}", self.base_url, path)).context("parsing URL")?;
        let host = url
            .host_str()
            .ok_or_else(|| anyhow!("missing host in {url}"))?;
        let port = url.port_or_known_default().unwrap_or(80);
        let addr: SocketAddr = format!("{host}:{port}")
            .parse()
            .context("resolving address")?;
        let mut stream = TcpStream::connect_timeout(&addr, Duration::from_secs(2))
            .context("connecting to agent")?;
        stream.set_read_timeout(Some(Duration::from_secs(2))).ok();
        stream.set_write_timeout(Some(Duration::from_secs(2))).ok();
        let req = format!(
            "GET {} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n",
            url.path(),
            host
        );
        stream
            .write_all(req.as_bytes())
            .context("sending request")?;
        let _ = stream.flush();

        let mut resp_bytes = Vec::new();
        let mut buf = [0u8; 1024];
        loop {
            match stream.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => resp_bytes.extend_from_slice(&buf[..n]),
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => continue,
                Err(e) if e.kind() == std::io::ErrorKind::ConnectionReset => {
                    // Use whatever we managed to read before reset.
                    break;
                }
                Err(e) => return Err(e).context("reading response"),
            }
        }
        let resp = String::from_utf8_lossy(&resp_bytes).to_string();
        let mut parts = resp.splitn(2, "\r\n\r\n");
        let status_line = parts.next().unwrap_or("");
        let body = parts.next().unwrap_or("").to_string();
        let status = status_line
            .split_whitespace()
            .nth(1)
            .and_then(|s| s.parse::<u16>().ok())
            .unwrap_or(0);
        Ok((status, body))
    }
}

#[cfg(test)]
mod tests {
    use super::AgentClient;
    use agent_core::state::StatusSnapshot;
    use std::{
        io::{Read, Write},
        net::TcpListener,
        thread,
    };

    #[test]
    fn base_url_normalizes_without_scheme() {
        let c = AgentClient::new("localhost:9100");
        assert_eq!(c.base_url(), "http://localhost:9100");
    }

    #[test]
    fn base_url_keeps_scheme() {
        let c = AgentClient::new("https://example.com");
        assert_eq!(c.base_url(), "https://example.com");
    }

    #[test]
    fn fetch_status_reads_json_from_local_server() {
        let listener = match TcpListener::bind("127.0.0.1:0") {
            Ok(l) => l,
            Err(_) => {
                // Environment may disallow binding; skip in that case.
                return;
            }
        };
        let addr = listener.local_addr().unwrap();

        thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                let mut _buf = [0u8; 1024];
                let _ = stream.read(&mut _buf);
                let body = r#"{
                    "healthy": true,
                    "load_avg_1m": 0.1,
                    "load_avg_5m": 0.1,
                    "load_avg_15m": 0.1,
                    "uptime_seconds": 5,
                    "last_scrape_unix_ms": 1,
                    "last_errors": [],
                    "node_power_watts": null,
                    "cpu_package_power_watts": [],
                    "cpu_temperatures": [],
                    "gpus": [],
                    "cpu_cores": 4,
                    "cpu_util_percent": 10.0,
                    "mem_total_bytes": 1024,
                    "mem_used_bytes": 512,
                    "mem_free_bytes": 256,
                    "swap_used_bytes": 0,
                    "disk_root_total_bytes": 1024,
                    "disk_root_used_bytes": 512,
                    "disk_root_io_time_ms": 1,
                    "primary_nic": "eth0",
                    "net_rx_bytes_per_sec": 10.0,
                    "net_tx_bytes_per_sec": 5.0,
                    "net_drops_per_sec": 0.0
                }"#;
                let resp = format!(
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nContent-Type: application/json\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = stream.write_all(resp.as_bytes());
            }
        });

        let client = AgentClient::new(&format!("{addr}"));
        let snapshot: StatusSnapshot = client.fetch_status().expect("status json");
        assert!(snapshot.healthy);
        assert_eq!(snapshot.cpu_cores, Some(4));
        assert_eq!(snapshot.cpu_util_percent, Some(10.0));
    }
}
