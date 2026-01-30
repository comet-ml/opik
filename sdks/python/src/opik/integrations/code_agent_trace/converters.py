"""
Converters for transforming Agent Trace records to Opik traces.

This module handles the mapping between Cursor hook events and Opik data model:
- generation_id → Opik Trace (one trace per agent "turn")
- User message (beforeSubmitPrompt) → trace input
- Assistant response + tool calls → trace output in OpenAI messages format
- Individual tool executions → spans within the trace
"""

import json
from typing import Any, Dict, List, Optional

from opik import id_helpers
from opik.types import FeedbackScoreDict, SpanType

from .helpers import (
    calculate_end_time,
    count_lines_changed,
    parse_timestamp,
    shorten_command,
    shorten_path,
)
from .message_builders import (
    build_assistant_message,
    build_chat_completion_response,
    build_code_changes_summary,
    build_tool_message,
    build_user_message,
)
from .types import (
    ConversionResult,
    SpanData,
    TraceData,
    TraceRecord,
)


def group_records_by_generation(
    records: List[TraceRecord],
) -> Dict[str, List[TraceRecord]]:
    """
    Group records by generation_id.

    Records with the same generation_id belong to the same agent "turn".
    Records without generation_id get their own unique group.

    Args:
        records: List of trace records.

    Returns:
        Dictionary mapping generation_id to list of records.
    """
    grouped: Dict[str, List[TraceRecord]] = {}

    for record in records:
        generation_id = record.get("generation_id")

        if generation_id:
            key = generation_id
        else:
            key = f"_standalone_{record.get('id', id_helpers.generate_id())}"

        grouped.setdefault(key, []).append(record)

    return grouped


def convert_generation_to_trace_and_spans(
    generation_id: str,
    records: List[TraceRecord],
    project_name: str,
) -> ConversionResult:
    """
    Convert records with same generation_id to Opik trace + spans.

    Input format: OpenAI messages array ready to send to LLM
    Output format: OpenAI chat completion response

    Args:
        generation_id: The generation ID grouping these records.
        records: List of trace records.
        project_name: Opik project name.

    Returns:
        Dictionary with 'trace' and 'spans' keys.

    Raises:
        ValueError: If records list is empty.
    """
    if not records:
        raise ValueError("Cannot convert empty record list")

    # Sort by timestamp
    sorted_records = sorted(records, key=lambda r: parse_timestamp(r.get("timestamp")))

    # Categorize records by event type
    user_messages: List[TraceRecord] = []
    assistant_responses: List[TraceRecord] = []
    assistant_thoughts: List[TraceRecord] = []
    tool_executions: List[TraceRecord] = []

    for record in sorted_records:
        event = record.get("event", "")
        if event == "user_message":
            user_messages.append(record)
        elif event == "assistant_message":
            assistant_responses.append(record)
        elif event == "assistant_thought":
            assistant_thoughts.append(record)
        elif event == "tool_execution":
            tool_executions.append(record)

    # Calculate time bounds
    first_record = sorted_records[0]
    last_record = sorted_records[-1]
    trace_start_time = parse_timestamp(first_record.get("timestamp"))
    last_start_time = parse_timestamp(last_record.get("timestamp"))

    # Try to get duration from last record
    last_data = last_record.get("data", {})
    trace_end_time = calculate_end_time(last_start_time, last_data.get("duration_ms"))

    # Build conversation messages in OpenAI chat format
    conversation_messages: List[Dict[str, Any]] = []

    # Add user message
    if user_messages:
        conversation_messages.append(build_user_message(user_messages[-1]))

    # If we have tool executions, build the assistant message with tool_calls
    if tool_executions:
        assistant_msg = build_assistant_message(
            response_records=[],
            thought_records=assistant_thoughts,
            tool_records=tool_executions,
        )
        conversation_messages.append(assistant_msg)

        # Add tool result messages for each tool execution
        for tool_record in tool_executions:
            conversation_messages.append(build_tool_message(tool_record))

    # Build the final assistant message for OUTPUT
    final_assistant_content: Optional[str] = None
    final_tool_calls: Optional[List[Dict[str, Any]]] = None

    # Add final assistant response
    if assistant_responses:
        final_response = assistant_responses[-1]
        content = final_response.get("data", {}).get("content", "")
        if content:
            final_assistant_content = content
    elif not tool_executions and assistant_thoughts:
        # If no tools, no response but we have thoughts, include them
        thoughts = [r.get("data", {}).get("content", "") for r in assistant_thoughts]
        reasoning = "\n\n".join(filter(None, thoughts))
        if reasoning:
            final_assistant_content = f"[Thinking]\n{reasoning}"

    # Add code changes summary to the final content
    code_changes_summary = build_code_changes_summary(tool_executions)
    if code_changes_summary:
        if final_assistant_content:
            final_assistant_content = (
                f"{final_assistant_content}\n\n{code_changes_summary}"
            )
        else:
            final_assistant_content = code_changes_summary

    # If no final content but we have tool calls, build tool_calls for output
    if not final_assistant_content and tool_executions:
        final_tool_calls = []
        for record in tool_executions:
            record_id = record.get("id", "")
            data = record.get("data", {})
            tool_type = data.get("tool_type", "unknown")

            if tool_type == "shell":
                arguments: Dict[str, Any] = {"command": data.get("command", "")}
                if data.get("cwd"):
                    arguments["cwd"] = data["cwd"]
                final_tool_calls.append(
                    {
                        "id": f"call_{record_id}",
                        "type": "function",
                        "function": {
                            "name": "shell",
                            "arguments": json.dumps(arguments),
                        },
                    }
                )
            elif tool_type == "file_edit":
                file_path = data.get("file_path", "")
                arguments = {"file_path": file_path}
                edits = data.get("edits", [])
                if edits:
                    arguments["edit_count"] = len(edits)
                final_tool_calls.append(
                    {
                        "id": f"call_{record_id}",
                        "type": "function",
                        "function": {
                            "name": "file_edit",
                            "arguments": json.dumps(arguments),
                        },
                    }
                )

    # Build trace INPUT: all messages captured so far (valid OpenAI format)
    # Input contains everything that was captured before the turn ended/was interrupted
    trace_input: Dict[str, Any] = {"messages": conversation_messages}

    # Determine if turn was interrupted
    # A turn is considered interrupted if we have a user message but no final assistant response
    # Tool-only turns (no user message) are NOT considered interrupted - they're autonomous actions
    has_user_input = bool(user_messages)
    has_final_response = bool(assistant_responses)
    is_interrupted = has_user_input and not has_final_response

    # Build trace OUTPUT: OpenAI chat completion response format
    model = first_record.get("model") or "unknown"

    # For interrupted turns, prepend interrupted message to any content
    output_content = final_assistant_content
    output_tool_calls = final_tool_calls
    finish_reason_override = None

    if is_interrupted:
        interrupted_msg = "[Agent turn was interrupted]"
        if output_content:
            output_content = f"{interrupted_msg}\n\n{output_content}"
        elif not output_tool_calls:
            output_content = interrupted_msg
        finish_reason_override = "stop"

    trace_output = build_chat_completion_response(
        generation_id=generation_id,
        model=model,
        created_timestamp=int(trace_start_time.timestamp()),
        content=output_content,
        tool_calls=output_tool_calls,
        finish_reason=finish_reason_override,
    )

    # Build trace name
    edit_count = sum(
        1 for r in tool_executions if r.get("data", {}).get("tool_type") == "file_edit"
    )
    shell_count = sum(
        1 for r in tool_executions if r.get("data", {}).get("tool_type") == "shell"
    )

    parts = []
    if is_interrupted:
        parts.append("interrupted")
    if edit_count > 0:
        parts.append(f"{edit_count} edit{'s' if edit_count > 1 else ''}")
    if shell_count > 0:
        parts.append(f"{shell_count} command{'s' if shell_count > 1 else ''}")
    trace_name = f"Agent Turn: {', '.join(parts)}" if parts else "Agent Turn"

    # Build trace metadata
    conversation_id = first_record.get("conversation_id")
    user_email = first_record.get("user_email")

    trace_metadata: Dict[str, Any] = {
        "source": "cursor-agent-trace",
    }
    if model and model != "unknown":
        trace_metadata["model"] = model
    if generation_id and not generation_id.startswith("_standalone_"):
        trace_metadata["generation_id"] = generation_id
    if conversation_id:
        trace_metadata["conversation_id"] = conversation_id
    if user_email:
        trace_metadata["user"] = user_email

    # Calculate feedback scores
    duration_seconds = (trace_end_time - trace_start_time).total_seconds()
    lines_changed = count_lines_changed([dict(r) for r in tool_executions])

    feedback_scores: List[FeedbackScoreDict] = [
        {"name": "duration_seconds", "value": round(duration_seconds, 2)},
        {"name": "lines_added", "value": float(lines_changed["lines_added"])},
        {"name": "lines_deleted", "value": float(lines_changed["lines_deleted"])},
    ]

    # Generate trace ID
    trace_id = id_helpers.generate_id(timestamp=trace_start_time)

    # Build trace data
    trace_data: TraceData = {
        "id": trace_id,
        "project_name": project_name,
        "name": trace_name,
        "start_time": trace_start_time,
        "end_time": trace_end_time,
        "input": trace_input,
        "output": trace_output,
        "metadata": trace_metadata,
        "tags": ["agent-trace"],
        "thread_id": conversation_id,
        "error_info": None,
        "feedback_scores": feedback_scores,
    }

    # Build spans for tool executions
    spans_data: List[SpanData] = [
        _build_span_for_tool(record, trace_id, project_name)
        for record in tool_executions
    ]

    # Sort spans by start time
    spans_data.sort(key=lambda s: s["start_time"])

    # Adjust end times for spans without duration
    for i, span in enumerate(spans_data):
        if span["start_time"] == span["end_time"]:
            if i + 1 < len(spans_data):
                span["end_time"] = spans_data[i + 1]["start_time"]
            else:
                span["end_time"] = trace_end_time

    return {"trace": trace_data, "spans": spans_data}


def _build_span_for_tool(
    record: TraceRecord,
    trace_id: str,
    project_name: str,
) -> SpanData:
    """Build span data for a tool execution record."""
    data = record.get("data", {})
    tool_type = data.get("tool_type", "unknown")

    start_time = parse_timestamp(record.get("timestamp"))
    duration_ms = data.get("duration_ms")
    end_time = calculate_end_time(start_time, duration_ms)

    span_input: Dict[str, Any]
    span_output: Dict[str, Any]
    span_type: SpanType

    if tool_type == "shell":
        command = data.get("command", "")
        span_name = f"Shell: {shorten_command(command)}"
        span_type = "tool"
        span_input = {"command": command}
        if data.get("cwd"):
            span_input["cwd"] = data["cwd"]
        span_output = {}
        if data.get("output"):
            span_output["output"] = data["output"]
        if duration_ms is not None:
            span_output["duration_ms"] = round(duration_ms, 2)
    elif tool_type == "file_edit":
        file_path = data.get("file_path", "unknown")
        span_name = f"Edit: {shorten_path(file_path)}"
        span_type = "general"
        span_input = {"file_path": file_path}
        if data.get("edits"):
            span_input["edits"] = data["edits"]
        span_output = {}
        if data.get("line_ranges"):
            span_output["line_ranges"] = data["line_ranges"]
    else:
        span_name = f"Tool: {tool_type}"
        span_type = "general"
        span_input = dict(data)
        span_output = {}

    return {
        "id": id_helpers.generate_id(timestamp=start_time),
        "trace_id": trace_id,
        "project_name": project_name,
        "name": span_name,
        "type": span_type,
        "start_time": start_time,
        "end_time": end_time,
        "input": span_input or None,
        "output": span_output or None,
        "metadata": {
            "record_id": record.get("id"),
            "tool_type": tool_type,
        },
        "tags": ["agent-trace"],
    }
