"""Adapt OpenAI-style messages to Anthropic API conventions."""

from __future__ import annotations

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
