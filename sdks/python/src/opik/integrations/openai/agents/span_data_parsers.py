from dataclasses import dataclass
from typing import Any, Dict

from agents import tracing

from opik.types import SpanType


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


@dataclass
class ParsedSpanData:
    name: str
    type: SpanType
    input: Dict[str, Any]
    output: Dict[str, Any]
    metadata: Dict[str, Any]


def parse_spandata(openai_span_data: tracing.SpanData) -> ParsedSpanData:
    """
    Parses the given SpanData instance and extracts input, output, and metadata.

    :param spandata: An instance of SpanData or its subclass.
    :return: A ParsedSpanData dataclass containing input, output, and metadata
    """
    metadata = openai_span_data.export()
    input_data = None
    output_data = None
    name = _span_name(openai_span_data)
    type = span_type(openai_span_data)

    if openai_span_data.type == "function":
        input_data = openai_span_data.input
        output_data = openai_span_data.output
        del metadata["input"], metadata["output"]

    elif openai_span_data.type == "generation":
        input_data = openai_span_data.input
        output_data = openai_span_data.output
        del metadata["input"], metadata["output"]

    elif openai_span_data.type == "response":
        input_data = openai_span_data.input
        output_data = openai_span_data.response
        del metadata["response_id"]  # Keep only relevant metadata

    elif openai_span_data.type == "agent":
        output_data = openai_span_data.output_type

    elif openai_span_data.type == "handoff":
        pass  # No explicit input or output

    elif openai_span_data.type == "custom":
        input_data = openai_span_data.data.get("input")
        output_data = openai_span_data.data.get("output")
        del metadata["data"]

    elif openai_span_data.type == "guardrail":
        pass  # No explicit input or output

    if input_data is not None and not isinstance(input_data, dict):
        input_data = {"input": input_data}

    if output_data is not None and not isinstance(output_data, dict):
        output_data = {"output": output_data}

    return ParsedSpanData(
        input=input_data,
        output=output_data,
        metadata=metadata,
        name=name,
        type=type,
    )
