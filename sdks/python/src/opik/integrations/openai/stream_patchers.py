import logging
import time
from typing import Iterator, AsyncIterator, List, Optional, Callable, TypeVar, Any

from opik.types import ErrorInfoDict
from opik.api_objects import trace, span
from opik.decorator import generator_wrappers, error_info_collector
import functools
import openai
import openai.lib.streaming.chat


LOGGER = logging.getLogger(__name__)


def _calculate_ttft_ms(
    first_token_time_ns: int,
    start_time_ns: int,
) -> float:
    """Calculate TTFT in milliseconds from nanosecond timestamps."""
    return (first_token_time_ns - start_time_ns) / 1_000_000


def _chunk_has_content(chunk: Any) -> bool:
    """
    Check if a stream chunk contains actual content (first token).

    For ChatCompletionChunk, content can be:
    - Text content in delta.content
    - Tool/function calls in delta.tool_calls

    Returns True if the chunk contains any meaningful content.
    """
    if not hasattr(chunk, "choices") or not chunk.choices:
        return False

    if not isinstance(chunk.choices, (list, tuple)):
        return False

    choice = chunk.choices[0]
    if not hasattr(choice, "delta") or choice.delta is None:
        return False

    delta = choice.delta

    if hasattr(delta, "content") and delta.content:
        return True

    if hasattr(delta, "tool_calls") and delta.tool_calls:
        return True

    return False


# Raw low-level stream methods
original_stream_iter_method = openai.Stream.__iter__
original_async_stream_aiter_method = openai.AsyncStream.__aiter__

# Stream manager (factory object) methods
original_chat_completion_stream_manager_enter_method = (
    openai.lib.streaming.chat.ChatCompletionStreamManager.__enter__
)
original_async_chat_completion_stream_manager_aenter_method = (
    openai.lib.streaming.chat.AsyncChatCompletionStreamManager.__aenter__
)

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

    def Stream__iter__decorator(dunder_iter_func: Callable) -> Callable:
        @functools.wraps(dunder_iter_func)
        def wrapper(
            self: openai.Stream,
        ) -> Iterator[StreamItem]:
            first_token_time_ns: Optional[int] = None
            try:
                accumulated_items: List[StreamItem] = []
                error_info: Optional[ErrorInfoDict] = None
                for item in dunder_iter_func(self):
                    if first_token_time_ns is None and _chunk_has_content(item):
                        first_token_time_ns = time.perf_counter_ns()
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
                if hasattr(self, "opik_tracked_instance"):
                    delattr(self, "opik_tracked_instance")

                    if first_token_time_ns is not None:
                        self.span_to_end.ttft = _calculate_ttft_ms(
                            first_token_time_ns,
                            self.span_to_end._created_at_perf_counter_ns,
                        )

                        if self.trace_to_end is not None:
                            self.trace_to_end.ttft = _calculate_ttft_ms(
                                first_token_time_ns,
                                self.trace_to_end._created_at_perf_counter_ns,
                            )

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
            first_token_time_ns: Optional[int] = None
            try:
                accumulated_items: List[StreamItem] = []
                error_info: Optional[ErrorInfoDict] = None

                async for item in dunder_aiter_func(self):
                    if first_token_time_ns is None and _chunk_has_content(item):
                        first_token_time_ns = time.perf_counter_ns()
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
                if hasattr(self, "opik_tracked_instance"):
                    delattr(self, "opik_tracked_instance")

                    if first_token_time_ns is not None:
                        self.span_to_end.ttft = _calculate_ttft_ms(
                            first_token_time_ns,
                            self.span_to_end._created_at_perf_counter_ns,
                        )

                        if self.trace_to_end is not None:
                            self.trace_to_end.ttft = _calculate_ttft_ms(
                                first_token_time_ns,
                                self.trace_to_end._created_at_perf_counter_ns,
                            )

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


def patch_sync_chat_completion_stream_manager(
    chat_completion_stream_manager: openai.lib.streaming.chat.ChatCompletionStreamManager,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[StreamItem]], Optional[AggregatedResult]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> openai.lib.streaming.chat.ChatCompletionStreamManager:
    """
    User flow that caused this non-trivial patching:

    ```
    stream_manager = openai_client.chat.completions.create(stream=True)

    with stream_manager as stream:
        for event in stream:
            print(event)
    ```

    `create` method with stream=True returns a stream_manager object that creates a Stream object in the context
    manager. We need to patch the __enter__ method to return our patched stream.
    """

    def ChatCompletionStreamManager__enter__decorator(enter_func: Callable) -> Callable:
        @functools.wraps(enter_func)
        def wrapper(
            self: openai.lib.streaming.chat.ChatCompletionStreamManager,
        ) -> openai.lib.streaming.chat.ChatCompletionStream:
            chat_completion_stream = enter_func(self)

            chat_completion_stream._raw_stream = patch_sync_stream(
                stream=chat_completion_stream._raw_stream,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=finally_callback,
            )

            return chat_completion_stream

        return wrapper

    # Replace the original __enter__ method with our decorated version
    openai.lib.streaming.chat.ChatCompletionStreamManager.__enter__ = (
        ChatCompletionStreamManager__enter__decorator(
            original_chat_completion_stream_manager_enter_method
        )
    )

    return chat_completion_stream_manager


def patch_async_chat_completion_stream_manager(
    async_chat_completion_stream_manager: openai.lib.streaming.chat.AsyncChatCompletionStreamManager,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[StreamItem]], Optional[AggregatedResult]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> openai.lib.streaming.chat.AsyncChatCompletionStreamManager:
    """
    User flow that caused this non-trivial patching:

    ```
    async_stream_manager = openai_client.chat.completions.create(stream=True)

    async with async_stream_manager as async_stream:
        async for event in async_stream:
            print(event)
    ```

    For more details see patch_sync_message_stream_manager docstring
    """

    def AsyncChatCompletionStreamManager__aenter__decorator(
        aenter_func: Callable,
    ) -> Callable:
        @functools.wraps(aenter_func)
        async def wrapper(
            self: openai.lib.streaming.chat.AsyncChatCompletionStreamManager,
        ) -> openai.lib.streaming.chat.AsyncChatCompletionStream:
            async_chat_completion_stream = await aenter_func(self)

            async_chat_completion_stream._raw_stream = patch_async_stream(
                stream=async_chat_completion_stream._raw_stream,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=finally_callback,
            )

            return async_chat_completion_stream

        return wrapper

    openai.lib.streaming.chat.AsyncChatCompletionStreamManager.__aenter__ = (
        AsyncChatCompletionStreamManager__aenter__decorator(
            original_async_chat_completion_stream_manager_aenter_method
        )
    )

    return async_chat_completion_stream_manager
