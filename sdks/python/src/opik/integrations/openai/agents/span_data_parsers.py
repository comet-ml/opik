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


def _parse_generation_span_content(
    span_data: tracing.GenerationSpanData,
) -> ParsedSpanData:
    # openai-agents 0.14+ routes `LitellmModel` through `generation_span`, so the model
    # string here carries the LiteLLM "<provider>/<model>" prefix. Usage is recorded in
    # OpenAI Responses-API shape (input_tokens/output_tokens) regardless of the underlying
    # provider, so the usage parser stays pinned to OPENAI; only the provider attribution
    # on the span needs to follow the prefix.
    model = span_data.model
    inferred_provider = (
        litellm_provider_mapping.infer_provider_from_litellm_model_prefix(model)
        or LLMProvider.OPENAI
    )

    if span_data.usage is not None:
        opik_usage = llm_usage.try_build_opik_usage_or_log_error(
            provider=LLMProvider.OPENAI,
            usage=span_data.usage,
            logger=LOGGER,
            error_message="Failed to log usage in openai agent generation span",
        )
    else:
        opik_usage = None

    input_data = span_data.input
    output_data = span_data.output
    if input_data is not None and not isinstance(input_data, dict):
        input_data = {"input": input_data}
    if output_data is not None and not isinstance(output_data, dict):
        output_data = {"output": output_data}

    metadata = span_data.export()
    for key in ("input", "output", "usage", "model"):
        metadata.pop(key, None)

    return ParsedSpanData(
        name="Generation",
        input=input_data,
        output=output_data,
        usage=opik_usage,
        type="llm",
        metadata=metadata,
        model=model,
        provider=inferred_provider,
    )


def _parse_response_span_content(span_data: tracing.ResponseSpanData) -> ParsedSpanData:
    response = span_data.response

    if response is None:
        return ParsedSpanData(
            name="Response",
            type="llm",
        )

    response_dict = span_data.response.model_dump()
    input = {"input": span_data.input}
    output = {"output": response.output}

    _, metadata = dict_utils.split_dict_by_keys(
        input_dict=response_dict,
        keys=["input", "output"],
    )

    # When `LitellmModel` routes requests through a non-OpenAI provider, `response.model`
    # carries a LiteLLM-style "<provider>/<model>" prefix (e.g. "gemini/gemini-3.1-flash").
    # The usage payload on `response.usage` is still OpenAI-shaped — the openai-agents SDK
    # adapts LiteLLM responses to OpenAI's `Response` type — so we keep OPENAI for usage
    # parsing but attribute the span to the real provider for backend cost lookup.
    inferred_provider = (
        litellm_provider_mapping.infer_provider_from_litellm_model_prefix(
            response.model
        )
        or LLMProvider.OPENAI
    )

    if response.usage is not None:
        opik_usage = llm_usage.try_build_opik_usage_or_log_error(
            provider=LLMProvider.OPENAI,
            usage=response.usage.model_dump(),
            logger=LOGGER,
            error_message="Failed to log usage in openai agent run",
        )
    else:
        opik_usage = None

    return ParsedSpanData(
        name="Response",
        input=input,
        output=output,
        usage=opik_usage,
        type="llm",
        metadata=metadata,
        model=response.model,
        provider=inferred_provider,
    )
