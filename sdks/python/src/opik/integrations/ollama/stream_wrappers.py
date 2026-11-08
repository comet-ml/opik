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
    """End the span of a stream that is collected before it finishes.

    A generator's body, and so its ``finally``, does not run until the first
    ``next()``. The span is already popped from the decorator by the time this
    wrapper is built, so a stream that is created and then dropped would leave
    it open for good. Registered with ``weakref.finalize``, the same way the
    LiteLLM integration does it.

    CPython clears a generator's weakrefs *before* running its close, for sync
    and async alike, so this fires first whenever a suspended stream is
    collected. It therefore has to report the outcome itself rather than defer
    to the body. Skipping it for a started stream leaves nothing to end the
    span when the body's ``finally`` never runs at all, which is what happens
    to an async generator on an event loop closed without
    ``shutdown_asyncgens()``.

    A started stream that is collected was abandoned part-way, so it is
    reported as terminated early. One that never started is reported without an
    error: nothing went wrong, it was simply dropped before it produced
    anything. The shared one-shot flag means the body's later ``finally``, when
    it does run, will not report a second time.
    """
    if state["finished"]:
        return
    state["finished"] = True
    error_info: Optional[ErrorInfoDict] = (
        error_info_collector.collect(
            GeneratorExit("stream was abandoned before it finished")
        )
        if state["started"]
        else None
    )
    finally_callback(
        output=None,
        error_info=error_info,
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
    """Wrap the generator ``Client.chat(stream=True)`` returns.

    Ollama hands back a plain generator rather than a stream object, so this
    wraps the iterator itself. Nothing is patched on a class, which keeps the
    per-call aggregator and callback where they belong -- on this closure -- and
    leaves untracked streams untouched.
    """

    finalizer: Optional[weakref.finalize] = None
    state: Dict[str, bool] = {"started": False, "finished": False}

    def wrapper() -> Iterator[Any]:
        accumulated_items: List[Any] = []
        error_info: Optional[ErrorInfoDict] = None
        completed = False
        state["started"] = True
        try:
            for item in stream:
                accumulated_items.append(item)
                yield item
            completed = True
        except Exception as exception:
            LOGGER.debug(
                "Exception raised from ollama stream: %s",
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
                "ollama stream terminated early: %s",
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
        error_info: Optional[ErrorInfoDict] = None
        completed = False
        state["started"] = True
        try:
            async for item in stream:
                accumulated_items.append(item)
                yield item
            completed = True
        except Exception as exception:
            LOGGER.debug(
                "Exception raised from ollama async stream: %s",
                str(exception),
                exc_info=True,
            )
            error_info = error_info_collector.collect(exception)
            raise exception
        except BaseException as exception:
            # asyncio.CancelledError and GeneratorExit are BaseException, not
            # Exception. Without this a cancelled or abandoned stream reaches
            # `finally` with error_info unset and its partial aggregate is
            # recorded as a completed response.
            LOGGER.debug(
                "ollama async stream terminated early: %s",
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
