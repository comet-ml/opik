"""
Code Agent Trace Integration for Opik.

This integration allows you to upload trace data from AI coding tools
like Cursor, Claude Code, and other AI coding agents to Opik for visibility
and attribution tracking.

Example:
    ```python
    import json
    from collections import defaultdict
    from opik.integrations.code_agent_trace import log_code_agent_turn, flush

    # Read and group traces by generation_id
    turns = defaultdict(list)
    with open(".agent-trace/traces.jsonl") as f:
        for line in f:
            if line.strip():
                record = json.loads(line)
                gen_id = record.get("generation_id", "unknown")
                turns[gen_id].append(record)

    # Log each turn
    for generation_id, records in turns.items():
        log_code_agent_turn(records, project_name="my-project")

    flush()
    ```

CLI Usage:
    ```bash
    # Upload all traces from default location
    opik code-agent-trace --project "my-project"

    # Upload from a specific file
    opik code-agent-trace -f ./my-traces.jsonl -p "my-project"

    # Preview what would be uploaded
    opik code-agent-trace -p "my-project" --dry-run
    ```
"""

from .api import flush, log_code_agent_turn
from .converters import (
    convert_generation_to_trace_and_spans,
    group_records_by_generation,
)
from .types import (
    ChatCompletionResponse,
    ConversionResult,
    LinesChanged,
    SpanData,
    TraceData,
    TraceRecord,
)

__all__ = [
    # Main API
    "log_code_agent_turn",
    "flush",
    # Converters
    "convert_generation_to_trace_and_spans",
    "group_records_by_generation",
    # Types
    "TraceRecord",
    "TraceData",
    "SpanData",
    "LinesChanged",
    "ConversionResult",
    "ChatCompletionResponse",
]
