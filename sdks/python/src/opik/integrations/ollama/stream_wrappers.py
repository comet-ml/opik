import logging
from typing import Any, AsyncIterator, Callable, Iterator, List, Optional

from opik.api_objects import span, trace
from opik.decorator import error_info_collector, generator_wrappers
from opik.types import ErrorInfoDict

LOGGER = logging.getLogger(__name__)


def wrap_sync_stream(
    stream: Iterator[Any],
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Optional[Any]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Iterator[Any]:
    """Wrap the generator ``Client.chat(stream=True)`` returns.

    Ollama hands back a plain generator rather than a stream object, so this
    wraps the iterator itself. Nothing is patched on a class, which keeps the
    per-call aggregator and callback where they belong -- on this closure -- and
    leaves untracked streams untouched.
    """

    def wrapper() -> Iterator[Any]:
        accumulated_items: List[Any] = []
        error_info: Optional[ErrorInfoDict] = None
        try:
            for item in stream:
                accumulated_items.append(item)
                yield item
        except Exception as exception:
            LOGGER.debug(
                "Exception raised from ollama stream: %s",
                str(exception),
                exc_info=True,
            )
            error_info = error_info_collector.collect(exception)
            raise exception
        finally:
            output = (
                generations_aggregator(accumulated_items)
                if error_info is None
                else None
            )
            finally_callback(
                output=output,
                error_info=error_info,
                capture_output=True,
                generators_span_to_end=span_to_end,
                generators_trace_to_end=trace_to_end,
            )

    return wrapper()


def wrap_async_stream(
    stream: AsyncIterator[Any],
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Optional[Any]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> AsyncIterator[Any]:
    """Async counterpart of :func:`wrap_sync_stream`."""

    async def wrapper() -> AsyncIterator[Any]:
        accumulated_items: List[Any] = []
        error_info: Optional[ErrorInfoDict] = None
        try:
            async for item in stream:
                accumulated_items.append(item)
                yield item
        except Exception as exception:
            LOGGER.debug(
                "Exception raised from ollama async stream: %s",
                str(exception),
                exc_info=True,
            )
            error_info = error_info_collector.collect(exception)
            raise exception
        finally:
            output = (
                generations_aggregator(accumulated_items)
                if error_info is None
                else None
            )
            finally_callback(
                output=output,
                error_info=error_info,
                capture_output=True,
                generators_span_to_end=span_to_end,
                generators_trace_to_end=trace_to_end,
            )

    return wrapper()
