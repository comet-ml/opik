import functools
import logging
from typing import (
    Any,
    AsyncIterator,
    Callable,
    Dict,
    Iterator,
    List,
    Optional,
)

from google.genai import types as genai_types

from opik.api_objects import span, trace
from opik.decorator import error_info_collector, generator_wrappers
from opik.types import ErrorInfoDict

LOGGER = logging.getLogger(__name__)


def wrap_sync_iterator(
    stream: Iterator[genai_types.GenerateContentResponse],
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[
        [List[genai_types.GenerateContentResponse]], genai_types.GenerateContentResponse
    ],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Iterator[genai_types.GenerateContentResponse]:
    items: List[Dict[str, Any]] = []

    try:
        error_info: Optional[ErrorInfoDict] = None
        for item in stream:
            items.append(item)

            yield item
    except Exception as exception:
        LOGGER.debug(
            "Exception raised in genai response stream.",
            str(exception),
            exc_info=True,
        )
        error_info = error_info_collector.collect(exception)
        raise exception
    finally:
        output = None if error_info is not None else generations_aggregator(items)

        finally_callback(
            output=output,
            error_info=error_info,
            generators_span_to_end=span_to_end,
            generators_trace_to_end=trace_to_end,
            capture_output=True,
        )


async def wrap_async_iterator(
    stream: AsyncIterator[genai_types.GenerateContentResponse],
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[
        [List[genai_types.GenerateContentResponse]], genai_types.GenerateContentResponse
    ],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> AsyncIterator[genai_types.GenerateContentResponse]:
    items: List[Dict[str, Any]] = []

    try:
        error_info: Optional[ErrorInfoDict] = None
        async for item in stream:
            items.append(item)

            yield item
    except Exception as exception:
        LOGGER.debug(
            "Exception raised in genai response stream.",
            str(exception),
            exc_info=True,
        )
        error_info = error_info_collector.collect(exception)
        raise exception
    finally:
        output = None if error_info is not None else generations_aggregator(items)

        finally_callback(
            output=output,
            error_info=error_info,
            generators_span_to_end=span_to_end,
            generators_trace_to_end=trace_to_end,
            capture_output=True,
        )


def generator_function_to_normal_function(
    generator_function: Callable[..., Iterator[genai_types.GenerateContentResponse]],
) -> Callable[..., Iterator[genai_types.GenerateContentResponse]]:
    @functools.wraps(generator_function)
    def generator_wrapper(
        *args: Any,
        **kwargs: Any,
    ) -> Iterator[genai_types.GenerateContentResponse]:
        return generator_function(*args, **kwargs)

    return generator_wrapper
