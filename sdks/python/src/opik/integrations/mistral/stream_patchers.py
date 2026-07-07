import logging
from typing import Any, AsyncIterator, Callable, Iterator, List, Optional

from opik.api_objects import span, trace
from opik.decorator import error_info_collector, generator_wrappers
from opik.types import ErrorInfoDict

LOGGER = logging.getLogger(__name__)

# The stream classes (mistralai's EventStream / EventStreamAsync) live in an
# internal module, so instead of importing them we operate on ``type(stream)``
# of the object chat.stream()/stream_async() returns. Their ``__iter__`` /
# ``__aiter__`` return ``self`` and iteration is driven by ``__next__`` /
# ``__anext__`` (pulling from an internal generator), so we can't wrap the
# iterator by re-iterating it — we drive the original ``__next__`` instead.
# ``__next__`` is never patched, so reading it off the class each call always
# yields the genuine method.


def patch_sync_event_stream(
    stream: Any,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Optional[Any]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Any:
    """Patch a sync event stream returned by ``client.chat.stream(...)``.

    Covers both ``for event in stream:`` and ``with stream as s: for event in s:``
    since the stream is its own iterator and context manager.
    """
    stream_cls = type(stream)
    original_next = stream_cls.__next__

    def wrapper(self: Any) -> Iterator[Any]:
        accumulated_items: List[Any] = []
        error_info: Optional[ErrorInfoDict] = None
        try:
            while True:
                try:
                    item = original_next(self)
                except StopIteration:
                    break
                accumulated_items.append(item)
                yield item
        except Exception as exception:
            LOGGER.debug(
                "Exception raised from mistralai event stream.",
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

    stream_cls.__iter__ = wrapper

    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end

    return stream


def patch_async_event_stream(
    stream: Any,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Optional[Any]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Any:
    """Patch an async event stream returned by ``client.chat.stream_async(...)``."""
    stream_cls = type(stream)
    original_anext = stream_cls.__anext__

    async def wrapper(self: Any) -> AsyncIterator[Any]:
        accumulated_items: List[Any] = []
        error_info: Optional[ErrorInfoDict] = None
        try:
            while True:
                try:
                    item = await original_anext(self)
                except StopAsyncIteration:
                    break
                accumulated_items.append(item)
                yield item
        except Exception as exception:
            LOGGER.debug(
                "Exception raised from mistralai async event stream.",
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

    stream_cls.__aiter__ = wrapper

    stream.opik_tracked_instance = True
    stream.span_to_end = span_to_end
    stream.trace_to_end = trace_to_end

    return stream
