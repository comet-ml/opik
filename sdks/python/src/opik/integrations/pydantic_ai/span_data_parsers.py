import dataclasses
import json
import logging
from collections.abc import Mapping
from typing import Any, Dict, List, Optional

from opentelemetry.sdk.trace import ReadableSpan
from opentelemetry.trace import StatusCode

from opik import llm_usage
from opik.llm_usage import (
    anthropic_usage,
    bedrock_usage,
    google_usage,
    openai_chat_completions_usage,
    unknown_usage,
)
from opik.types import ErrorInfoDict, SpanType

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class ParsedSpanStartData:
    name: str
    type: SpanType


@dataclasses.dataclass
class ParsedSpanEndData:
    name: Optional[str] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    usage: Optional[llm_usage.OpikUsage] = None
    model: Optional[str] = None
    provider: Optional[str] = None
    metadata: Optional[Dict[str, Any]] = None
    prompts: Optional[List[Dict[str, Any]]] = None
    thread_id: Optional[str] = None
    error_info: Optional[ErrorInfoDict] = None


def parse_span_start_data(
    name: Optional[str], attributes: Mapping[str, Any]
) -> Optional[ParsedSpanStartData]:
    span_name = name or "pydantic_ai"
    if span_name == "running tools":
        return None

    is_agent_run = (
        attributes.get("gen_ai.operation.name") == "invoke_agent"
        or span_name == "agent run"
    )
    if is_agent_run:
        agent_name = (
            attributes.get("agent_name") or attributes.get("gen_ai.agent.name") or ""
        )
        display_name = f"run {agent_name}" if agent_name else span_name
        return ParsedSpanStartData(name=display_name, type="general")

    if span_name.startswith("chat "):
        span_type: SpanType = "llm"
    elif "tool" in span_name:
        span_type = "tool"
    else:
        span_type = "general"

    return ParsedSpanStartData(name=span_name, type=span_type)


def parse_span_end_data(span: ReadableSpan, span_type: SpanType) -> ParsedSpanEndData:
    attributes = dict(span.attributes or {})
    if attributes.get("pydantic_ai.all_messages"):
        result = _parse_agent_run_data(attributes)
    elif span_type == "llm":
        result = _parse_llm_data(attributes)
    elif span_type == "tool":
        result = _parse_tool_data(attributes)
    else:
        result = _parse_general_data(attributes)

    result.error_info = _extract_error_info(span)
    return result


def build_usage(
    attributes: Mapping[str, Any], provider: Optional[str]
) -> Optional[llm_usage.OpikUsage]:
    input_tokens = _integer_attribute(attributes, "gen_ai.usage.input_tokens")
    output_tokens = _integer_attribute(attributes, "gen_ai.usage.output_tokens")
    if input_tokens is None and output_tokens is None:
        return None

    input_tokens = input_tokens or 0
    output_tokens = output_tokens or 0
    cache_read_tokens = (
        _integer_attribute(attributes, "gen_ai.usage.cache_read.input_tokens") or 0
    )
    cache_creation_tokens = (
        _integer_attribute(attributes, "gen_ai.usage.cache_creation.input_tokens") or 0
    )
    fresh_input_tokens = max(
        0, input_tokens - cache_read_tokens - cache_creation_tokens
    )

    normalized_provider = (provider or "").lower()
    if "bedrock" in normalized_provider:
        provider_usage = bedrock_usage.BedrockUsage(
            inputTokens=fresh_input_tokens,
            outputTokens=output_tokens,
            cacheReadInputTokens=cache_read_tokens,
            cacheWriteInputTokens=cache_creation_tokens,
        )
    elif "anthropic" in normalized_provider:
        provider_usage = anthropic_usage.AnthropicUsage(
            input_tokens=fresh_input_tokens,
            output_tokens=output_tokens,
            cache_read_input_tokens=cache_read_tokens,
            cache_creation_input_tokens=cache_creation_tokens,
        )
    elif "openai" in normalized_provider:
        prompt_token_details = (
            openai_chat_completions_usage.PromptTokensDetails(
                cached_tokens=cache_read_tokens
            )
            if cache_read_tokens
            else None
        )
        provider_usage = openai_chat_completions_usage.OpenAICompletionsUsage(
            prompt_tokens=input_tokens,
            completion_tokens=output_tokens,
            total_tokens=input_tokens + output_tokens,
            prompt_tokens_details=prompt_token_details,
            cache_creation_input_tokens=cache_creation_tokens,
        )
    elif "google" in normalized_provider or "gemini" in normalized_provider:
        provider_usage = google_usage.GoogleGeminiUsage(
            prompt_token_count=input_tokens,
            candidates_token_count=output_tokens,
            total_token_count=input_tokens + output_tokens,
            cached_content_token_count=cache_read_tokens,
            cache_creation_input_tokens=cache_creation_tokens,
        )
    else:
        cache_usage = {}
        if cache_read_tokens:
            cache_usage["cache_read_input_tokens"] = cache_read_tokens
        if cache_creation_tokens:
            cache_usage["cache_creation_input_tokens"] = cache_creation_tokens
        provider_usage = unknown_usage.UnknownUsage.from_original_usage_dict(
            cache_usage
        )

    return llm_usage.OpikUsage(
        prompt_tokens=input_tokens,
        completion_tokens=output_tokens,
        total_tokens=input_tokens + output_tokens,
        provider_usage=provider_usage,
    )


def _parse_llm_data(attributes: Dict[str, Any]) -> ParsedSpanEndData:
    model = attributes.get("gen_ai.request.model") or attributes.get(
        "gen_ai.response.model"
    )
    provider = attributes.get("gen_ai.provider.name") or attributes.get("gen_ai.system")
    input_messages = _try_json(attributes.get("gen_ai.input.messages"))
    output_messages = _try_json(attributes.get("gen_ai.output.messages"))

    input_data = {"messages": input_messages} if input_messages else None
    output: Dict[str, Any] = {}
    if output_messages:
        output["messages"] = output_messages
    if finish_reasons := attributes.get("gen_ai.response.finish_reasons"):
        output["finish_reasons"] = finish_reasons

    return ParsedSpanEndData(
        input=input_data,
        output=output or None,
        usage=build_usage(attributes, _optional_string(provider)),
        model=_optional_string(model),
        provider=_optional_string(provider),
    )


def _parse_tool_data(attributes: Dict[str, Any]) -> ParsedSpanEndData:
    input_data: Dict[str, Any] = {}
    output: Dict[str, Any] = {}

    tool_name = attributes.get("gen_ai.tool.name")
    tool_arguments = attributes.get("tool_arguments") or attributes.get(
        "gen_ai.tool.call.arguments"
    )
    if tool_name:
        input_data["tool_name"] = tool_name
    if tool_arguments:
        input_data["arguments"] = _try_json(tool_arguments)
    if tools := attributes.get("tools"):
        input_data["tools"] = tools

    tool_result = attributes.get("tool_response") or attributes.get(
        "gen_ai.tool.call.result"
    )
    if tool_result:
        output["result"] = _try_json(tool_result)

    return ParsedSpanEndData(input=input_data or None, output=output or None)


def _parse_general_data(attributes: Dict[str, Any]) -> ParsedSpanEndData:
    model = attributes.get("gen_ai.request.model") or attributes.get(
        "gen_ai.response.model"
    )
    provider = attributes.get("gen_ai.provider.name") or attributes.get("gen_ai.system")
    return ParsedSpanEndData(
        usage=build_usage(attributes, _optional_string(provider)),
        model=_optional_string(model),
        provider=_optional_string(provider),
    )


def _parse_agent_run_data(attributes: Dict[str, Any]) -> ParsedSpanEndData:
    result = _parse_general_data(attributes)
    _apply_reserved_metadata(result, attributes.get("metadata"))

    if "final_result" in attributes:
        result.output = {"response": _try_json(attributes["final_result"])}

    messages = _try_json(attributes.get("pydantic_ai.all_messages"))
    if not isinstance(messages, list):
        return result

    new_message_index = _integer_attribute(attributes, "pydantic_ai.new_message_index")
    current_messages = (
        messages[new_message_index:]
        if new_message_index is not None and 0 <= new_message_index < len(messages)
        else messages
    )

    for message in reversed(current_messages):
        if not isinstance(message, Mapping) or message.get("role") != "user":
            continue
        text = _text_from_parts(message.get("parts"))
        if text:
            result.input = {"prompt": text}
            break

    if result.output is None:
        for message in reversed(current_messages):
            if not isinstance(message, Mapping) or message.get("role") not in (
                "model-response",
                "assistant",
            ):
                continue
            parts = message.get("parts")
            final_result = _final_result_from_parts(parts)
            if final_result is not None:
                result.output = {"response": final_result}
                break
            text = _text_from_parts(parts)
            if text:
                result.output = {"response": text}
                break

    return result


def _apply_reserved_metadata(result: ParsedSpanEndData, raw_metadata: Any) -> None:
    metadata = _try_json(raw_metadata)
    if not isinstance(metadata, Mapping):
        return

    provider = metadata.get("opik.provider")
    if provider is not None:
        if isinstance(provider, str):
            result.provider = provider
        else:
            LOGGER.warning("Ignoring non-string Pydantic AI opik.provider metadata")

    span_name = metadata.get("opik.span_name")
    if span_name is not None:
        if isinstance(span_name, str):
            result.name = span_name
        else:
            LOGGER.warning("Ignoring non-string Pydantic AI opik.span_name metadata")

    opik_metadata = metadata.get("opik.metadata")
    if opik_metadata is not None:
        if isinstance(opik_metadata, Mapping):
            result.metadata = dict(opik_metadata)
        else:
            LOGGER.warning("Ignoring non-mapping Pydantic AI opik.metadata metadata")

    prompts = metadata.get("opik.prompts")
    if prompts is not None:
        if isinstance(prompts, list) and all(
            isinstance(prompt, Mapping) for prompt in prompts
        ):
            result.prompts = [dict(prompt) for prompt in prompts]
        else:
            LOGGER.warning("Ignoring invalid Pydantic AI opik.prompts metadata")

    thread_id = metadata.get("opik.thread_id")
    if thread_id is not None:
        if isinstance(thread_id, str):
            result.thread_id = thread_id
        else:
            LOGGER.warning("Ignoring non-string Pydantic AI opik.thread_id metadata")


def _extract_error_info(span: ReadableSpan) -> Optional[ErrorInfoDict]:
    exception_attributes: Optional[Dict[str, Any]] = None
    for event in reversed(span.events or []):
        if event.name == "exception":
            exception_attributes = dict(event.attributes or {})
            break

    if exception_attributes is None and span.status.status_code is not StatusCode.ERROR:
        return None

    exception_type = "Error"
    message: Optional[str] = None
    traceback = ""
    if exception_attributes is not None:
        exception_type = str(
            exception_attributes.get("exception.type") or exception_type
        )
        if exception_message := exception_attributes.get("exception.message"):
            message = str(exception_message)
        if exception_stacktrace := exception_attributes.get("exception.stacktrace"):
            traceback = str(exception_stacktrace)

    if message is None and span.status.description:
        message = span.status.description

    error_info: ErrorInfoDict = {
        "exception_type": exception_type,
        "traceback": traceback,
    }
    if message:
        error_info["message"] = message
    return error_info


def _integer_attribute(attributes: Mapping[str, Any], key: str) -> Optional[int]:
    value = attributes.get(key)
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        LOGGER.warning("Ignoring non-integer Pydantic AI attribute %s", key)
        return None


def _optional_string(value: Any) -> Optional[str]:
    return str(value) if value is not None else None


def _try_json(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except (json.JSONDecodeError, ValueError):
            return value
    return value


def _text_from_parts(parts: Any) -> str:
    if not isinstance(parts, list):
        return ""
    return " ".join(
        str(part.get("content", ""))
        for part in parts
        if isinstance(part, Mapping) and part.get("type") == "text"
    )


def _final_result_from_parts(parts: Any) -> Any:
    if not isinstance(parts, list):
        return None
    for part in parts:
        if (
            not isinstance(part, Mapping)
            or part.get("type") != "tool_call"
            or part.get("name") != "final_result"
        ):
            continue
        arguments = _try_json(part.get("arguments"))
        if isinstance(arguments, Mapping) and "response" in arguments:
            return arguments["response"]
        return arguments
    return None
