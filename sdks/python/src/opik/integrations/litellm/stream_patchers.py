import logging
import functools
from typing import List, Optional, Callable, TypeVar, Any

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


def _should_track_stream(stream: Any, tracking_marker_attr: str) -> bool:
    return hasattr(stream, tracking_marker_attr)


def _initialize_stream_tracking(
    stream: Any, accumulator_attr: str, error_attr: str
) -> None:
    if not hasattr(stream, accumulator_attr):
        setattr(stream, accumulator_attr, [])
        setattr(stream, error_attr, None)


def _cleanup_stream_tracking(
    stream: Any, tracking_marker_attr: str, accumulator_attr: str, error_attr: str
) -> None:
    for attr in [accumulator_attr, error_attr, tracking_marker_attr]:
        if hasattr(stream, attr):
            delattr(stream, attr)


def _finalize_stream_tracking(
    stream: Any,
    accumulator_attr: str,
    error_attr: str,
    span_attr: str,
    trace_attr: str,
    generations_aggregator: Callable,
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> None:
    accumulated_items = getattr(stream, accumulator_attr, [])
    error_info = getattr(stream, error_attr, None)
    span_to_end = getattr(stream, span_attr)
    trace_to_end = getattr(stream, trace_attr)

    output = generations_aggregator(accumulated_items)
    finally_callback(
        output=output,
        error_info=error_info,
        capture_output=True,
        generators_span_to_end=span_to_end,
        generators_trace_to_end=trace_to_end,
    )


def _create_sync_next_wrapper(
    original_next: Callable,
    generations_aggregator: Callable,
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Callable:
    @functools.wraps(original_next)
    def wrapper(
        self: litellm.litellm_core_utils.streaming_handler.CustomStreamWrapper,
    ) -> StreamItem:
        tracking_marker = "opik_tracked_instance"
        accumulator_attr = "_opik_accumulated_items"
        error_attr = "_opik_error_info"

        if _should_track_stream(self, tracking_marker):
            _initialize_stream_tracking(self, accumulator_attr, error_attr)

        try:
            item = original_next(self)
            if hasattr(self, accumulator_attr):
                getattr(self, accumulator_attr).append(item)
            return item
        except StopIteration:
            if hasattr(self, accumulator_attr):
                try:
                    _finalize_stream_tracking(
                        self,
                        accumulator_attr,
                        error_attr,
                        "span_to_end",
                        "trace_to_end",
                        generations_aggregator,
                        finally_callback,
                    )
                finally:
                    _cleanup_stream_tracking(
                        self, tracking_marker, accumulator_attr, error_attr
                    )
            raise
        except Exception as exception:
            if hasattr(self, accumulator_attr):
                setattr(self, error_attr, error_info_collector.collect(exception))
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
    ) -> StreamItem:
        tracking_marker = "opik_tracked_instance_async"
        accumulator_attr = "_opik_accumulated_items_async"
        error_attr = "_opik_error_info_async"

        if _should_track_stream(self, tracking_marker):
            _initialize_stream_tracking(self, accumulator_attr, error_attr)

        try:
            item = await original_anext(self)
            if hasattr(self, accumulator_attr):
                getattr(self, accumulator_attr).append(item)
            return item
        except StopAsyncIteration:
            if hasattr(self, accumulator_attr):
                try:
                    _finalize_stream_tracking(
                        self,
                        accumulator_attr,
                        error_attr,
                        "span_to_end_async",
                        "trace_to_end_async",
                        generations_aggregator,
                        finally_callback,
                    )
                finally:
                    _cleanup_stream_tracking(
                        self, tracking_marker, accumulator_attr, error_attr
                    )
            raise
        except Exception as exception:
            if hasattr(self, accumulator_attr):
                setattr(self, error_attr, error_info_collector.collect(exception))
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
