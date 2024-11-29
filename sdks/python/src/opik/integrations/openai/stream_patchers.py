import logging
from typing import Iterator, AsyncIterator, Any, List, Optional, Callable
from opik.api_objects import trace, span
from opik.decorator import generator_wrappers
from openai.types.chat import chat_completion_chunk, chat_completion
import functools
import openai

LOGGER = logging.getLogger(__name__)

original_stream_iter_method = openai.Stream.__iter__
original_async_stream_aiter_method = openai.AsyncStream.__aiter__


def patch_sync_stream(
    stream: openai.Stream,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[
        [List[chat_completion_chunk.ChatCompletionChunk]],
        chat_completion.ChatCompletion,
    ],
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
        ) -> Iterator[Any]:
            try:
                accumulated_items: List[chat_completion_chunk.ChatCompletionChunk] = []
                for item in dunder_iter_func(self):
                    accumulated_items.append(item)
                    yield item
            finally:
                if not hasattr(self, "opik_tracked_instance"):
                    return

                delattr(self, "opik_tracked_instance")
                aggregated_output = generations_aggregator(accumulated_items)
                finally_callback(
                    output=aggregated_output,
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
    generations_aggregator: Callable[
        [List[chat_completion_chunk.ChatCompletionChunk]],
        chat_completion.ChatCompletion,
    ],
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
        ) -> AsyncIterator[Any]:
            try:
                accumulated_items: List[chat_completion_chunk.ChatCompletionChunk] = []
                async for item in dunder_aiter_func(self):
                    accumulated_items.append(item)
                    yield item
            finally:
                if not hasattr(self, "opik_tracked_instance"):
                    return

                delattr(self, "opik_tracked_instance")
                aggregated_output = generations_aggregator(accumulated_items)
                finally_callback(
                    output=aggregated_output,
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
