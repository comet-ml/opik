import logging
from contextlib import contextmanager
from typing import Generator, Any

from opik import context_storage
from opik.api_objects import opik_client
from opik.decorator import arguments_helpers, base_track_decorator, error_info_collector
from opik.types import DistributedTraceHeadersDict


LOGGER = logging.getLogger(__name__)


@contextmanager
def distributed_headers(
    headers: DistributedTraceHeadersDict, flush: bool = False
) -> Generator[None, Any, None]:
    """
    Context manager for managing distributed tracing headers.

    This context manager is used to handle distributed tracing headers in a
    structured manner. It ensures root span creation, error logging during user
    script execution, and cleanup of root span data after use.

    Args:
        headers: Distributed tracing headers used for root span creation.
        flush: Whether to flush the client data after the root span is created and processed.
    """
    if not headers:
        LOGGER.warning(
            "Empty distributed headers provided. Skipping setting distributed headers."
        )
        yield
        return

    start_span_parameters = arguments_helpers.StartSpanParameters(
        name="root",
        type="general",
    )

    # create the root span with distributed headers
    span_creation_result = base_track_decorator.add_start_candidates(
        start_span_parameters=start_span_parameters,
        opik_distributed_trace_headers=headers,
        opik_args_data=None,
        tracing_active=True,
        create_duplicate_root_span=True,
    )

    end_arguments = arguments_helpers.EndSpanParameters()

    try:
        yield
    except Exception as exception:
        LOGGER.error(
            "Error in user's script while executing distributed headers context manager: %s",
            str(exception),
            exc_info=True,
        )

        # collect error info
        end_arguments.error_info = error_info_collector.collect(exception)
        end_arguments.output = None
        raise
    finally:
        # save root span data at the end of the context manager
        client = opik_client.get_client_cached()

        # Initialize end time before saving
        span_creation_result.span_data.update(
            **end_arguments.to_kwargs()
        ).init_end_time()
        client.span(**span_creation_result.span_data.as_parameters)

        # Clean up root span data from context
        opik_context_storage = context_storage.get_current_context_instance()
        opik_context_storage.pop_span_data(ensure_id=span_creation_result.span_data.id)

        if flush:
            client.flush()
