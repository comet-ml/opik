import logging
import functools
from typing import Iterator, AsyncIterator, List, Optional, Callable, TypeVar

from opik.types import ErrorInfoDict
from opik.api_objects import trace, span
from opik.decorator import generator_wrappers, error_info_collector
import litellm.litellm_core_utils.streaming_handler


LOGGER = logging.getLogger(__name__)

StreamItem = TypeVar("StreamItem")
AggregatedResult = TypeVar("AggregatedResult")

# Store original iterator methods at module load time before any patching
# CustomStreamWrapper is an iterator (not just an iterable), so we need to patch __next__ and __anext__
original_custom_stream_wrapper_next_method = litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper.__next__
original_custom_stream_wrapper_anext_method = litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper.__anext__


def patch_stream(
    stream: litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[StreamItem]], Optional[AggregatedResult]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper:
    """
    Patch a LiteLLM stream to track chunks and aggregate them.
    
    LiteLLM's CustomStreamWrapper is an iterator (not just an iterable), so it uses
    __next__ and __anext__ methods for iteration. We patch these methods to accumulate
    chunks and call the aggregator when iteration completes.
    
    Used for streaming completion calls like:
    ```
    # Sync
    stream = litellm.completion(model="...", messages=[...], stream=True)
    for chunk in stream:
        print(chunk)
    
    # Async
    stream = await litellm.acompletion(model="...", messages=[...], stream=True)
    async for chunk in stream:
        print(chunk)
    ```
    
    Args:
        stream: The LiteLLM stream wrapper to patch
        span_to_end: The span to update when stream completes
        trace_to_end: The trace to update when stream completes (if any)
        generations_aggregator: Function to aggregate chunks into final response
        finally_callback: Callback to execute when stream finishes
        
    Returns:
        The patched stream wrapper
    """
    # Patch sync __next__
    def CustomStreamWrapper__next__decorator(dunder_next_func: Callable) -> Callable:
        @functools.wraps(dunder_next_func)
        def wrapper(
            self: litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper,
        ) -> StreamItem:
            # Initialize accumulator if this is a tracked instance
            if not hasattr(self, "_opik_accumulated_items"):
                if hasattr(self, "opik_tracked_instance"):
                    self._opik_accumulated_items = []
                    self._opik_error_info = None
            
            try:
                item = dunder_next_func(self)
                if hasattr(self, "_opik_accumulated_items"):
                    self._opik_accumulated_items.append(item)
                return item
            except StopIteration:
                # Stream is exhausted, finalize tracking if this was tracked
                if hasattr(self, "_opik_accumulated_items"):
                    try:
                        output = generations_aggregator(self._opik_accumulated_items)
                        finally_callback(
                            output=output,
                            error_info=self._opik_error_info,
                            capture_output=True,
                            generators_span_to_end=self.span_to_end,
                            generators_trace_to_end=self.trace_to_end,
                        )
                    finally:
                        # Clean up
                        if hasattr(self, "_opik_accumulated_items"):
                            delattr(self, "_opik_accumulated_items")
                        if hasattr(self, "_opik_error_info"):
                            delattr(self, "_opik_error_info")
                        if hasattr(self, "opik_tracked_instance"):
                            delattr(self, "opik_tracked_instance")
                raise
            except Exception as exception:
                # Track error and re-raise
                if hasattr(self, "_opik_accumulated_items"):
                    self._opik_error_info = error_info_collector.collect(exception)
                    LOGGER.debug(
                        "Exception raised from LiteLLM stream: %s",
                        str(exception),
                        exc_info=True,
                    )
                raise

        return wrapper

    # Patch async __anext__
    def CustomStreamWrapper__anext__decorator(dunder_anext_func: Callable) -> Callable:
        @functools.wraps(dunder_anext_func)
        async def wrapper(
            self: litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper,
        ) -> StreamItem:
            # Initialize accumulator if this is a tracked instance
            if not hasattr(self, "_opik_accumulated_items_async"):
                if hasattr(self, "opik_tracked_instance_async"):
                    self._opik_accumulated_items_async = []
                    self._opik_error_info_async = None
            
            try:
                item = await dunder_anext_func(self)
                if hasattr(self, "_opik_accumulated_items_async"):
                    self._opik_accumulated_items_async.append(item)
                return item
            except StopAsyncIteration:
                # Stream is exhausted, finalize tracking if this was tracked
                if hasattr(self, "_opik_accumulated_items_async"):
                    try:
                        output = generations_aggregator(self._opik_accumulated_items_async)
                        finally_callback(
                            output=output,
                            error_info=self._opik_error_info_async,
                            capture_output=True,
                            generators_span_to_end=self.span_to_end_async,
                            generators_trace_to_end=self.trace_to_end_async,
                        )
                    finally:
                        # Clean up
                        if hasattr(self, "_opik_accumulated_items_async"):
                            delattr(self, "_opik_accumulated_items_async")
                        if hasattr(self, "_opik_error_info_async"):
                            delattr(self, "_opik_error_info_async")
                        if hasattr(self, "opik_tracked_instance_async"):
                            delattr(self, "opik_tracked_instance_async")
                raise
            except Exception as exception:
                # Track error and re-raise
                if hasattr(self, "_opik_accumulated_items_async"):
                    self._opik_error_info_async = error_info_collector.collect(exception)
                    LOGGER.debug(
                        "Exception raised from LiteLLM async stream: %s",
                        str(exception),
                        exc_info=True,
                    )
                raise

        return wrapper

    # Patch both methods globally
    litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper.__next__ = (
        CustomStreamWrapper__next__decorator(original_custom_stream_wrapper_next_method)
    )
    litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper.__anext__ = (
        CustomStreamWrapper__anext__decorator(original_custom_stream_wrapper_anext_method)
    )

    # Mark this specific instance as tracked for both sync and async
    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end
    
    stream.opik_tracked_instance_async = True
    stream.span_to_end_async = span_to_end
    stream.trace_to_end_async = trace_to_end

    return stream

