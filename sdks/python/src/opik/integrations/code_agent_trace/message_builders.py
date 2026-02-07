"""
OpenAI message format builders for Code Agent Trace integration.

This module builds messages in OpenAI chat format from trace records.
"""

import json
import re
from typing import Any, Dict, List, Optional

from .helpers import shorten_path
from .types import TraceRecord

# Non-breaking space character for preserving indentation in markdown
NBSP = "\u00a0"


def _preserve_leading_whitespace(line: str) -> str:
    """
    Replace leading spaces/tabs with non-breaking spaces to preserve indentation.

    Markdown renderers often collapse regular spaces, but non-breaking spaces
    are preserved, ensuring code indentation is visible.
    """
    # Find leading whitespace
    match = re.match(r"^(\s*)", line)
    if not match:
        return line

    leading = match.group(1)
    rest = line[len(leading) :]

    # Replace spaces with non-breaking spaces, tabs with 4 non-breaking spaces
    preserved = leading.replace("\t", NBSP * 4).replace(" ", NBSP)
    return preserved + rest


def build_user_message(record: TraceRecord) -> Dict[str, Any]:
    """
    Build OpenAI-format user message from a trace record.

    Args:
        record: Trace record with event="user_message".

    Returns:
        User message in OpenAI format with content as array of parts.
    """
    data = record.get("data", {})
    content_parts: List[Dict[str, Any]] = []

    # Add text content
    text_content = data.get("content", "")
    if text_content:
        content_parts.append(
            {
                "type": "text",
                "text": text_content,
            }
        )

    # Add attachments as images/files
    attachments = data.get("attachments", [])
    for attachment in attachments:
        file_path = attachment.get("filePath", "")
        if file_path:
            # Check if it's an image
            if any(
                file_path.lower().endswith(ext)
                for ext in [".png", ".jpg", ".jpeg", ".gif", ".webp"]
            ):
                content_parts.append(
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": file_path,
                            "detail": "high",
                        },
                    }
                )
            else:
                # Non-image file attachment
                content_parts.append(
                    {
                        "type": "file",
                        "file": {
                            "path": file_path,
                            "type": attachment.get("type", "file"),
                        },
                    }
                )

    return {
        "role": "user",
        "content": content_parts if content_parts else [{"type": "text", "text": ""}],
    }


def build_assistant_message(
    response_records: List[TraceRecord],
    thought_records: List[TraceRecord],
    tool_records: List[TraceRecord],
) -> Dict[str, Any]:
    """
    Build OpenAI-format assistant message with tool calls.

    Args:
        response_records: Records with event="assistant_message".
        thought_records: Records with event="assistant_thought".
        tool_records: Records with event="tool_execution".

    Returns:
        Assistant message in OpenAI format.
    """
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

    # Add reasoning/thinking if present
    if thought_records:
        thoughts = [r.get("data", {}).get("content", "") for r in thought_records]
        reasoning = "\n\n".join(filter(None, thoughts))
        if reasoning:
            if message["content"] is None:
                message["content"] = f"[Thinking]\n{reasoning}"
            else:
                message["reasoning_content"] = reasoning

    # Add tool calls if present
    if tool_records:
        tool_calls: List[Dict[str, Any]] = []
        for record in tool_records:
            record_id = record.get("id", "")
            data = record.get("data", {})
            tool_type = data.get("tool_type", "unknown")

            if tool_type == "shell":
                arguments: Dict[str, Any] = {
                    "command": data.get("command", ""),
                }
                if data.get("cwd"):
                    arguments["cwd"] = data["cwd"]

                tool_calls.append(
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
                short_path = shorten_path(file_path, max_length=80)
                arguments = {
                    "file_path": short_path,
                }
                edits = data.get("edits", [])
                if edits:
                    arguments["edit_count"] = len(edits)

                tool_calls.append(
                    {
                        "id": f"call_{record_id}",
                        "type": "function",
                        "function": {
                            "name": "file_edit",
                            "arguments": json.dumps(arguments),
                        },
                    }
                )
        if tool_calls:
            message["tool_calls"] = tool_calls

    return message


def build_tool_message(record: TraceRecord) -> Dict[str, Any]:
    """
    Build OpenAI-format tool result message.

    Args:
        record: Trace record with event="tool_execution".

    Returns:
        Tool message in OpenAI format.
    """
    record_id = record.get("id", "unknown")
    data = record.get("data", {})
    tool_type = data.get("tool_type", "unknown")

    if tool_type == "shell":
        tool_name = "shell"
        result: Dict[str, Any] = {
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

    else:
        tool_name = tool_type
        content = json.dumps(data)

    return {
        "role": "tool",
        "tool_call_id": f"call_{record_id}",
        "name": tool_name,
        "content": content,
    }


def build_code_changes_summary(file_edit_records: List[TraceRecord]) -> Optional[str]:
    """
    Build a markdown-formatted summary of all code changes.

    Args:
        file_edit_records: List of tool execution records.

    Returns:
        Markdown summary string, or None if no file edits were made.
    """
    if not file_edit_records:
        return None

    # Group edits by file
    files_changed: Dict[str, List[Dict[str, Any]]] = {}
    for record in file_edit_records:
        data = record.get("data", {})
        if data.get("tool_type") != "file_edit":
            continue
        file_path = data.get("file_path", "unknown")
        if file_path not in files_changed:
            files_changed[file_path] = []
        files_changed[file_path].append(data)

    if not files_changed:
        return None

    # Build markdown summary
    lines = ["## Code Changes Summary", ""]

    for file_path, edits_list in files_changed.items():
        # Get short filename for display
        short_name = file_path.split("/")[-1] if "/" in file_path else file_path

        # Collect all line ranges
        all_ranges = []
        for edit_data in edits_list:
            ranges = edit_data.get("line_ranges", [])
            for r in ranges:
                start = r.get("start_line")
                end = r.get("end_line")
                if start and end:
                    if start == end:
                        all_ranges.append(f"L{start}")
                    else:
                        all_ranges.append(f"L{start}-{end}")

        # Format file entry
        lines.append(f"### `{short_name}`")
        lines.append(f"**Path:** `{file_path}`")
        if all_ranges:
            lines.append(f"**Lines modified:** {', '.join(all_ranges)}")

        # Show edit details in unified diff format
        for edit_data in edits_list:
            edits = edit_data.get("edits", [])
            line_ranges = edit_data.get("line_ranges", [])

            for idx, edit in enumerate(edits):
                old_str = edit.get("old_string", "")
                new_str = edit.get("new_string", "")

                if not old_str and not new_str:
                    continue

                # Get start line for this edit
                start_line = 1
                if idx < len(line_ranges):
                    start_line = line_ranges[idx].get("start_line", 1) or 1

                # Count lines for unified diff header
                old_lines = old_str.split("\n") if old_str else []
                new_lines = new_str.split("\n") if new_str else []
                old_count = len(old_lines)
                new_count = len(new_lines)

                # Build unified diff header: @@ -start,count +start,count @@
                lines.append("")
                lines.append("```diff")
                lines.append(
                    f"@@ -{start_line},{old_count} +{start_line},{new_count} @@"
                )

                # Show removed lines - use non-breaking spaces to preserve indentation
                for line in old_lines:
                    preserved_line = _preserve_leading_whitespace(line)
                    lines.append(f"-{preserved_line}")

                # Show added lines - use non-breaking spaces to preserve indentation
                for line in new_lines:
                    preserved_line = _preserve_leading_whitespace(line)
                    lines.append(f"+{preserved_line}")

                lines.append("```")

        lines.append("")

    return "\n".join(lines)


def build_chat_completion_response(
    generation_id: str,
    model: str,
    created_timestamp: int,
    content: Optional[str],
    tool_calls: Optional[List[Dict[str, Any]]],
    finish_reason: Optional[str] = None,
) -> Dict[str, Any]:
    """
    Build OpenAI chat completion response format.

    Args:
        generation_id: Generation ID for the completion ID.
        model: Model name.
        created_timestamp: Unix timestamp when created.
        content: Assistant message content, or None.
        tool_calls: List of tool calls, or None.
        finish_reason: Optional finish reason override.

    Returns:
        Chat completion response in OpenAI format.
    """
    final_message: Dict[str, Any] = {
        "role": "assistant",
        "content": content,
        "refusal": None,
    }
    if tool_calls:
        final_message["tool_calls"] = tool_calls

    # Determine finish_reason: explicit override > content-based > tool_calls
    if finish_reason is not None:
        resolved_finish_reason = finish_reason
    elif content:
        resolved_finish_reason = "stop"
    else:
        resolved_finish_reason = "tool_calls"

    choice: Dict[str, Any] = {
        "index": 0,
        "message": final_message,
        "finish_reason": resolved_finish_reason,
    }

    return {
        "id": f"chatcmpl-{generation_id[:20]}" if generation_id else "chatcmpl-unknown",
        "object": "chat.completion",
        "created": created_timestamp,
        "model": model or "unknown",
        "choices": [choice],
    }
