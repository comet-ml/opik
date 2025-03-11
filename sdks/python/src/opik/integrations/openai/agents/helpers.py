from agents import tracing
from opik.types import SpanType
from typing import Any


def span_type(span: tracing.Span[Any]) -> SpanType:
    if span.span_data.type in ["function", "guardrail"]:
        return "tool"
    elif span.span_data.type in ["generation", "response"]:
        return "llm"

    return "general"


def span_name(span: tracing.Span[Any]) -> str:
    if (
        isinstance(span.span_data, tracing.AgentSpanData)
        or isinstance(span.span_data, tracing.FunctionSpanData)
        or isinstance(span.span_data, tracing.GuardrailSpanData)
        or isinstance(span.span_data, tracing.CustomSpanData)
    ):
        return span.span_data.name
    elif isinstance(span.span_data, tracing.GenerationSpanData):
        return "Generation"
    elif isinstance(span.span_data, tracing.ResponseSpanData):
        return "Response"
    elif isinstance(span.span_data, tracing.HandoffSpanData):
        return "Handoff"
    else:
        return "Unknown"
