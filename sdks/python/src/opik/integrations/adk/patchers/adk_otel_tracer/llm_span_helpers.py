import enum
from opik.api_objects import span

SPAN_STATUS = "_OPIK_SPAN_STATUS"


class LLMSpanStatus(str, enum.Enum):
    STARTED = "started"
    READY_FOR_FINALIZATION = "ready_for_finalization"


def is_externally_created_llm_span_ready_for_immediate_finalization(
    span_data: span.SpanData,
) -> bool:
    return (
        span_data.type == "llm"
        and span_data.metadata is not None
        and span_data.metadata.get(SPAN_STATUS, None)
        == LLMSpanStatus.READY_FOR_FINALIZATION
    )


def is_externally_created_llm_span_that_just_started(
    span_data: span.SpanData,
) -> bool:
    return (
        span_data.type == "llm"
        and span_data.metadata is not None
        and span_data.metadata.get(SPAN_STATUS, None) == LLMSpanStatus.STARTED
    )
