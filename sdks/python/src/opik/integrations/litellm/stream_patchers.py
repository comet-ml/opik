import logging
import functools
from typing import Any, List, Optional, Callable, TypeVar

from opik.api_objects import trace, span
from opik.decorator import generator_wrappers, error_info_collector
import litellm.litellm_core_utils.streaming_handler


LOGGER = logging.getLogger(__name__)

StreamItem = TypeVar("StreamItem")
AggregatedResult = TypeVar("AggregatedResult")

_original_next = (
    litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper.__next__
)
_original_anext = (
    litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper.__anext__
)


def _create_sync_next_wrapper(
    original_next: Callable,
    generations_aggregator: Callable,
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Callable:
    @functools.wraps(original_next)
    def wrapper(
        self: litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper,
    ) -> Any:
        if not hasattr(self, "_opik_accumulated_items"):
            if hasattr(self, "opik_tracked_instance"):
                self._opik_accumulated_items = []
                self._opik_error_info = None

        try:
            item = original_next(self)
            if hasattr(self, "_opik_accumulated_items"):
                self._opik_accumulated_items.append(item)
            return item
        except StopIteration:
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
                    if hasattr(self, "_opik_accumulated_items"):
                        delattr(self, "_opik_accumulated_items")
                    if hasattr(self, "_opik_error_info"):
                        delattr(self, "_opik_error_info")
                    if hasattr(self, "opik_tracked_instance"):
                        delattr(self, "opik_tracked_instance")
            raise
        except Exception as exception:
            if hasattr(self, "_opik_accumulated_items"):
                self._opik_error_info = error_info_collector.collect(exception)
                LOGGER.debug(
                    "Exception raised from LiteLLM stream: %s",
                    str(exception),
                    exc_info=True,
                )
            raise

    return wrapper


def _create_async_next_wrapper(
    original_anext: Callable,
    generations_aggregator: Callable,
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Callable:
    @functools.wraps(original_anext)
    async def wrapper(
        self: litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper,
    ) -> Any:
        if not hasattr(self, "_opik_accumulated_items_async"):
            if hasattr(self, "opik_tracked_instance_async"):
                self._opik_accumulated_items_async = []
                self._opik_error_info_async = None

        try:
            item = await original_anext(self)
            if hasattr(self, "_opik_accumulated_items_async"):
                self._opik_accumulated_items_async.append(item)
            return item
        except StopAsyncIteration:
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
                    if hasattr(self, "_opik_accumulated_items_async"):
                        delattr(self, "_opik_accumulated_items_async")
                    if hasattr(self, "_opik_error_info_async"):
                        delattr(self, "_opik_error_info_async")
                    if hasattr(self, "opik_tracked_instance_async"):
                        delattr(self, "opik_tracked_instance_async")
            raise
        except Exception as exception:
            if hasattr(self, "_opik_accumulated_items_async"):
                self._opik_error_info_async = error_info_collector.collect(exception)
                LOGGER.debug(
                    "Exception raised from LiteLLM async stream: %s",
                    str(exception),
                    exc_info=True,
                )
            raise

    return wrapper


def patch_stream(
    stream: litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[StreamItem]], Optional[AggregatedResult]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper:
    litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper.__next__ = (
        _create_sync_next_wrapper(
            _original_next, generations_aggregator, finally_callback
        )
    )
    litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper.__anext__ = (
        _create_async_next_wrapper(
            _original_anext, generations_aggregator, finally_callback
        )
    )

    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end

    stream.opik_tracked_instance_async = True
    stream.span_to_end_async = span_to_end
    stream.trace_to_end_async = trace_to_end

    return stream
