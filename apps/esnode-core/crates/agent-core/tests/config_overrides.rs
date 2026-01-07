use std::time::Duration;

use agent_core::{config::ConfigOverrides, AgentConfig};

#[test]
fn overrides_apply_all_booleans_and_scalars() {
    let mut base = AgentConfig::default();

    let overrides = ConfigOverrides {
        enable_cpu: Some(false),
        enable_memory: Some(false),
        enable_disk: Some(false),
        enable_network: Some(false),
        enable_gpu: Some(false),
        enable_gpu_amd: Some(true),
        enable_power: Some(false),
        enable_gpu_mig: Some(true),
        enable_gpu_events: Some(true),
        gpu_visible_devices: Some(Some("GPU-123,1".to_string())),
        mig_config_devices: Some(Some("GPU-123".to_string())),
        k8s_mode: Some(true),
        enable_mcp: Some(true),
        enable_app: Some(true),
        enable_rack_thermals: Some(true),
        orchestrator: None,
        app_metrics_url: None,
        listen_address: Some("1.2.3.4:9999".to_string()),
        scrape_interval: Some(Duration::from_secs(10)),
        enable_local_tsdb: Some(false),
        local_tsdb_path: Some("/tmp/tsdb".to_string()),
        local_tsdb_retention_hours: Some(12),
        local_tsdb_max_disk_mb: Some(321),
        managed_server: Some(Some("srv".to_string())),
        managed_cluster_id: Some(Some("cluster".to_string())),
        managed_node_id: Some(Some("node".to_string())),
        managed_join_token: Some(Some("token".to_string())),
        managed_last_contact_unix_ms: Some(Some(123)),
        node_power_envelope_watts: Some(456.0),
        log_level: None,
    };

    base.apply_overrides(overrides);

    assert!(!base.enable_cpu);
    assert!(!base.enable_memory);
    assert!(!base.enable_disk);
    assert!(!base.enable_network);
    assert!(!base.enable_gpu);
    assert!(base.enable_gpu_amd);
    assert!(!base.enable_power);
    assert!(base.enable_gpu_mig);
    assert!(base.enable_gpu_events);
    assert_eq!(base.gpu_visible_devices.as_deref(), Some("GPU-123,1"));
    assert_eq!(base.mig_config_devices.as_deref(), Some("GPU-123"));
    assert!(base.k8s_mode);
    assert!(base.enable_mcp);
    assert!(base.enable_app);
    assert!(base.enable_rack_thermals);
    assert_eq!(base.listen_address, "1.2.3.4:9999");
    assert_eq!(base.scrape_interval, Duration::from_secs(10));
    assert!(!base.enable_local_tsdb);
    assert_eq!(base.local_tsdb_path, "/tmp/tsdb");
    assert_eq!(base.local_tsdb_retention_hours, 12);
    assert_eq!(base.local_tsdb_max_disk_mb, 321);
    assert_eq!(base.managed_server.as_deref(), Some("srv"));
    assert_eq!(base.managed_cluster_id.as_deref(), Some("cluster"));
    assert_eq!(base.managed_node_id.as_deref(), Some("node"));
    assert_eq!(base.managed_join_token.as_deref(), Some("token"));
    assert_eq!(base.managed_last_contact_unix_ms, Some(123));
    assert_eq!(base.node_power_envelope_watts, Some(456.0));
}
