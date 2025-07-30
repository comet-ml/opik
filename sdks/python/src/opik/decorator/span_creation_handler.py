from typing import (
    Optional,
    Tuple,
)

import opik.context_storage as context_storage
import opik.datetime_helpers as datetime_helpers
from opik.api_objects import helpers, span, trace
from opik.types import DistributedTraceHeadersDict

from . import arguments_helpers


def create_span_respecting_context(
    start_span_arguments: arguments_helpers.StartSpanParameters,
    distributed_trace_headers: Optional[DistributedTraceHeadersDict],
    opik_context_storage: Optional[context_storage.OpikContextStorage] = None,
) -> Tuple[Optional[trace.TraceData], span.SpanData]:
    """
    Handles different span creation flows.
    """

    if opik_context_storage is None:
        opik_context_storage = context_storage.get_current_context_instance()

    if distributed_trace_headers:
        span_data = arguments_helpers.create_span_data(
            start_span_arguments=start_span_arguments,
            parent_span_id=distributed_trace_headers["opik_parent_span_id"],
            trace_id=distributed_trace_headers["opik_trace_id"],
        )

        return None, span_data

    current_span_data = opik_context_storage.top_span_data()
    current_trace_data = opik_context_storage.get_trace_data()

    if current_span_data is not None:
        # There is already at least one span in the current context - attach a new span to it.
        #
        # NOTE: We can have a situation when span data is in context, but there is no trace data
        # because we are in a distributed environment and trace data was created in another thread.
        # See: https://github.com/comet-ml/opik/pull/2244
        if current_trace_data is None:
            show_warning = False
        else:
            show_warning = current_trace_data.created_by != "evaluation"

        project_name = helpers.resolve_child_span_project_name(
            parent_project_name=current_span_data.project_name,
            child_project_name=start_span_arguments.project_name,
            show_warning=show_warning,
        )

        start_span_arguments.project_name = project_name

        span_data = arguments_helpers.create_span_data(
            start_span_arguments=start_span_arguments,
            parent_span_id=current_span_data.id,
            trace_id=current_span_data.trace_id,
        )

        return None, span_data

    if current_trace_data is not None and current_span_data is None:
        # By default, we expect trace to be created with a span.
        # But there can be cases when trace was created and added
        # to context manually (not via decorator).
        # In that case the decorator should just create a span for the existing trace.

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

        return None, span_data

    if current_span_data is None and current_trace_data is None:
        # Create a trace and root span because it is
        # the first decorated function run in the current context.
        current_trace_data = trace.TraceData(
            id=helpers.generate_id(),
            start_time=datetime_helpers.local_timestamp(),
            name=start_span_arguments.name,
            input=start_span_arguments.input,
            metadata=start_span_arguments.metadata,
            tags=start_span_arguments.tags,
            project_name=start_span_arguments.project_name,
        )

        current_span_data = arguments_helpers.create_span_data(
            start_span_arguments=start_span_arguments,
            parent_span_id=None,
            trace_id=current_trace_data.id,
        )

    return current_trace_data, current_span_data
