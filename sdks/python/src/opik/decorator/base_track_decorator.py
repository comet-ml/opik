import functools
import logging
import inspect
import abc

from typing import (
    List,
    Any,
    Dict,
    Set,
    Optional,
    Callable,
    Tuple,
    Generator,
    Union,
    AsyncGenerator,
)

from ..types import SpanType, DistributedTraceHeadersDict, ErrorInfoDict
from . import (
    arguments_helpers,
    generator_wrappers,
    inspect_helpers,
    error_info_collector,
)
from ..api_objects import opik_client, helpers, span, trace
from .. import context_storage, logging_messages, datetime_helpers, config

LOGGER = logging.getLogger(__name__)

TRACES_CREATED_BY_DECORATOR: Set[str] = set()


class BaseTrackDecorator(abc.ABC):
    """
    For internal usage.

    All TrackDecorator instances share the same context and can be
    used together simultaneously.

    The following methods must be implemented in the subclass:
        * _start_span_inputs_preprocessor
        * _end_span_inputs_preprocessor
        * _generators_handler (the default implementation is provided but still needs to be called via `super()`)

    Overriding other methods of this class is not recommended.
    """

    def __init__(self) -> None:
        self.provider: Optional[str] = None
        """ Name of the LLM provider. Used in subclasses in integrations track decorators. """

    @functools.cached_property
    def disabled(self) -> bool:
        config_ = config.OpikConfig()
        return config_.track_disable

    def track(
        self,
        name: Optional[Union[Callable, str]] = None,
        type: SpanType = "general",
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        capture_input: bool = True,
        ignore_arguments: Optional[List[str]] = None,
        capture_output: bool = True,
        generations_aggregator: Optional[Callable[[List[Any]], Any]] = None,
        flush: bool = False,
        project_name: Optional[str] = None,
    ) -> Union[Callable, Callable[[Callable], Callable]]:
        """
        Decorator to track the execution of a function.

        Can be used as @track or @track().

        Args:
            name: The name of the span.
            type: The type of the span.
            tags: Tags to associate with the span.
            metadata: Metadata to associate with the span.
            capture_input: Whether to capture the input arguments.
            ignore_arguments: The list of the arguments NOT to include into span/trace inputs.
            capture_output: Whether to capture the output result.
            generations_aggregator: Function to aggregate generation results.
            flush: Whether to flush the client after logging.
            project_name: The name of the project to log data.

        Returns:
            Callable: The decorated function(if used without parentheses)
                or the decorator function (if used with parentheses).

        Note:
            You can use this decorator to track nested functions, Opik will automatically create
            a trace and correctly span nested function calls.

            This decorator can be used to track both synchronous and asynchronous functions,
            and also synchronous and asynchronous generators.
            It automatically detects the function type and applies the appropriate tracking logic.
        """
        track_options = arguments_helpers.TrackOptions(
            name=None,
            type=type,
            tags=tags,
            metadata=metadata,
            capture_input=capture_input,
            ignore_arguments=ignore_arguments,
            capture_output=capture_output,
            generations_aggregator=generations_aggregator,
            flush=flush,
            project_name=project_name,
        )

        if callable(name):
            # Decorator was used without '()'. It means that decorated function
            # automatically passed as the first argument of 'track' function - name
            func = name
            return self._decorate(
                func=func,
                track_options=track_options,
            )

        track_options.name = name

        def decorator(func: Callable) -> Callable:
            return self._decorate(
                func=func,
                track_options=track_options,
            )

        return decorator

    def _decorate(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
    ) -> Callable:
        if not inspect_helpers.is_async(func):
            return self._tracked_sync(
                func=func,
                track_options=track_options,
            )

        return self._tracked_async(
            func=func,
            track_options=track_options,
        )

    def _tracked_sync(
        self, func: Callable, track_options: arguments_helpers.TrackOptions
    ) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            self._before_call(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )

            result = None
            error_info: Optional[ErrorInfoDict] = None
            try:
                result = func(*args, **kwargs)
            except Exception as exception:
                LOGGER.debug(
                    logging_messages.EXCEPTION_RAISED_FROM_TRACKED_FUNCTION,
                    func.__name__,
                    (args, kwargs),
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                raise exception
            finally:
                generator_or_generator_container = self._generators_handler(
                    result,
                    track_options.capture_output,
                    track_options.generations_aggregator,
                )
                if generator_or_generator_container is not None:
                    return generator_or_generator_container

                self._after_call(
                    output=result,
                    error_info=error_info,
                    capture_output=track_options.capture_output,
                    flush=track_options.flush,
                )
                if result is not None:
                    return result

        wrapper.opik_tracked = True  # type: ignore

        return wrapper

    def _tracked_async(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
    ) -> Callable:
        @functools.wraps(func)
        async def wrapper(*args, **kwargs) -> Any:  # type: ignore
            self._before_call(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )
            result = None
            error_info: Optional[ErrorInfoDict] = None
            try:
                result = await func(*args, **kwargs)
            except Exception as exception:
                LOGGER.error(
                    logging_messages.EXCEPTION_RAISED_FROM_TRACKED_FUNCTION,
                    func.__name__,
                    (args, kwargs),
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                raise exception
            finally:
                generator = self._generators_handler(
                    result,
                    track_options.capture_output,
                    track_options.generations_aggregator,
                )
                if generator is not None:  # TODO: test this flow for async generators
                    return generator

                self._after_call(
                    output=result,
                    error_info=error_info,
                    capture_output=track_options.capture_output,
                    flush=track_options.flush,
                )
                if result is not None:
                    return result

        wrapper.opik_tracked = True  # type: ignore
        return wrapper

    def _before_call(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> None:
        try:
            opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict] = (
                kwargs.pop("opik_distributed_trace_headers", None)
            )

            start_span_arguments = self._start_span_inputs_preprocessor(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )

            self._create_span(
                start_span_arguments,
                opik_distributed_trace_headers,
            )

        except Exception as exception:
            LOGGER.error(
                logging_messages.UNEXPECTED_EXCEPTION_ON_SPAN_CREATION_FOR_TRACKED_FUNCTION,
                func.__name__,
                (args, kwargs),
                str(exception),
                exc_info=True,
            )

    def _create_span(
        self,
        start_span_arguments: arguments_helpers.StartSpanParameters,
        distributed_trace_headers: Optional[DistributedTraceHeadersDict] = None,
    ) -> None:
        """
        Handles different span creation flows.
        """
        span_data: span.SpanData
        trace_data: trace.TraceData

        if distributed_trace_headers:
            span_data = arguments_helpers.create_span_data(
                start_span_arguments=start_span_arguments,
                parent_span_id=distributed_trace_headers["opik_parent_span_id"],
                trace_id=distributed_trace_headers["opik_trace_id"],
            )
            context_storage.add_span_data(span_data)
            return

        current_span_data = context_storage.top_span_data()
        current_trace_data = context_storage.get_trace_data()

        if current_span_data is not None:
            # There is already at least one span in current context.
            # Simply attach a new span to it.
            assert current_trace_data is not None

            project_name = helpers.resolve_child_span_project_name(
                parent_project_name=current_span_data.project_name,
                child_project_name=start_span_arguments.project_name,
                show_warning=current_trace_data.created_by != "evaluation",
            )

            start_span_arguments.project_name = project_name

            span_data = arguments_helpers.create_span_data(
                start_span_arguments=start_span_arguments,
                parent_span_id=current_span_data.id,
                trace_id=current_span_data.trace_id,
            )
            context_storage.add_span_data(span_data)
            return

        if current_trace_data is not None and current_span_data is None:
            # By default, we expect trace to be created with a span.
            # But there can be cases when trace was created and added
            # to context manually (not via decorator).
            # In that case decorator should just create a span for the existing trace.

            project_name = helpers.resolve_child_span_project_name(
                parent_project_name=current_trace_data.project_name,
                child_project_name=start_span_arguments.project_name,
                show_warning=current_trace_data.created_by != "evaluation",
            )

            start_span_arguments.project_name = project_name

            span_data = arguments_helpers.create_span_data(
                start_span_arguments=start_span_arguments,
                parent_span_id=None,
                trace_id=current_trace_data.id,
            )
            context_storage.add_span_data(span_data)
            return

        if current_span_data is None and current_trace_data is None:
            # Create a trace and root span because it is
            # the first decorated function run in current context.
            trace_data = trace.TraceData(
                id=helpers.generate_id(),
                start_time=datetime_helpers.local_timestamp(),
                name=start_span_arguments.name,
                input=start_span_arguments.input,
                metadata=start_span_arguments.metadata,
                tags=start_span_arguments.tags,
                project_name=start_span_arguments.project_name,
            )
            TRACES_CREATED_BY_DECORATOR.add(trace_data.id)

            span_data = arguments_helpers.create_span_data(
                start_span_arguments=start_span_arguments,
                parent_span_id=None,
                trace_id=trace_data.id,
            )

            context_storage.set_trace_data(trace_data)
            context_storage.add_span_data(span_data)
            return

    def _after_call(
        self,
        output: Optional[Any],
        error_info: Optional[ErrorInfoDict],
        capture_output: bool,
        generators_span_to_end: Optional[span.SpanData] = None,
        generators_trace_to_end: Optional[trace.TraceData] = None,
        flush: bool = False,
    ) -> None:
        if self.disabled:
            return

        try:
            if output is not None:
                end_arguments = self._end_span_inputs_preprocessor(
                    output=output,
                    capture_output=capture_output,
                )
            else:
                end_arguments = arguments_helpers.EndSpanParameters(
                    error_info=error_info
                )

            if generators_span_to_end is None:
                span_data_to_end, trace_data_to_end = pop_end_candidates()
            else:
                span_data_to_end, trace_data_to_end = (
                    generators_span_to_end,
                    generators_trace_to_end,
                )

            client = opik_client.get_client_cached()

            span_data_to_end.init_end_time().update(
                **end_arguments.to_kwargs(),
            )

            client.span(**span_data_to_end.__dict__)

            if trace_data_to_end is not None:
                trace_data_to_end.init_end_time().update(
                    **end_arguments.to_kwargs(
                        ignore_keys=["usage", "model", "provider"]
                    ),
                )

                client.trace(**trace_data_to_end.__dict__)

            if flush:
                client.flush()

        except Exception as exception:
            LOGGER.error(
                logging_messages.UNEXPECTED_EXCEPTION_ON_SPAN_FINALIZATION_FOR_TRACKED_FUNCTION,
                output,
                str(exception),
                exc_info=True,
            )

    @abc.abstractmethod
    def _generators_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Union[Generator, AsyncGenerator]]:
        """
        Subclasses must override this method to customize generator objects handling
        This is the implementation for regular generators and async generators that
        uses aggregator function passed to track.

        However, sometimes the function might return an instance of some specific class which
        is not a python generator itself, but implements some API for iterating through data chunks.
        In that case `_generators_handler` must be fully overridden in the subclass.

        This is usually the case when creating an integration with some LLM library.
        """
        if inspect.isgenerator(output):
            span_to_end, trace_to_end = pop_end_candidates()
            # For some reason mypy things wrap_sync_generator returns Any
            return generator_wrappers.wrap_sync_generator(  # type: ignore[no-any-return]
                generator=output,
                capture_output=capture_output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        if inspect.isasyncgen(output):
            span_to_end, trace_to_end = pop_end_candidates()
            # For some reason mypy things wrap_async_generator returns Any
            return generator_wrappers.wrap_async_generator(  # type: ignore[no-any-return]
                generator=output,
                capture_output=capture_output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        NOT_A_GENERATOR = None

        return NOT_A_GENERATOR

    @abc.abstractmethod
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        """
        Subclasses must override this method to customize generating
        span/trace parameters from the function input arguments
        """
        pass

    @abc.abstractmethod
    def _end_span_inputs_preprocessor(
        self,
        output: Optional[Any],
        capture_output: bool,
    ) -> arguments_helpers.EndSpanParameters:
        """
        Subclasses must override this method to customize generating
        span/trace parameters from the function return value
        """
        pass


def pop_end_candidates() -> Tuple[span.SpanData, Optional[trace.TraceData]]:
    """
    Pops span and trace (if trace exists) data created by @track decorator
    from the current context, returns popped objects.

    Decorator can't attach any child objects to the popped ones because
    they are no longer in context stack.
    """
    span_data_to_end = context_storage.pop_span_data()
    assert (
        span_data_to_end is not None
    ), "When pop_end_candidates is called, top span data must not be None. Otherwise something is wrong."

    trace_data_to_end = None

    possible_trace_data_to_end = context_storage.get_trace_data()
    if (
        context_storage.span_data_stack_empty()
        and possible_trace_data_to_end is not None
        and possible_trace_data_to_end.id in TRACES_CREATED_BY_DECORATOR
    ):
        trace_data_to_end = context_storage.pop_trace_data()
        TRACES_CREATED_BY_DECORATOR.discard(possible_trace_data_to_end.id)

    return span_data_to_end, trace_data_to_end
