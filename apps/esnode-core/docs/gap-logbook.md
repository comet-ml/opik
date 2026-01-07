# ESNODE Gap Logbook (living document)

Tracks real-world failures → missing signals/actions. Use this to drive roadmap.

## Open gaps
- GPU ECC/thermal/power throttling flags: add explicit degradation gauges and status summary.
- NIC retransmits and disk latency buckets: add measurements + alert-friendly gauges.
- Workload attribution labels: propagate model/namespace/user to AI efficiency, network, and power metrics.
- Auto-remediation actions: opt-in, guarded (disk cleanup on high IOPS latency, cache drop on severe memory pressure, task rebalance on thermal/power headroom) with dry-run and audit logs.
- Feedback loop: record before/after impact for orchestrator actions; emit events/metrics.

## Recently closed
- Orchestrator control API defaulted to loopback; bearer token support added.
- Network/disk degradation flags (drops, busy) and swap spike flag added; status exposes flags and aggregate score.
- TSDB export snapshot avoids index reset.
