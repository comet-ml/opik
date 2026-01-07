// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use std::time::{Duration, Instant};

use async_trait::async_trait;
use tracing::{debug, warn};

use crate::collectors::Collector;
use crate::metrics::MetricsRegistry;
use crate::state::StatusState;

pub struct AppCollector {
    url: String,
    status: StatusState,
    last_tokens: Option<f64>,
    last_ts: Option<Instant>,
    warned: bool,
    agent_label: String,
    client: reqwest::Client,
}

impl AppCollector {
    pub fn new(status: StatusState, url: String, agent_label: String) -> Self {
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(2))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        Self {
            url,
            status,
            last_tokens: None,
            last_ts: None,
            warned: false,
            agent_label,
            client,
        }
    }

    async fn fetch_metrics(&self) -> Option<String> {
        let resp = self.client.get(&self.url).send().await;
        match resp {
            Ok(r) => r.text().await.ok(),
            Err(e) => {
                debug!("Failed to fetch app metrics from {}: {}", self.url, e);
                None
            }
        }
    }

    fn parse_tokens(&self, body: &str) -> Option<f64> {
        let mut total = 0.0;
        let mut found = false;

        for line in body.lines() {
            if line.starts_with('#') {
                continue;
            }
            // Support vLLM, TGI, and generic counters
            // vllm:generation_tokens_total 1234
            // vllm:prompt_tokens_total 5678
            // tgi_generated_tokens 9012
            // model_tokens_total 3456
            if line.starts_with("vllm:generation_tokens_total")
                || line.starts_with("vllm:prompt_tokens_total")
                || line.starts_with("tgi_generated_tokens")
                || line.starts_with("model_tokens_total")
            {
                if let Some(val_str) = line.split_whitespace().last() {
                    if let Ok(val) = val_str.parse::<f64>() {
                        total += val;
                        found = true;
                    }
                }
            }
        }

        if found {
            Some(total)
        } else {
            None
        }
    }
}

#[async_trait]
impl Collector for AppCollector {
    fn name(&self) -> &'static str {
        "app"
    }

    async fn collect(&mut self, metrics: &MetricsRegistry) -> anyhow::Result<()> {
        let Some(body) = self.fetch_metrics().await else {
            if !self.warned {
                warn!("App metrics endpoint unreachable at {}", self.url);
                self.warned = true;
            }
            return Ok(());
        };

        if let Some(current_tokens) = self.parse_tokens(&body) {
            let now = Instant::now();

            if let (Some(prev_tokens), Some(prev_ts)) = (self.last_tokens, self.last_ts) {
                let dt = now.duration_since(prev_ts).as_secs_f64();
                if dt > 0.0 && current_tokens >= prev_tokens {
                    let rate = (current_tokens - prev_tokens) / dt;
                    metrics.app_tokens_per_sec.set(rate);
                    self.status.set_app_metrics(rate);

                    // Also update the convenience efficiency metric if we have power
                    if let Some(tps) = self.status.snapshot().app_tokens_per_watt {
                        metrics
                            .ai_tokens_per_watt
                            .with_label_values(&[self.agent_label.as_str()])
                            .set(tps);
                    }
                }
            }

            self.last_tokens = Some(current_tokens);
            self.last_ts = Some(now);
            self.warned = false; // Reset warning on success
        }

        Ok(())
    }
}
