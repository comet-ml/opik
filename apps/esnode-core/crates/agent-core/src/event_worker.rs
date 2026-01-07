//! Dedicated NVML event worker. Best-effort; only runs when GPU events are enabled.

#[cfg(all(feature = "gpu", target_os = "linux"))]
use nvml_wrapper::{bitmasks::event::EventTypes, enums::event::XidError, Nvml};
#[cfg(all(feature = "gpu", target_os = "linux"))]
use tokio::sync::mpsc::Sender;

#[cfg(all(feature = "gpu", target_os = "linux"))]
#[derive(Debug, Clone)]
pub struct EventRecord {
    pub uuid: String,
    pub index: String,
    pub kind: String,
    pub xid_code: Option<i32>,
    pub ts_ms: u64,
}

#[cfg(all(feature = "gpu", target_os = "linux"))]
pub fn spawn_event_worker(
    tx: Sender<EventRecord>,
    visible_filter: Option<std::collections::HashSet<String>>,
) {
    // Spawn a detached task; it will exit on NVML errors.
    tokio::spawn(async move {
        let nvml = match Nvml::init() {
            Ok(n) => n,
            Err(e) => {
                tracing::warn!("NVML event worker init failed: {}", e);
                return;
            }
        };
        let count = match nvml.device_count() {
            Ok(c) => c,
            Err(e) => {
                tracing::warn!("NVML event worker device count failed: {}", e);
                return;
            }
        };
        let event_set = match nvml.create_event_set() {
            Ok(es) => es,
            Err(e) => {
                tracing::warn!("NVML create_event_set failed: {}", e);
                return;
            }
        };
        let mut event_set = Some(event_set);
        // Register events per device (best-effort)
        for idx in 0..count {
            if let Ok(device) = nvml.device_by_index(idx) {
                let uuid = device.uuid().unwrap_or_else(|_| format!("GPU-{}", idx));
                if let Some(filter) = &visible_filter {
                    if !filter.contains(&uuid) && !filter.contains(&idx.to_string()) {
                        continue;
                    }
                }
                if let Some(es) = event_set.take() {
                    if let Ok(es2) = device.register_events(
                        EventTypes::SINGLE_BIT_ECC_ERROR
                            | EventTypes::DOUBLE_BIT_ECC_ERROR
                            | EventTypes::CRITICAL_XID_ERROR
                            | EventTypes::PSTATE_CHANGE
                            | EventTypes::CLOCK_CHANGE,
                        es,
                    ) {
                        event_set = Some(es2);
                    }
                }
            }
        }
        let Some(event_set) = event_set else {
            tracing::warn!("NVML event set registration failed");
            return;
        };
        loop {
            match event_set.wait(5000) {
                Ok(ev) => {
                    let ts_ms = chrono::Utc::now().timestamp_millis() as u64;
                    let uuid = ev.device.uuid().unwrap_or_else(|_| "unknown".to_string());
                    let mut kind = "other".to_string();
                    let mut xid_code: Option<i32> = None;
                    if ev.event_type.contains(EventTypes::SINGLE_BIT_ECC_ERROR) {
                        kind = "ecc_single".to_string();
                    } else if ev.event_type.contains(EventTypes::DOUBLE_BIT_ECC_ERROR) {
                        kind = "ecc_double".to_string();
                    } else if ev.event_type.contains(EventTypes::CRITICAL_XID_ERROR) {
                        kind = "xid".to_string();
                        xid_code = ev.event_data.map(|x| match x {
                            XidError::Value(v) => v as i32,
                            XidError::Unknown => -1,
                        });
                    }
                    let _ = tx
                        .send(EventRecord {
                            uuid,
                            index: ev.device.index().unwrap_or(0).to_string(),
                            kind,
                            xid_code,
                            ts_ms,
                        })
                        .await;
                }
                Err(nvml_wrapper::error::NvmlError::Timeout) => continue,
                Err(e) => {
                    tracing::debug!("NVML event worker exiting: {}", e);
                    break;
                }
            }
        }
    });
}
