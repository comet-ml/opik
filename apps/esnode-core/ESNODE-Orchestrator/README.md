# ESNODE-Orchestrator

**Library for Autonomous Resource Orchestration in ESNODE-Core.**

This crate provides optional, advanced orchestration capabilities for the ESNODE agent, including:
- **Device Scoring**: Performance-per-watt aware task scheduling.
- **Autonomous Features**:
    - **Zombie Reaper**: Cleans up dead processes.
    - **Turbo Mode**: Optimizes latency for critical tasks.
    - **Smart Bin-Packing**: Efficiently allocates tasks to devices.
    - **Flash Preemption**: Preempts low-priority tasks for urgent ones.
    - **Dataset Prefetching**: Loads data ahead of computation.
    - **Bandwidth Reserves**: Quality of Service for network traffic.
    - **Filesystem Cleanup**: Maintains storage health.

## Usage

This library is designed to be embedded within `esnode-core`.

### Configuration

Enable via `esnode.toml`:

```toml
[orchestrator]
enabled = true
enable_zombie_reaper = true
# ... other features
```

Or via CLI flag:
```bash
esnode-core --enable-orchestrator=true
```
