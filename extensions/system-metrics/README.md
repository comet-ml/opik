# Opik system metrics extension

This extension stores application-level Agent metrics in Opik's existing ClickHouse database. It does not require Prometheus, Kubernetes API access, a DaemonSet, node mounts, or a privileged container.

## Data path

```text
Python Agent -> Opik authenticated API -> system_metrics ClickHouse table -> Opik query API
```

The backend owns `workspace_id` and `project_id`; clients cannot supply either value in a metric point. A client supplies a stable `service_instance_id`, and writes the same value to root Trace metadata for time-window correlation.

## Docker deployment

The default local compose configuration enables the feature with `SYSTEM_METRICS_ENABLED=true`. Build the modified backend and start the stack from the repository root:

```bash
./opik.sh --build
./opik.sh --verify
```

The backend migration creates `system_metrics` automatically. Samples expire after 30 days.

## Ingestion API

```http
POST /api/v1/private/projects/{projectId}/system-metrics/batch
Content-Type: application/json
```

```json
{
  "metrics": [
    {
      "sample_id": "0199ffff-0000-7000-8000-000000000001",
      "service_name": "peppa-agent",
      "service_instance_id": "worker-5b7a",
      "agent_id": "assistant",
      "metric_name": "agent.process.memory",
      "unit": "By",
      "timestamp": "2026-09-20T14:00:00Z",
      "value": 134217728,
      "attributes": {"state": "rss"}
    }
  ]
}
```

The endpoint accepts at most 1,000 points by default. Each point supports at most 16 bounded attributes. Timestamps older than seven days or more than five minutes in the future are rejected. Retries remain idempotent after ClickHouse merges because `sample_id` is part of the replacing table key.

## Query API

```http
GET /api/v1/private/projects/{projectId}/system-metrics
    ?instance_id=worker-5b7a
    &metric_name=agent.process.memory
    &from=2026-09-20T13:55:00Z
    &to=2026-09-20T14:05:00Z
```

The default maximum range is 24 hours and the default maximum response is 10,000 points. The response sets `truncated=true` when more points exist.

## Python integration

See [`python/README.md`](python/README.md). The reporter collects current-process CPU time, RSS, heartbeat, bounded HTTP request metrics, and capacity/inode information for explicitly configured container-visible paths.

## Configuration

```text
SYSTEM_METRICS_ENABLED=true
SYSTEM_METRICS_MAX_QUERY_RANGE=24h
SYSTEM_METRICS_MAX_BATCH_SIZE=1000
SYSTEM_METRICS_MAX_QUERY_POINTS=10000
```

The metric table is independent from Trace and Span tables. This keeps retention and high-frequency writes isolated and avoids changing Trace storage semantics.
