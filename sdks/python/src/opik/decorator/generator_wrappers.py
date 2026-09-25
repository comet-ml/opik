import asyncio
import atexit
import logging
import threading
import weakref
from typing import (
    Any,
    AsyncGenerator,
    Callable,
    Generator,
    List,
    Optional,
    Protocol,
    TypeVar,
    Generic,
)

import opik.context_storage as context_storage
import opik.logging_messages as logging_messages
from opik.api_objects import span, trace
from opik.types import DistributedTraceHeadersDict, ErrorInfoDict

from . import arguments_helpers, error_info_collector, span_creation_handler

LOGGER = logging.getLogger(__name__)

# Wrappers whose span was started but not ended yet. `__del__` does not run for
# objects still alive at interpreter exit, so these are ended from an atexit hook
# that runs before Opik's own exit-time flush.
_UNFINISHED_GENERATORS: "weakref.WeakSet[BaseTrackedGenerator]" = weakref.WeakSet()
_exit_hook_lock = threading.Lock()
_exit_hook_registered = False


YieldType = TypeVar("YieldType")


class FinishGeneratorCallback(Protocol):
    def __call__(
        self,
        output: Any,
        error_info: Optional[ErrorInfoDict],
        capture_output: bool,
        generators_span_to_end: Optional[span.SpanData] = None,
        generators_trace_to_end: Optional[trace.TraceData] = None,
    ) -> None: ...


class BaseTrackedGenerator(Generic[YieldType]):
    def __init__(
        self,
        start_span_arguments: arguments_helpers.StartSpanParameters,
        opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict],
        track_options: arguments_helpers.TrackOptions,
        finally_callback: FinishGeneratorCallback,
    ):
        self._start_span_arguments = start_span_arguments
        self._opik_distributed_trace_headers = opik_distributed_trace_headers
        self._track_options = track_options

        self._created_span_data: Optional[span.SpanData] = None
        self._created_trace_data: Optional[trace.TraceData] = None

        self._accumulated_values: List[YieldType] = []

        self._finally_callback = finally_callback

        # A generator can stop being consumed at any point, and the span must be
        # ended exactly once however that happens: exhaustion, an error, an
        # explicit close, or the consumer simply dropping it.
        self._span_finished = False

    def _ensure_span_and_trace_created(self) -> None:
        if self._created_span_data is not None:
            return

        result = span_creation_handler.create_span_respecting_context(
            self._start_span_arguments, self._opik_distributed_trace_headers
        )

        self._created_trace_data = result.trace_data
        self._created_span_data = result.span_data

        _register_exit_hook()
        _UNFINISHED_GENERATORS.add(self)

    def _mark_span_finished(self) -> bool:
        """Return True only for the first caller, which is the one that ends the span."""
        if self._span_finished:
            return False
        self._span_finished = True
        _UNFINISHED_GENERATORS.discard(self)
        return True

    def _finalize_if_unfinished(self) -> None:
        """End the span of a generator that was never consumed to the end.

        A partially consumed generator never raises `StopIteration`, so nothing else
        ends its span and the work is never reported. What it did yield is recorded,
        since that is what actually happened.

        Does nothing when the generator was never started (no span exists yet) or has
        already been finished.
        """
        if self._created_span_data is None:
            return
        self._end_span_with_yielded_output()

    def _finalize_at_exit(self) -> None:
        self._finalize_if_unfinished()

    def _handle_stop_iteration_before_raising(self) -> None:
        self._end_span_with_yielded_output()

    def _end_span_with_yielded_output(self) -> None:
        if not self._mark_span_finished():
            return

        output = _try_aggregate_items(
            self._accumulated_values,
            generations_aggregator=self._track_options.generations_aggregator,
        )
        self._finally_callback(
            output=output,
            error_info=None,
            capture_output=self._track_options.capture_output,
            generators_span_to_end=self._created_span_data,
            generators_trace_to_end=self._created_trace_data,
        )

    def _handle_generator_exception_before_raising(
        self, exception: BaseException
    ) -> None:
        if not self._mark_span_finished():
            return

        LOGGER.debug(
            "Exception raised from tracked generator: %s",
            str(exception),
            exc_info=True,
        )
        error_info = error_info_collector.collect(exception)
        self._finally_callback(
            output=None,
            error_info=error_info,
            capture_output=self._track_options.capture_output,
            generators_span_to_end=self._created_span_data,
            generators_trace_to_end=self._created_trace_data,
        )


class SyncTrackedGenerator(BaseTrackedGenerator[YieldType]):
    def __init__(
        self,
        generator: Generator[YieldType, None, None],
        start_span_arguments: arguments_helpers.StartSpanParameters,
        opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict],
        track_options: arguments_helpers.TrackOptions,
        finally_callback: FinishGeneratorCallback,
    ) -> None:
        super().__init__(
            start_span_arguments=start_span_arguments,
            opik_distributed_trace_headers=opik_distributed_trace_headers,
            track_options=track_options,
            finally_callback=finally_callback,
        )
        self._generator = generator

    def __iter__(self) -> "SyncTrackedGenerator":
        return self

    def __next__(self) -> YieldType:
        try:
            self._ensure_span_and_trace_created()
            assert self._created_span_data is not None

            with context_storage.temporary_context(
                self._created_span_data, self._created_trace_data
            ):
                value = next(self._generator)
                self._accumulated_values.append(value)
                return value
        except StopIteration:
            self._handle_stop_iteration_before_raising()
            raise
        except Exception as exception:
            self._handle_generator_exception_before_raising(exception)
            raise

    def close(self) -> None:
        """Close the underlying generator and end the span.

        Mirrors `generator.close()`, so `contextlib.closing` and an explicit close
        both end the span of a generator that was not consumed to the end. A failure
        during cleanup is recorded on the span rather than lost behind a span that
        claims to have succeeded.
        """
        try:
            self._generator.close()
        except BaseException as exception:
            self._handle_generator_exception_before_raising(exception)
            raise
        self._finalize_if_unfinished()

    def _finalize_at_exit(self) -> None:
        self.close()

    def __del__(self) -> None:
        # A generator dropped without being exhausted has its `close()` called by the
        # interpreter; this wrapper is a plain iterator, so it has to do the same for
        # itself or the span started in `__next__` is never ended. Going through
        # `close()` also records a failure in the generator's own cleanup.
        # The end time is stamped here, which can be later than when the caller
        # stopped iterating; an explicit `close()` gives an exact one.
        try:
            self.close()
        except Exception:
            LOGGER.debug("Failed to close dropped tracked generator", exc_info=True)


class AsyncTrackedGenerator(BaseTrackedGenerator[YieldType]):
    def __init__(
        self,
        generator: AsyncGenerator[YieldType, None],
        start_span_arguments: arguments_helpers.StartSpanParameters,
        opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict],
        track_options: arguments_helpers.TrackOptions,
        finally_callback: FinishGeneratorCallback,
    ) -> None:
        super().__init__(
            start_span_arguments=start_span_arguments,
            opik_distributed_trace_headers=opik_distributed_trace_headers,
            track_options=track_options,
            finally_callback=finally_callback,
        )
        self._generator = generator
        self._loop: Optional[asyncio.AbstractEventLoop] = None

    def __aiter__(self) -> "AsyncTrackedGenerator":
        return self

    async def __anext__(self) -> YieldType:
        try:
            self._ensure_span_and_trace_created()
            assert self._created_span_data is not None
            if self._loop is None:
                self._loop = asyncio.get_running_loop()

            with context_storage.temporary_context(
                self._created_span_data, self._created_trace_data
            ):
                value = await self._generator.__anext__()
                self._accumulated_values.append(value)
                return value
        except StopAsyncIteration:
            self._handle_stop_iteration_before_raising()
            raise
        except Exception as exception:
            self._handle_generator_exception_before_raising(exception)
            raise

    async def aclose(self) -> None:
        """Close the underlying async generator and end the span.

        As with the sync wrapper, a failure during cleanup is recorded on the span
        instead of being replaced by a successful one.
        """
        try:
            await self._generator.aclose()
        except BaseException as exception:
            self._handle_generator_exception_before_raising(exception)
            raise
        self._finalize_if_unfinished()

    async def _aclose_quietly(self) -> None:
        try:
            await self.aclose()
        except Exception:
            LOGGER.debug("Failed to close dropped tracked generator", exc_info=True)

    def __del__(self) -> None:
        # As asyncio does for a dropped native async generator, schedule `aclose()`
        # on the loop it was iterated on, so the generator's own cleanup runs, and a
        # failure there is recorded, before the span is ended. Without a running
        # loop the generator cannot be closed, so only the span is ended.
        try:
            if self._span_finished or self._created_span_data is None:
                return
            loop = self._loop
            if loop is not None and loop.is_running():
                asyncio.run_coroutine_threadsafe(self._aclose_quietly(), loop)
                return
            self._finalize_if_unfinished()
        except Exception:
            LOGGER.debug("Failed to close dropped tracked generator", exc_info=True)


def _register_exit_hook() -> None:
    global _exit_hook_registered
    if _exit_hook_registered:
        return
    with _exit_hook_lock:
        if _exit_hook_registered:
            return
        # Registered on first use, which is after Opik's own flush hook was
        # registered at import. atexit runs hooks in reverse order, so these spans
        # are ended before that flush.
        atexit.register(_finalize_unfinished_generators)
        _exit_hook_registered = True


def _finalize_unfinished_generators() -> None:
    """End the spans of tracked generators still alive at interpreter exit."""
    for generator in list(_UNFINISHED_GENERATORS):
        try:
            generator._finalize_at_exit()
        except Exception:
            LOGGER.debug("Failed to end span of tracked generator", exc_info=True)


def _try_aggregate_items(
    items: List[Any], generations_aggregator: Optional[Callable[[List[Any]], str]]
) -> str:
    if generations_aggregator is not None:
        try:
            output = generations_aggregator(items)
        except Exception:
            LOGGER.error(
                logging_messages.FAILED_TO_AGGREGATE_GENERATORS_YIELDED_VALUES_WITH_PROVIDED_AGGREGATOR_IN_TRACKED_FUNCTION,
                items,
                generations_aggregator,
                exc_info=True,
            )
            output = str(items)
    else:
        output = "".join([str(item) for item in items])

    return output
