import dataclasses
from typing import Any, Dict, Optional
import logging

from agents import tracing

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
    provider: Optional[LLMProvider] = None


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
        input_data = openai_span_data.input
        output_data = openai_span_data.output
        del content["input"], content["output"]

    elif openai_span_data.type == "response":
        return _parse_response_span_content(openai_span_data)

    elif openai_span_data.type == "agent":
        output_data = openai_span_data.output_type

    elif openai_span_data.type == "handoff":
        pass  # No explicit input or output

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
        provider=LLMProvider.OPENAI,
    )
