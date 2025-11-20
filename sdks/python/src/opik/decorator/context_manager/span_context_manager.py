import logging
from contextlib import contextmanager
from typing import Optional, Dict, Any, List, Generator

from opik.api_objects import span, opik_client
from opik.types import SpanType
from opik import context_storage
from .. import arguments_helpers, base_track_decorator, error_info_collector

LOGGER = logging.getLogger(__name__)


@contextmanager
def start_as_current_span(
    name: str,
    type: SpanType = "general",
    input: Optional[Dict[str, Any]] = None,
    output: Optional[Dict[str, Any]] = None,
    tags: Optional[List[str]] = None,
    metadata: Optional[Dict[str, Any]] = None,
    project_name: Optional[str] = None,
    model: Optional[str] = None,
    provider: Optional[str] = None,
    flush: bool = False,
    **kwargs: Dict[str, Any],
) -> Generator[span.SpanData, Any, None]:
    """
    A context manager for starting and managing a span and parent trace.

    This function creates a span and parent trace (if missing) with input parameters, processes outputs,
    handles errors, and ensures the span/trace data is saved and flushed at the end of its lifecycle.
    It integrates distributed tracing headers and allows additional metadata, tags, and other
    contextual information to be provided.

    Args:
        name: The name of the span to create.
        type: The type of the span. Defaults to "general".
        input: A dictionary representing the input data associated with the span.
        output: A dictionary for providing the output associated with the span.
        tags: A list of tags to associate with the span.
        metadata: A dictionary of additional metadata to attach to the span or trace.
        project_name: The name of the project associated with this span.
        model: The model name related to the span or trace.
        provider: The provider responsible for the span or trace.
        flush: Whether to flush the client data after the span is created and processed.
        **kwargs (Dict[str, Any]): Additional parameters that may be passed to the
            context manager.

    Yields:
        An iterator that provides the span data within the context of the span manager lifecycle.
    """
    start_span_parameters = arguments_helpers.StartSpanParameters(
        name=name,
        input=input,
        type=type,
        project_name=project_name,
        model=model,
        provider=provider,
    )
    distributed_headers = arguments_helpers.extract_distributed_trace_headers(kwargs)

    # create span/trace with input parameters
    span_creation_result = base_track_decorator.add_start_candidates(
        start_span_parameters=start_span_parameters,
        opik_distributed_trace_headers=distributed_headers,
        opik_args_data=None,
        tracing_active=True,
    )

    end_arguments = arguments_helpers.EndSpanParameters(
        input=span_creation_result.span_data.input or input,
        output=span_creation_result.span_data.output or output,
        tags=span_creation_result.span_data.tags or tags,
        metadata=span_creation_result.span_data.metadata or metadata,
        provider=span_creation_result.span_data.provider or provider,
        model=span_creation_result.span_data.model or model,
    )
    try:
        yield span_creation_result.span_data

        # update end arguments
        end_arguments.input = span_creation_result.span_data.input or input
        end_arguments.output = span_creation_result.span_data.output or output
        end_arguments.tags = span_creation_result.span_data.tags or tags
        end_arguments.metadata = span_creation_result.span_data.metadata or metadata
        end_arguments.provider = span_creation_result.span_data.provider or provider
        end_arguments.model = span_creation_result.span_data.model or model
    except Exception as exception:
        LOGGER.error(
            "Error in user's script while executing span context manager: %s",
            str(exception),
            exc_info=True,
        )

        # collect error info
        end_arguments.error_info = error_info_collector.collect(exception)
        end_arguments.output = None
        raise
    finally:
        # save span/trace data at the end of the context manager
        client = opik_client.get_client_cached()

        span_creation_result.span_data.init_end_time().update(
            **end_arguments.to_kwargs(),
        )
        client.span(**span_creation_result.span_data.as_parameters)

        if span_creation_result.trace_data is not None:
            span_creation_result.trace_data.init_end_time().update(
                **end_arguments.to_kwargs(ignore_keys=["usage", "model", "provider"]),
            )
            client.trace(**span_creation_result.trace_data.as_parameters)

        # Clean up span and trace from context
        opik_context_storage = context_storage.get_current_context_instance()
        opik_context_storage.pop_span_data(ensure_id=span_creation_result.span_data.id)
        if span_creation_result.trace_data is not None:
            opik_context_storage.pop_trace_data(
                ensure_id=span_creation_result.trace_data.id
            )

        if flush:
            client.flush()
