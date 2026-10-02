# Opik application system metrics

This extension sends Python process, HTTP and configured filesystem metrics directly to the Opik backend. Metrics are stored in the dedicated ClickHouse `system_metrics` table.

```bash
python -m pip install -r requirements.txt
```

```python
from opik_system_metrics import OpikSystemMetrics

metrics = OpikSystemMetrics(
    base_url="http://127.0.0.1:5173/api",
    project_id="YOUR_PROJECT_UUID",
    service_name="peppa-agent",
    # Use the Pod UID/name or another stable process-instance identifier.
    service_instance_id="worker-5b7a",
    agent_id="assistant",
    filesystem_paths={"workspace": "/app/workspace"},
).start()

# Add the same instance ID to the root Opik Trace metadata.
trace_metadata = {"service.instance.id": metrics.instance_id}

# Call while the Opik trace context is active. Trace ID and Thread ID are
# discovered automatically from opik.opik_context.
metrics.record_request("/agents/{id}", "POST", 200, 0.25)

# MCP requests use the same pipeline and are marked separately in the UI.
metrics.record_mcp_request(
    "/mcp/tools/call",
    "POST",
    200,
    0.12,
    mcp_method="tools/call",
)

# Middleware outside the tracked call can pass the correlation explicitly.
metrics.record_request(
    "/agents/{id}",
    "POST",
    200,
    0.25,
    trace_id=trace_id,
    thread_id=thread_id,
)

# Run from the application shutdown hook.
metrics.close()
```

If `service_instance_id` is omitted, the reporter uses `OPIK_SERVICE_INSTANCE_ID` and then the container hostname. For authenticated Opik installations, pass `api_key` and `workspace`. The reporter uses a bounded in-memory buffer and a three-second HTTP timeout. Failed uploads remain buffered until the buffer fills; metric loss never blocks the Agent request path.
