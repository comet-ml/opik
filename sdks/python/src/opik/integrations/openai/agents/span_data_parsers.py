import dataclasses
from typing import Any, Dict, Optional, Union
import logging

from agents import tracing

from opik.llm_usage import litellm_provider_mapping
from opik.types import SpanType, LLMProvider
import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage

LOGGER = logging.getLogger(__name__)


def span_type(openai_span_data: tracing.SpanData) -> SpanType:
    if openai_span_data.type in ["function", "guardrail"]:
        return "tool"
    elif openai_span_data.type in ["generation", "response"]:
        return "llm"

    return "general"


def _span_name(openai_span_data: tracing.SpanData) -> str:
    if (
        isinstance(openai_span_data, tracing.AgentSpanData)
        or isinstance(openai_span_data, tracing.FunctionSpanData)
        or isinstance(openai_span_data, tracing.GuardrailSpanData)
        or isinstance(openai_span_data, tracing.CustomSpanData)
    ):
        return openai_span_data.name
    elif isinstance(openai_span_data, tracing.GenerationSpanData):
        return "Generation"
    elif isinstance(openai_span_data, tracing.ResponseSpanData):
        return "Response"
    elif isinstance(openai_span_data, tracing.HandoffSpanData):
        return "Handoff"
    # TaskSpanData and TurnSpanData appeared in openai-agents 0.14.0
    elif openai_span_data.type == "task":
        return "Task"
    elif openai_span_data.type == "turn":
        return "Turn"
    else:
        return "Unknown"


@dataclasses.dataclass
class ParsedSpanData:
    name: str
    type: SpanType
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    metadata: Optional[Dict[str, Any]] = None
    usage: Optional[llm_usage.OpikUsage] = None
    model: Optional[str] = None
    provider: Optional[Union[LLMProvider, str]] = None


def parse_spandata(openai_span_data: tracing.SpanData) -> ParsedSpanData:
    """
    Parses the given SpanData instance and extracts input, output, and metadata.

    :param spandata: An instance of SpanData or its subclass.
    :return: A ParsedSpanData dataclass containing input, output, and metadata
    """
    content = openai_span_data.export()

    metadata = None
    input_data = None
    output_data = None
    name = _span_name(openai_span_data)
    type = span_type(openai_span_data)

    if openai_span_data.type == "function":
        input_data = openai_span_data.input
        output_data = openai_span_data.output
        del content["input"], content["output"]

    elif openai_span_data.type == "generation":
        return _parse_generation_span_content(openai_span_data)

    elif openai_span_data.type == "response":
        return _parse_response_span_content(openai_span_data)

    elif openai_span_data.type == "agent":
        output_data = openai_span_data.output_type

    elif openai_span_data.type == "handoff":
        pass  # No explicit input or output

    # TaskSpanData and TurnSpanData appeared in openai-agents 0.14.0
    elif openai_span_data.type == "task":
        pass  # Structural span wrapping the entire agent run

    elif openai_span_data.type == "turn":
        pass  # Structural span wrapping a single agent turn

    elif openai_span_data.type == "custom":
        input_data = openai_span_data.data.get("input")
        output_data = openai_span_data.data.get("output")
        del content["data"]

    elif openai_span_data.type == "guardrail":
        pass  # No explicit input or output

    if input_data is not None and not isinstance(input_data, dict):
        input_data = {"input": input_data}

    if output_data is not None and not isinstance(output_data, dict):
        output_data = {"output": output_data}

    metadata = content

    return ParsedSpanData(
        input=input_data,
        output=output_data,
        metadata=metadata,
        name=name,
        type=type,
    )


def _infer_provider(model: Optional[str]) -> Union[LLMProvider, str]:
    """Map a model string to a provider, defaulting to OpenAI for un-prefixed models."""
    return (
        litellm_provider_mapping.infer_provider_from_litellm_model_prefix(model)
        or LLMProvider.OPENAI
    )


def _wrap_as_dict(value: Any, key: str) -> Optional[Dict[str, Any]]:
    if value is None or isinstance(value, dict):
        return value
    return {key: value}


def _build_opik_usage(
    usage: Optional[Dict[str, Any]], error_message: str
) -> Optional[llm_usage.OpikUsage]:
    # openai-agents always emits usage in the OpenAI Responses shape, even for
    # LiteLLM-routed calls, so the OpenAI parser handles every provider here.
    if usage is None:
        return None
    return llm_usage.try_build_opik_usage_or_log_error(
        provider=LLMProvider.OPENAI,
        usage=usage,
        logger=LOGGER,
        error_message=error_message,
    )


_GENERATION_SPAN_HANDLED_KEYS = {"input", "output", "usage", "model"}


def _parse_generation_span_content(
    span_data: tracing.GenerationSpanData,
) -> ParsedSpanData:
    metadata = {
        key: value
        for key, value in span_data.export().items()
        if key not in _GENERATION_SPAN_HANDLED_KEYS
    }
    return ParsedSpanData(
        name="Generation",
        type="llm",
        input=_wrap_as_dict(span_data.input, "input"),
        output=_wrap_as_dict(span_data.output, "output"),
        usage=_build_opik_usage(
            span_data.usage,
            "Failed to log usage in openai agent generation span",
        ),
        metadata=metadata,
        model=span_data.model,
        provider=_infer_provider(span_data.model),
    )


def _parse_response_span_content(span_data: tracing.ResponseSpanData) -> ParsedSpanData:
    response = span_data.response
    if response is None:
        return ParsedSpanData(name="Response", type="llm")

    _, metadata = dict_utils.split_dict_by_keys(
        input_dict=response.model_dump(),
        keys=["input", "output"],
    )
    usage = response.usage.model_dump() if response.usage is not None else None

    return ParsedSpanData(
        name="Response",
        type="llm",
        input={"input": span_data.input},
        output={"output": response.output},
        usage=_build_opik_usage(usage, "Failed to log usage in openai agent run"),
        metadata=metadata,
        model=response.model,
        provider=_infer_provider(response.model),
    )
