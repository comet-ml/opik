import abc
import functools
import inspect
import logging
from typing import (
    Any,
    Callable,
    Dict,
    List,
    Optional,
    Set,
    Tuple,
    Union,
    NamedTuple,
)

from .. import context_storage, logging_messages, tracing_runtime_config
from ..api_objects import opik_client, span, trace
from ..types import DistributedTraceHeadersDict, ErrorInfoDict, SpanType
from . import (
    arguments_helpers,
    error_info_collector,
    generator_wrappers,
    inspect_helpers,
    opik_args,
    span_creation_handler,
)

LOGGER = logging.getLogger(__name__)

TRACES_CREATED_BY_DECORATOR: Set[str] = set()


class TrackingStartOptions(NamedTuple):
    start_span_parameters: arguments_helpers.StartSpanParameters
    opik_args: Optional[opik_args.OpikArgs]
    opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict]


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

            Tracing is checked only once at the start of the call; a call that
            began while tracing was enabled will still be logged even if
            tracing is disabled before it returns.
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
        """
        Tracking strategies:

            * Regular sync and async functions/methods: start the span when the
        function is called, end the span when the function is finished. While the
        function is working, the span is kept in opik context, so it can be a parent for the
        spans created by nested tracked functions.

            * Generators and async generators: start the span when the generator started
        yielding values, end the trace when the generator finished yielding values.
        Span is kept in the opik context only while __next__ or __anext__ method is working.
        It means that the span can be a parent only for spans created by tracked functions
        called inside __next__ or __anext__.

            * Sync and async functions that return a stream or stream manager object
        recognizable by `_streams_handler`: span is started when the function is called,
        finished when the stream chunks are exhausted. Span is NOT kept inside the opik context.
        So these spans can't be parents for other spans. This is usually the case LLM API calls
        with `stream=True`.
        """
        # Idempotency: skip re-decoration if already tracked
        if hasattr(func, "opik_tracked") and func.opik_tracked:  # type: ignore
            return func

        if inspect.isgeneratorfunction(func):
            return self._tracked_sync_generator(func=func, track_options=track_options)

        if inspect.isasyncgenfunction(func):
            return self._tracked_async_generator(
                func=func,
                track_options=track_options,
            )

        if inspect_helpers.is_async(func):
            return self._tracked_async(
                func=func,
                track_options=track_options,
            )

        return self._tracked_sync(
            func=func,
            track_options=track_options,
        )

    def _prepare_tracking_start_options(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> TrackingStartOptions:
        opik_distributed_trace_headers = (
            arguments_helpers.extract_distributed_trace_headers(kwargs)
        )

        opik_args_ = None
        try:
            opik_args_ = opik_args.extract_opik_args(kwargs, func)

            start_span_arguments = self._start_span_inputs_preprocessor(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )

            # Apply opik_args to start span arguments
            start_span_arguments = opik_args.apply_opik_args_to_start_span_params(
                params=start_span_arguments,
                opik_args=opik_args_,
            )
        except Exception as exception:
            LOGGER.error(
                logging_messages.UNEXPECTED_EXCEPTION_ON_SPAN_CREATION_FOR_TRACKED_FUNCTION,
                inspect_helpers.get_function_name(func),
                (args, kwargs),
                str(exception),
                exc_info=True,
            )

            start_span_arguments = arguments_helpers.StartSpanParameters(
                name=inspect_helpers.get_function_name(func),
                type=track_options.type,
                tags=track_options.tags,
                metadata=track_options.metadata,
                project_name=track_options.project_name,
            )

        return TrackingStartOptions(
            start_span_arguments, opik_args_, opik_distributed_trace_headers
        )

    def _tracked_sync_generator(
        self, func: Callable, track_options: arguments_helpers.TrackOptions
    ) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            if not tracing_runtime_config.is_tracing_active():
                return func(*args, **kwargs)

            track_start_options = self._prepare_tracking_start_options(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )

            try:
                result = generator_wrappers.SyncTrackedGenerator(
                    func(*args, **kwargs),
                    start_span_arguments=track_start_options.start_span_parameters,
                    opik_distributed_trace_headers=track_start_options.opik_distributed_trace_headers,
                    track_options=track_options,
                    finally_callback=self._after_call,
                )
                return result
            except Exception as exception:
                LOGGER.debug(
                    logging_messages.EXCEPTION_RAISED_FROM_TRACKED_FUNCTION,
                    inspect_helpers.get_function_name(func),
                    (args, kwargs),
                    str(exception),
                    exc_info=True,
                )
                raise exception

        wrapper.opik_tracked = True  # type: ignore

        return wrapper

    def _tracked_async_generator(
        self, func: Callable, track_options: arguments_helpers.TrackOptions
    ) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            if not tracing_runtime_config.is_tracing_active():
                return func(*args, **kwargs)

            track_start_options = self._prepare_tracking_start_options(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )

            try:
                result = generator_wrappers.AsyncTrackedGenerator(
                    func(*args, **kwargs),
                    start_span_arguments=track_start_options.start_span_parameters,
                    opik_distributed_trace_headers=track_start_options.opik_distributed_trace_headers,
                    track_options=track_options,
                    finally_callback=self._after_call,
                )
                return result
            except Exception as exception:
                LOGGER.debug(
                    logging_messages.EXCEPTION_RAISED_FROM_TRACKED_FUNCTION,
                    inspect_helpers.get_function_name(func),
                    (args, kwargs),
                    str(exception),
                    exc_info=True,
                )
                raise exception

        wrapper.opik_tracked = True  # type: ignore

        return wrapper

    def _tracked_sync(
        self, func: Callable, track_options: arguments_helpers.TrackOptions
    ) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            if not tracing_runtime_config.is_tracing_active():
                return func(*args, **kwargs)
            self._before_call(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )

            result = None
            error_info: Optional[ErrorInfoDict] = None
            func_exception = None
            try:
                result = func(*args, **kwargs)
            except Exception as exception:
                LOGGER.debug(
                    logging_messages.EXCEPTION_RAISED_FROM_TRACKED_FUNCTION,
                    inspect_helpers.get_function_name(func),
                    (args, kwargs),
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                func_exception = exception

            stream_or_stream_manager = self._streams_handler(
                result,
                track_options.capture_output,
                track_options.generations_aggregator,
            )
            if stream_or_stream_manager is not None:
                return stream_or_stream_manager

            self._after_call(
                output=result,
                error_info=error_info,
                capture_output=track_options.capture_output,
                flush=track_options.flush,
            )
            if func_exception is not None:
                raise func_exception
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
            if not tracing_runtime_config.is_tracing_active():
                return await func(*args, **kwargs)
            self._before_call(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )
            result = None
            error_info: Optional[ErrorInfoDict] = None
            func_exception = None
            try:
                result = await func(*args, **kwargs)
            except Exception as exception:
                LOGGER.debug(
                    logging_messages.EXCEPTION_RAISED_FROM_TRACKED_FUNCTION,
                    inspect_helpers.get_function_name(func),
                    (args, kwargs),
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                func_exception = exception

            stream_or_stream_manager = self._streams_handler(
                result,
                track_options.capture_output,
                track_options.generations_aggregator,
            )
            if stream_or_stream_manager is not None:
                return stream_or_stream_manager

            self._after_call(
                output=result,
                error_info=error_info,
                capture_output=track_options.capture_output,
                flush=track_options.flush,
            )
            if func_exception is not None:
                raise func_exception
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
            self.__before_call_unsafe(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )
        except Exception as exception:
            LOGGER.error(
                logging_messages.UNEXPECTED_EXCEPTION_ON_SPAN_CREATION_FOR_TRACKED_FUNCTION,
                inspect_helpers.get_function_name(func),
                (args, kwargs),
                str(exception),
                exc_info=True,
            )

    def __before_call_unsafe(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> None:
        track_start_options = self._prepare_tracking_start_options(
            func=func,
            track_options=track_options,
            args=args,
            kwargs=kwargs,
        )

        add_start_candidates(
            start_span_parameters=track_start_options.start_span_parameters,
            opik_distributed_trace_headers=track_start_options.opik_distributed_trace_headers,
            opik_args_data=track_start_options.opik_args,
            tracing_active=tracing_runtime_config.is_tracing_active(),
        )

    def _after_call(
        self,
        output: Optional[Any],
        error_info: Optional[ErrorInfoDict],
        capture_output: bool,
        generators_span_to_end: Optional[span.SpanData] = None,
        generators_trace_to_end: Optional[trace.TraceData] = None,
        flush: bool = False,
    ) -> None:
        try:
            self.__after_call_unsafe(
                output=output,
                error_info=error_info,
                capture_output=capture_output,
                generators_span_to_end=generators_span_to_end,
                generators_trace_to_end=generators_trace_to_end,
                flush=flush,
            )
        except Exception as exception:
            LOGGER.error(
                logging_messages.UNEXPECTED_EXCEPTION_ON_SPAN_FINALIZATION_FOR_TRACKED_FUNCTION,
                output,
                str(exception),
                exc_info=True,
            )

    def __after_call_unsafe(
        self,
        output: Optional[Any],
        error_info: Optional[ErrorInfoDict],
        capture_output: bool,
        generators_span_to_end: Optional[span.SpanData] = None,
        generators_trace_to_end: Optional[trace.TraceData] = None,
        flush: bool = False,
    ) -> None:
        if generators_span_to_end is None:
            span_data_to_end, trace_data_to_end = pop_end_candidates()
        else:
            span_data_to_end, trace_data_to_end = (
                generators_span_to_end,
                generators_trace_to_end,
            )

        if output is not None:
            try:
                end_arguments = self._end_span_inputs_preprocessor(
                    output=output,
                    capture_output=capture_output,
                    current_span_data=span_data_to_end,
                )
            except Exception as e:
                LOGGER.error(
                    logging_messages.UNEXPECTED_EXCEPTION_ON_SPAN_FINALIZATION_FOR_TRACKED_FUNCTION,
                    output,
                    str(e),
                    exc_info=True,
                )

                end_arguments = arguments_helpers.EndSpanParameters(
                    output={"output": output}
                )
        else:
            end_arguments = arguments_helpers.EndSpanParameters(error_info=error_info)

        client = opik_client.get_client_cached()

        span_data_to_end.init_end_time().update(
            **end_arguments.to_kwargs(),
        )

        client.span(**span_data_to_end.as_parameters)

        if trace_data_to_end is not None:
            trace_data_to_end.init_end_time().update(
                **end_arguments.to_kwargs(ignore_keys=["usage", "model", "provider"]),
            )

            client.trace(**trace_data_to_end.as_parameters)

        if flush:
            client.flush()

    @abc.abstractmethod
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Any]:
        """
        Subclasses must override this method to customize stream-like objects handling.
        Stream objects are usually the objects returned by LLM providers when invoking their API with
        `stream=True` option.

        Opik's approach for such stream objects is to start the span when the API call is made and
        finish the span when the stream chunks are exhausted.
        """

        NO_STREAM_DETECTED = None

        return NO_STREAM_DETECTED

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
        current_span_data: span.SpanData,
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
    they are no longer in the context stack.
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


def add_start_candidates(
    start_span_parameters: arguments_helpers.StartSpanParameters,
    opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict],
    opik_args_data: Optional[opik_args.OpikArgs],
    tracing_active: bool,
) -> span_creation_handler.SpanCreationResult:
    """
    Handles the creation and registration of a new start span and trace while respecting the
    tracing context based on given parameters. It also applies relevant arguments
    to the trace if it was created and handles client logging if the tracing is active.

    Args:
        start_span_parameters: The parameters used to start the span, including the
            span name and other configurations.
        opik_distributed_trace_headers: Optional headers for distributed tracing, which
            are passed to the span creation process.
        opik_args_data : Optional additional arguments that can be applied to the trace
            data after the span is created.
        tracing_active: A boolean indicating whether a tracing is active.

    Returns:
        The result of the span creation, including the span and trace data.
    """
    span_creation_result = span_creation_handler.create_span_respecting_context(
        start_span_arguments=start_span_parameters,
        distributed_trace_headers=opik_distributed_trace_headers,
    )
    context_storage.add_span_data(span_creation_result.span_data)

    if tracing_active:
        client = opik_client.get_client_cached()

        if client.config.log_start_trace_span:
            client.span(**span_creation_result.span_data.as_start_parameters)

    if span_creation_result.trace_data is not None:
        add_start_trace_candidate(
            trace_data=span_creation_result.trace_data,
            opik_args_data=opik_args_data,
            tracing_active=tracing_active,
        )

    return span_creation_result


def add_start_trace_candidate(
    trace_data: trace.TraceData,
    opik_args_data: Optional[opik_args.OpikArgs],
    tracing_active: bool,
) -> None:
    """
    Adds a start trace candidate to the current context storage and updates
    it with the given Opik arguments if applicable.

    This function initializes the trace data in the current context and
    tracks its creation. It also applies provided Opik argument modifications
    to the trace and logs the start trace span in the client if tracing is
    active and logging is enabled.

    Args:
        trace_data: The trace data object to be added and initialized in the
            current context storage. It contains details about the trace.
        opik_args_data: Optional OpikArgs object containing additional data
            to be applied to the trace. This may include configurations
            that modify or enrich the trace data.
        tracing_active: A boolean indicating whether a tracing is active.
    """
    context_storage.set_trace_data(trace_data)
    TRACES_CREATED_BY_DECORATOR.add(trace_data.id)

    # Handle thread_id and trace updates after span/trace creation
    opik_args.apply_opik_args_to_trace(opik_args=opik_args_data, trace_data=trace_data)

    if not tracing_active:
        return

    client = opik_client.get_client_cached()
    if client.config.log_start_trace_span:
        client.trace(**trace_data.as_start_parameters)
