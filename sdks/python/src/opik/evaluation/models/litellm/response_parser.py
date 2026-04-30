"""Parse LiteLLM ``ModelResponse`` objects into the OpenAI-shape ``ConversationDict``."""

from __future__ import annotations

from typing import Any, Dict, List, Optional, TYPE_CHECKING, Tuple, Union, cast

from opik import exceptions

from .. import base_model
from . import util

if TYPE_CHECKING:
    from litellm.types.utils import (
        ChatCompletionMessageToolCall,
        Function,
        ModelResponse,
    )

# litellm wraps response_format-driven outputs in a synthetic tool call with
# this function name when the underlying provider (e.g. Anthropic) only exposes
# structured output via tool_use.
_RESPONSE_FORMAT_TOOL_NAME = "json_tool_call"


# Aliases for the heterogeneous shapes litellm hands us. Real responses arrive
# as pydantic models, but tests and certain provider integrations may surface
# the same data as plain dicts.
_RawToolCall = Union["ChatCompletionMessageToolCall", Dict[str, Any]]
_RawFunction = Union["Function", Dict[str, Any]]


def parse_assistant_message(
    response: "ModelResponse",
) -> base_model.ConversationDict:
    """Convert a LiteLLM completion response into an assistant ``ConversationDict``.

    Returns ``{"role": "assistant", "content": ...}`` for text responses and
    ``{"role": "assistant", "tool_calls": [...]}`` for tool-calling responses.
    Both fields can co-exist. Raises ``BaseLLMError`` when neither is present.
    """
    message = _normalise_message(response)

    content: Optional[str] = _extract_text_content(message)
    tool_calls: List[base_model.ToolCall] = _extract_tool_calls(message)

    if content is None:
        # litellm represents response_format as a synthetic tool_use on providers
        # that don't natively support it. Promote those arguments back into
        # ``content`` so existing JSON-parsing callers don't have to special-case it.
        content = _extract_response_format_arguments(message)

    if content is None and not tool_calls:
        raise exceptions.BaseLLMError(
            "Received None as the output from the LLM. Please verify your environment "
            "configuration and ensure that the API keys for the models in use "
            "(e.g., OPENAI_API_KEY) are set correctly."
        )

    assistant: base_model.ConversationDict = {"role": "assistant"}
    if content is not None:
        assistant["content"] = content
    if tool_calls:
        assistant["tool_calls"] = tool_calls
    return assistant


def _normalise_message(response: "ModelResponse") -> Dict[str, Any]:
    choices = getattr(response, "choices", None)
    if not isinstance(choices, list) or not choices:
        raise exceptions.BaseLLMError(
            "LLM response did not contain any choices to parse."
        )

    choice: Dict[str, Any] = util.normalise_choice(choices[0])
    return _as_mapping(choice.get("message"))


def _extract_text_content(message: Dict[str, Any]) -> Optional[str]:
    content = message.get("content")
    if content is None:
        return None
    if not isinstance(content, str):
        raise exceptions.BaseLLMError("LLM choice contains non-text content")
    return content


def _extract_response_format_arguments(message: Dict[str, Any]) -> Optional[str]:
    for raw_tool_call in _raw_tool_calls(message):
        name, arguments = _function_fields(raw_tool_call)
        if name == _RESPONSE_FORMAT_TOOL_NAME and arguments is not None:
            return arguments
    return None


def _extract_tool_calls(message: Dict[str, Any]) -> List[base_model.ToolCall]:
    parsed: List[base_model.ToolCall] = []
    for raw_tool_call in _raw_tool_calls(message):
        name, arguments = _function_fields(raw_tool_call)
        # The synthetic response_format shim is surfaced as content above.
        if name == _RESPONSE_FORMAT_TOOL_NAME:
            continue
        call_id = _get_str(raw_tool_call, "id")
        if call_id is None or name is None:
            continue
        # Zero-arg tool calls legitimately omit ``arguments``; normalize to
        # an empty JSON object so downstream consumers can always ``json.loads``.
        normalised_arguments = arguments if arguments is not None else "{}"
        parsed.append(
            cast(
                base_model.ToolCall,
                {
                    "id": call_id,
                    "type": "function",
                    "function": {
                        "name": name,
                        "arguments": normalised_arguments,
                    },
                },
            )
        )
    return parsed


def _raw_tool_calls(message: Dict[str, Any]) -> List[_RawToolCall]:
    raw = message.get("tool_calls")
    if not raw:
        return []
    return list(raw)


def _function_fields(tool_call: _RawToolCall) -> Tuple[Optional[str], Optional[str]]:
    function: Optional[_RawFunction] = _get(tool_call, "function")
    if function is None:
        return None, None
    return _get_str(function, "name"), _get_str(function, "arguments")


def _as_mapping(value: Any) -> Dict[str, Any]:
    """Best-effort coerce a pydantic model / dataclass / dict into a plain dict.

    LiteLLM responses may surface nested fields as either dicts (when
    ``model_dump`` succeeds upstream) or as the raw pydantic objects. Coercing
    here lets the rest of the parser use a single dict-shaped code path.
    """
    if isinstance(value, dict):
        return value
    if value is None:
        return {}
    if hasattr(value, "model_dump") and callable(value.model_dump):
        try:
            return value.model_dump()
        except TypeError:
            pass
    return {
        key: getattr(value, key)
        for key in (
            "content",
            "tool_calls",
            "name",
            "arguments",
            "id",
            "type",
            "function",
        )
        if hasattr(value, key)
    }


def _get(value: Any, key: str) -> Any:
    if isinstance(value, dict):
        return value.get(key)
    return getattr(value, key, None)


def _get_str(value: Any, key: str) -> Optional[str]:
    raw = _get(value, key)
    if raw is None:
        return None
    return str(raw)
