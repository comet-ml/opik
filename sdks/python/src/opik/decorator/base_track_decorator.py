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
from ..types import SpanType, DistributedTraceHeadersDict
from . import arguments_helpers, generator_wrappers, inspect_helpers
from ..api_objects import opik_client, helpers, span, trace
from .. import context_storage, logging_messages, datetime_helpers

LOGGER = logging.getLogger(__name__)

TRACES_CREATED_BY_DECORATOR: Set[str] = set()


class BaseTrackDecorator(abc.ABC):
    """
    For internal usage.

    All TrackDecorator instances share the same context and can be
    used together simultaneously.
    """

    def track(
        self,
        name: Optional[Union[Callable, str]] = None,
        type: SpanType = "general",
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        capture_input: bool = True,
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
        if callable(name):
            # Decorator was used without '()'. It means that decorated function
            # automatically passed as the first argument of 'track' function - name
            func = name
            return self._decorate(
                func=func,
                name=None,
                type=type,
                tags=tags,
                metadata=metadata,
                capture_input=capture_input,
                capture_output=capture_output,
                generations_aggregator=generations_aggregator,
                flush=flush,
                project_name=project_name,
            )

        def decorator(func: Callable) -> Callable:
            return self._decorate(
                func=func,
                name=name,
                type=type,
                tags=tags,
                metadata=metadata,
                capture_input=capture_input,
                capture_output=capture_output,
                generations_aggregator=generations_aggregator,
                flush=flush,
                project_name=project_name,
            )

        return decorator

    def _decorate(
        self,
        func: Callable,
        name: Optional[str],
        type: SpanType,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        capture_input: bool,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
        flush: bool,
        project_name: Optional[str],
    ) -> Callable:
        if not inspect_helpers.is_async(func):
            return self._tracked_sync(
                func=func,
                name=name,
                type=type,
                tags=tags,
                metadata=metadata,
                capture_input=capture_input,
                capture_output=capture_output,
                generations_aggregator=generations_aggregator,
                flush=flush,
                project_name=project_name,
            )

        return self._tracked_async(
            func=func,
            name=name,
            type=type,
            tags=tags,
            metadata=metadata,
            capture_input=capture_input,
            capture_output=capture_output,
            generations_aggregator=generations_aggregator,
            flush=flush,
            project_name=project_name,
        )

    def _tracked_sync(
        self,
        func: Callable,
        name: Optional[str],
        type: SpanType,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        capture_input: bool,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
        flush: bool,
        project_name: Optional[str],
    ) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            self._before_call(
                func=func,
                name=name,
                type=type,
                tags=tags,
                metadata=metadata,
                capture_input=capture_input,
                project_name=project_name,
                args=args,
                kwargs=kwargs,
            )

            result = None
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
                raise exception
            finally:
                generator = self._generators_handler(
                    result,
                    capture_output,
                    generations_aggregator,
                )
                if generator is not None:
                    return generator

                self._after_call(
                    output=result,
                    capture_output=capture_output,
                    flush=flush,
                )
                if result is not None:
                    return result

        return wrapper

    def _tracked_async(
        self,
        func: Callable,
        name: Optional[str],
        type: SpanType,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        capture_input: bool,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
        flush: bool,
        project_name: Optional[str],
    ) -> Callable:
        @functools.wraps(func)
        async def wrapper(*args, **kwargs) -> Any:  # type: ignore
            self._before_call(
                func=func,
                name=name,
                type=type,
                tags=tags,
                metadata=metadata,
                capture_input=capture_input,
                project_name=project_name,
                args=args,
                kwargs=kwargs,
            )
            result = None
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
                raise exception
            finally:
                generator = self._generators_handler(
                    result,
                    capture_output,
                    generations_aggregator,
                )
                if generator is not None:  # TODO: test this flow for async generators
                    return generator

                self._after_call(
                    output=result,
                    capture_output=capture_output,
                    flush=flush,
                )
                if result is not None:
                    return result

        return wrapper

    def _before_call(
        self,
        func: Callable,
        name: Optional[str],
        type: SpanType,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        capture_input: bool,
        project_name: Optional[str],
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> None:
        try:
            opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict] = (
                kwargs.pop("opik_distributed_trace_headers", None)
            )

            start_span_arguments = self._start_span_inputs_preprocessor(
                func=func,
                name=name,
                type=type,
                tags=tags,
                metadata=metadata,
                capture_input=capture_input,
                project_name=project_name,
                args=args,
                kwargs=kwargs,
            )

            if opik_distributed_trace_headers is None:
                self._create_span(start_span_arguments)
            else:
                self._create_distributed_node_root_span(
                    start_span_arguments, opik_distributed_trace_headers
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
        self, start_span_arguments: arguments_helpers.StartSpanParameters
    ) -> None:
        """
        Handles different span creation flows.
        """
        current_span_data = context_storage.top_span_data()
        current_trace_data = context_storage.get_trace_data()

        span_data: span.SpanData
        trace_data: trace.TraceData

        if current_span_data is not None:
            # There is already at least one span in current context.
            # Simply attach a new span to it.

            if start_span_arguments.project_name != current_span_data.project_name:
                if start_span_arguments.project_name is not None:
                    LOGGER.warning(
                        "You are attempting to log data into a nested span under "
                        f'the project name "{start_span_arguments.project_name}". '
                        f'However, the project name "{current_span_data.project_name}" '
                        "from parent span will be used instead."
                    )
                start_span_arguments.project_name = current_span_data.project_name

            span_data = span.SpanData(
                id=helpers.generate_id(),
                parent_span_id=current_span_data.id,
                trace_id=current_span_data.trace_id,
                start_time=datetime_helpers.local_timestamp(),
                name=start_span_arguments.name,
                type=start_span_arguments.type,
                tags=start_span_arguments.tags,
                metadata=start_span_arguments.metadata,
                input=start_span_arguments.input,
                project_name=start_span_arguments.project_name,
            )
            context_storage.add_span_data(span_data)
            return

        if current_trace_data is not None and current_span_data is None:
            # By default, we expect trace to be created with a span.
            # But there can be cases when trace was created and added
            # to context manually (not via decorator).
            # In that case decorator should just create a span for the existing trace.

            if start_span_arguments.project_name != current_trace_data.project_name:
                if start_span_arguments.project_name is not None:
                    LOGGER.warning(
                        "You are attempting to log data into a nested span under "
                        f'the project name "{start_span_arguments.project_name}". '
                        f'However, the project name "{current_trace_data.project_name}" '
                        "from the trace will be used instead."
                    )
                start_span_arguments.project_name = current_trace_data.project_name

            span_data = span.SpanData(
                id=helpers.generate_id(),
                parent_span_id=None,
                trace_id=current_trace_data.id,
                start_time=datetime_helpers.local_timestamp(),
                name=start_span_arguments.name,
                type=start_span_arguments.type,
                tags=start_span_arguments.tags,
                metadata=start_span_arguments.metadata,
                input=start_span_arguments.input,
                project_name=start_span_arguments.project_name,
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

            span_data = span.SpanData(
                id=helpers.generate_id(),
                parent_span_id=None,
                trace_id=trace_data.id,
                start_time=datetime_helpers.local_timestamp(),
                name=start_span_arguments.name,
                type=start_span_arguments.type,
                tags=start_span_arguments.tags,
                metadata=start_span_arguments.metadata,
                input=start_span_arguments.input,
                project_name=start_span_arguments.project_name,
            )

            context_storage.set_trace_data(trace_data)
            context_storage.add_span_data(span_data)
            return

    def _create_distributed_node_root_span(
        self,
        start_span_arguments: arguments_helpers.StartSpanParameters,
        distributed_trace_headers: DistributedTraceHeadersDict,
    ) -> None:
        span_data = span.SpanData(
            id=helpers.generate_id(),
            parent_span_id=distributed_trace_headers["opik_parent_span_id"],
            trace_id=distributed_trace_headers["opik_trace_id"],
            name=start_span_arguments.name,
            input=start_span_arguments.input,
            metadata=start_span_arguments.metadata,
            tags=start_span_arguments.tags,
            type=start_span_arguments.type,
            project_name=start_span_arguments.project_name,
        )
        context_storage.add_span_data(span_data)

    def _after_call(
        self,
        output: Optional[Any],
        capture_output: bool,
        generators_span_to_end: Optional[span.SpanData] = None,
        generators_trace_to_end: Optional[trace.TraceData] = None,
        flush: bool = False,
    ) -> None:
        try:
            if output is not None:
                end_arguments = self._end_span_inputs_preprocessor(
                    output=output,
                    capture_output=capture_output,
                )
            else:
                end_arguments = arguments_helpers.EndSpanParameters()

            if generators_span_to_end is None:
                span_data_to_end, trace_data_to_end = pop_end_candidates()
            else:
                span_data_to_end, trace_data_to_end = (
                    generators_span_to_end,
                    generators_trace_to_end,
                )
            span_data_to_end.init_end_time().update(
                **end_arguments.to_kwargs(),
            )

            client = opik_client.get_client_cached()
            client.span(**span_data_to_end.__dict__)

            if trace_data_to_end is not None:
                trace_data_to_end.init_end_time().update(
                    **end_arguments.to_kwargs(ignore_keys=["usage"]),
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

    def _generators_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Union[Generator, AsyncGenerator]]:
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
        name: Optional[str],
        type: SpanType,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        capture_input: bool,
        args: Tuple,
        kwargs: Dict[str, Any],
        project_name: Optional[str],
    ) -> arguments_helpers.StartSpanParameters: ...

    @abc.abstractmethod
    def _end_span_inputs_preprocessor(
        self,
        output: Optional[Any],
        capture_output: bool,
    ) -> arguments_helpers.EndSpanParameters: ...


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
