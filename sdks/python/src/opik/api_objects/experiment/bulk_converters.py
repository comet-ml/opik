import logging
from typing import Any, Dict, List, Mapping, Optional, Sequence

import pydantic

from opik import exceptions, id_helpers
from opik.rest_api import types as rest_api_types
from opik.types import FeedbackScoreDict
from . import bulk_item
from .. import constants

# The dataset path's encoder, reused rather than reimplemented: one hook deciding what
# the flexible types the generated client accepted are rendered as, for both uploads.
from ..dataset import streaming_writer

LOGGER = logging.getLogger(__name__)

_JSON_LIKE_FIELDS = ("input", "output", "metadata")

_BYTES_PER_MB = 1024 * 1024


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


def _validate_project_name_match(
    record: bulk_item.ExperimentItemBulkRecord,
    index: int,
    project_name: Optional[str],
    failure_reasons: List[str],
) -> None:
    """Mirror ExperimentItemBulkUploadValidator.

    When a request-level project_name is set, the backend rejects the whole
    upload if any item-level trace names a different project.
    """
    if project_name is None or not project_name.strip():
        return

    trace = record.trace
    if trace is None or trace.project_name is None or not trace.project_name.strip():
        return

    if trace.project_name.casefold() != project_name.casefold():
        failure_reasons.append(
            f"items[{index}].trace.project_name ({trace.project_name!r}) does not match "
            f"the upload project_name ({project_name!r})"
        )


def _validate_project_name_consistency(
    records: Sequence[bulk_item.ExperimentItemBulkRecord],
    project_name: Optional[str],
    failure_reasons: List[str],
) -> None:
    for index, record in enumerate(records):
        _validate_project_name_match(record, index, project_name, failure_reasons)


def validate_record(
    record: bulk_item.ExperimentItemBulkRecord,
    index: int,
    project_name: Optional[str],
) -> None:
    """Validate one record, for callers that validate as they stream.

    Same checks as :func:`validate_records`, which keeps the whole upload in memory to
    run them. Both checks operate on individual records, so neither needs the full list.
    """
    failure_reasons: List[str] = []

    _validate_record(record, index, failure_reasons)
    _validate_project_name_match(record, index, project_name, failure_reasons)

    if failure_reasons:
        raise exceptions.ValidationError(
            prefix="batch_upload_items", failure_reasons=failure_reasons
        )


def validate_records(
    records: Sequence[bulk_item.ExperimentItemBulkRecord],
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


def _wire_value(value: Any) -> Any:
    """One field of a generated wire model, as the request body carries it.

    Only the generated models are rewritten. A caller's own ``input``, ``metadata`` or
    ``evaluate_task_result`` is handed on by reference for the JSON encoder to walk in
    C, which is the Python walk this whole path exists to remove -- so nothing here
    recurses into one. A list is rebuilt because ``spans`` and ``feedback_scores`` are
    lists of models; that costs one ``isinstance`` per element of a caller's list and
    still never descends into it.
    """
    if isinstance(value, pydantic.BaseModel):
        return _wire_fields(value)
    if isinstance(value, list):
        return [
            _wire_fields(member) if isinstance(member, pydantic.BaseModel) else member
            for member in value
        ]
    return value


def _wire_fields(model: pydantic.BaseModel) -> Dict[str, Any]:
    """One generated wire model as the dict the generated client would have sent.

    ``UniversalBaseModel.dict`` unions an ``exclude_unset`` dump with an
    ``exclude_none`` one, so a field reaches the wire when it was set -- even to None --
    or when it has a non-None default. Every field on these bulk write views defaults to
    None, so that reduces to the fields that were set, which is exactly what
    :func:`to_rest_record` decides.

    Omitted-versus-null is the point rather than a detail: the backend maps
    ``evaluate_task_result`` to a Jackson ``JsonNode``, where an explicit null
    deserializes to ``NullNode`` and trips the "either evaluate_task_result or trace"
    validator. Serialising the model itself would emit every unset field as null and
    fail every record that carries a trace.

    The models are built before this runs, so pydantic has already applied the
    coercions the wire form depends on -- an integer feedback score is a float by the
    time it is read here, as it was on the wire before.
    """
    # pydantic v2 keeps the set names on `__pydantic_fields_set__` and extras off
    # `__dict__`; v1 has `__fields_set__` and puts extras on `__dict__`.
    fields_set = getattr(model, "__pydantic_fields_set__", None)
    if fields_set is None:
        fields_set = model.__fields_set__
    values: Mapping[str, Any] = model.__dict__
    extra = getattr(model, "__pydantic_extra__", None)
    if extra:
        # Declared fields in declaration order, then extras -- the order the generated
        # dump produces.
        values = {**values, **extra}
    return {
        name: _wire_value(value) for name, value in values.items() if name in fields_set
    }


class UnmeasurableRecordError(Exception):
    """This record could not be serialised, so it can be neither measured nor sent.

    Raised rather than returned as a number, because there is no number that tells the
    truth here: every caller reads a size that large as "over the per-request limit" -- a
    plausible, wrong account of an exception thrown inside the encoder, which sends
    whoever hit it looking at the size of their data.

    Refusing is deliberate. The encoder behind it renders every shape the generated
    client accepted, and for anything else the generated client's last resort was
    ``vars(obj)`` -- uploading an object as a dict of its attributes rather than saying
    it could not be sent. Mirrors ``ItemNotSerializableError`` on the dataset path.
    """

    def __init__(self, cause: BaseException) -> None:
        super().__init__(f"could not serialize the record: {type(cause).__name__}")
        self.cause = cause


def unmeasurable_failure_reason(
    index: int, error: UnmeasurableRecordError, max_size_MB: float
) -> str:
    """The one wording for a record that cannot be serialised, shared by both paths.

    Both paths reject such a record and both have to say why. Two copies of the
    sentence is two things to keep true of each other, and the whole point of the
    sentence is that it does not mislead.
    """
    return (
        f"items[{index}] could not be serialized: the encoder raised "
        f"{type(error.cause).__name__}. This is not the {max_size_MB}MB limit; see "
        f"the logged traceback for the value responsible"
    )


def serialize_record(rest_record: Any) -> bytes:
    """One converted record's request-body bytes.

    The single pass over a record: these are the bytes spliced into the request, and
    their length is the size the batching loop budgets in. Measuring what is produced
    rather than predicting what something else would produce is what removes the second
    walk -- the generated client used to encode the record again on its way out.

    ``json_helpers`` answers with orjson where a wheel exists and the standard library
    otherwise, including for the values orjson refuses outright (integers beyond 64
    bits). ``encode_flexible`` is the dataset path's hook, unchanged and shared: the
    flexible types the generated client accepted are rendered as it rendered them, and
    anything else raises rather than being degraded into ``vars(obj)``.

    The ``try`` is broad because the one thing under it that is not ours is the caller's
    own data: an encoder hook reaches ``__str__`` on a value that may raise anything.
    """
    try:
        return streaming_writer.dumps(_wire_fields(rest_record))
    except Exception as error:
        LOGGER.warning(
            "Could not serialize an experiment item; the upload will reject it.",
            exc_info=True,
        )
        raise UnmeasurableRecordError(error) from error


def size_MB(payload: bytes) -> float:
    """What a serialised record weighs, for a caller that already holds its bytes."""
    return len(payload) / _BYTES_PER_MB


def payload_size_MB(rest_record: Any) -> float:
    """One converted record's JSON size, in megabytes.

    The length of the bytes that will be sent, not an estimate of them, so a batch built
    from these numbers is the size it measures -- which is what the oversize retry
    exists to cover for and now rarely has to. A record that cannot be serialised raises
    :class:`UnmeasurableRecordError`, which the caller reports as itself rather than as
    a size, because it cannot be sent either.
    """
    return size_MB(serialize_record(rest_record))


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
