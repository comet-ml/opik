import logging
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

from opik import context_storage, logging_messages
from opik.api_objects import span, trace
from opik.types import DistributedTraceHeadersDict, ErrorInfoDict

from . import arguments_helpers, error_info_collector, span_creation_handler

LOGGER = logging.getLogger(__name__)


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

    def _ensure_span_and_trace_created(self) -> None:
        if self._created_span_data is not None:
            return

        self._created_trace_data, self._created_span_data = (
            span_creation_handler.create_span_respecting_context(
                self._start_span_arguments, self._opik_distributed_trace_headers
            )
        )

    def _handle_stop_iteration_before_raising(self) -> None:
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

    def _handle_generator_exception_before_raising(self, exception: Exception) -> None:
        LOGGER.debug(
            "Exception raised from tracked generator",
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

    def __aiter__(self) -> "AsyncTrackedGenerator":
        return self

    async def __anext__(self) -> YieldType:
        try:
            self._ensure_span_and_trace_created()
            assert self._created_span_data is not None

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
