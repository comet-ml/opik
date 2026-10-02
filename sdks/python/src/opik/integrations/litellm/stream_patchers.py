import logging
import threading
import weakref
from typing import Any, AsyncIterator, Callable, Iterator, List, Optional, TypeVar

from opik.api_objects import trace, span
from opik.decorator import generator_wrappers, error_info_collector
from litellm.litellm_core_utils import streaming_handler


LOGGER = logging.getLogger(__name__)

StreamItem = TypeVar("StreamItem")
AggregatedResult = TypeVar("AggregatedResult")

_original_iter = streaming_handler.CustomStreamWrapper.__iter__
_original_aiter = streaming_handler.CustomStreamWrapper.__aiter__
_original_next = streaming_handler.CustomStreamWrapper.__next__
_original_anext = streaming_handler.CustomStreamWrapper.__anext__
# Older LiteLLM versions have no `aclose()`.
_original_aclose = getattr(streaming_handler.CustomStreamWrapper, "aclose", None)

_STATE_ATTRIBUTE = "_opik_stream_state"


class _TrackedStreamState:
    """Everything Opik needs to end the span of one tracked stream.

    Kept off the stream itself so that ending the span never needs the stream
    object, and so the span is ended at most once whichever path gets there
    first.
    """

    def __init__(
        self,
        span_to_end: span.SpanData,
        trace_to_end: Optional[trace.TraceData],
        generations_aggregator: Callable[[List[Any]], Any],
        finally_callback: generator_wrappers.FinishGeneratorCallback,
    ) -> None:
        self.span_to_end = span_to_end
        self.trace_to_end = trace_to_end
        self.generations_aggregator = generations_aggregator
        self.finally_callback = finally_callback
        self.items: List[Any] = []
        self.finished = False
        self.finalizer: Optional[weakref.finalize] = None
        self._lock = threading.Lock()

    def finish(
        self,
        error_info: Optional[error_info_collector.ErrorInfoDict] = None,
        completed: bool = True,
    ) -> None:
        with self._lock:
            if self.finished:
                return
            self.finished = True

        if self.finalizer is not None:
            self.finalizer.detach()

        output = None
        if error_info is None:
            try:
                output = self._build_output(completed)
            except Exception:
                # Whatever went wrong, the span must still be ended.
                LOGGER.error(
                    "Failed to build the output of a LiteLLM stream", exc_info=True
                )

        self.items = []
        self.finally_callback(
            output=output,
            error_info=error_info,
            capture_output=True,
            generators_span_to_end=self.span_to_end,
            generators_trace_to_end=self.trace_to_end,
        )

    def finish_abandoned(self) -> None:
        """End the span of a stream the caller stopped reading early."""
        self.finish(completed=False)

    def _build_output(self, completed: bool) -> Any:
        output = self.generations_aggregator(self.items)
        if completed:
            return output

        # Ends up in the span metadata: everything outside `choices` does.
        if output is None:
            return {"stream_completed": False}
        output.stream_completed = False
        if not _received_finish_reason(self.items):
            # LiteLLM fills in "stop" when no chunk carried one.
            for choice in output.choices:
                choice.finish_reason = None
        return output


def _received_finish_reason(items: List[Any]) -> bool:
    return any(
        getattr(choice, "finish_reason", None)
        for item in items
        for choice in (getattr(item, "choices", None) or [])
    )


def _get_state(stream: Any) -> Optional[_TrackedStreamState]:
    return getattr(stream, _STATE_ATTRIBUTE, None)


def _next_wrapper(self: streaming_handler.CustomStreamWrapper) -> Any:
    state = _get_state(self)
    if state is None or state.finished:
        return _original_next(self)

    try:
        item = _original_next(self)
    except StopIteration:
        state.finish()
        raise
    except BaseException as exception:
        # BaseException too: a timeout or cancellation (CancelledError) and a
        # KeyboardInterrupt end the call just as surely as a provider error.
        LOGGER.debug(
            "Exception raised from LiteLLM stream: %s",
            str(exception),
            exc_info=True,
        )
        state.finish(error_info=error_info_collector.collect(exception))
        raise

    state.items.append(item)
    return item


async def _anext_wrapper(self: streaming_handler.CustomStreamWrapper) -> Any:
    state = _get_state(self)
    if state is None or state.finished:
        return await _original_anext(self)

    try:
        item = await _original_anext(self)
    except StopAsyncIteration:
        state.finish()
        raise
    except BaseException as exception:
        LOGGER.debug(
            "Exception raised from LiteLLM async stream: %s",
            str(exception),
            exc_info=True,
        )
        state.finish(error_info=error_info_collector.collect(exception))
        raise

    state.items.append(item)
    return item


def _iterate_until_closed(
    stream: streaming_handler.CustomStreamWrapper, state: _TrackedStreamState
) -> Iterator[Any]:
    # The `for` loop holds only this generator, so a `break`, a `return` or an
    # exception in the loop body closes it right away and runs the `finally`.
    try:
        while True:
            try:
                item = stream.__next__()
            except StopIteration:
                return
            yield item
    finally:
        state.finish_abandoned()


async def _aiterate_until_closed(
    stream: streaming_handler.CustomStreamWrapper, state: _TrackedStreamState
) -> AsyncIterator[Any]:
    try:
        while True:
            try:
                item = await stream.__anext__()
            except StopAsyncIteration:
                return
            yield item
    finally:
        state.finish_abandoned()


def _iter_wrapper(self: streaming_handler.CustomStreamWrapper) -> Iterator[Any]:
    state = _get_state(self)
    if state is None:
        return _original_iter(self)
    return _iterate_until_closed(self, state)


def _aiter_wrapper(self: streaming_handler.CustomStreamWrapper) -> AsyncIterator[Any]:
    state = _get_state(self)
    if state is None:
        return _original_aiter(self)
    return _aiterate_until_closed(self, state)


async def _aclose_wrapper(self: streaming_handler.CustomStreamWrapper) -> None:
    assert _original_aclose is not None
    try:
        await _original_aclose(self)
    finally:
        state = _get_state(self)
        if state is not None:
            state.finish_abandoned()


def _patch_stream_class() -> None:
    streaming_handler.CustomStreamWrapper.__iter__ = _iter_wrapper  # type: ignore[method-assign]
    streaming_handler.CustomStreamWrapper.__aiter__ = _aiter_wrapper  # type: ignore[method-assign]
    streaming_handler.CustomStreamWrapper.__next__ = _next_wrapper  # type: ignore[method-assign]
    streaming_handler.CustomStreamWrapper.__anext__ = _anext_wrapper  # type: ignore[method-assign]
    if _original_aclose is not None:
        streaming_handler.CustomStreamWrapper.aclose = _aclose_wrapper  # type: ignore[method-assign]


def patch_stream(
    stream: streaming_handler.CustomStreamWrapper,
    span_to_end: span.SpanData,
    trace_to_end: Optional[trace.TraceData],
    generations_aggregator: Callable[[List[StreamItem]], Optional[AggregatedResult]],
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> streaming_handler.CustomStreamWrapper:
    _patch_stream_class()
    state = _TrackedStreamState(
        span_to_end, trace_to_end, generations_aggregator, finally_callback
    )
    setattr(stream, _STATE_ATTRIBUTE, state)
    # Backstop for a stream read with `next()` or never read at all, then
    # dropped: end the span when the stream is collected, or at interpreter
    # exit. The finalizer holds the state only, never the stream.
    state.finalizer = weakref.finalize(stream, state.finish_abandoned)
    return stream
