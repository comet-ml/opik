"""In-process bridge from Pydantic AI's OpenTelemetry spans to Opik.

Pydantic AI exposes no native callback hooks — its only observability surface is
OpenTelemetry (``Agent.instrument_all(InstrumentationSettings(...))``). This
``SpanProcessor`` consumes those spans locally and creates the corresponding Opik
spans directly via the client, exactly like the ADK/LangChain integrations do
(``context_storage`` + ``SpanData`` + ``client.__internal_api__span__``). Nothing
is exported over OTLP and no logfire dependency is required.

When an ``@opik.track`` trace is already active the agent spans nest under it;
otherwise the root ``invoke_agent`` span creates its own trace.
"""

import json
import logging
from typing import Any, Dict, List, Optional, Tuple

import opik
from opentelemetry.sdk.trace import ReadableSpan, Span, SpanProcessor
from opentelemetry.trace import StatusCode

from opik import context_storage
from opik.api_objects.span.span_data import SpanData
from opik.api_objects.trace.trace_data import TraceData
from opik.decorator import arguments_helpers, span_creation_handler
from opik.types import SpanType

LOGGER = logging.getLogger(__name__)


def _try_json(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except (json.JSONDecodeError, ValueError):
            return value
    return value


def _text_from_parts(parts: List[Dict[str, Any]]) -> str:
    return " ".join(p.get("content", "") for p in parts if p.get("type") == "text")


def _final_result_from_parts(parts: List[Dict[str, Any]]) -> Any:
    for part in parts:
        if part.get("type") != "tool_call" or part.get("name") != "final_result":
            continue
        arguments = _try_json(part.get("arguments"))
        if isinstance(arguments, dict) and "response" in arguments:
            return arguments["response"]
        return arguments
    return None


def _token_usage(attrs: Dict[str, Any]) -> Optional[Dict[str, int]]:
    """OTEL gen_ai token attributes -> Opik usage keys.

    pydantic-ai normalises `input_tokens` to include the cached prefix (`cache_read`
    and `cache_creation` are a breakdown of it, not additive) for every provider that
    reports cache usage (Anthropic direct and Bedrock alike). When cache tokens are
    present we emit the native shape so Opik routes through its parser and applies cache-discounted
    pricing — that parser re-adds cache to `input_tokens`, so we emit the *fresh* portion to avoid
    double-counting. Providers that report no cache tokens get plain `prompt`/`completion`;
    Opik prices off the model name.
    """
    input_tokens = attrs.get("gen_ai.usage.input_tokens")
    output_tokens = attrs.get("gen_ai.usage.output_tokens")
    if input_tokens is None and output_tokens is None:
        return None
    input_tokens = int(input_tokens or 0)
    output_tokens = int(output_tokens or 0)

    cache_read = int(attrs.get("gen_ai.usage.cache_read.input_tokens") or 0)
    cache_creation = int(attrs.get("gen_ai.usage.cache_creation.input_tokens") or 0)
    if cache_read or cache_creation:
        return {
            "input_tokens": max(0, input_tokens - cache_read - cache_creation),
            "output_tokens": output_tokens,
            "cache_read_input_tokens": cache_read,
            "cache_creation_input_tokens": cache_creation,
        }

    return {
        "prompt_tokens": input_tokens,
        "completion_tokens": output_tokens,
        "total_tokens": input_tokens + output_tokens,
    }


class OpikSpanProcessor(SpanProcessor):
    """Maps Pydantic AI OTEL spans to Opik spans in-process.

    Pydantic AI v2 (instrumentation format 5) emits these span types, mapped to Opik types:
      "invoke_agent <name>"  -> general   (container for the whole agent run)
      "execute_tool <name>"  -> tool      (individual tool call with args/result)
      "chat <model>"         -> llm       (LLM API call with messages/tokens)
    """

    def __init__(
        self,
        *,
        project_name: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
    ) -> None:
        self._project_name = project_name
        self._metadata = metadata
        self._tags = tags
        self._span_map: Dict[int, Tuple[SpanData, Optional[TraceData]]] = {}

    @staticmethod
    def _classify_span(name: str) -> SpanType:
        if name.startswith("chat "):
            return "llm"
        if "tool" in name:
            return "tool"
        return "general"

    def on_start(self, span: Span, parent_context: Any = None) -> None:
        try:
            self._handle_start(span)
        except Exception:  # per OTel contract on_start must not raise
            LOGGER.debug("Failed to start Opik span for Pydantic AI", exc_info=True)

    def _handle_start(self, span: Span) -> None:
        name = span.name or "pydantic_ai"
        if name == "running tools":
            return

        attrs = dict(span.attributes or {})
        is_root = (
            attrs.get("gen_ai.operation.name") == "invoke_agent" or name == "agent run"
        )
        if is_root:
            agent_name = (
                attrs.get("agent_name") or attrs.get("gen_ai.agent.name") or ""
            )
            display_name = f"run {agent_name}" if agent_name else name
            span_type: SpanType = "general"
        else:
            display_name = name
            span_type = self._classify_span(name)

        start_args = arguments_helpers.StartSpanParameters(
            type=span_type,
            name=display_name,
            project_name=self._project_name,
            metadata=self._metadata,
            tags=self._tags,
        )
        result = span_creation_handler.create_span_respecting_context(
            start_span_arguments=start_args,
            distributed_trace_headers=None,
        )

        context_storage.add_span_data(result.span_data)
        client = opik.get_global_client()
        if client.config.log_start_trace_span:
            client.__internal_api__span__(**result.span_data.as_start_parameters)

        if result.trace_data is not None:
            context_storage.set_trace_data(result.trace_data)
            if client.config.log_start_trace_span:
                client.__internal_api__trace__(**result.trace_data.as_start_parameters)

        ctx = span.context
        if ctx is None:
            return
        self._span_map[ctx.span_id] = (result.span_data, result.trace_data)

    def _extract_llm_data(self, attrs: Dict[str, Any]) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        input_data: Dict[str, Any] = {}
        output: Dict[str, Any] = {}

        result["model"] = attrs.get("gen_ai.request.model") or attrs.get(
            "gen_ai.response.model"
        )
        result["provider"] = attrs.get("gen_ai.system") or attrs.get(
            "gen_ai.provider.name"
        )
        result["usage"] = _token_usage(attrs)

        input_msgs = attrs.get("gen_ai.input.messages")
        if input_msgs:
            input_data["messages"] = _try_json(input_msgs)

        output_msgs = attrs.get("gen_ai.output.messages")
        if output_msgs:
            output["messages"] = _try_json(output_msgs)

        finish_reasons = attrs.get("gen_ai.response.finish_reasons")
        if finish_reasons:
            output["finish_reasons"] = finish_reasons

        result["input"] = input_data or None
        result["output"] = output or None
        return result

    def _extract_tool_data(self, attrs: Dict[str, Any]) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        input_data: Dict[str, Any] = {}
        output: Dict[str, Any] = {}

        tool_name = attrs.get("gen_ai.tool.name")
        # v2: "tool_arguments" / "tool_response"; v3+: "gen_ai.tool.call.*"
        tool_args = attrs.get("tool_arguments") or attrs.get(
            "gen_ai.tool.call.arguments"
        )

        if tool_name:
            input_data["tool_name"] = tool_name
        if tool_args:
            input_data["arguments"] = _try_json(tool_args)
        tools_list = attrs.get("tools")
        if tools_list:
            input_data["tools"] = tools_list

        tool_result = attrs.get("tool_response") or attrs.get("gen_ai.tool.call.result")
        if tool_result:
            output["result"] = _try_json(tool_result)

        result["input"] = input_data or None
        result["output"] = output or None
        return result

    def _extract_general_data(self, attrs: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "usage": _token_usage(attrs),
            "model": attrs.get("gen_ai.request.model")
            or attrs.get("gen_ai.response.model"),
            "provider": attrs.get("gen_ai.system") or attrs.get("gen_ai.provider.name"),
            "input": None,
            "output": None,
        }

    def _extract_agent_run_data(self, attrs: Dict[str, Any]) -> Dict[str, Any]:
        result = self._extract_general_data(attrs)

        metadata_raw = attrs.get("metadata")
        if metadata_raw:
            metadata = _try_json(metadata_raw)
            if isinstance(metadata, dict):
                if metadata.get("opik.provider"):
                    result["provider"] = metadata["opik.provider"]
                if metadata.get("opik.span_name"):
                    result["span_name"] = metadata["opik.span_name"]
                if metadata.get("opik.metadata"):
                    result["opik_metadata"] = metadata["opik.metadata"]
                if metadata.get("opik.prompts"):
                    result["opik_prompts"] = metadata["opik.prompts"]
                if metadata.get("opik.thread_id"):
                    result["thread_id"] = metadata["opik.thread_id"]

        if "final_result" in attrs:
            result["output"] = {"response": _try_json(attrs["final_result"])}

        all_messages_raw = attrs.get("pydantic_ai.all_messages")
        if not all_messages_raw:
            return result

        messages = _try_json(all_messages_raw)
        if not isinstance(messages, list):
            return result

        for msg in messages:
            if msg.get("role") == "user":
                text = _text_from_parts(msg.get("parts", []))
                if text:
                    result["input"] = {"prompt": text}
                break

        for msg in reversed(messages):
            if msg.get("role") in ("model-response", "assistant"):
                parts = msg.get("parts", [])
                if result["output"] is None:
                    final_result = _final_result_from_parts(parts)
                    if final_result is not None:
                        result["output"] = {"response": final_result}
                if result["output"] is None:
                    text = _text_from_parts(parts)
                    if text:
                        result["output"] = {"response": text}
                break

        return result

    @staticmethod
    def _extract_error_info(span: ReadableSpan) -> Optional[Dict[str, str]]:
        exception_attrs: Optional[Dict[str, Any]] = None
        for event in reversed(span.events or []):
            if event.name == "exception":
                exception_attrs = dict(event.attributes or {})
                break

        if exception_attrs is None and span.status.status_code is not StatusCode.ERROR:
            return None

        exception_type = "Error"
        message: Optional[str] = None
        traceback = ""

        if exception_attrs is not None:
            exception_type = str(exception_attrs.get("exception.type") or exception_type)
            if exception_message := exception_attrs.get("exception.message"):
                message = str(exception_message)
            if exception_stacktrace := exception_attrs.get("exception.stacktrace"):
                traceback = str(exception_stacktrace)

        if message is None and span.status.description:
            message = span.status.description

        error_info = {"exception_type": exception_type, "traceback": traceback}
        if message:
            error_info["message"] = message
        return error_info

    def on_end(self, span: ReadableSpan) -> None:
        try:
            self._handle_end(span)
        except Exception:
            LOGGER.debug("Failed to end Opik span for Pydantic AI", exc_info=True)

    def _handle_end(self, span: ReadableSpan) -> None:
        ctx = span.context
        if ctx is None:
            return
        entry = self._span_map.pop(ctx.span_id, None)
        if entry is None:
            return
        span_data, trace_data = entry

        context_storage.pop_span_data(ensure_id=span_data.id)

        attrs = dict(span.attributes or {})
        span_type = span_data.type or "general"
        if attrs.get("pydantic_ai.all_messages"):
            data = self._extract_agent_run_data(attrs)
        elif span_type == "llm":
            data = self._extract_llm_data(attrs)
        elif span_type == "tool":
            data = self._extract_tool_data(attrs)
        else:
            data = self._extract_general_data(attrs)

        if data.get("span_name"):
            span_name = data["span_name"]
        elif span_data.type == "tool" and (
            tool_name := attrs.get("gen_ai.tool.name")
            or (data.get("input") or {}).get("tool_name")
        ):
            prefix = (span_data.name or "").removesuffix(f" {tool_name}")
            span_name = f"{prefix}: {tool_name}"
        else:
            span_name = span_data.name

        model = str(data["model"]) if data.get("model") else None
        provider = str(data["provider"]) if data.get("provider") else None
        metadata = data.get("opik_metadata")
        error_info = self._extract_error_info(span)

        span_data.init_end_time().update(
            name=span_name,
            input=data.get("input"),
            output=data.get("output"),
            usage=data.get("usage"),
            model=model,
            provider=provider,
            metadata=metadata,
            error_info=error_info,
            prompts=data.get("opik_prompts"),
        )

        client = opik.get_global_client()
        client.__internal_api__span__(**span_data.as_parameters)

        if trace_data is not None:
            context_storage.pop_trace_data(ensure_id=trace_data.id)
            trace_data.init_end_time().update(
                name=span_name,
                input=data.get("input"),
                output=data.get("output"),
                metadata=metadata,
                error_info=error_info,
                thread_id=data.get("thread_id"),
            )
            client.__internal_api__trace__(**trace_data.as_parameters)

    def shutdown(self) -> None:
        return None

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        return True
