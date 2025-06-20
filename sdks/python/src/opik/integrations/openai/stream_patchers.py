import logging
from typing import Iterator, AsyncIterator, List, Optional, Callable, TypeVar

from opik.types import ErrorInfoDict
from opik.api_objects import trace, span
from opik.decorator import generator_wrappers, error_info_collector
import functools
import openai
from openai._response import ResponseContextManager

LOGGER = logging.getLogger(__name__)

original_stream_iter_method = openai.Stream.__iter__
original_async_stream_aiter_method = openai.AsyncStream.__aiter__
if hasattr(ResponseContextManager, "__enter__"):
    original_sync_context_manager_enter_method = ResponseContextManager.__enter__
if hasattr(ResponseContextManager, "__aenter__"):
    original_async_context_manager_aenter_method = ResponseContextManager.__aenter__

StreamItem = TypeVar("StreamItem")
AggregatedResult = TypeVar("AggregatedResult")


def patch_sync_stream(
    stream: openai.Stream,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[StreamItem]], Optional[AggregatedResult]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> openai.Stream:
    """
    Used in the following cases
    ```
    stream = client.messages.create(stream=True)
    for event in stream:
        print(event)
    ```

    """
    if isinstance(stream, ResponseContextManager):

        def ContextManager__enter__decorator(dunder_enter_func: Callable) -> Callable:
            @functools.wraps(dunder_enter_func)
            def wrapper(
                self: ResponseContextManager,
            ) -> Iterator[StreamItem]:
                response = dunder_enter_func(self)
                return patch_sync_stream(
                    response,
                    self.span_to_end,
                    self.trace_to_end,
                    generations_aggregator,
                    finally_callback,
                )

            return wrapper

        ResponseContextManager.__enter__ = ContextManager__enter__decorator(
            original_sync_context_manager_enter_method
        )
        stream.opik_tracked_instance = True
        stream.span_to_end = span_to_end
        stream.trace_to_end = trace_to_end
        return stream

    def Stream__iter__decorator(dunder_iter_func: Callable) -> Callable:
        @functools.wraps(dunder_iter_func)
        def wrapper(
            self: openai.Stream,
        ) -> Iterator[StreamItem]:
            try:
                accumulated_items: List[StreamItem] = []
                error_info: Optional[ErrorInfoDict] = None
                # HACK: a bit ugly, but for openai audio speech, the stream object is not an iterator
                # but an object with `iter_bytes` method
                items_iterator = (
                    dunder_iter_func(self)
                    if not hasattr(self, "iter_bytes")
                    else self.iter_bytes()
                )
                for item in items_iterator:
                    accumulated_items.append(item)
                    yield item
            except Exception as exception:
                LOGGER.debug(
                    "Exception raised from openai.Stream.",
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

    openai.Stream.__iter__ = Stream__iter__decorator(original_stream_iter_method)

    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end

    return stream


def patch_async_stream(
    stream: openai.AsyncStream,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[StreamItem]], Optional[AggregatedResult]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> openai.Stream:
    """
    Used in the following cases
    ```
    astream = async_client.messages.create(stream=True)
    async for event in astream:
        print(event)
    ```
    """

    def AsyncStream__aiter__decorator(dunder_aiter_func: Callable) -> Callable:
        @functools.wraps(dunder_aiter_func)
        async def wrapper(
            self: openai.AsyncStream,
        ) -> AsyncIterator[StreamItem]:
            try:
                accumulated_items: List[StreamItem] = []
                error_info: Optional[ErrorInfoDict] = None

                async for item in dunder_aiter_func(self):
                    accumulated_items.append(item)
                    yield item
            except Exception as exception:
                LOGGER.debug(
                    "Exception raised from openai.AsyncStream.",
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

    openai.AsyncStream.__aiter__ = AsyncStream__aiter__decorator(
        original_async_stream_aiter_method
    )

    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end

    return stream
