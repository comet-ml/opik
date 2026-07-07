import functools
import logging
from typing import Any, AsyncIterator, Callable, Iterator, List, Optional

from mistralai.utils import eventstreaming

from opik.api_objects import span, trace
from opik.decorator import error_info_collector, generator_wrappers
from opik.types import ErrorInfoDict

LOGGER = logging.getLogger(__name__)

# Captured once, before any patching. Mistral's EventStream.__iter__ returns
# ``self`` and iteration is driven by __next__ (which pulls from an internal
# generator), so unlike the openai/anthropic streams we cannot wrap __iter__ by
# re-iterating it — we drive the original __next__ instead.
original_event_stream_next_method = eventstreaming.EventStream.__next__
original_event_stream_async_anext_method = eventstreaming.EventStreamAsync.__anext__


def patch_sync_event_stream(
    stream: eventstreaming.EventStream,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Optional[Any]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> eventstreaming.EventStream:
    """Patch ``client.chat.stream(...)``.

    Covers both ``for event in stream:`` and ``with stream as s: for event in s:``
    since EventStream is its own iterator and context manager.
    """

    def EventStream__iter__decorator(dunder_next_func: Callable) -> Callable:
        @functools.wraps(eventstreaming.EventStream.__iter__)
        def wrapper(self: eventstreaming.EventStream) -> Iterator[Any]:
            accumulated_items: List[Any] = []
            error_info: Optional[ErrorInfoDict] = None
            try:
                while True:
                    try:
                        item = dunder_next_func(self)
                    except StopIteration:
                        break
                    accumulated_items.append(item)
                    yield item
            except Exception as exception:
                LOGGER.debug(
                    "Exception raised from mistralai EventStream.",
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                raise exception
            finally:
                if not hasattr(self, "opik_tracked_instance"):
                    return

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

    eventstreaming.EventStream.__iter__ = EventStream__iter__decorator(
        original_event_stream_next_method
    )

    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end

    return stream


def patch_async_event_stream(
    stream: eventstreaming.EventStreamAsync,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Optional[Any]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> eventstreaming.EventStreamAsync:
    """Patch ``client.chat.stream_async(...)``."""

    def EventStreamAsync__aiter__decorator(dunder_anext_func: Callable) -> Callable:
        @functools.wraps(eventstreaming.EventStreamAsync.__aiter__)
        async def wrapper(
            self: eventstreaming.EventStreamAsync,
        ) -> AsyncIterator[Any]:
            accumulated_items: List[Any] = []
            error_info: Optional[ErrorInfoDict] = None
            try:
                while True:
                    try:
                        item = await dunder_anext_func(self)
                    except StopAsyncIteration:
                        break
                    accumulated_items.append(item)
                    yield item
            except Exception as exception:
                LOGGER.debug(
                    "Exception raised from mistralai EventStreamAsync.",
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                raise exception
            finally:
                if not hasattr(self, "opik_tracked_instance"):
                    return

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

    eventstreaming.EventStreamAsync.__aiter__ = EventStreamAsync__aiter__decorator(
        original_event_stream_async_anext_method
    )

    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end

    return stream
