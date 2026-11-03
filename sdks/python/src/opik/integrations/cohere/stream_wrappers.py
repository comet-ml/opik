import logging
import weakref
from typing import Any, AsyncIterator, Callable, Dict, Iterator, List, Optional

from opik.api_objects import span, trace
from opik.decorator import error_info_collector, generator_wrappers
from opik.types import ErrorInfoDict

LOGGER = logging.getLogger(__name__)


def _end_abandoned_stream(
    state: Dict[str, bool],
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> None:
    """End the span of a stream that was never iterated.

    A generator's body, and so its ``finally``, does not run until the first
    ``next()``. The span is already popped from the decorator by the time this
    wrapper is built, so a stream that is created and then dropped would leave
    it open for good. Registered with ``weakref.finalize`` and detached as soon
    as the generator does run, the same way the LiteLLM integration does it.

    ``detach()`` is enough for a sync generator, which CPython closes before it
    clears weakrefs, but not for an async one: its ``finally`` only runs when
    the event loop awaits ``aclose()`` through the asyncgen finalizer hook,
    which is after this has already fired. So skip once the body has started
    and let that ``finally`` report the real output instead of ``None``.
    """
    if state["started"] or state["finished"]:
        return
    state["finished"] = True
    finally_callback(
        output=None,
        error_info=None,
        capture_output=True,
        generators_span_to_end=span_to_end,
        generators_trace_to_end=trace_to_end,
    )


def wrap_sync_stream(
    stream: Iterator[Any],
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Optional[Any]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Iterator[Any]:
    """Wrap the generator ``ClientV2.chat_stream()`` returns.

    Cohere's chat_stream() returns a plain iterator rather than a stream object, so this
    wraps the iterator itself. Nothing is patched on a class, which keeps the
    per-call aggregator and callback where they belong -- on this closure -- and
    leaves untracked streams untouched.
    """

    finalizer: Optional[weakref.finalize] = None
    state: Dict[str, bool] = {"started": False, "finished": False}

    def wrapper() -> Iterator[Any]:
        accumulated_items: List[Any] = []
        state["started"] = True
        completed = False
        error_info: Optional[ErrorInfoDict] = None
        try:
            for item in stream:
                accumulated_items.append(item)
                yield item
            completed = True
        except Exception as exception:
            LOGGER.debug(
                "Exception raised from cohere stream: %s",
                str(exception),
                exc_info=True,
            )
            error_info = error_info_collector.collect(exception)
            raise exception
        except BaseException as exception:
            # GeneratorExit is raised when a consumer abandons the stream
            # (``break``, or the generator being closed/collected). It is not an
            # Exception subclass, so without this it reaches `finally` with
            # error_info unset and a truncated stream is recorded as a success.
            LOGGER.debug(
                "cohere stream terminated early: %s",
                type(exception).__name__,
                exc_info=True,
            )
            error_info = error_info_collector.collect(exception)
            raise
        finally:
            if finalizer is not None:
                finalizer.detach()
            output = (
                generations_aggregator(accumulated_items)
                if error_info is None and completed
                else None
            )
            if not state["finished"]:
                state["finished"] = True
                finally_callback(
                    output=output,
                    error_info=error_info,
                    capture_output=True,
                    generators_span_to_end=span_to_end,
                    generators_trace_to_end=trace_to_end,
                )

    generator = wrapper()
    finalizer = weakref.finalize(
        generator,
        _end_abandoned_stream,
        state,
        span_to_end,
        trace_to_end,
        finally_callback,
    )
    return generator


def wrap_async_stream(
    stream: AsyncIterator[Any],
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[Any]], Optional[Any]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> AsyncIterator[Any]:
    """Async counterpart of :func:`wrap_sync_stream`."""

    finalizer: Optional[weakref.finalize] = None
    state: Dict[str, bool] = {"started": False, "finished": False}

    async def wrapper() -> AsyncIterator[Any]:
        accumulated_items: List[Any] = []
        state["started"] = True
        completed = False
        error_info: Optional[ErrorInfoDict] = None
        try:
            async for item in stream:
                accumulated_items.append(item)
                yield item
            completed = True
        except Exception as exception:
            LOGGER.debug(
                "Exception raised from cohere async stream: %s",
                str(exception),
                exc_info=True,
            )
            error_info = error_info_collector.collect(exception)
            raise exception
        except BaseException as exception:
            # GeneratorExit is raised when a consumer abandons the stream
            # (``break``, or the generator being closed/collected). It is not an
            # Exception subclass, so without this it reaches `finally` with
            # error_info unset and a truncated stream is recorded as a success.
            LOGGER.debug(
                "cohere async stream terminated early: %s",
                type(exception).__name__,
                exc_info=True,
            )
            error_info = error_info_collector.collect(exception)
            raise
        finally:
            if finalizer is not None:
                finalizer.detach()
            output = (
                generations_aggregator(accumulated_items)
                if error_info is None and completed
                else None
            )
            if not state["finished"]:
                state["finished"] = True
                finally_callback(
                    output=output,
                    error_info=error_info,
                    capture_output=True,
                    generators_span_to_end=span_to_end,
                    generators_trace_to_end=trace_to_end,
                )

    generator = wrapper()
    finalizer = weakref.finalize(
        generator,
        _end_abandoned_stream,
        state,
        span_to_end,
        trace_to_end,
        finally_callback,
    )
    return generator
