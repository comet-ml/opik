import functools
import logging
from typing import AsyncIterator, Callable, Iterator, List, Optional, TypeVar

import cerebras.cloud.sdk as cerebras

from opik.api_objects import span, trace
from opik.decorator import error_info_collector, generator_wrappers
from opik.types import ErrorInfoDict

LOGGER = logging.getLogger(__name__)

original_stream_iter_method = cerebras.Stream.__iter__
original_async_stream_aiter_method = cerebras.AsyncStream.__aiter__

StreamItem = TypeVar("StreamItem")
AggregatedResult = TypeVar("AggregatedResult")


def patch_sync_stream(
    stream: cerebras.Stream,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[StreamItem]], Optional[AggregatedResult]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> cerebras.Stream:
    """Patch a ``cerebras.Stream`` so iterating over it ends the Opik span.

    Used when the caller does ``stream = client.chat.completions.create(stream=True)``
    and iterates over ``stream``.
    """

    def Stream__iter__decorator(dunder_iter_func: Callable) -> Callable:
        @functools.wraps(dunder_iter_func)
        def wrapper(self: cerebras.Stream) -> Iterator[StreamItem]:
            try:
                accumulated_items: List[StreamItem] = []
                error_info: Optional[ErrorInfoDict] = None
                for item in dunder_iter_func(self):
                    accumulated_items.append(item)
                    yield item
            except Exception as exception:
                LOGGER.debug(
                    "Exception raised from cerebras.Stream.",
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                raise exception
            finally:
                if hasattr(self, "opik_tracked_instance"):
                    delattr(self, "opik_tracked_instance")
                    output = (
                        generations_aggregator(accumulated_items)
                        if error_info is None
                        else None
                    )
                    finally_callback(
                        output=output,
                        error_info=error_info,
                        capture_output=True,
                        generators_span_to_end=self.span_to_end,
                        generators_trace_to_end=self.trace_to_end,
                    )

        return wrapper

    cerebras.Stream.__iter__ = Stream__iter__decorator(original_stream_iter_method)

    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end

    return stream


def patch_async_stream(
    stream: cerebras.AsyncStream,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[StreamItem]], Optional[AggregatedResult]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> cerebras.AsyncStream:
    """Patch a ``cerebras.AsyncStream`` so iterating over it ends the Opik span."""

    def AsyncStream__aiter__decorator(dunder_aiter_func: Callable) -> Callable:
        @functools.wraps(dunder_aiter_func)
        async def wrapper(self: cerebras.AsyncStream) -> AsyncIterator[StreamItem]:
            try:
                accumulated_items: List[StreamItem] = []
                error_info: Optional[ErrorInfoDict] = None
                async for item in dunder_aiter_func(self):
                    accumulated_items.append(item)
                    yield item
            except Exception as exception:
                LOGGER.debug(
                    "Exception raised from cerebras.AsyncStream.",
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                raise exception
            finally:
                if hasattr(self, "opik_tracked_instance"):
                    delattr(self, "opik_tracked_instance")
                    output = (
                        generations_aggregator(accumulated_items)
                        if error_info is None
                        else None
                    )
                    finally_callback(
                        output=output,
                        error_info=error_info,
                        capture_output=True,
                        generators_span_to_end=self.span_to_end,
                        generators_trace_to_end=self.trace_to_end,
                    )

        return wrapper

    cerebras.AsyncStream.__aiter__ = AsyncStream__aiter__decorator(
        original_async_stream_aiter_method
    )

    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end

    return stream
