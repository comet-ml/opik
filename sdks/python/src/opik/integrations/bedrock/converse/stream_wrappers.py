import logging
from typing import Any
from collections.abc import Generator, Callable
from opik.types import ErrorInfoDict

from opik.api_objects import trace, span
from opik.decorator import generator_wrappers, error_info_collector

from botocore import eventstream

LOGGER = logging.getLogger(__name__)


def wrap_stream(
    stream: eventstream.EventStream,
    capture_output: bool,
    span_to_end: span.SpanData,
    trace_to_end: trace.TraceData | None,
    generations_aggregator: Callable[[list[Any]], Any],
    response_metadata: dict[str, Any],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Generator[Any, None, None]:
    items: list[dict[str, Any]] = []

    try:
        error_info: ErrorInfoDict | None = None
        for item in stream:
            items.append(item)

            yield item
    except Exception as exception:
        LOGGER.debug(
            "Exception raised from botocore.eventstream.EventStream.",
            str(exception),
            exc_info=True,
        )
        error_info = error_info_collector.collect(exception)
        raise exception
    finally:
        if error_info is None:
            output = generations_aggregator(items)
            output["ResponseMetadata"] = response_metadata
        else:
            output = None

        finally_callback(
            output=output,
            error_info=error_info,
            generators_span_to_end=span_to_end,
            generators_trace_to_end=trace_to_end,
            capture_output=capture_output,
        )
