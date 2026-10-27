"""Bounded evaluation feedback shared by optimizer reasoning prompts."""

import json
import re
from typing import Any

from .multimodal import redact_inline_media


def sanitize_feedback(value: Any) -> Any:
    """Summarize media in text feedback; native images are supplied separately as evidence."""
    if isinstance(value, str):
        return redact_inline_media(value)
    if isinstance(value, list):
        return [sanitize_feedback(item) for item in value]
    if isinstance(value, dict):
        if value.get("type") in {"image_url", "input_audio", "audio_url", "video_url"}:
            return {"type": value["type"], "media": "[media]"}
        return {key: sanitize_feedback(item) for key, item in value.items()}
    return value


# Dataset keys that hold the expected answer or bookkeeping rather than task input.
LABEL_FIELDS = frozenset(
    {
        "id",
        "answer",
        "label",
        "output",
        "expected_output",
        "ground_truth",
        "target",
        "metadata",
        "response",
        "supporting_facts",
    }
)
_LABEL_FIELD_PATTERN = re.compile(
    r"(^|[_-])(answer|gold|correct|target|label|output|ground|reference)([_-]|$)"
)


def is_label_field(key: str) -> bool:
    """Whether a dataset key looks like a label (answer, target, …) or bookkeeping."""
    return key in LABEL_FIELDS or bool(_LABEL_FIELD_PATTERN.search(key.lower()))


# Per-field and per-test-case caps keep a batch of 25 cases inside the reasoning model's context.
_FIELD_CHARS = 400
_TRACE_CHARS = 3000


def _truncate(value: Any, limit: int = _FIELD_CHARS) -> str:
    value = sanitize_feedback(value)
    text = value if isinstance(value, str) else json.dumps(value, default=str)
    return text if len(text) <= limit else text[:limit] + "…"


def _llm_message(output: Any) -> Any:
    """The assistant message of a chat-completion span, without the response envelope."""
    try:
        message = output["choices"][0]["message"]
    except (KeyError, IndexError, TypeError):
        return output
    tool_calls = [
        f"{call['function']['name']}({call['function']['arguments']})"
        for call in message.get("tool_calls") or []
    ]
    return {
        key: value
        for key, value in {
            "content": message.get("content"),
            "tool_calls": tool_calls,
        }.items()
        if value
    }


def _format_agent_trace(trace: Any) -> str:
    """Render the agent's steps under the evaluation task span, one line per span.

    Only the first LLM call's input is shown: it is the rendered prompt the agent got
    for this case. Later LLM inputs just replay the growing message history, whose
    new parts (tool results) are already on the tool spans.
    """
    lines: list[str] = []
    first_llm_input = True

    def walk(span: Any, depth: int) -> None:
        nonlocal first_llm_input
        indent = "  " * depth
        model = f" ({span.model})" if span.model else ""
        lines.append(f"{indent}- [{span.type}] {span.name}{model}")
        if span.input and (span.type != "llm" or first_llm_input):
            if span.type == "llm":
                first_llm_input = False
                messages = span.input.get("messages", span.input)
                lines.append(
                    f"{indent}  input: {_truncate(messages, 2 * _FIELD_CHARS)}"
                )
            else:
                lines.append(f"{indent}  input: {_truncate(span.input)}")
        if span.output:
            output = _llm_message(span.output) if span.type == "llm" else span.output
            lines.append(f"{indent}  output: {_truncate(output)}")
        if span.error_info:
            lines.append(f"{indent}  error: {_truncate(span.error_info)}")
        for child in span.spans:
            walk(child, depth + 1)

    # The first span is the task (the engine relies on this too); later ones are scoring.
    # The task span's own input/output are shown separately, so start at its children.
    if trace.spans:
        for child in trace.spans[0].spans:
            walk(child, 0)
    return _truncate("\n".join(lines), _TRACE_CHARS)


def format_evaluation_feedback(
    dataset_item: Any, output: Any, scores: Any, trace: Any = None
) -> str:
    """Describe a case with its actual fields, metric reasons and optional agent steps."""
    text = f"Dataset item: {_truncate(dataset_item)}\nOutput: {_truncate(output)}\nScores: {_truncate(scores)}"
    if trace is not None:
        steps = _format_agent_trace(trace)
        if steps:
            text += f"\nAgent trace:\n{steps}"
    return text


def input_fields(item: Any) -> dict[str, str]:
    """A dataset item's input fields, each capped, with label-like keys dropped."""
    if not isinstance(item, dict):
        return {"input": _truncate(item)}
    return {
        key: _truncate(value)
        for key, value in item.items()
        if not is_label_field(str(key))
    }
