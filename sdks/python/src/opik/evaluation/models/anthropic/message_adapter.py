"""Adapt OpenAI-style messages to Anthropic API conventions."""

from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Optional, Set, Tuple, Type

import pydantic

LOGGER = logging.getLogger(__name__)

# Parameters accepted by anthropic.messages.create()
_SUPPORTED_PARAMS: frozenset[str] = frozenset(
    {
        "model",
        "messages",
        "max_tokens",
        "system",
        "temperature",
        "top_p",
        "top_k",
        "stop_sequences",
        "tools",
        "tool_choice",
        "metadata",
    }
)


def _parse_tool_call_arguments(arguments: Any) -> Any:
    """Decode an OpenAI tool call's `arguments` field into a dict.

    OpenAI emits arguments as a JSON-encoded string; Anthropic's
    `tool_use` block expects an already-parsed object in the `input`
    field. We fall back to the empty dict on malformed JSON rather
    than raising — the Anthropic SDK will surface its own validation
    error if the schema doesn't match, which is more actionable than
    "your tool arguments aren't JSON".
    """
    if isinstance(arguments, (dict, list)):
        return arguments
    if not isinstance(arguments, str):
        return {}
    try:
        return json.loads(arguments)
    except (TypeError, ValueError):
        return {}


def normalize_messages(
    messages: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    """Translate OpenAI-shape conversation history to Anthropic shape.

    Two transformations are required for any history that includes
    tool round-trips (i.e. the agentic loop's follow-up turns):

    1. `{"role": "tool", "tool_call_id": X, "content": ...}` becomes a
       `{"type": "tool_result", "tool_use_id": X, "content": ...}`
       block inside a user message. Anthropic rejects the `tool` role
       outright ("Allowed roles are 'user' or 'assistant'").

    2. `{"role": "assistant", "content": ..., "tool_calls": [...]}`
       becomes an assistant message whose `content` is a list of
       blocks — optional `text` block first, then one `tool_use` block
       per call (with the JSON-decoded `input`).

    Consecutive tool messages are coalesced into a single user message
    with multiple `tool_result` blocks — Anthropic requires this when
    the prior assistant turn emitted multiple `tool_use` blocks. The
    rest of the message shapes (system, plain user, assistant text)
    pass through unchanged so the diff against pre-loop callers stays
    small.
    """
    result: List[Dict[str, Any]] = []
    pending_tool_results: List[Dict[str, Any]] = []

    def flush_tool_results() -> None:
        if pending_tool_results:
            result.append({"role": "user", "content": list(pending_tool_results)})
            pending_tool_results.clear()

    for msg in messages:
        role = msg.get("role")
        if role == "tool":
            pending_tool_results.append(
                {
                    "type": "tool_result",
                    "tool_use_id": msg.get("tool_call_id"),
                    "content": msg.get("content", ""),
                }
            )
            continue

        flush_tool_results()

        if role == "assistant":
            tool_calls = msg.get("tool_calls") or []
            text_content = msg.get("content")
            if not tool_calls:
                # Pure text assistant message — keep the simple
                # string-content shape Anthropic accepts.
                result.append({"role": "assistant", "content": text_content})
                continue
            blocks: List[Dict[str, Any]] = []
            if isinstance(text_content, str) and text_content:
                blocks.append({"type": "text", "text": text_content})
            for call in tool_calls:
                function = call.get("function") or {}
                blocks.append(
                    {
                        "type": "tool_use",
                        "id": call.get("id"),
                        "name": function.get("name"),
                        "input": _parse_tool_call_arguments(
                            function.get("arguments", "{}")
                        ),
                    }
                )
            result.append({"role": "assistant", "content": blocks})
            continue

        # Pass-through for any other role (`user`, plus anything
        # `extract_system_messages` left in place — system is normally
        # split off before this runs, but it's not catastrophic if it
        # ends up here).
        result.append(msg)

    flush_tool_results()
    return result


def extract_system_messages(
    messages: List[Dict[str, Any]],
) -> Tuple[Optional[str], List[Dict[str, Any]]]:
    """Separate system messages from conversation messages.

    Anthropic requires system content as a top-level parameter rather than
    a message with role="system".
    """
    system_parts: List[str] = []
    non_system: List[Dict[str, Any]] = []
    for msg in messages:
        if msg.get("role") == "system":
            system_parts.append(msg.get("content", ""))
        else:
            non_system.append(msg)
    system_text = "\n\n".join(system_parts) if system_parts else None
    return system_text, non_system


def pydantic_to_output_config(model: Type[pydantic.BaseModel]) -> Dict[str, Any]:
    """Build an Anthropic ``output_config`` dict from a pydantic model.

    Uses the native ``json_schema`` output format (SDK >=0.85) which
    constrains the model to produce JSON matching the schema directly,
    without the tool_use indirection.
    """
    schema = model.model_json_schema()
    schema.pop("title", None)
    return {
        "format": {
            "type": "json_schema",
            "schema": schema,
        },
    }


def strip_anthropic_prefix(model_name: str) -> str:
    if model_name.startswith("anthropic/"):
        return model_name[len("anthropic/") :]
    return model_name


def filter_unsupported_params(
    params: Dict[str, Any],
    already_warned: Set[str],
) -> Dict[str, Any]:
    filtered: Dict[str, Any] = {}
    for key, value in params.items():
        if key in _SUPPORTED_PARAMS:
            filtered[key] = value
        elif key not in already_warned:
            LOGGER.debug(
                "Dropping unsupported Anthropic parameter '%s'.",
                key,
            )
            already_warned.add(key)
    return filtered


# OpenAI accepts `tool_choice` as a bare string ("auto" / "none" /
# "required") or as an object naming a specific function; Anthropic
# requires the object form in all cases and uses different keys. The
# agentic loop emits OpenAI-style values because that's the dominant
# convention, so we translate at the adapter seam rather than
# threading provider awareness up through the caller.
#
# Mapping rationale:
# - "auto" → {"type": "auto"} (let the model decide)
# - "none" → {"type": "none"} (forbid tool use)
# - "required" → {"type": "any"} (force *some* tool, no specific one)
# - {"type": "function", "function": {"name": X}} → {"type": "tool",
#   "name": X} (force a specific tool by name)
_OPENAI_TO_ANTHROPIC_TOOL_CHOICE_STR: Dict[str, Dict[str, str]] = {
    "auto": {"type": "auto"},
    "none": {"type": "none"},
    "required": {"type": "any"},
}


def _normalize_one_tool(tool: Any) -> Any:
    """Translate one OpenAI-style tool spec to Anthropic's shape.

    OpenAI:
        {"type": "function", "function": {
            "name": ..., "description": ..., "parameters": {...}
        }}
    Anthropic (`type: "custom"` discriminator required by the newer
    schema; the `function` value is rejected by the API):
        {"type": "custom", "name": ..., "description": ...,
         "input_schema": {...}}

    Already-Anthropic-shaped tools (anything that isn't OpenAI's
    `type=function` wrapper) pass through unchanged so callers who
    hand-build the native spec aren't disturbed.
    """
    if not isinstance(tool, dict):
        return tool
    if tool.get("type") != "function":
        return tool
    function = tool.get("function") or {}
    name = function.get("name")
    if not isinstance(name, str):
        # Malformed OpenAI spec — let the SDK surface the error
        # rather than silently rewriting it into something Anthropic
        # would also reject.
        return tool
    translated: Dict[str, Any] = {
        "type": "custom",
        "name": name,
    }
    if "description" in function:
        translated["description"] = function["description"]
    if "parameters" in function:
        translated["input_schema"] = function["parameters"]
    return translated


def extract_tool_names(tools: Any) -> List[str]:
    """Pull the `name` field out of each tool spec.

    Tolerates both OpenAI shape (`tool["function"]["name"]`) and the
    Anthropic-native shape (`tool["name"]`) so callers can use this
    before or after `normalize_tools` runs. Skips entries missing a
    name rather than raising — the SDK will surface those errors via
    its own validation.
    """
    if not isinstance(tools, list):
        return []
    names: List[str] = []
    for tool in tools:
        if not isinstance(tool, dict):
            continue
        if tool.get("type") == "function":
            function = tool.get("function") or {}
            name = function.get("name")
        else:
            name = tool.get("name")
        if isinstance(name, str):
            names.append(name)
    return names


def normalize_tools(tools: Any) -> Any:
    """Translate a list of OpenAI-style tool specs to Anthropic shape.

    Non-list values pass through untouched so callers passing `None`
    or other sentinel-ish values aren't surprised.
    """
    if not isinstance(tools, list):
        return tools
    return [_normalize_one_tool(t) for t in tools]


def normalize_tool_choice(value: Any) -> Any:
    """Translate OpenAI-style `tool_choice` values into Anthropic's
    object form. Pass-through for already-correct shapes and for
    unrecognized values (let the SDK surface the error rather than
    silently dropping it).
    """
    if isinstance(value, str):
        return _OPENAI_TO_ANTHROPIC_TOOL_CHOICE_STR.get(value, value)
    if isinstance(value, dict):
        # OpenAI's "force this specific function" shape:
        #   {"type": "function", "function": {"name": "read"}}
        # Anthropic equivalent:
        #   {"type": "tool", "name": "read"}
        if value.get("type") == "function":
            function = value.get("function") or {}
            name = function.get("name")
            if isinstance(name, str):
                return {"type": "tool", "name": name}
    return value
