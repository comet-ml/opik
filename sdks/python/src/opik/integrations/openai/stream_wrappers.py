import logging
from typing import Iterator, AsyncIterator, Any, List, Optional, Callable
from opik.api_objects import trace, span
from opik.decorator import generator_wrappers
from openai.types.chat import chat_completion_chunk, chat_completion
import functools
import openai
from openai.lib.streaming import chat
LOGGER = logging.getLogger(__name__)

original_stream_iter_method = openai.Stream.__iter__
original_async_stream_aiter_method = openai.AsyncStream.__aiter__

original_chat_completion_stream_iter_method = chat.ChatCompletionStream.__iter__
original_async_chat_completion_stream_aiter_method = chat.AsyncChatCompletionStream.__aiter__

original_chat_completion_stream_manager_enter_method = chat.ChatCompletionStreamManager.__enter__
original_async_chat_completion_stream_manager_aenter_method = (
    chat.AsyncChatCompletionStreamManager.__aenter__
)

def patch_sync_stream(
    stream: openai.Stream,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[chat_completion_chunk.ChatCompletionChunk]], chat_completion.ChatCompletion],
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
    generations_aggregator: Callable[[List[chat_completion_chunk.ChatCompletionChunk]], chat_completion.ChatCompletion],
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


def patch_sync_message_stream_manager(
    chat_completion_stream_manager: chat.ChatCompletionStreamManager,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> chat.ChatCompletionStreamManager:
    """
    User flow that caused this non-trivial patching:

    ```
    stream_manager = openai_client.messages.stream(...)

    with stream_manager as stream:
        for event in stream:
            print(event)

        stream.get_final_message()
    ```

    `stream` method returns an object (stream_manager), that creates a MessageStream object in the context
    manager. MessageStream class has it's own public API (e.g. get_final_message), so we can't replace it with our generator.
    We need to patch __iter__ method of MessageStream, so that when it returns a generator object, we
    wrap it.

    In addition, its possible that generator is used multiple times. We are making sure, that we execute our
    logging logic only once.
    """

    def ChatCompletionStream__iter__decorator(dunder_iter_func: Callable) -> Callable:
        @functools.wraps(dunder_iter_func)
        def wrapper(
            self: chat.ChatCompletionStream,
        ) -> Iterator[chat.ChatCompletionStreamEvent]:
            try:
                for item in dunder_iter_func(self):
                    yield item
            finally:
                if not hasattr(self, "opik_tracked_instance"):
                    return

                delattr(self, "opik_tracked_instance")
                finally_callback(
                    output=self.get_final_completion(),
                    capture_output=True,
                    generators_span_to_end=self.span_to_end,
                    generators_trace_to_end=self.trace_to_end,
                )

        return wrapper

    def ChatCompletionStreamManager__enter__decorator(dunder_enter_func: Callable) -> Callable:
        @functools.wraps(dunder_enter_func)
        def wrapper(self: chat.ChatCompletionStreamManager) -> chat.ChatCompletionStream:
            result: chat.ChatCompletionStream = dunder_enter_func(self)

            if hasattr(self, "opik_tracked_instance"):
                result.opik_tracked_instance = True
                result.span_to_end = self.span_to_end
                result.trace_to_end = self.trace_to_end

            return result

        return wrapper

    # We are decorating class methods instead of instance methods because
    # python interpreter often (if not always) looks for dunder methods for in classes, not instances, by .
    # Decorating an instance method will not work, original method will always be called.
    chat.ChatCompletionStreamManager.__enter__ = ChatCompletionStreamManager__enter__decorator(
        original_chat_completion_stream_manager_enter_method
    )
    chat.ChatCompletionStream.__iter__ = ChatCompletionStream__iter__decorator(
        original_chat_completion_stream_iter_method
    )

    chat_completion_stream_manager.opik_tracked_instance = True
    chat_completion_stream_manager.span_to_end = span_to_end
    chat_completion_stream_manager.trace_to_end = trace_to_end

    return chat_completion_stream_manager


def patch_async_message_stream_manager(
    async_chat_completion_stream_manager: chat.ChatCompletionStreamManager,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> chat.ChatCompletionStreamManager:
    """
    User flow that caused this non-trivial patching:

    ```
    async_stream_manager = async_openai_client.messages.stream(...)

    async with async_stream_manager as async_stream:
        async for event in async_stream:
            print(event)

        await stream.get_final_completion()
    ```

    For more details see patch_sync_message_stream_manager docstring
    """

    def AsyncChatCompletionStream__aiter__decorator(dunder_aiter_func: Callable) -> Callable:
        @functools.wraps(dunder_aiter_func)
        async def wrapper(
            self: chat.AsyncChatCompletionStream,
        ) -> AsyncIterator[chat.ChatCompletionStreamEvent]:
            try:
                async for item in dunder_aiter_func(self):
                    yield item
            finally:
                if not hasattr(self, "opik_tracked_instance"):
                    return

                delattr(self, "opik_tracked_instance")

                finally_callback(
                    output=await self.get_final_completion(),
                    capture_output=True,
                    generators_span_to_end=self.span_to_end,
                    generators_trace_to_end=self.trace_to_end,
                )

        return wrapper

    def AsyncChatCompletionStreamManager__aenter__decorator(
        dunder_aenter_func: Callable,
    ) -> Callable:
        @functools.wraps(dunder_aenter_func)
        async def wrapper(
            self: chat.AsyncChatCompletionStreamManager,
        ) -> chat.AsyncChatCompletionStream:
            result: chat.AsyncChatCompletionStream = await dunder_aenter_func(self)

            if hasattr(self, "opik_tracked_instance"):
                result.opik_tracked_instance = True
                result.span_to_end = self.span_to_end
                result.trace_to_end = self.trace_to_end

            return result

        return wrapper

    # We are decorating class methods instead of instance methods because
    # python interpreter often (if not always) looks for dunder methods for in classes, not instances, by .
    # Decorating an instance method will not work, original method will always be called.
    chat.AsyncChatCompletionStreamManager.__aenter__ = (
        AsyncChatCompletionStreamManager__aenter__decorator(
            original_async_chat_completion_stream_manager_aenter_method
        )
    )
    chat.AsyncChatCompletionStream.__aiter__ = AsyncChatCompletionStream__aiter__decorator(
        original_async_chat_completion_stream_aiter_method
    )

    async_chat_completion_stream_manager.opik_tracked_instance = True
    async_chat_completion_stream_manager.span_to_end = span_to_end
    async_chat_completion_stream_manager.trace_to_end = trace_to_end

    return async_chat_completion_stream_manager
