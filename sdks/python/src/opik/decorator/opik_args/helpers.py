import inspect
import logging
from typing import Any, Dict, Optional, Callable

from .. import arguments_helpers
from ...api_objects import trace, data_helpers
from . import api_classes


LOGGER = logging.getLogger(__name__)


def extract_opik_args(
    kwargs: Dict[str, Any], func: Callable
) -> Optional[api_classes.OpikArgs]:
    """
    Extracts opik_args from kwargs and returns the parsed OpikArgs or None.

    Mutates kwargs in place by removing "opik_args" if the function does not explicitly
    declare opik_args as a parameter.

    Args:
        kwargs: Function keyword arguments
        func: The function being decorated

    Returns:
        opik_args if found, otherwise None.
    """

    # Check if a function has opik_args in its signature
    try:
        sig = inspect.signature(func)
        has_opik_args_param = "opik_args" in sig.parameters
    except (ValueError, TypeError):
        # If we can't inspect the signature, assume no opik_args parameter
        has_opik_args_param = False

    # If function explicitly has opik_args parameter, just get it without popping
    if has_opik_args_param:
        opik_args_dict = kwargs.get("opik_args", None)
    else:
        # If function doesn't have opik_args parameter, pop it from kwargs
        opik_args_dict = kwargs.pop("opik_args", None)

    if opik_args_dict is None:
        return None

    return api_classes.OpikArgs.from_dict(opik_args_dict)


def apply_opik_args_to_trace(
    opik_args: Optional[api_classes.OpikArgs],
    trace_data: Optional[trace.TraceData],
) -> None:
    """
    Apply opik_args to the trace data, including thread_id, tags, and metadata.

    Args:
        opik_args: The configuration extracted from function arguments
        trace_data: The current trace data to modify
    """
    if opik_args is None or opik_args.trace_args is None or trace_data is None:
        return

    # Handle thread_id
    if opik_args.trace_args.thread_id is not None:
        # Check for thread_id conflict
        if (
            trace_data.thread_id is not None
            and trace_data.thread_id != opik_args.trace_args.thread_id
        ):
            LOGGER.warning(
                "Trace already has thread_id='%s', but opik_args specifies thread_id='%s'. Keeping existing thread_id.",
                trace_data.thread_id,
                opik_args.trace_args.thread_id,
            )
        else:
            # Apply thread_id to trace
            trace_data.thread_id = opik_args.trace_args.thread_id

    # Apply trace tags if specified
    if opik_args.trace_args.tags:
        existing_tags = trace_data.tags or []
        merged_tags = data_helpers.merge_tags(existing_tags, opik_args.trace_args.tags)
        trace_data.tags = merged_tags

    # Apply trace metadata if specified
    if opik_args.trace_args.metadata:
        existing_metadata = trace_data.metadata or {}
        merged_metadata = data_helpers.merge_metadata(
            existing_metadata, opik_args.trace_args.metadata
        )
        trace_data.metadata = merged_metadata


def apply_opik_args_to_start_span_params(
    params: arguments_helpers.StartSpanParameters,
    opik_args: Optional[api_classes.OpikArgs],
) -> arguments_helpers.StartSpanParameters:
    """Apply opik_args to StartSpanParameters, merging tags and metadata."""
    if opik_args is None or opik_args.span_args is None:
        return params

    span_config = opik_args.span_args

    # Merge tags and metadata
    merged_tags = data_helpers.merge_tags(params.tags, span_config.tags)
    merged_metadata = data_helpers.merge_metadata(params.metadata, span_config.metadata)

    # Create updated parameters
    return arguments_helpers.StartSpanParameters(
        type=params.type,
        name=params.name,
        tags=merged_tags,
        metadata=merged_metadata,
        input=params.input,
        project_name=params.project_name,
        model=params.model,
        provider=params.provider,
    )
