import anthropic
import functools
from typing import Generator, Any, Optional, Callable
from opik.api_objects import trace, span
from opik.decorator import generator_wrappers


original_message_stream_iter_method = anthropic.MessageStream.__iter__
original_async_message_stream_iter_method = anthropic.AsyncMessageStream.__aiter__


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

    def wrapped_generator(
        generator: Generator[Any, None, None], message_stream: anthropic.MessageStream
    ) -> Generator[Any, None, None]:
        try:
            for item in generator:
                yield item
        finally:
            delattr(message_stream, "opik_tracked_instance")
            finally_callback(
                output=message_stream.get_final_message(),
                capture_output=True,
                generators_span_to_end=span_to_end,
                generators_trace_to_end=trace_to_end,
            )

    def stream__iter__decorator(dunder_iter_func: Callable) -> Callable:
        @functools.wraps(dunder_iter_func)
        def wrapper(self: anthropic.MessageStream) -> Generator[Any, None, None]:
            result = original_message_stream_iter_method(self)
            if hasattr(self, "opik_tracked_instance"):
                return wrapped_generator(result, self)

            return result

        return wrapper

    def stream_manager_enter_decorator(dunder_enter_func: Callable) -> Callable:
        @functools.wraps(dunder_enter_func)
        def wrapper(self: anthropic.MessageStreamManager) -> anthropic.MessageStream:
            result: anthropic.MessageStream = dunder_enter_func(self)

            if hasattr(self, "opik_tracked_instance"):
                result.opik_tracked_instance = True

            return result

        return wrapper

    # We are decorating class methods instead of instance methods because
    # python interpreter often (if not always) looks for dunder methods for in classes, not instances, by .
    # Decorating an instance method will not work, original method will always be called.
    anthropic.MessageStreamManager.__enter__ = stream_manager_enter_decorator(
        anthropic.MessageStreamManager.__enter__
    )
    anthropic.MessageStream.__iter__ = stream__iter__decorator(
        anthropic.MessageStream.__iter__
    )

    message_stream_manager.opik_tracked_instance = True

    return message_stream_manager


def patch_async_message_stream_manager(
    message_stream_manager: anthropic.AsyncMessageStreamManager,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> anthropic.AsyncMessageStreamManager:
    

    return message_stream_manager
