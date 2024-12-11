from typing import (
    Generator,
    Protocol,
    Any,
    AsyncGenerator,
    Optional,
    Callable,
    List,
)
from opik.types import ErrorInfoDict

from opik.api_objects import span, trace
import logging
from opik import logging_messages
from . import error_info_collector

LOGGER = logging.getLogger(__name__)


class FinishGeneratorCallback(Protocol):
    def __call__(
        self,
        output: Any,
        error_info: Optional[ErrorInfoDict],
        capture_output: bool,
        generators_span_to_end: Optional[span.SpanData] = None,
        generators_trace_to_end: Optional[trace.TraceData] = None,
    ) -> None: ...


def wrap_sync_generator(
    generator: Generator,
    capture_output: bool,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Optional[Callable[[List[Any]], str]],
    finally_callback: FinishGeneratorCallback,
) -> Generator[Any, None, None]:
    items = []
    error_info: Optional[ErrorInfoDict] = None
    try:
        for item in generator:
            items.append(item)

            yield item
    except Exception as exception:
        LOGGER.debug(
            "Exception raised from tracked generator",
            str(exception),
            exc_info=True,
        )
        error_info = error_info_collector.collect(exception)
        raise exception
    finally:
        output = (
            _try_aggregate_items(items, generations_aggregator)
            if error_info is None
            else None
        )

        finally_callback(
            output=output,
            error_info=error_info,
            generators_span_to_end=span_to_end,
            generators_trace_to_end=trace_to_end,
            capture_output=capture_output,
        )


async def wrap_async_generator(
    generator: AsyncGenerator,
    capture_output: bool,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Optional[Callable[[List[Any]], str]],
    finally_callback: FinishGeneratorCallback,
) -> AsyncGenerator[Any, None]:
    items = []
    error_info: Optional[ErrorInfoDict] = None

    try:
        async for item in generator:
            items.append(item)

            yield item
    except Exception as exception:
        LOGGER.debug(
            "Exception raised from tracked async generator",
            str(exception),
            exc_info=True,
        )
        error_info = error_info_collector.collect(exception)
        raise exception
    finally:
        output = (
            _try_aggregate_items(items, generations_aggregator)
            if error_info is None
            else None
        )

        finally_callback(
            output=output,
            error_info=error_info,
            generators_span_to_end=span_to_end,
            generators_trace_to_end=trace_to_end,
            capture_output=capture_output,
        )


def _try_aggregate_items(
    items: List[Any], generations_aggregator: Optional[Callable[[List[Any]], str]]
) -> str:
    if generations_aggregator is not None:
        try:
            output = generations_aggregator(items)
        except Exception:
            LOGGER.error(
                logging_messages.FAILED_TO_AGGREGATE_GENERATORS_YIELDED_VALUES_WITH_PROVIDED_AGGREGATOR_IN_TRACKED_FUNCTION,
                items,
                generations_aggregator,
                exc_info=True,
            )
            output = str(items)
    else:
        output = str(items)  # TODO: decide how to convert to string

    return output
