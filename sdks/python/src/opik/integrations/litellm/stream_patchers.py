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


def _finish_tracked_stream(
    stream: litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper,
    generations_aggregator: Callable,
    finally_callback: generator_wrappers.FinishGeneratorCallback,
    error_info: Optional[error_info_collector.ErrorInfoDict],
    items_attribute: str,
    error_info_attribute: str,
    tracked_attribute: str,
    span_attribute: str,
    trace_attribute: str,
) -> None:
    """End the span of `stream` exactly once and drop the attributes we set.

    Called both when the stream completes and when it fails part-way through:
    every other provider wrapper in this package ends the span from a `finally`,
    so a stream that raised used to leave its span open with no error recorded
    (the error_info was collected onto the stream and then dropped). Dropping
    `items_attribute` is also what stops a second call from ending the span
    twice, the same way the other wrappers guard on their tracked attribute.
    """
    try:
        output = (
            generations_aggregator(getattr(stream, items_attribute))
            if error_info is None
            else None
        )
        finally_callback(
            output=output,
            error_info=error_info,
            capture_output=True,
            generators_span_to_end=getattr(stream, span_attribute),
            generators_trace_to_end=getattr(stream, trace_attribute),
        )
    finally:
        for attribute in (
            items_attribute,
            error_info_attribute,
            tracked_attribute,
        ):
            if hasattr(stream, attribute):
                delattr(stream, attribute)


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
                _finish_tracked_stream(
                    self,
                    generations_aggregator,
                    finally_callback,
                    self._opik_error_info,
                    items_attribute="_opik_accumulated_items",
                    error_info_attribute="_opik_error_info",
                    tracked_attribute="opik_tracked_instance",
                    span_attribute="span_to_end",
                    trace_attribute="trace_to_end",
                )
            raise
        except Exception as exception:
            if hasattr(self, "_opik_accumulated_items"):
                LOGGER.debug(
                    "Exception raised from LiteLLM stream: %s",
                    str(exception),
                    exc_info=True,
                )
                _finish_tracked_stream(
                    self,
                    generations_aggregator,
                    finally_callback,
                    error_info_collector.collect(exception),
                    items_attribute="_opik_accumulated_items",
                    error_info_attribute="_opik_error_info",
                    tracked_attribute="opik_tracked_instance",
                    span_attribute="span_to_end",
                    trace_attribute="trace_to_end",
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
                _finish_tracked_stream(
                    self,
                    generations_aggregator,
                    finally_callback,
                    self._opik_error_info_async,
                    items_attribute="_opik_accumulated_items_async",
                    error_info_attribute="_opik_error_info_async",
                    tracked_attribute="opik_tracked_instance_async",
                    span_attribute="span_to_end_async",
                    trace_attribute="trace_to_end_async",
                )
            raise
        except Exception as exception:
            if hasattr(self, "_opik_accumulated_items_async"):
                LOGGER.debug(
                    "Exception raised from LiteLLM async stream: %s",
                    str(exception),
                    exc_info=True,
                )
                _finish_tracked_stream(
                    self,
                    generations_aggregator,
                    finally_callback,
                    error_info_collector.collect(exception),
                    items_attribute="_opik_accumulated_items_async",
                    error_info_attribute="_opik_error_info_async",
                    tracked_attribute="opik_tracked_instance_async",
                    span_attribute="span_to_end_async",
                    trace_attribute="trace_to_end_async",
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
