"""
Public API for logging Agent Trace data to Opik.

This module provides functions for uploading Agent Trace records
from tools like Cursor, Claude Code, and other AI coding agents.
"""

from typing import Any, Dict, List, Optional

from opik.api_objects import opik_client, trace

from .converters import convert_generation_to_trace_and_spans


def log_code_agent_turn(
    trace_records: List[Dict[str, Any]],
    project_name: Optional[str] = None,
) -> trace.Trace:
    """
    Log a single agent turn (all operations with the same generation_id) to Opik.

    This function creates one Opik trace with multiple spans representing the
    individual operations (file edits, shell commands) within a single agent turn.

    All trace_records MUST have the same generation_id. Use this function when you
    have already grouped records by generation_id. For batch processing of multiple
    turns, group records by generation_id first and call this function for each group.

    Mapping:
    - generation_id → one Opik trace (the agent turn)
    - Individual operations → spans within the trace
    - conversation_id → thread_id (groups multiple turns into a session)
    - timestamp + duration_ms → start_time and end_time

    Args:
        trace_records: List of Agent Trace records for a single agent turn.
            All records must have the same generation_id.
        project_name: The name of the project. If not set, uses the default
            project from Opik configuration.

    Returns:
        trace.Trace: The created trace object.

    Raises:
        ValueError: If trace_records is empty or contains mixed generation_ids.

    Example:
        ```python
        import json
        from collections import defaultdict
        from opik.integrations.code_agent_trace import log_code_agent_turn

        # Read and group traces by generation_id
        turns = defaultdict(list)
        with open(".agent-trace/traces.jsonl") as f:
            for line in f:
                if line.strip():
                    record = json.loads(line)
                    gen_id = record.get("metadata", {}).get("generation_id", "unknown")
                    turns[gen_id].append(record)

        # Log each turn
        for generation_id, records in turns.items():
            trace = log_code_agent_turn(records, project_name="my-project")
            print(f"Logged turn {generation_id}: {len(records)} operations")
        ```
    """
    if not trace_records:
        raise ValueError("trace_records cannot be empty")

    # Validate all records have the same generation_id (support both formats)
    generation_ids = set()
    for record in trace_records:
        # New format: generation_id at top level
        gen_id = record.get("generation_id")
        # Old format: generation_id in metadata
        if not gen_id:
            metadata = record.get("metadata") or {}
            gen_id = metadata.get("generation_id")
        generation_ids.add(gen_id)

    if len(generation_ids) > 1:
        raise ValueError(
            f"All trace_records must have the same generation_id. "
            f"Found {len(generation_ids)} different generation_ids: {generation_ids}"
        )

    generation_id = generation_ids.pop() or f"_standalone_{trace_records[0].get('id', 'unknown')}"

    # Get cached client
    client = opik_client.get_client_cached()

    # Resolve project name
    effective_project_name = project_name or client._project_name

    # Convert to trace + spans
    result = convert_generation_to_trace_and_spans(
        generation_id=generation_id,
        records=trace_records,
        project_name=effective_project_name,
    )

    trace_data = result["trace"]
    spans_data = result["spans"]

    # Create the trace with feedback scores
    created_trace = client.trace(
        id=trace_data["id"],
        name=trace_data["name"],
        start_time=trace_data["start_time"],
        end_time=trace_data["end_time"],
        input=trace_data["input"],
        output=trace_data["output"],
        metadata=trace_data["metadata"],
        tags=trace_data["tags"],
        thread_id=trace_data["thread_id"],
        error_info=trace_data["error_info"],
        feedback_scores=trace_data.get("feedback_scores"),
        project_name=effective_project_name,
    )

    # Create spans for each operation
    for span_data in spans_data:
        client.span(
            trace_id=trace_data["id"],
            id=span_data["id"],
            name=span_data["name"],
            start_time=span_data["start_time"],
            end_time=span_data["end_time"],
            input=span_data["input"],
            output=span_data["output"],
            metadata=span_data["metadata"],
            tags=span_data["tags"],
            type=span_data["type"],
            project_name=effective_project_name,
        )

    return created_trace


def flush() -> None:
    """Flush pending Opik data to the backend."""
    client = opik_client.get_client_cached()
    client.flush()
