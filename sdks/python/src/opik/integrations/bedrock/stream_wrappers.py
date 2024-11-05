import logging
from typing import Generator, Any, List, Optional, Callable, Dict
from opik.api_objects import trace, span
from opik.decorator import generator_wrappers

from botocore import eventstream

LOGGER = logging.getLogger(__name__)


def wrap_stream(
    stream: eventstream.EventStream,
    capture_output: bool,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Any],
    response_metadata: Dict[str, Any],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Generator[Any, None, None]:
    items: List[Dict[str, Any]] = []

    try:
        for item in stream:
            items.append(item)

            yield item

    finally:
        aggregated_output = generations_aggregator(items)
        aggregated_output["ResponseMetadata"] = response_metadata

        finally_callback(
            output=aggregated_output,
            generators_span_to_end=span_to_end,
            generators_trace_to_end=trace_to_end,
            capture_output=capture_output,
        )
