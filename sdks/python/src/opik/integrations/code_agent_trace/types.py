"""
Type definitions for Code Agent Trace integration.

This module defines strict TypedDict types for trace records from
AI coding tools like Cursor, Claude Code, and other AI coding agents.
"""

from typing import Any, Dict, List, Optional, TypedDict

from opik.types import FeedbackScoreDict, SpanType


# =============================================================================
# OpenAI Message Format Types
# =============================================================================


class TextContentPart(TypedDict):
    """Text content part in OpenAI message format."""

    type: str  # "text"
    text: str


class ImageUrlDetail(TypedDict, total=False):
    """Image URL detail in OpenAI message format."""

    url: str
    detail: str


class ImageContentPart(TypedDict):
    """Image content part in OpenAI message format."""

    type: str  # "image_url"
    image_url: ImageUrlDetail


class FileDetail(TypedDict, total=False):
    """File detail in OpenAI message format."""

    path: str
    type: str


class FileContentPart(TypedDict):
    """File content part in OpenAI message format."""

    type: str  # "file"
    file: FileDetail


class ToolCallFunction(TypedDict):
    """Tool call function in OpenAI message format."""

    name: str
    arguments: str  # JSON string


class ToolCall(TypedDict):
    """Tool call in OpenAI message format."""

    id: str
    type: str  # "function"
    function: ToolCallFunction


class UserMessage(TypedDict):
    """User message in OpenAI format."""

    role: str  # "user"
    content: List[Any]  # List of content parts


class AssistantMessage(TypedDict, total=False):
    """Assistant message in OpenAI format."""

    role: str  # "assistant"
    content: Optional[str]
    refusal: Optional[str]
    audio: Optional[Any]
    function_call: Optional[Any]
    tool_calls: Optional[List[ToolCall]]
    reasoning_content: Optional[str]


class ToolMessage(TypedDict):
    """Tool result message in OpenAI format."""

    role: str  # "tool"
    tool_call_id: str
    name: str
    content: str


class ChatCompletionChoice(TypedDict):
    """Choice in OpenAI chat completion response."""

    index: int
    message: AssistantMessage
    finish_reason: str


class ChatCompletionResponse(TypedDict):
    """OpenAI chat completion response format."""

    id: str
    object: str  # "chat.completion"
    created: int
    model: str
    choices: List[ChatCompletionChoice]


# =============================================================================
# Trace Record Types (from Cursor hooks)
# =============================================================================


class ShellExecutionData(TypedDict, total=False):
    """Data for shell execution tool."""

    tool_type: str  # "shell"
    command: str
    output: str
    cwd: str
    duration_ms: float


class FileEditData(TypedDict, total=False):
    """Data for file edit tool."""

    tool_type: str  # "file_edit"
    file_path: str
    edits: List[Dict[str, str]]  # {"old_string": ..., "new_string": ...}
    line_ranges: List[Dict[str, int]]  # {"start_line": ..., "end_line": ...}


class UserMessageData(TypedDict, total=False):
    """Data for user message event."""

    content: str
    attachments: List[Dict[str, str]]


class AssistantMessageData(TypedDict, total=False):
    """Data for assistant message event."""

    content: str


class TraceRecord(TypedDict, total=False):
    """
    A single trace record from Cursor hooks.

    This represents one event captured by the trace-hook.ts script.
    """

    version: str
    id: str
    timestamp: str
    event: str  # "user_message", "assistant_message", "tool_execution", etc.
    conversation_id: str
    generation_id: str
    model: str
    user_email: str
    data: Dict[str, Any]


# =============================================================================
# Opik Data Types
# =============================================================================


class SpanData(TypedDict, total=False):
    """Data for creating an Opik span."""

    id: str
    trace_id: str
    project_name: str
    name: str
    type: SpanType
    start_time: Any  # datetime.datetime
    end_time: Any  # datetime.datetime
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Dict[str, Any]
    tags: List[str]


class TraceData(TypedDict, total=False):
    """Data for creating an Opik trace."""

    id: str
    project_name: str
    name: str
    start_time: Any  # datetime.datetime
    end_time: Any  # datetime.datetime
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Dict[str, Any]
    tags: List[str]
    thread_id: Optional[str]
    error_info: Optional[Any]
    feedback_scores: List[FeedbackScoreDict]


class ConversionResult(TypedDict):
    """Result of converting records to trace and spans."""

    trace: TraceData
    spans: List[SpanData]


class LinesChanged(TypedDict):
    """Count of lines added and deleted."""

    lines_added: int
    lines_deleted: int
