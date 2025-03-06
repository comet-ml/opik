from . import trace_data
from .. import span
from ... import id_helpers
from typing import List, Tuple
import logging

LOGGER = logging.getLogger(__name__)


def prepare_traces_and_spans_for_copy(
    destination_project_name: str,
    traces_data: List[trace_data.TraceData],
    spans_data: List[span.SpanData],
) -> Tuple[List[trace_data.TraceData], List[span.SpanData]]:
    trace_id_mapping = {}
    traces_copy = []
    for trace_ in traces_data:
        id = id_helpers.generate_id(trace_.start_time)
        trace_id_mapping[trace_.id] = id

        trace_.id = id
        trace_.project_name = destination_project_name
        traces_copy.append(trace_)

    span_id_mapping = {}
    for span_ in spans_data:
        id = id_helpers.generate_id(span_.start_time)
        span_id_mapping[span_.id] = id

    spans_copy = []
    for span_ in spans_data:
        if span_.trace_id not in trace_id_mapping:
            LOGGER.debug(
                "While copying a span to a new project, found orphan span that will not be copied with id: %s and trace id: %s",
                span_.id,
                span_.trace_id,
            )
            continue

        span_.project_name = destination_project_name
        span_.trace_id = trace_id_mapping[span_.trace_id]
        if span_.parent_span_id:
            if span_.parent_span_id not in span_id_mapping:
                LOGGER.debug(
                    "While copying a span to a new project, found orphan span with parent span id that will not be copied with id: %s and parent_span id: %s",
                    span_.id,
                    span_.parent_span_id,
                )
                continue
            span_.parent_span_id = span_id_mapping.get(span_.parent_span_id)

        span_.id = span_id_mapping[span_.id]
        spans_copy.append(span_)

    return traces_copy, spans_copy
