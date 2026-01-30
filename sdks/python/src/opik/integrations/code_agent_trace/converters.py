"""
Converters for transforming Agent Trace records to Opik traces with OpenAI-format messages.

This module handles the mapping between Cursor hook events and Opik data model:
- generation_id → Opik Trace (one trace per agent "turn")
- User message (beforeSubmitPrompt) → trace input
- Assistant response + tool calls → trace output in OpenAI messages format
- Individual tool executions → spans within the trace
"""

import datetime
import logging
from typing import Any, Dict, List, Optional, Union

from opik import datetime_helpers, id_helpers

LOGGER = logging.getLogger(__name__)


# =============================================================================
# Timestamp Helpers
# =============================================================================


def _parse_timestamp(timestamp_str: Optional[str]) -> datetime.datetime:
    """Parse ISO 8601 timestamp to datetime."""
    if not timestamp_str:
        return datetime_helpers.local_timestamp()
    try:
        if timestamp_str.endswith("Z"):
            timestamp_str = timestamp_str[:-1] + "+00:00"
        return datetime.datetime.fromisoformat(timestamp_str)
    except (ValueError, TypeError):
        LOGGER.warning("Failed to parse timestamp '%s', using current time", timestamp_str)
        return datetime_helpers.local_timestamp()


def _calculate_end_time(
    start_time: datetime.datetime, duration_ms: Optional[float]
) -> datetime.datetime:
    """Calculate end time from start time and duration."""
    if duration_ms is None:
        return start_time
    return start_time + datetime.timedelta(milliseconds=duration_ms)


# =============================================================================
# Display Helpers
# =============================================================================


def _shorten_path(path: str, max_length: int = 40) -> str:
    """Shorten file path for display."""
    if len(path) <= max_length:
        return path
    parts = path.split("/")
    if len(parts) <= 2:
        return path
    return f"{parts[0]}/.../{parts[-1]}"


def _shorten_command(command: str, max_length: int = 60) -> str:
    """Shorten command for display (removes cd prefix)."""
    if " && " in command:
        command = command.split(" && ")[-1]
    if len(command) <= max_length:
        return command
    return command[: max_length - 3] + "..."


# =============================================================================
# OpenAI Message Format Builders
# =============================================================================


def _build_user_message(record: Dict[str, Any]) -> Dict[str, Any]:
    """
    Build OpenAI-format user message.
    
    Format:
    {
        "role": "user",
        "content": [
            {"type": "text", "text": "..."},
            {"type": "image_url", "image_url": {"url": "...", "detail": "high"}}
        ]
    }
    """
    data = record.get("data", {})
    content_parts: List[Dict[str, Any]] = []
    
    # Add text content
    text_content = data.get("content", "")
    if text_content:
        content_parts.append({
            "type": "text",
            "text": text_content,
        })
    
    # Add attachments as images/files
    attachments = data.get("attachments", [])
    for attachment in attachments:
        file_path = attachment.get("filePath", "")
        if file_path:
            # Check if it's an image
            if any(file_path.lower().endswith(ext) for ext in [".png", ".jpg", ".jpeg", ".gif", ".webp"]):
                content_parts.append({
                    "type": "image_url",
                    "image_url": {
                        "url": file_path,
                        "detail": "high",
                    },
                })
            else:
                # Non-image file attachment
                content_parts.append({
                    "type": "file",
                    "file": {
                        "path": file_path,
                        "type": attachment.get("type", "file"),
                    },
                })
    
    return {
        "role": "user",
        "content": content_parts if content_parts else [{"type": "text", "text": ""}],
    }


def _build_assistant_message(
    response_records: List[Dict[str, Any]],
    thought_records: List[Dict[str, Any]],
    tool_records: List[Dict[str, Any]],
) -> Dict[str, Any]:
    """
    Build OpenAI-format assistant message with tool calls.
    
    Format:
    {
        "role": "assistant",
        "content": "...",  # or null if only tool calls
        "tool_calls": [
            {
                "id": "call_xxx",
                "type": "function",
                "function": {
                    "name": "tool_name",
                    "arguments": "{\"key\": \"value\"}"  # JSON string
                }
            }
        ]
    }
    """
    import json
    
    message: Dict[str, Any] = {
        "role": "assistant",
        "content": None,
        "refusal": None,
        "audio": None,
        "function_call": None,
    }
    
    # Add content from response
    if response_records:
        content = response_records[-1].get("data", {}).get("content", "")
        if content:
            message["content"] = content
    
    # Add reasoning/thinking if present (as part of content or separate field)
    if thought_records:
        thoughts = [r.get("data", {}).get("content", "") for r in thought_records]
        reasoning = "\n\n".join(filter(None, thoughts))
        if reasoning:
            # Include reasoning in content if no other content
            if message["content"] is None:
                message["content"] = f"[Thinking]\n{reasoning}"
            else:
                message["reasoning_content"] = reasoning
    
    # Add tool calls if present
    if tool_records:
        tool_calls = []
        for record in tool_records:
            record_id = record.get("id", "")
            data = record.get("data", {})
            tool_type = data.get("tool_type", "unknown")
            
            if tool_type == "shell":
                arguments = {
                    "command": data.get("command", ""),
                }
                if data.get("cwd"):
                    arguments["cwd"] = data["cwd"]
                    
                tool_calls.append({
                    "id": f"call_{record_id}",
                    "type": "function",
                    "function": {
                        "name": "shell",
                        "arguments": json.dumps(arguments),
                    },
                })
            elif tool_type == "file_edit":
                file_path = data.get("file_path", "")
                # Shorten path for arguments display
                short_path = _shorten_path(file_path, max_length=80)
                arguments = {
                    "file_path": short_path,
                }
                edits = data.get("edits", [])
                if edits:
                    # Summarize edits for display
                    arguments["edit_count"] = len(edits)
                    
                tool_calls.append({
                    "id": f"call_{record_id}",
                    "type": "function",
                    "function": {
                        "name": "file_edit",
                        "arguments": json.dumps(arguments),
                    },
                })
            elif tool_type == "tab_edit":
                file_path = data.get("file_path", "")
                short_path = _shorten_path(file_path, max_length=80)
                arguments = {"file_path": short_path}
                
                tool_calls.append({
                    "id": f"call_{record_id}",
                    "type": "function",
                    "function": {
                        "name": "tab_edit",
                        "arguments": json.dumps(arguments),
                    },
                })
        
        if tool_calls:
            message["tool_calls"] = tool_calls
    
    return message


def _build_tool_message(record: Dict[str, Any]) -> Dict[str, Any]:
    """
    Build OpenAI-format tool result message.
    
    Format:
    {
        "role": "tool",
        "tool_call_id": "call_xxx",
        "name": "tool_name",
        "content": "{\"result\": \"...\"}"  # JSON string or plain text
    }
    """
    import json
    
    record_id = record.get("id", "unknown")
    data = record.get("data", {})
    tool_type = data.get("tool_type", "unknown")
    
    if tool_type == "shell":
        tool_name = "shell"
        result = {
            "command": data.get("command", ""),
            "output": data.get("output", ""),
        }
        if data.get("duration_ms") is not None:
            result["duration_ms"] = round(data["duration_ms"], 2)
        if data.get("cwd"):
            result["cwd"] = data["cwd"]
        content = json.dumps(result)
        
    elif tool_type == "file_edit":
        tool_name = "file_edit"
        file_path = data.get("file_path", "unknown")
        line_ranges = data.get("line_ranges", [])
        result = {
            "file_path": file_path,
            "status": "success",
        }
        if line_ranges:
            result["lines_modified"] = [
                {"start": r.get("start_line"), "end": r.get("end_line")} 
                for r in line_ranges
            ]
        content = json.dumps(result)
        
    elif tool_type == "tab_edit":
        tool_name = "tab_edit"
        file_path = data.get("file_path", "unknown")
        result = {
            "file_path": file_path,
            "status": "success",
        }
        content = json.dumps(result)
        
    else:
        tool_name = tool_type
        content = json.dumps(data)
    
    return {
        "role": "tool",
        "tool_call_id": f"call_{record_id}",
        "name": tool_name,
        "content": content,
    }


# =============================================================================
# Record Grouping
# =============================================================================


def group_records_by_generation(
    records: List[Dict[str, Any]]
) -> Dict[str, List[Dict[str, Any]]]:
    """
    Group records by generation_id.

    Records with the same generation_id belong to the same agent "turn".
    Records without generation_id get their own unique group.

    Args:
        records: List of trace records.

    Returns:
        Dictionary mapping generation_id to list of records.
    """
    grouped: Dict[str, List[Dict[str, Any]]] = {}

    for record in records:
        generation_id = record.get("generation_id")

        if generation_id:
            key = generation_id
        else:
            key = f"_standalone_{record.get('id', id_helpers.generate_id())}"

        grouped.setdefault(key, []).append(record)

    return grouped


# =============================================================================
# Span Building
# =============================================================================


def _build_span_for_tool(
    record: Dict[str, Any],
    trace_id: str,
    project_name: str,
) -> Dict[str, Any]:
    """Build span data for a tool execution record."""
    data = record.get("data", {})
    tool_type = data.get("tool_type", "unknown")
    
    start_time = _parse_timestamp(record.get("timestamp"))
    duration_ms = data.get("duration_ms")
    end_time = _calculate_end_time(start_time, duration_ms)
    
    if tool_type == "shell":
        command = data.get("command", "")
        span_name = f"Shell: {_shorten_command(command)}"
        span_type = "tool"
        span_input = {"command": command}
        if data.get("cwd"):
            span_input["cwd"] = data["cwd"]
        span_output: Dict[str, Any] = {}
        if data.get("output"):
            span_output["output"] = data["output"]
        if duration_ms is not None:
            span_output["duration_ms"] = round(duration_ms, 2)
    elif tool_type == "file_edit":
        file_path = data.get("file_path", "unknown")
        span_name = f"Edit: {_shorten_path(file_path)}"
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
        span_input = data
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


# =============================================================================
# Main Conversion Functions
# =============================================================================


def convert_generation_to_trace_and_spans(
    generation_id: str,
    records: List[Dict[str, Any]],
    project_name: str,
) -> Dict[str, Any]:
    """
    Convert records with same generation_id to Opik trace + spans.

    Both input and output are in OpenAI messages format:
    - Input: [{"role": "user", "content": "..."}]
    - Output: [{"role": "assistant", ...}, {"role": "tool", ...}, ...]

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
    sorted_records = sorted(records, key=lambda r: _parse_timestamp(r.get("timestamp")))

    # Categorize records by event type
    user_messages: List[Dict[str, Any]] = []
    assistant_responses: List[Dict[str, Any]] = []
    assistant_thoughts: List[Dict[str, Any]] = []
    assistant_texts: List[Dict[str, Any]] = []  # Text before tool calls (from preToolUse)
    tool_executions: List[Dict[str, Any]] = []
    
    for record in sorted_records:
        event = record.get("event", "")
        if event == "user_message":
            user_messages.append(record)
        elif event == "assistant_message":
            assistant_responses.append(record)
        elif event == "assistant_thought":
            assistant_thoughts.append(record)
        elif event == "assistant_text":
            assistant_texts.append(record)
        elif event == "tool_execution":
            tool_executions.append(record)

    # Calculate time bounds
    first_record = sorted_records[0]
    last_record = sorted_records[-1]
    trace_start_time = _parse_timestamp(first_record.get("timestamp"))
    last_start_time = _parse_timestamp(last_record.get("timestamp"))
    
    # Try to get duration from last record
    last_data = last_record.get("data", {})
    trace_end_time = _calculate_end_time(last_start_time, last_data.get("duration_ms"))

    # Build trace input (OpenAI messages format - user message)
    input_messages: List[Dict[str, Any]] = []
    if user_messages:
        input_messages.append(_build_user_message(user_messages[-1]))
    
    trace_input = {"messages": input_messages} if input_messages else None

    # Build trace output (OpenAI messages format)
    output_messages: List[Dict[str, Any]] = []
    
    # Collect assistant text from preToolUse agent_message (text before tool calls)
    assistant_text_contents = []
    for text_record in assistant_texts:
        content = text_record.get("data", {}).get("content", "")
        if content:
            assistant_text_contents.append(content)
    
    # If we have tool executions, build the assistant message with tool_calls
    if tool_executions:
        # Combine all assistant text with tool calls in one message
        combined_content = "\n\n".join(assistant_text_contents) if assistant_text_contents else None
        
        # Build assistant message with content (text before tools) and tool_calls
        assistant_msg = _build_assistant_message(
            response_records=[],  # Final response comes later
            thought_records=assistant_thoughts,
            tool_records=tool_executions,
        )
        # Add the combined text content
        if combined_content:
            assistant_msg["content"] = combined_content
        
        output_messages.append(assistant_msg)
        
        # Add tool result messages for each tool execution
        for tool_record in tool_executions:
            output_messages.append(_build_tool_message(tool_record))
    elif assistant_text_contents:
        # No tools but we have assistant text - add it as a message
        output_messages.append({
            "role": "assistant",
            "content": "\n\n".join(assistant_text_contents),
        })
    
    # Add final assistant response (if we have one from afterAgentResponse)
    if assistant_responses:
        final_response = assistant_responses[-1]
        content = final_response.get("data", {}).get("content", "")
        if content:
            output_messages.append({
                "role": "assistant",
                "content": content,
            })
    elif not tool_executions and not assistant_text_contents and assistant_thoughts:
        # If no tools, no text, no response but we have thoughts, include them
        thoughts = [r.get("data", {}).get("content", "") for r in assistant_thoughts]
        reasoning = "\n\n".join(filter(None, thoughts))
        if reasoning:
            output_messages.append({
                "role": "assistant",
                "content": f"[Thinking]\n{reasoning}",
            })

    trace_output = {"messages": output_messages} if output_messages else None

    # Build trace name
    edit_count = sum(1 for r in tool_executions if r.get("data", {}).get("tool_type") == "file_edit")
    shell_count = sum(1 for r in tool_executions if r.get("data", {}).get("tool_type") == "shell")
    
    parts = []
    if edit_count > 0:
        parts.append(f"{edit_count} edit{'s' if edit_count > 1 else ''}")
    if shell_count > 0:
        parts.append(f"{shell_count} command{'s' if shell_count > 1 else ''}")
    trace_name = f"Agent Turn: {', '.join(parts)}" if parts else "Agent Turn"

    # Build trace metadata (include generation_id and conversation_id here)
    model = first_record.get("model")
    conversation_id = first_record.get("conversation_id")
    
    trace_metadata: Dict[str, Any] = {
        "source": "cursor-agent-trace",
    }
    if model:
        trace_metadata["model"] = model
    if generation_id and not generation_id.startswith("_standalone_"):
        trace_metadata["generation_id"] = generation_id
    if conversation_id:
        trace_metadata["conversation_id"] = conversation_id

    # Generate trace ID
    trace_id = id_helpers.generate_id(timestamp=trace_start_time)

    # Build trace data
    trace_data = {
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
    }

    # Build spans for tool executions
    spans_data = [
        _build_span_for_tool(record, trace_id, project_name)
        for record in tool_executions
    ]

    # Adjust end times for spans without duration (use next span's start time)
    for i, span in enumerate(spans_data):
        if span["start_time"] == span["end_time"]:
            if i + 1 < len(spans_data):
                span["end_time"] = spans_data[i + 1]["start_time"]
            else:
                span["end_time"] = trace_end_time

    return {"trace": trace_data, "spans": spans_data}


# =============================================================================
# Legacy Support
# =============================================================================


def group_traces_by_generation(
    traces: List[Dict[str, Any]]
) -> Dict[str, List[Dict[str, Any]]]:
    """
    Legacy function: Group traces by generation_id from metadata.
    
    For backward compatibility with old trace format.
    """
    grouped: Dict[str, List[Dict[str, Any]]] = {}

    for trace in traces:
        # Try new format first
        generation_id = trace.get("generation_id")
        
        # Fall back to old format (metadata.generation_id)
        if not generation_id:
            metadata = trace.get("metadata") or {}
            generation_id = metadata.get("generation_id")

        if generation_id:
            key = generation_id
        else:
            key = f"_standalone_{trace.get('id', id_helpers.generate_id())}"

        grouped.setdefault(key, []).append(trace)

    return grouped


def convert_agent_trace_to_span_data(
    trace: Dict[str, Any],
    trace_id: str,
    project_name: str,
) -> Dict[str, Any]:
    """
    Legacy function: Convert old-format Agent Trace record to span data.
    """
    # Check if this is new format
    if "event" in trace:
        return _build_span_for_tool(trace, trace_id, project_name)
    
    # Old format handling
    metadata = trace.get("metadata") or {}
    
    start_time = _parse_timestamp(trace.get("timestamp"))
    end_time = _calculate_end_time(start_time, metadata.get("duration_ms"))
    
    # Determine type from old format
    command = metadata.get("command")
    files = trace.get("files", [])
    file_path = files[0].get("path") if files else None
    
    if command:
        span_name = f"Shell: {_shorten_command(command)}"
        span_type = "tool"
        span_input = {"command": command}
        span_output: Dict[str, Any] = {}
        if metadata.get("duration_ms"):
            span_output["duration_ms"] = round(metadata["duration_ms"], 2)
    elif file_path and file_path not in (".shell-history", ".sessions"):
        span_name = f"Edit: {_shorten_path(file_path)}"
        span_type = "general"
        span_input = {"file_path": file_path}
        span_output = {}
    else:
        span_name = "Agent Operation"
        span_type = "general"
        span_input = {}
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
        "metadata": {"record_id": trace.get("id")},
        "tags": ["agent-trace"],
    }
