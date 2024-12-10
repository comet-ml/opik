import anthropic
import logging
import functools
from typing import (
    Optional,
    Callable,
    Iterator,
    AsyncIterator,
    Any,
)
from opik.types import ErrorInfoDict
from opik.api_objects import trace, span
from opik.decorator import generator_wrappers, error_info_collector

from anthropic.lib.streaming import _messages


LOGGER = logging.getLogger(__name__)

original_stream_iter_method = anthropic.Stream.__iter__
original_async_stream_aiter_method = anthropic.AsyncStream.__aiter__

original_message_stream_iter_method = anthropic.MessageStream.__iter__
original_async_message_stream_aiter_method = anthropic.AsyncMessageStream.__aiter__

original_message_stream_manager_enter_method = anthropic.MessageStreamManager.__enter__
original_async_message_stream_manager_aenter_method = (
    anthropic.AsyncMessageStreamManager.__aenter__
)


def patch_sync_stream(
    stream: anthropic.Stream,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> anthropic.Stream:
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
            self: anthropic.Stream,
        ) -> Iterator[Any]:
            try:
                accumulated_message = None
                error_info: Optional[ErrorInfoDict] = None

                for item in dunder_iter_func(self):
                    accumulated_message = _messages.accumulate_event(
                        event=item, current_snapshot=accumulated_message
                    )
                    yield item
            except Exception as exception:
                LOGGER.debug(
                    "Exception raised from anthropic.Stream.",
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                raise exception
            finally:
                if not hasattr(self, "opik_tracked_instance"):
                    return

                delattr(self, "opik_tracked_instance")
                output = accumulated_message if error_info is None else None
                finally_callback(
                    output=output,
                    error_info=error_info,
                    capture_output=True,
                    generators_span_to_end=self.span_to_end,
                    generators_trace_to_end=self.trace_to_end,
                )

        return wrapper

    anthropic.Stream.__iter__ = Stream__iter__decorator(original_stream_iter_method)

    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end

    return stream


def patch_async_stream(
    stream: anthropic.AsyncStream,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> anthropic.Stream:
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
            self: anthropic.AsyncStream,
        ) -> AsyncIterator[Any]:
            try:
                accumulated_message = None
                error_info: Optional[ErrorInfoDict] = None

                async for item in dunder_aiter_func(self):
                    accumulated_message = _messages.accumulate_event(
                        event=item, current_snapshot=accumulated_message
                    )
                    yield item
            except Exception as exception:
                LOGGER.debug(
                    "Exception raised from anthropic.AsyncStream.",
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                raise exception
            finally:
                if not hasattr(self, "opik_tracked_instance"):
                    return

                delattr(self, "opik_tracked_instance")
                output = accumulated_message if error_info is None else None
                finally_callback(
                    output=output,
                    error_info=error_info,
                    capture_output=True,
                    generators_span_to_end=self.span_to_end,
                    generators_trace_to_end=self.trace_to_end,
                )

        return wrapper

    anthropic.AsyncStream.__aiter__ = AsyncStream__aiter__decorator(
        original_async_stream_aiter_method
    )

    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end

    return stream


def patch_sync_message_stream_manager(
    message_stream_manager: anthropic.MessageStreamManager,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> anthropic.MessageStreamManager:
    """
    User flow that caused this non-trivial patching:

    ```
    stream_manager = anthropic_client.messages.stream(...)

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

    def MessageStream__iter__decorator(dunder_iter_func: Callable) -> Callable:
        @functools.wraps(dunder_iter_func)
        def wrapper(
            self: anthropic.MessageStream,
        ) -> Iterator[anthropic.MessageStreamEvent]:
            try:
                error_info: Optional[ErrorInfoDict] = None
                for item in dunder_iter_func(self):
                    yield item
            except Exception as exception:
                LOGGER.debug(
                    "Exception raised from anthropic.MessageStream.",
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                raise exception
            finally:
                if not hasattr(self, "opik_tracked_instance"):
                    return

                delattr(self, "opik_tracked_instance")

                accumulated_output = (
                    self.get_final_message() if error_info is None else None
                )

                finally_callback(
                    output=accumulated_output,
                    error_info=error_info,
                    capture_output=True,
                    generators_span_to_end=self.span_to_end,
                    generators_trace_to_end=self.trace_to_end,
                )

        return wrapper

    def MessageStreamManager__enter__decorator(dunder_enter_func: Callable) -> Callable:
        @functools.wraps(dunder_enter_func)
        def wrapper(self: anthropic.MessageStreamManager) -> anthropic.MessageStream:
            result: anthropic.MessageStream = dunder_enter_func(self)

            if hasattr(self, "opik_tracked_instance"):
                result.opik_tracked_instance = True
                result.span_to_end = self.span_to_end
                result.trace_to_end = self.trace_to_end

            return result

        return wrapper

    # We are decorating class methods instead of instance methods because
    # python interpreter often (if not always) looks for dunder methods for in classes, not instances, by .
    # Decorating an instance method will not work, original method will always be called.
    anthropic.MessageStreamManager.__enter__ = MessageStreamManager__enter__decorator(
        original_message_stream_manager_enter_method
    )
    anthropic.MessageStream.__iter__ = MessageStream__iter__decorator(
        original_message_stream_iter_method
    )

    message_stream_manager.opik_tracked_instance = True
    message_stream_manager.span_to_end = span_to_end
    message_stream_manager.trace_to_end = trace_to_end

    return message_stream_manager


def patch_async_message_stream_manager(
    async_message_stream_manager: anthropic.AsyncMessageStreamManager,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> anthropic.AsyncMessageStreamManager:
    """
    User flow that caused this non-trivial patching:

    ```
    async_stream_manager = async_anthropic_client.messages.stream(...)

    async with async_stream_manager as async_stream:
        async for event in async_stream:
            print(event)

        await stream.get_final_message()
    ```

    For more details see patch_sync_message_stream_manager docstring
    """

    def AsyncMessageStream__aiter__decorator(dunder_aiter_func: Callable) -> Callable:
        @functools.wraps(dunder_aiter_func)
        async def wrapper(
            self: anthropic.AsyncMessageStream,
        ) -> AsyncIterator[anthropic.MessageStreamEvent]:
            try:
                error_info: Optional[ErrorInfoDict] = None
                async for item in dunder_aiter_func(self):
                    yield item
            except Exception as exception:
                LOGGER.debug(
                    "Exception raised from anthropic.AsyncMessageStream.",
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                raise exception
            finally:
                if not hasattr(self, "opik_tracked_instance"):
                    return

                delattr(self, "opik_tracked_instance")

                accumulated_output = (
                    await self.get_final_message() if error_info is None else None
                )

                finally_callback(
                    output=accumulated_output,
                    error_info=error_info,
                    capture_output=True,
                    generators_span_to_end=self.span_to_end,
                    generators_trace_to_end=self.trace_to_end,
                )

        return wrapper

    def AsyncMessageStreamManager__aenter__decorator(
        dunder_aenter_func: Callable,
    ) -> Callable:
        @functools.wraps(dunder_aenter_func)
        async def wrapper(
            self: anthropic.AsyncMessageStreamManager,
        ) -> anthropic.AsyncMessageStream:
            result: anthropic.AsyncMessageStream = await dunder_aenter_func(self)

            if hasattr(self, "opik_tracked_instance"):
                result.opik_tracked_instance = True
                result.span_to_end = self.span_to_end
                result.trace_to_end = self.trace_to_end

            return result

        return wrapper

    # We are decorating class methods instead of instance methods because
    # python interpreter often (if not always) looks for dunder methods for in classes, not instances, by .
    # Decorating an instance method will not work, original method will always be called.
    anthropic.AsyncMessageStreamManager.__aenter__ = (
        AsyncMessageStreamManager__aenter__decorator(
            original_async_message_stream_manager_aenter_method
        )
    )
    anthropic.AsyncMessageStream.__aiter__ = AsyncMessageStream__aiter__decorator(
        original_async_message_stream_aiter_method
    )

    async_message_stream_manager.opik_tracked_instance = True
    async_message_stream_manager.span_to_end = span_to_end
    async_message_stream_manager.trace_to_end = trace_to_end

    return async_message_stream_manager
