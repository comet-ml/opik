"""Experiment + trace/span cascade for ``opik migrate dataset`` (Slice 3).

Reads source experiments referencing the migrating dataset and recreates them
under the destination project, re-emitting their traces and spans with FK
fields rewritten using the maps populated by Slice 2.

**Cascade-copy semantics.** Like Slice 1's dataset copy, this is a copy --
not a move. Source experiments stay in their original projects with all
their traces and spans intact; the destination project gets brand-new
experiments (new ids) that reference the destination's dataset/version/item
ids and carry independent copies of the trace+span data. Users who want
the source side cleaned up will use ``--delete-source`` (out of this slice's
scope) once they've verified the destination.

**Cross-project follow.** ``find_experiments(dataset_id=...)`` is project-
agnostic at the REST layer, so every experiment referencing the source
dataset cascades to ``--to-project`` regardless of which project it was
originally in. This is the epic's "baseline follow" default and never
produces dangling references, but it does produce duplicates when the
source dataset was referenced by experiments in multiple projects. Slice 4
(OPIK-6417) adds detection + reporting on top of this behaviour.

FK remap during recreation:

  source dataset_id         -> dest_dataset_id            (Slice 1)
  source dataset_version_id -> plan.version_remap[old]    (Slice 2)
  source dataset_item_id    -> plan.item_id_remap[old]    (Slice 2)
  source trace_id           -> built here as traces copy  (this slice)
  source project_id         -> target_project_name        (this slice)

Stripped on the destination experiment (matches Jacques's "strip the link"
policy from the epic discussion; the pointers would otherwise dangle):

  prompt_versions  -- prompt entity isn't cascaded in v1 (epic open question)
  optimization_id  -- optimization entity is cascaded in Slice 4 (OPIK-6417)

Spans cascade with their parent trace; tree ordering is preserved via
``sort_spans_topologically`` from the import path so ``parent_span_id``
remap entries always exist by the time a child span is processed.

Per-experiment failures stop the cascade (the audit log's ``failed`` entry
captures partial progress via ``ExperimentCascadeResult``); per-item missing-
trace / missing-item conditions are tallied as skip counters rather than
failures, matching Slice 1/2's ``skipped_items`` semantics.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Set

import opik
import opik.id_helpers as id_helpers_module
from opik.api_objects import rest_helpers, rest_stream_parser
from opik.rest_api import OpikApi
from opik.rest_api.types.experiment_item_public import ExperimentItemPublic
from opik.rest_api.types.experiment_public import ExperimentPublic
from opik.rest_api.types.span_public import SpanPublic
from opik.rest_api.types.span_write import SpanWrite
from opik.rest_api.types.trace_public import TracePublic
from opik.rest_api.types.trace_write import TraceWrite

from ...imports.experiment import ExperimentData, recreate_experiment
from ...imports.utils import sort_spans_topologically
from ..audit import AuditLog
from ..errors import ExperimentCascadeError

LOGGER = logging.getLogger(__name__)

_EXPERIMENT_PAGE_SIZE = 100
_EXPERIMENT_ITEM_BATCH = 500
_SPAN_SEARCH_PAGE_SIZE = 500
_TRACE_BATCH_SIZE = 100
_SPAN_BATCH_SIZE = 100
_FEEDBACK_BATCH_SIZE = 500


@dataclass
class ExperimentCascadeResult:
    """Outcome of a full experiment cascade.

    Aggregated counters land in the audit log's ``cascade_experiments``
    entry; ``trace_id_remap`` is also stashed on the plan so Slice 4
    (optimization cascade) can reuse the mapping when it remaps
    optimization-level trace references.
    """

    trace_id_remap: Dict[str, str] = field(default_factory=dict)
    experiments_migrated: int = 0
    experiments_skipped: int = 0
    traces_migrated: int = 0
    spans_migrated: int = 0
    items_skipped_missing_trace: int = 0
    items_skipped_missing_item: int = 0
    # Per-experiment skip reasons for the audit log. Each entry is
    # ``{"id": ..., "name": ..., "reason": ...}``; capped at the most
    # recent failures to keep the audit JSON bounded.
    skipped_experiments: List[Dict[str, Any]] = field(default_factory=list)


def cascade_experiments(
    client: opik.Opik,
    rest_client: OpikApi,
    *,
    source_dataset_id: str,
    target_dataset_name: str,
    target_project_name: str,
    version_remap: Dict[str, str],
    item_id_remap: Dict[str, str],
    audit: AuditLog,
    progress_callback: Optional[Callable[[int, int, str], None]] = None,
) -> ExperimentCascadeResult:
    """Enumerate source experiments referencing ``source_dataset_id`` and
    recreate each one at the destination, with traces+spans riding along.

    ``progress_callback(completed, total, label)`` fires once before each
    experiment so callers can drive a progress bar; matches the shape used
    by ``version_replay.replay_all_versions``.
    """
    result = ExperimentCascadeResult()
    del audit  # not currently used (umbrella action wraps via execute_plan_loop)

    source_experiments = list(_list_source_experiments(rest_client, source_dataset_id))
    total = len(source_experiments)

    if total == 0:
        LOGGER.info(
            "No experiments reference dataset %s; cascade is a no-op.",
            source_dataset_id,
        )
        return result

    for index, experiment in enumerate(source_experiments):
        label = experiment.name or experiment.id or f"<experiment[{index}]>"
        if progress_callback is not None:
            progress_callback(index, total, label)

        if experiment.id is None:
            # Defensive: the BE should always return an id; if it doesn't,
            # treat as cascade-fatal because we can't enumerate items
            # without it.
            raise ExperimentCascadeError(
                f"BE returned experiment without id at position {index}: {experiment!r}"
            )

        cascade_one_experiment(
            client,
            rest_client,
            source_experiment=experiment,
            target_dataset_name=target_dataset_name,
            target_project_name=target_project_name,
            version_remap=version_remap,
            item_id_remap=item_id_remap,
            result=result,
        )

    if progress_callback is not None:
        progress_callback(total, total, "done")

    return result


def cascade_one_experiment(
    client: opik.Opik,
    rest_client: OpikApi,
    *,
    source_experiment: ExperimentPublic,
    target_dataset_name: str,
    target_project_name: str,
    version_remap: Dict[str, str],
    item_id_remap: Dict[str, str],
    result: ExperimentCascadeResult,
) -> None:
    """Migrate one source experiment: read items -> copy traces + spans ->
    recreate experiment via ``imports.experiment.recreate_experiment``.

    Mutates ``result`` in place.
    """
    experiment_id = source_experiment.id
    experiment_name = source_experiment.name
    assert experiment_id is not None  # narrowed in the caller

    # The streaming endpoint takes experiment_name (workspace-unique
    # per dataset, in practice). If the BE returned an experiment
    # without a name we can't enumerate items via the stream API.
    if not experiment_name:
        raise ExperimentCascadeError(
            f"Source experiment {experiment_id} has no name; "
            "stream_experiment_items requires experiment_name."
        )

    items = _stream_experiment_items(rest_client, experiment_name)
    if not items:
        # An experiment with no items is degenerate but recreate-able; we
        # still recreate it so users see the row at the destination.
        LOGGER.info(
            "Experiment %s (%s) has no items; recreating empty.",
            experiment_id,
            experiment_name,
        )

    # Collect distinct source trace ids for the items we plan to migrate.
    source_trace_ids: Set[str] = {item.trace_id for item in items if item.trace_id}

    traces_copied, spans_copied = _copy_traces_and_spans(
        rest_client,
        source_trace_ids=source_trace_ids,
        target_project_name=target_project_name,
        trace_id_remap=result.trace_id_remap,
    )
    result.traces_migrated += traces_copied
    result.spans_migrated += spans_copied

    # Build the ExperimentData payload that recreate_experiment consumes,
    # adapting the REST ExperimentPublic + ExperimentItemPublic into the
    # disk-export-shaped dict the function was originally written for.
    experiment_data = _build_experiment_data(source_experiment, items)

    target_version_id = version_remap.get(source_experiment.dataset_version_id or "")

    recreated = recreate_experiment(
        client=client,
        experiment_data=experiment_data,
        project_name=target_project_name,
        trace_id_map=result.trace_id_remap,
        dataset_item_id_map=item_id_remap,
        target_project_name=target_project_name,
        target_dataset_name=target_dataset_name,
        target_dataset_version_id=target_version_id,
    )

    if recreated:
        result.experiments_migrated += 1
    else:
        result.experiments_skipped += 1
        result.skipped_experiments.append(
            {
                "id": experiment_id,
                "name": source_experiment.name,
                "reason": "recreate_experiment returned False",
            }
        )

    # Tally per-item skips visible after the recreate call. ``recreate_experiment``
    # prints its own skip counts but doesn't return them; we infer the two
    # mapping-miss totals by comparing source items against the remap entries
    # so the cascade-level audit counters stay accurate.
    for item in items:
        if item.trace_id and item.trace_id not in result.trace_id_remap:
            result.items_skipped_missing_trace += 1
        if item.dataset_item_id and item.dataset_item_id not in item_id_remap:
            result.items_skipped_missing_item += 1


def _list_source_experiments(
    rest_client: OpikApi, source_dataset_id: str
) -> List[ExperimentPublic]:
    """Page through ``find_experiments(dataset_id=...)`` to exhaustion."""
    collected: List[ExperimentPublic] = []
    page = 1
    while True:
        response = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.experiments.find_experiments(
                dataset_id=source_dataset_id,
                page=page,
                size=_EXPERIMENT_PAGE_SIZE,
            )
        )
        page_content = response.content or []
        collected.extend(page_content)
        if len(page_content) < _EXPERIMENT_PAGE_SIZE:
            break
        page += 1
    return collected


def _stream_experiment_items(
    rest_client: OpikApi, experiment_name: str
) -> List[ExperimentItemPublic]:
    """Stream all items for one experiment.

    The REST endpoint takes ``experiment_name`` (not id) and returns a
    raw NDJSON byte stream that we parse via ``rest_stream_parser``. The
    resulting ``ExperimentItemPublic`` objects have ``extra="allow"``, so
    any BE-returned fields outside the typed schema (input, output,
    feedback_scores, assertion_results, execution_policy, description,
    status, usage, total_estimated_cost, duration) survive on
    ``item.model_extra`` -- which is what the cascade consumes to
    reconstruct full-fidelity destination experiment items.
    """
    raw_stream = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
        lambda: rest_client.experiments.stream_experiment_items(
            experiment_name=experiment_name,
            limit=_EXPERIMENT_ITEM_BATCH,
        )
    )
    return rest_stream_parser.read_and_parse_stream(
        stream=raw_stream,
        item_class=ExperimentItemPublic,
    )


def _copy_traces_and_spans(
    rest_client: OpikApi,
    *,
    source_trace_ids: Set[str],
    target_project_name: str,
    trace_id_remap: Dict[str, str],
) -> tuple[int, int]:
    """Re-emit traces + spans under ``target_project_name``.

    Populates ``trace_id_remap`` in place with one entry per copied trace.
    Returns ``(traces_copied, spans_copied)`` for counter aggregation.

    Feedback scores on each source trace are re-emitted against the new
    destination trace via ``score_batch_of_traces`` after the trace batch
    create. Span feedback scores are handled in ``_copy_spans_for_trace``
    (we have the span id remap there).
    """
    if not source_trace_ids:
        return 0, 0

    # Skip traces we already copied (idempotent retries, cross-experiment
    # trace sharing in the rare case it happens).
    new_source_ids = [tid for tid in source_trace_ids if tid not in trace_id_remap]
    if not new_source_ids:
        return 0, 0

    trace_writes: List[TraceWrite] = []
    source_to_new_trace: Dict[str, str] = {}
    source_traces_by_id: Dict[str, TracePublic] = {}

    for source_trace_id in new_source_ids:
        source_trace = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda sid=source_trace_id: rest_client.traces.get_trace_by_id(id=sid)
        )
        source_traces_by_id[source_trace_id] = source_trace
        new_trace_id = id_helpers_module.generate_id()
        source_to_new_trace[source_trace_id] = new_trace_id
        trace_writes.append(
            _build_trace_write(source_trace, new_trace_id, target_project_name)
        )

    # Batch-create traces, then batch-create spans (parents must exist
    # before children at the API level).
    for batch in _chunks(trace_writes, _TRACE_BATCH_SIZE):
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda b=batch: rest_client.traces.create_traces(traces=b)
        )

    trace_id_remap.update(source_to_new_trace)
    traces_copied = len(trace_writes)

    # Re-emit feedback scores on the destination traces. The trace create
    # path doesn't accept feedback scores -- they live in a separate
    # per-trace table that score_batch_of_traces writes into.
    _copy_trace_feedback_scores(
        rest_client,
        source_traces_by_id=source_traces_by_id,
        source_to_new_trace=source_to_new_trace,
        target_project_name=target_project_name,
    )

    spans_copied = 0
    for source_trace_id, new_trace_id in source_to_new_trace.items():
        spans_copied += _copy_spans_for_trace(
            rest_client,
            source_trace_id=source_trace_id,
            new_trace_id=new_trace_id,
            target_project_name=target_project_name,
        )

    return traces_copied, spans_copied


def _copy_trace_feedback_scores(
    rest_client: OpikApi,
    *,
    source_traces_by_id: Dict[str, TracePublic],
    source_to_new_trace: Dict[str, str],
    target_project_name: str,
) -> None:
    """Re-emit per-trace feedback scores under the destination project.

    Reads ``feedback_scores`` off each source trace's read payload (already
    fetched during the trace copy, no extra round-trip) and rewrites them
    as a single ``score_batch_of_traces`` call keyed by the destination
    trace id.

    No-op for traces with no feedback scores. The cascade does not copy
    ``span_feedback_scores`` here -- those are an aggregated view of
    span-level scores, not separately persisted.
    """
    from opik.rest_api.types.feedback_score_batch_item import (
        FeedbackScoreBatchItem,
    )

    batch: List[FeedbackScoreBatchItem] = []
    for source_trace_id, new_trace_id in source_to_new_trace.items():
        source = source_traces_by_id.get(source_trace_id)
        if source is None or not source.feedback_scores:
            continue
        for score in source.feedback_scores:
            batch.append(
                FeedbackScoreBatchItem(
                    id=new_trace_id,
                    project_name=target_project_name,
                    name=score.name,
                    category_name=score.category_name,
                    value=score.value,
                    reason=score.reason,
                    source=score.source,
                )
            )

    if not batch:
        return

    for chunk in _chunks(batch, _FEEDBACK_BATCH_SIZE):
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda c=chunk: rest_client.traces.score_batch_of_traces(scores=c)
        )


def _copy_spans_for_trace(
    rest_client: OpikApi,
    *,
    source_trace_id: str,
    new_trace_id: str,
    target_project_name: str,
) -> int:
    """Read source spans for one trace, mint new ids preserving the parent
    tree, and batch-create at the destination.

    After the span batch create, re-emit per-span feedback scores against
    the new span ids so span-level scores (cost, quality, etc.) survive
    the cascade. ``score_batch_of_spans`` is the write surface; spans
    don't accept feedback scores on the create payload directly.
    """
    from opik.rest_api.types.feedback_score_batch_item import (
        FeedbackScoreBatchItem,
    )

    source_spans = list(
        _fetch_spans_for_trace(rest_client, source_trace_id=source_trace_id)
    )
    if not source_spans:
        return 0

    # Topological order: parents before children, so the parent_span_id
    # remap entry for every child is always populated by the time we
    # process it. ``sort_spans_topologically`` operates on dicts; we
    # convert through model_dump and back.
    span_dicts = [span.model_dump() for span in source_spans]
    span_dicts = sort_spans_topologically(span_dicts)

    span_id_remap: Dict[str, str] = {}
    span_writes: List[SpanWrite] = []
    span_feedback_batch: List[FeedbackScoreBatchItem] = []
    for span_dict in span_dicts:
        original_id = span_dict.get("id")
        new_span_id = id_helpers_module.generate_id()
        if original_id:
            span_id_remap[original_id] = new_span_id

        original_parent = span_dict.get("parent_span_id")
        new_parent = span_id_remap.get(original_parent) if original_parent else None

        span_writes.append(
            _build_span_write(
                span_dict,
                new_span_id=new_span_id,
                new_trace_id=new_trace_id,
                new_parent_span_id=new_parent,
                target_project_name=target_project_name,
            )
        )

        # Collect per-span feedback scores keyed by the new span id so
        # we can batch-emit them after the spans land.
        for score in span_dict.get("feedback_scores") or []:
            span_feedback_batch.append(
                FeedbackScoreBatchItem(
                    id=new_span_id,
                    project_name=target_project_name,
                    name=score["name"],
                    category_name=score.get("category_name"),
                    value=score["value"],
                    reason=score.get("reason"),
                    source=score["source"],
                )
            )

    for batch in _chunks(span_writes, _SPAN_BATCH_SIZE):
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda b=batch: rest_client.spans.create_spans(spans=b)
        )

    if span_feedback_batch:
        for chunk in _chunks(span_feedback_batch, _FEEDBACK_BATCH_SIZE):
            rest_helpers.ensure_rest_api_call_respecting_rate_limit(
                lambda c=chunk: rest_client.spans.score_batch_of_spans(scores=c)
            )

    return len(span_writes)


def _fetch_spans_for_trace(
    rest_client: OpikApi, *, source_trace_id: str
) -> List[SpanPublic]:
    """Search source spans by trace_id, paginating to exhaustion."""
    collected: List[SpanPublic] = []
    page = 1
    while True:
        response = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.spans.get_spans_by_project(
                trace_id=source_trace_id,
                page=page,
                size=_SPAN_SEARCH_PAGE_SIZE,
            )
        )
        page_content = response.content or []
        collected.extend(page_content)
        if len(page_content) < _SPAN_SEARCH_PAGE_SIZE:
            break
        page += 1
    return collected


def _build_trace_write(
    source: TracePublic, new_id: str, target_project_name: str
) -> TraceWrite:
    return TraceWrite(
        id=new_id,
        project_name=target_project_name,
        name=source.name,
        start_time=source.start_time,
        end_time=source.end_time,
        input=source.input,
        output=source.output,
        metadata=source.metadata,
        tags=source.tags,
        error_info=_to_error_info_write(source.error_info),
        last_updated_at=source.last_updated_at,
        ttft=source.ttft,
        thread_id=source.thread_id,
    )


def _build_span_write(
    source_dict: Dict[str, Any],
    *,
    new_span_id: str,
    new_trace_id: str,
    new_parent_span_id: Optional[str],
    target_project_name: str,
) -> SpanWrite:
    # Only forward fields that exist on SpanWrite; the BE adds extras on read
    # (project_id, feedback_scores, comments, duration aggregates) that the
    # write surface either rejects or recomputes.
    return SpanWrite(
        id=new_span_id,
        project_name=target_project_name,
        trace_id=new_trace_id,
        parent_span_id=new_parent_span_id,
        name=source_dict.get("name"),
        type=source_dict.get("type"),
        start_time=source_dict["start_time"],
        end_time=source_dict.get("end_time"),
        input=source_dict.get("input"),
        output=source_dict.get("output"),
        metadata=source_dict.get("metadata"),
        model=source_dict.get("model"),
        provider=source_dict.get("provider"),
        tags=source_dict.get("tags"),
        usage=source_dict.get("usage"),
        error_info=source_dict.get("error_info"),
        last_updated_at=source_dict.get("last_updated_at"),
        total_estimated_cost=source_dict.get("total_estimated_cost"),
        total_estimated_cost_version=source_dict.get("total_estimated_cost_version"),
        ttft=source_dict.get("ttft"),
    )


def _to_error_info_write(error_info: Any) -> Any:
    """``TraceWrite.error_info`` is ``ErrorInfoWrite``; ``TracePublic`` returns
    ``ErrorInfoPublic``. The two share the relevant fields verbatim, so we
    pass the read shape through and let pydantic's ``extra="allow"`` accept
    the difference. Returning the raw value keeps this a single-line helper
    today, but kept named in case the schemas diverge in the future."""
    return error_info


def _build_experiment_data(
    source: ExperimentPublic, items: List[ExperimentItemPublic]
) -> ExperimentData:
    """Adapt the REST ``ExperimentPublic`` + items into the
    ``ExperimentData`` dataclass that ``recreate_experiment`` consumes.

    The disk-export shape stores experiment metadata as a flat dict matching
    the BE schema field names; we mirror that here so ``recreate_experiment``
    can read fields like ``type`` / ``evaluation_method`` / ``optimization_id``
    / ``tags`` / ``metadata`` / ``dataset_name`` verbatim.

    Per-item fidelity: ``ExperimentItemPublic`` exposes only the FK fields in
    its typed schema (id, experiment_id, dataset_item_id, trace_id), but the
    BE returns a richer payload that ``extra='allow'`` surfaces on
    ``model_extra``. We pull every field the destination ``ExperimentItem``
    write surface accepts -- input, output, feedback_scores,
    assertion_results, execution_policy, description, status, usage,
    total_estimated_cost, duration -- so the cascaded item is a verbatim
    copy of the source item, not just an FK shell.
    """
    # ``optimization_id`` is intentionally omitted from the payload: Slice 3
    # doesn't cascade the optimization entity (Slice 4 owns it), so even if
    # the source experiment carried one, forwarding it would produce a
    # dangling pointer at the destination. ``recreate_experiment`` also
    # drops it via the migrate-path guard; omitting it here keeps the
    # intent visible at the call site too.
    experiment_dict: Dict[str, Any] = {
        "id": source.id,
        "name": source.name,
        "dataset_name": source.dataset_name,
        "dataset_id": source.dataset_id,
        "dataset_version_id": source.dataset_version_id,
        "metadata": source.metadata,
        "tags": source.tags,
        "type": source.type if source.type else "regular",
        "evaluation_method": (
            source.evaluation_method if source.evaluation_method else "dataset"
        ),
    }
    items_dicts = [_experiment_item_to_dict(item) for item in items]
    return ExperimentData(experiment=experiment_dict, items=items_dicts)


# Per-item fields the migrate path forwards to the destination
# ``ExperimentItem`` write payload. Sourced from ``ExperimentItemPublic.model_extra``
# (BE returns them; the typed schema drops them). Kept as a module-level
# constant so the production code and the unit-test stand-in stay in sync.
#
# Some fields are dataset-type-specific in practice:
#   - ``feedback_scores`` -> regular ``dataset`` experiments
#   - ``assertion_results`` / ``execution_policy`` -> ``evaluation_suite``
#     (test suite) experiments
#   - the rest (``input``, ``output``, ``usage``, ``total_estimated_cost``,
#     ``duration``, ``description``, ``status``) are common to both
#
# We don't branch on dataset type because the BE-returned shape IS the
# source of truth: a regular-dataset item won't have ``assertion_results``
# in its read payload, so the ``is not None`` guard in
# ``_experiment_item_to_dict`` drops it naturally. Same the other way.
# This keeps a single code path and stays robust if the BE evolves which
# fields apply to which type.
_EXPERIMENT_ITEM_FIDELITY_FIELDS = (
    "input",
    "output",
    "feedback_scores",
    "assertion_results",
    "execution_policy",
    "description",
    "status",
    "usage",
    "total_estimated_cost",
    "duration",
)


def _experiment_item_to_dict(item: ExperimentItemPublic) -> Dict[str, Any]:
    """Flatten an experiment item read into the dict shape
    ``recreate_experiment`` consumes.

    The typed FK fields land first; the BE-returned extras come from
    ``model_extra`` (pydantic v2) and are passed through verbatim.
    """
    out: Dict[str, Any] = {
        "id": item.id,
        "trace_id": item.trace_id,
        "dataset_item_id": item.dataset_item_id,
    }
    extras = getattr(item, "model_extra", None) or {}
    for field_name in _EXPERIMENT_ITEM_FIDELITY_FIELDS:
        if field_name in extras and extras[field_name] is not None:
            out[field_name] = extras[field_name]
    return out


def _chunks(seq: List[Any], size: int) -> List[List[Any]]:
    return [seq[i : i + size] for i in range(0, len(seq), size)]
