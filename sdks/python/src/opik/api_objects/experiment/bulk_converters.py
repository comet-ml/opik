from typing import Any, Dict, List, Optional

from opik import exceptions, id_helpers
from opik.rest_api import types as rest_api_types
from opik.types import FeedbackScoreDict
from . import bulk_item
from .. import constants

_JSON_LIKE_FIELDS = ("input", "output", "metadata")


def _validate_json_like_fields(
    source: Any,
    failure_reasons: List[str],
    location: str,
) -> None:
    """Reject str/list where the backend expects a JSON object.

    The wire type accepts ``str`` and ``List[Dict]`` as well as ``Dict``, but a
    string lands in ClickHouse as an opaque blob that the UI cannot render as
    structured input/output. Callers hitting the raw Fern client discover this
    only after the data is already stored, so we reject it up front.
    """
    for field_name in _JSON_LIKE_FIELDS:
        value = getattr(source, field_name, None)
        if value is None or isinstance(value, dict):
            continue
        failure_reasons.append(
            f"{location}.{field_name} must be a dict, got {type(value).__name__}"
        )


def _validate_feedback_score(
    score: Any,
    failure_reasons: List[str],
    location: str,
) -> None:
    """Check the keys the conversion reads, which would otherwise raise KeyError."""
    if not isinstance(score, dict):
        failure_reasons.append(f"{location} must be a dict, got {type(score).__name__}")
        return

    if not score.get("name"):
        failure_reasons.append(f"{location}.name is required and must be non-empty")

    value = score.get("value")
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        failure_reasons.append(f"{location}.value is required and must be a number")


def _validate_error_info(
    error_info: Any,
    failure_reasons: List[str],
    location: str,
) -> None:
    """Check the fields the wire model requires, avoiding a raw pydantic error."""
    if error_info is None:
        return

    if not isinstance(error_info, dict):
        failure_reasons.append(
            f"{location}.error_info must be a dict, got {type(error_info).__name__}"
        )
        return

    for required_key in ("exception_type", "traceback"):
        if not error_info.get(required_key):
            failure_reasons.append(
                f"{location}.error_info.{required_key} is required and must be non-empty"
            )


def _validate_record(
    record: bulk_item.ExperimentItemBulkRecord,
    index: int,
    failure_reasons: List[str],
) -> None:
    location = f"items[{index}]"

    if not record.dataset_item_id:
        failure_reasons.append(f"{location}.dataset_item_id must be a non-empty string")

    if record.evaluate_task_result is not None and record.trace is not None:
        failure_reasons.append(
            f"{location} must provide either evaluate_task_result or trace, but not both"
        )

    # Without either field the backend silently creates a hidden trace whose
    # output is null, so the item is stored but invisible to the user.
    if record.evaluate_task_result is None and record.trace is None:
        failure_reasons.append(
            f"{location} must provide either evaluate_task_result or trace"
        )

    if record.evaluate_task_result is not None and not isinstance(
        record.evaluate_task_result, dict
    ):
        failure_reasons.append(
            f"{location}.evaluate_task_result must be a dict, "
            f"got {type(record.evaluate_task_result).__name__}"
        )

    if record.trace is not None:
        _validate_json_like_fields(record.trace, failure_reasons, f"{location}.trace")
        _validate_error_info(
            record.trace.error_info, failure_reasons, f"{location}.trace"
        )

    for span_index, span in enumerate(record.spans or []):
        span_location = f"{location}.spans[{span_index}]"
        _validate_json_like_fields(span, failure_reasons, span_location)
        _validate_error_info(span.error_info, failure_reasons, span_location)

    for score_index, score in enumerate(record.feedback_scores or []):
        _validate_feedback_score(
            score, failure_reasons, f"{location}.feedback_scores[{score_index}]"
        )


def _validate_project_name_consistency(
    records: List[bulk_item.ExperimentItemBulkRecord],
    project_name: Optional[str],
    failure_reasons: List[str],
) -> None:
    """Mirror ExperimentItemBulkUploadValidator.

    When a request-level project_name is set, the backend rejects the whole
    upload if any item-level trace names a different project.
    """
    if project_name is None or not project_name.strip():
        return

    for index, record in enumerate(records):
        trace = record.trace
        if (
            trace is None
            or trace.project_name is None
            or not trace.project_name.strip()
        ):
            continue
        if trace.project_name.casefold() != project_name.casefold():
            failure_reasons.append(
                f"items[{index}].trace.project_name ({trace.project_name!r}) does not match "
                f"the upload project_name ({project_name!r})"
            )


def validate_records(
    records: List[bulk_item.ExperimentItemBulkRecord],
    project_name: Optional[str],
) -> None:
    """Raise :class:`opik.exceptions.ValidationError` if any record is invalid."""
    failure_reasons: List[str] = []

    for index, record in enumerate(records):
        _validate_record(record, index, failure_reasons)

    _validate_project_name_consistency(records, project_name, failure_reasons)

    if failure_reasons:
        raise exceptions.ValidationError(
            prefix="batch_upload_items", failure_reasons=failure_reasons
        )


def _to_rest_trace(
    trace: bulk_item.ExperimentItemBulkTrace,
) -> rest_api_types.TraceExperimentItemBulkWriteView:
    return rest_api_types.TraceExperimentItemBulkWriteView(
        id=trace.id if trace.id is not None else id_helpers.generate_id(),
        project_name=trace.project_name,
        name=trace.name,
        start_time=trace.start_time,
        end_time=trace.end_time,
        input=trace.input,
        output=trace.output,
        metadata=trace.metadata,
        tags=trace.tags,
        error_info=(
            rest_api_types.ErrorInfoExperimentItemBulkWriteView(**trace.error_info)
            if trace.error_info is not None
            else None
        ),
        thread_id=trace.thread_id,
    )


def _to_rest_span(
    span: bulk_item.ExperimentItemBulkSpan,
) -> rest_api_types.SpanExperimentItemBulkWriteView:
    return rest_api_types.SpanExperimentItemBulkWriteView(
        id=span.id if span.id is not None else id_helpers.generate_id(),
        parent_span_id=span.parent_span_id,
        name=span.name,
        type=span.type,
        start_time=span.start_time,
        end_time=span.end_time,
        input=span.input,
        output=span.output,
        metadata=span.metadata,
        model=span.model,
        provider=span.provider,
        tags=span.tags,
        usage=span.usage,
        error_info=(
            rest_api_types.ErrorInfoExperimentItemBulkWriteView(**span.error_info)
            if span.error_info is not None
            else None
        ),
        total_estimated_cost=span.total_estimated_cost,
    )


def _to_rest_feedback_score(
    score: FeedbackScoreDict,
) -> rest_api_types.FeedbackScoreExperimentItemBulkWriteView:
    return rest_api_types.FeedbackScoreExperimentItemBulkWriteView(
        name=score["name"],
        value=score["value"],
        category_name=score.get("category_name"),
        reason=score.get("reason"),
        source=constants.FEEDBACK_SCORE_SOURCE_SDK,
    )


def to_rest_record(
    record: bulk_item.ExperimentItemBulkRecord,
) -> rest_api_types.ExperimentItemBulkRecordExperimentItemBulkWriteView:
    # Only set the fields the caller actually provided. The backend maps
    # evaluate_task_result to a Jackson JsonNode, so an explicit JSON null
    # deserializes to NullNode rather than Java null — sending
    # "evaluate_task_result": null next to a trace trips the
    # "cannot provide both" validator. Unset fields are omitted from the
    # request body, which is what the backend expects.
    optional_fields: Dict[str, Any] = {}

    if record.evaluate_task_result is not None:
        optional_fields["evaluate_task_result"] = record.evaluate_task_result

    if record.trace is not None:
        optional_fields["trace"] = _to_rest_trace(record.trace)

    if record.spans is not None:
        optional_fields["spans"] = [_to_rest_span(span) for span in record.spans]

    if record.feedback_scores is not None:
        optional_fields["feedback_scores"] = [
            _to_rest_feedback_score(score) for score in record.feedback_scores
        ]

    return rest_api_types.ExperimentItemBulkRecordExperimentItemBulkWriteView(
        dataset_item_id=record.dataset_item_id,
        **optional_fields,
    )
