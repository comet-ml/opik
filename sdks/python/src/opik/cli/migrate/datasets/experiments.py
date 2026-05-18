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
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Callable, Dict, List, Literal, Optional, Set, Tuple, cast

import opik
import opik.id_helpers as id_helpers_module
from opik.api_objects import rest_helpers, rest_stream_parser
from opik.api_objects.experiment import experiment_item, rest_operations
from opik.rest_api import OpikApi
from opik.rest_api.types.experiment_item_public import ExperimentItemPublic
from opik.rest_api.types.experiment_public import ExperimentPublic
from opik.rest_api.types.span_public import SpanPublic
from opik.rest_api.types.trace_public import TracePublic
from opik.types import (
    BatchAssertionResultDict,
    BatchFeedbackScoreDict,
    ErrorInfoDict,
)

from ...imports.experiment import ExperimentData, recreate_experiment
from ...imports.utils import sort_spans_topologically
from ..audit import AuditLog
from ..errors import ExperimentCascadeError

LOGGER = logging.getLogger(__name__)

# Outer progress: ``(completed, total, label)`` -- fired once before each
# experiment with ``completed`` = experiments done so far. ``label="done"``
# signals the final tick (executor uses it to strip the ``(N/total)``
# suffix and avoid an off-by-one display at completion).
ProgressCallback = Callable[[int, int, str], None]

# Inner progress: ``(completed, total, label)`` -- ticks within a single
# experiment so the outer experiment-level bar isn't a frozen one-step-
# per-experiment readout. ``total`` is the number of inner steps for THIS
# experiment (recomputed per experiment, since experiments can have wildly
# different trace counts). ``label`` describes the step that just
# completed (e.g. ``"trace 47/150"``, ``"spans for trace 47/150"``,
# ``"flush"``, ``"recreate"``). Executor renders this on a nested Rich
# bar; tests use it to assert the cascade ticks every read/write phase.
InnerProgressCallback = Callable[[int, int, str], None]


class _InnerProgress:
    """Small adapter that drives an ``InnerProgressCallback`` across the
    per-experiment work phases.

    The cascade pre-computes a total step count for THIS experiment
    (typically ``2N + fixed_overhead`` for ``N`` traces -- one tick per
    trace read, one per span fetch, plus a handful for read-items / flush
    / log-scores / log-assertions / recreate). Each call to ``tick(label)``
    increments the counter and fires the callback with the latest label
    so the UI updates smoothly even when the algorithmic work hasn't
    advanced (e.g. the executor's Rich bar repaints on every callback).

    No-op when the callback is None, so passing ``inner_progress_callback=None``
    from tests keeps the cascade machinery unchanged.
    """

    def __init__(self, callback: Optional[InnerProgressCallback], total: int) -> None:
        self._callback = callback
        self._total = max(total, 1)
        self._completed = 0

    def tick(self, label: str) -> None:
        if self._callback is None:
            return
        self._completed = min(self._completed + 1, self._total)
        self._callback(self._completed, self._total, label)

    def finish(self, label: str = "done") -> None:
        """Force the bar to 100% on completion -- guards against
        miscounted totals (e.g. some traces were already in the remap
        from a prior experiment and got skipped, so we ticked fewer
        times than estimated)."""
        if self._callback is None:
            return
        self._completed = self._total
        self._callback(self._completed, self._total, label)


_EXPERIMENT_PAGE_SIZE = 100

# Buffer around the experiment's trace start/end times when bulk-fetching
# spans via ``search_spans(from_time, to_time)``. Late-arriving spans
# (the streamer is async; a span tied to a trace can land after the trace's
# own ``end_time``) and clock skew across SDK clients motivate the buffer.
# 5 minutes covers the common cases without ballooning over-fetch from
# concurrent activity in the same project. Traces with spans landing more
# than 5 minutes past the trace window are accepted as a known edge case
# (logged as zero-bucket warnings at the end of the bulk read).
_SPAN_BULK_WINDOW_BUFFER = timedelta(minutes=5)


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
    progress_callback: Optional[ProgressCallback] = None,
    inner_progress_callback: Optional[InnerProgressCallback] = None,
) -> ExperimentCascadeResult:
    """Enumerate source experiments referencing ``source_dataset_id`` and
    recreate each one at the destination, with traces+spans riding along.

    Source-side reads (``get_spans_by_project``) scope by ``project_id``
    PER-EXPERIMENT -- derived from ``source_experiment.project_id`` on
    each iteration -- because experiments are always project-scoped
    (unlike datasets, which can be workspace-scoped) and cross-project
    experiments referencing the same source dataset legitimately live
    in different projects.

    ``progress_callback(completed, total, label)`` fires once before each
    experiment so callers can drive a progress bar; matches the shape used
    by ``version_replay.replay_all_versions``.

    Returns
    -------
    ExperimentCascadeResult
        Aggregated cascade outcome, mutated in place as each source
        experiment is processed. Fields:

        - ``trace_id_remap`` -- source trace id -> newly-minted destination
          trace id. Stashed on ``plan.trace_id_remap`` by the executor so
          Slice 4 (optimization cascade) can reuse the mapping when it
          remaps optimization-level trace references.
        - ``experiments_migrated`` / ``experiments_skipped`` -- per-
          experiment counters. An experiment is "skipped" only when
          ``recreate_experiment`` returns ``False`` (degenerate cases like
          all items missing a trace mapping); fatal errors raise
          ``ExperimentCascadeError`` instead.
        - ``traces_migrated`` / ``spans_migrated`` -- per-entity counters
          aggregating across every source experiment processed.
        - ``items_skipped_missing_trace`` / ``items_skipped_missing_item``
          -- per-experiment-item skip counters, tallied after the recreate
          call by comparing each source item's ``trace_id`` /
          ``dataset_item_id`` against the remaps.
        - ``skipped_experiments`` -- bounded list of
          ``{"id", "name", "reason"}`` entries for the audit log.
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
            inner_progress_callback=inner_progress_callback,
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
    inner_progress_callback: Optional[InnerProgressCallback] = None,
) -> None:
    """Migrate one source experiment: read items -> copy traces + spans ->
    recreate experiment via ``imports.experiment.recreate_experiment``.

    The source project is derived from ``source_experiment.project_id`` --
    experiments are always project-scoped on the BE, so every experiment
    returned by ``find_experiments(dataset_id=...)`` carries a non-null
    ``project_id`` (even when the dataset itself was workspace-scoped).
    Using per-experiment ``project_id`` -- rather than threading a single
    dataset-level project -- means cross-project experiments (i.e. ones
    living in a project other than the source dataset's project) read
    their traces / spans from the correct scope.

    Mutates ``result`` in place.
    """
    experiment_id = source_experiment.id
    experiment_name = source_experiment.name
    assert experiment_id is not None  # narrowed in the caller

    source_dataset_id = source_experiment.dataset_id
    if not source_dataset_id:
        raise ExperimentCascadeError(
            f"Source experiment {experiment_id} has no dataset_id; "
            "find_dataset_items_with_experiment_items requires the "
            "dataset id to enumerate the experiment's items."
        )

    source_project_id = source_experiment.project_id
    if not source_project_id:
        # Defensive: the BE should never return a project-less
        # experiment (experiments are always project-scoped). If it
        # does, fail clearly rather than letting ``get_spans_by_project``
        # 400 with an opaque message.
        raise ExperimentCascadeError(
            f"Source experiment {experiment_id} has no project_id; "
            "cascade requires project_id to scope span reads."
        )

    # Source-side read goes through the Compare view (rather than the
    # ``stream_experiment_items`` Public view) because we need each
    # item's ``assertion_results``, which only the Compare view surfaces.
    # Trace-scoped assertion results are then re-emitted at the
    # destination via ``store_assertions_batch`` in _copy_traces_and_spans.
    items = _read_source_experiment_items(
        rest_client,
        source_dataset_id=source_dataset_id,
        source_experiment_id=experiment_id,
    )
    if not items:
        # An experiment with no items is degenerate but recreate-able; we
        # still recreate it so users see the row at the destination.
        LOGGER.info(
            "Experiment %s (%s) has no items; recreating empty.",
            experiment_id,
            experiment_name,
        )

    # Collect distinct source trace ids for the items we plan to migrate,
    # plus the assertion_results keyed by trace id (one trace can carry
    # multiple assertion results across items, though the typical 1:1
    # shape is one item -> one trace).
    source_trace_ids: Set[str] = {item.trace_id for item in items if item.trace_id}
    assertion_results_by_source_trace: Dict[str, List[Any]] = {}
    for item in items:
        if item.trace_id and item.assertion_results:
            assertion_results_by_source_trace.setdefault(item.trace_id, []).extend(
                item.assertion_results
            )

    # Inner progress total = 1 (read items, just completed)
    # + 1 (bulk-read traces via search_traces(filter=experiment_id))
    # + N (per-trace emit ticks; the writes are streamer-batched so each
    #     tick is in-memory but gives the user motion)
    # + 1 (flush traces) + 1 (log trace feedback) + 1 (log assertions)
    # + 1 (bulk-read spans via search_spans(from_time, to_time))
    # + N (per-trace span emit ticks from the in-memory bucket)
    # + 1 (flush spans + log span feedback)
    # + 1 (recreate)
    # = 2N + 8. The trace count we use is the SET size (deduped) -- not
    # ``len(items)`` -- to avoid overcounting items that share a trace.
    # ``_InnerProgress`` clamps overshoots at ``total`` so a stale estimate
    # (e.g. idempotent-skip removes traces) doesn't push the bar past 100%.
    inner_total = 1 + 1 + 2 * len(source_trace_ids) + 6
    inner = _InnerProgress(inner_progress_callback, inner_total)
    inner.tick(label="read items")

    traces_copied, spans_copied = _copy_traces_and_spans(
        client,
        rest_client,
        source_experiment_id=experiment_id,
        source_experiment_name=experiment_name,
        source_trace_ids=source_trace_ids,
        source_project_id=source_project_id,
        target_project_name=target_project_name,
        trace_id_remap=result.trace_id_remap,
        assertion_results_by_source_trace=assertion_results_by_source_trace,
        inner_progress=inner,
    )
    result.traces_migrated += traces_copied
    result.spans_migrated += spans_copied

    # Build the ExperimentData payload that recreate_experiment consumes.
    # Only the FK fields land on the destination ExperimentItem -- the
    # rest of the per-item fidelity (input/output/feedback_scores/
    # assertion_results/etc.) is READ-ONLY on the BE and reconstructs
    # from the underlying trace + span + assertion entities (which the
    # cascade copies in _copy_traces_and_spans).
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
    # Snap the inner bar to 100% so the executor's nested Rich bar
    # finishes cleanly even when our pre-computed ``inner_total`` ran a
    # bit hot or cold (e.g. fewer ticks fired because idempotent-skip
    # removed traces from this experiment).
    inner.finish(label="recreated" if recreated else "skipped")

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
    """Page through ``find_experiments(dataset_id=...)`` to exhaustion.

    Stays on ``rest_client`` rather than ``client.get_dataset_experiments``:
    the high-level wrapper takes ``dataset_name`` only, which would
    require a per-call name lookup (the cascade has the source
    ``dataset_id`` from the plan, but the source dataset is renamed to
    ``<name>_v1`` by the migrate, so the source name isn't stable across
    the cascade's runtime). The ``rest_client`` call takes ``dataset_id``
    directly.
    """
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


def _read_source_experiment_items(
    rest_client: OpikApi,
    *,
    source_dataset_id: str,
    source_experiment_id: str,
) -> List[experiment_item.ExperimentItemContent]:
    """Read all items for one source experiment via the Compare view.

    Routes through the high-level
    ``api_objects.experiment.rest_operations.find_experiment_items_for_dataset``
    helper, which paginates
    ``datasets.find_dataset_items_with_experiment_items`` internally
    (PAGE_SIZE=100), flattens each page's per-dataset-item
    ``experiment_items`` list, and returns ``ExperimentItemContent``
    dataclasses with ``assertion_results`` already normalized to
    ``List[AssertionResultDict]``.

    Compare view (vs. the Public ``stream_experiment_items``) is the
    correct read shape here because only Compare surfaces
    ``assertion_results``, which the cascade needs in order to re-emit
    them at the destination scoped to the new trace id via
    ``client.log_assertion_results``.

    ``max_results`` is the helper's caller-side "stop at N results" knob,
    designed for paginated/search-as-you-type UIs that don't want to
    fetch the long tail. A migration is lossless by contract -- silently
    truncating any experiment's items would corrupt the destination --
    so we pass ``sys.maxsize`` to let the helper's underlying pagination
    walk every page of the source experiment. ``truncate=False`` likewise
    keeps the per-item Compare payloads at full fidelity (the cascade
    only consumes ``id`` / ``trace_id`` / ``dataset_item_id`` /
    ``assertion_results`` today, so the BE-side truncation flag wouldn't
    affect correctness, but forwarding ``False`` matches the prior call
    shape and future-proofs against the cascade ever consuming a
    truncatable field).

    The underlying endpoint takes ``experiment_ids`` as a JSON-array
    string -- not a comma-separated value or a list -- and 400s on
    either of the other forms; the helper handles the JSON encoding
    internally.
    """
    return rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id=source_dataset_id,
        experiment_ids=[source_experiment_id],
        max_results=sys.maxsize,
        truncate=False,
    )


def _discover_trace_projects(
    rest_client: OpikApi,
    *,
    source_experiment_name: str,
    fallback_project_id: str,
) -> Dict[str, Set[str]]:
    """Map each source trace_id to the project where its trace actually lives.

    Cross-project experiments are legal on the BE: ``experiment_items``
    rows can reference traces in projects different from the experiment's
    own project. The BE populates ``experiment_items.project_id`` from
    ``traces.project_id`` at write time (see
    ``ExperimentItemService.populateProjectIdFromTraces``), and
    ``streamExperimentItems`` surfaces that field on each row (verified
    on staging -- the Compare-view endpoint omits ``project_id`` via its
    ``@JsonView`` annotation, but the stream-experiment-items endpoint
    has no view restriction and includes it).

    We stream the experiment's items, group source trace_ids by their
    actual project_id, and return ``{project_id: {trace_ids}}``. The
    cascade then issues one ``search_traces`` and one ``search_spans``
    per distinct project -- typically 1 in practice (``opik.evaluate(...)``
    co-locates), but legal cross-project setups stay lossless without
    falling back to per-trace ``get_trace_content`` for each.

    Items whose ``project_id`` is ``None`` (defensive; the BE
    populate-from-traces step shouldn't leave nulls but the schema allows
    it) are routed to ``fallback_project_id`` -- the experiment's own
    project. This matches the per-trace fallback's old behavior from
    commit ``7e0f9a8bb``: ``trace.project_id or source_project_id``.
    """
    trace_ids_by_project: Dict[str, Set[str]] = {}

    def _fetch_page(batch_size: int, last_retrieved_id: Optional[str]) -> Any:
        return rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            operation_name="stream_experiment_items (project discovery)",
            rest_callable=lambda: list(
                rest_client.experiments.stream_experiment_items(
                    experiment_name=source_experiment_name,
                    limit=batch_size,
                    last_retrieved_id=last_retrieved_id,
                    truncate=True,
                )
            ),
        )

    items = rest_stream_parser.read_and_parse_full_stream(
        read_source=_fetch_page,
        max_results=sys.maxsize,
        parsed_item_class=ExperimentItemPublic,
    )

    for item in items:
        trace_id = item.trace_id
        if not trace_id:
            continue
        project_id = item.project_id or fallback_project_id
        trace_ids_by_project.setdefault(project_id, set()).add(trace_id)

    return trace_ids_by_project


def _copy_traces_and_spans(
    client: opik.Opik,
    rest_client: OpikApi,
    *,
    source_experiment_id: str,
    source_experiment_name: str,
    source_trace_ids: Set[str],
    source_project_id: str,
    target_project_name: str,
    trace_id_remap: Dict[str, str],
    assertion_results_by_source_trace: Optional[Dict[str, List[Any]]] = None,
    inner_progress: Optional["_InnerProgress"] = None,
) -> tuple[int, int]:
    """Re-emit traces + spans under ``target_project_name`` via the high-level
    Opik client's streamer infrastructure.

    Writes route through ``client.__internal_api__trace__`` (traces),
    ``client._streamer.put(CreateSpanMessage(...))`` (spans),
    ``client.log_traces_feedback_scores`` / ``client.log_spans_feedback_scores``
    (feedback scores), and ``client.log_assertion_results`` (assertions).
    The streamer batches across messages and handles retry/backpressure
    internally; no manual ``rest_helpers.ensure_rest_api_call_respecting_rate_limit``
    wrap on the write path.

    Spans use a direct ``_streamer.put(CreateSpanMessage(...))`` rather
    than ``client.span(...)`` because the public ``client.span(usage=...)``
    path invokes ``helpers.add_usage_to_metadata`` which merges ``usage``
    into ``metadata["usage"]``. That's a user-facing write-side convenience
    for fresh spans, but it conflicts with this cascade's round-trip
    metadata-fidelity contract: source spans have ``usage`` in their own
    field and ``metadata`` distinct, and we need both to round-trip
    untouched. The streamer's ``CreateSpanMessage`` accepts them as
    separate fields and serializes verbatim.

    Trace reads now go through one ``client.search_traces(filter=
    "experiment_id=...", truncate=False)`` call per experiment -- the BE
    exposes ``TraceField.EXPERIMENT_ID`` as a first-class filter, so a
    single paginated read returns every trace linked to this experiment.
    This is the per-experiment fix for the per-trace 30-then-pause rate-
    limit pattern. Span reads stay per-trace (no ``experiment_id`` filter
    on ``SpanField``); the bulk-read win is on traces only.

    Populates ``trace_id_remap`` in place with one entry per copied trace.
    Returns ``(traces_copied, spans_copied)`` for counter aggregation.

    ``source_project_id`` is only a defensive fallback used when an
    individual source trace has a null ``project_id`` field. Per-trace
    span reads use the trace's own ``project_id`` -- spans live in the
    same project as their parent trace, which may differ from the
    experiment's project (the BE does not enforce single-project
    invariance across an experiment's traces).
    """
    if not source_trace_ids:
        return 0, 0

    # Skip traces we already copied (idempotent retries, cross-experiment
    # trace sharing in the rare case it happens).
    new_source_ids = [tid for tid in source_trace_ids if tid not in trace_id_remap]
    if not new_source_ids:
        return 0, 0

    source_to_new_trace: Dict[str, str] = {}
    project_id_to_name_cache: Dict[str, str] = {}

    # Phase 1a: discover which projects this experiment's traces actually
    # live in. The BE allows ``experiment_items`` rows to reference traces
    # in projects different from the experiment's own project (legal but
    # rare; ``opik.evaluate(...)`` always co-locates). We stream the
    # experiment's items via ``streamExperimentItems`` -- each row carries
    # ``project_id`` populated from the trace's actual project at write
    # time -- and group by project so we can issue one ``search_traces``
    # per distinct project. Single-project experiments (the common case)
    # still produce one read; cross-project experiments stay lossless.
    traces_by_project: Dict[str, Set[str]] = _discover_trace_projects(
        rest_client,
        source_experiment_name=source_experiment_name,
        fallback_project_id=source_project_id,
    )

    # Phase 1b: fetch source traces in BULK via
    # ``search_traces(filter="experiment_id=...")`` -- one HTTP read PER
    # DISTINCT PROJECT. The BE has ``TraceField.EXPERIMENT_ID`` as a
    # first-class filter (joined through ``experiment_items``), but the
    # outer SQL clamps ``project_id = :project_id`` -- so the filter is
    # AND-ed with the project scope. Looping per project covers every
    # trace including cross-project ones.
    #
    # ``truncate=False`` is required for round-trip fidelity: the SDK
    # wrapper defaults to ``True``, which replaces inline base64 image
    # data in input/output/metadata with the placeholder ``"[image]"``.
    # We need the raw bytes preserved.
    #
    # ``max_results=sys.maxsize`` lets the wrapper's internal pagination
    # (PAGE_SIZE=2000 via ``last_retrieved_id`` cursor) walk every page;
    # the cap is the wrapper's caller-side "stop at N" UI knob, not a
    # safety limit -- a migration must be lossless.
    source_traces_by_id: Dict[str, TracePublic] = {}
    for project_id in traces_by_project:
        project_name = _resolve_project_name(
            client, project_id=project_id, cache=project_id_to_name_cache
        )
        bulk_traces = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda pn=project_name: client.search_traces(
                project_name=pn,
                filter_string=f'experiment_id = "{source_experiment_id}"',
                max_results=sys.maxsize,
                truncate=False,
            ),
            operation_name="search_traces (experiment cascade)",
        )
        for t in bulk_traces:
            if t.id is not None:
                source_traces_by_id[t.id] = t
    if inner_progress is not None:
        inner_progress.tick(
            label=f"fetched {len(source_traces_by_id)} traces in bulk "
            f"({len(traces_by_project)} project{'s' if len(traces_by_project) != 1 else ''})"
        )

    # Defensive fallback: if any trace_ids from the Compare-view items are
    # missing from the bulk search response (rare -- shouldn't happen if
    # the experiment_items table is consistent), fall back to per-trace
    # ``get_trace_content`` so correctness wins over throughput. This keeps
    # the cascade lossless even if the join filter has edge-case misses.
    missing_ids = [tid for tid in new_source_ids if tid not in source_traces_by_id]
    if missing_ids:
        LOGGER.warning(
            "search_traces(experiment_id=%s) returned %d traces but %d "
            "were expected from Compare-view items; falling back to "
            "get_trace_content for %d missing ids.",
            source_experiment_id,
            len(source_traces_by_id),
            len(new_source_ids),
            len(missing_ids),
        )
        for tid in missing_ids:
            fetched = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
                lambda sid=tid: client.get_trace_content(id=sid),
                operation_name="get_trace_content (fallback)",
            )
            source_traces_by_id[tid] = fetched

    # Phase 1b: emit destination traces. ``source="experiment"`` matches
    # what opik.evaluate(...) writes on the source (the public
    # client.trace() would override to source="sdk", which the deep-
    # compare would flag).
    total_traces = len(new_source_ids)
    for index, source_trace_id in enumerate(new_source_ids, start=1):
        source_trace = source_traces_by_id[source_trace_id]
        new_trace_id = id_helpers_module.generate_id()
        source_to_new_trace[source_trace_id] = new_trace_id

        client.__internal_api__trace__(
            id=new_trace_id,
            name=source_trace.name,
            start_time=source_trace.start_time,
            end_time=source_trace.end_time,
            input=source_trace.input,
            output=source_trace.output,
            metadata=source_trace.metadata,
            tags=source_trace.tags,
            error_info=_to_error_info_dict(source_trace.error_info),
            thread_id=source_trace.thread_id,
            project_name=target_project_name,
            source=getattr(source_trace, "source", None) or "experiment",
        )
        if inner_progress is not None:
            inner_progress.tick(label=f"trace {index}/{total_traces}")

    trace_id_remap.update(source_to_new_trace)
    traces_copied = len(new_source_ids)

    # Flush traces before writing trace-attached records (feedback scores,
    # assertion results, spans). The streamer batches writes without
    # ordering guarantees within a flush window; assertions referencing a
    # trace can fail if the BE hasn't persisted the trace yet.
    client.flush()
    if inner_progress is not None:
        inner_progress.tick(label="flushed traces")

    # Re-emit trace-level feedback scores via the high-level batched API.
    _log_trace_feedback_scores(
        client,
        source_traces_by_id=source_traces_by_id,
        source_to_new_trace=source_to_new_trace,
        target_project_name=target_project_name,
    )
    if inner_progress is not None:
        inner_progress.tick(label="logged trace feedback scores")

    # Re-emit per-trace assertion results. Skipped for callers that
    # didn't read them (regular-dataset path).
    if assertion_results_by_source_trace:
        _log_trace_assertion_results(
            client,
            assertion_results_by_source_trace=assertion_results_by_source_trace,
            source_to_new_trace=source_to_new_trace,
            target_project_name=target_project_name,
        )
    if inner_progress is not None:
        inner_progress.tick(label="logged assertion results")

    # Phase 2a: bulk-fetch spans for the experiment, one call per distinct
    # project the experiment's traces live in. ``search_spans`` (like
    # ``search_traces``) clamps ``project_id`` on the outer SQL, so a
    # single call cannot cover cross-project experiments. We reuse the
    # ``traces_by_project`` mapping computed before the trace bulk-read.
    #
    # Each per-project call uses a ``[from_time, to_time]`` window derived
    # from THAT project's traces' start/end timestamps, then filters
    # client-side by ``trace_id in <project's trace_ids>`` so spans from
    # concurrent activity in the same project + time window are discarded.
    #
    # We drop to the Fern method because the high-level
    # ``client.search_spans`` wrapper doesn't expose ``from_time`` /
    # ``to_time``. Tactical Fern use, scoped to this one bulk read.
    spans_by_trace_id = _bulk_fetch_spans_for_experiment(
        client,
        source_traces_by_id=source_traces_by_id,
        traces_by_project=traces_by_project,
        project_id_to_name_cache=project_id_to_name_cache,
        expected_trace_ids=set(source_to_new_trace.keys()),
    )
    if inner_progress is not None:
        total_spans_in_bulk = sum(len(s) for s in spans_by_trace_id.values())
        inner_progress.tick(
            label=f"fetched {total_spans_in_bulk} spans in bulk "
            f"({len(traces_by_project)} project{'s' if len(traces_by_project) != 1 else ''})"
        )

    # Phase 2b: emit destination spans per-trace from the in-memory
    # bucket. Same topological-sort + per-trace span_id_remap logic as
    # before; the only change is the source of ``source_spans`` shifted
    # from a per-trace REST call to a dict lookup.
    spans_emitted = 0
    span_feedback_scores: List[BatchFeedbackScoreDict] = []
    span_trace_count = len(source_to_new_trace)
    for index, (source_trace_id, new_trace_id) in enumerate(
        source_to_new_trace.items(), start=1
    ):
        per_trace_count, per_trace_fbs = _emit_spans_for_trace(
            client,
            source_spans=spans_by_trace_id.get(source_trace_id, []),
            new_trace_id=new_trace_id,
            target_project_name=target_project_name,
        )
        spans_emitted += per_trace_count
        span_feedback_scores.extend(per_trace_fbs)
        if inner_progress is not None:
            inner_progress.tick(label=f"spans for trace {index}/{span_trace_count}")

    # Flush spans before their feedback scores (the BE rejects a score
    # whose entity id doesn't exist yet).
    client.flush()

    if span_feedback_scores:
        client.log_spans_feedback_scores(
            scores=span_feedback_scores, project_name=target_project_name
        )
    if inner_progress is not None:
        inner_progress.tick(label="flushed spans + logged span feedback scores")

    return traces_copied, spans_emitted


def _log_trace_feedback_scores(
    client: opik.Opik,
    *,
    source_traces_by_id: Dict[str, TracePublic],
    source_to_new_trace: Dict[str, str],
    target_project_name: str,
) -> None:
    """Re-emit per-trace feedback scores under the destination project via
    ``client.log_traces_feedback_scores``.

    Reads ``feedback_scores`` off each source trace's read payload
    (already fetched during the trace copy, no extra round-trip) and
    rewrites them keyed by the destination trace id. The high-level API
    handles batching + streamer routing.

    No-op for traces with no feedback scores.
    """
    batch: List[BatchFeedbackScoreDict] = []
    for source_trace_id, new_trace_id in source_to_new_trace.items():
        source = source_traces_by_id.get(source_trace_id)
        if source is None or not source.feedback_scores:
            continue
        for score in source.feedback_scores:
            entry: BatchFeedbackScoreDict = {
                "id": new_trace_id,
                "project_name": target_project_name,
                "name": score.name,
                "value": score.value,
            }
            if score.reason is not None:
                entry["reason"] = score.reason
            if score.category_name is not None:
                entry["category_name"] = score.category_name
            batch.append(entry)

    if not batch:
        return

    client.log_traces_feedback_scores(scores=batch, project_name=target_project_name)


def _log_trace_assertion_results(
    client: opik.Opik,
    *,
    assertion_results_by_source_trace: Dict[str, List[Any]],
    source_to_new_trace: Dict[str, str],
    target_project_name: str,
) -> None:
    """Re-emit per-trace assertion results via ``client.log_assertion_results``.

    Assertion results aren't a field on ``ExperimentItem`` (the BE drops
    that field on write -- it's READ-ONLY on the Compare view, computed
    from the underlying assertion-results entity table). They are
    written via the dedicated assertion-results ingestion endpoint, which
    the high-level client exposes as ``log_assertion_results`` -- that
    routes through the streamer like every other write.

    For Slice 3 we only see assertion results on items via the Compare
    view's ``ExperimentItemCompare.assertion_results``; those are the
    trace-scoped writes the source had. We re-emit them scoped to the new
    destination trace id so the destination ``ExperimentItemCompare``
    surfaces them in the same place at the same shape.

    The read shape ``AssertionResultCompare`` carries ``value`` / ``passed``
    / ``reason``; ``client.log_assertion_results`` accepts dicts with
    ``id`` (= trace id), ``name``, ``status`` ("passed" | "failed"),
    ``reason``. The mapping is:

      AssertionResultCompare.value  <->  log_assertion_results.name
      AssertionResultCompare.passed <->  log_assertion_results.status
                                          ("passed" | "failed")
      AssertionResultCompare.reason <->  log_assertion_results.reason
    """
    batch: List[BatchAssertionResultDict] = []
    for source_trace_id, results in assertion_results_by_source_trace.items():
        new_trace_id = source_to_new_trace.get(source_trace_id)
        if not new_trace_id:
            # Trace wasn't copied (e.g. earlier idempotent-skip); nothing
            # to remap against.
            continue
        for ar in results:
            # ``ar`` is an ``AssertionResultCompare``; defensive .get for
            # callers passing dict-like stand-ins in tests.
            value = (
                getattr(ar, "value", None)
                if not isinstance(ar, dict)
                else ar.get("value")
            )
            passed = (
                getattr(ar, "passed", None)
                if not isinstance(ar, dict)
                else ar.get("passed")
            )
            reason = (
                getattr(ar, "reason", None)
                if not isinstance(ar, dict)
                else ar.get("reason")
            )
            if value is None or passed is None:
                # Skip degenerate entries; the BE write rejects items
                # missing the required ``name``/``status`` fields.
                continue
            # ``status`` is ``Literal["passed", "failed"]`` on
            # ``BatchAssertionResultDict``; the explicit ternary keeps mypy
            # narrowing intact (a bare ``str`` would widen and fail).
            status: Literal["passed", "failed"] = "passed" if passed else "failed"
            entry: BatchAssertionResultDict = {
                "id": new_trace_id,
                "project_name": target_project_name,
                "name": value,
                "status": status,
            }
            if reason is not None:
                entry["reason"] = reason
            batch.append(entry)

    if not batch:
        return

    client.log_assertion_results(
        assertion_results=batch, project_name=target_project_name
    )


def _resolve_project_name(
    client: opik.Opik,
    *,
    project_id: str,
    cache: Dict[str, str],
) -> str:
    """Translate a project_id to project_name with caching.

    ``client.search_traces`` and ``client.search_spans`` take
    ``project_name`` (the underlying BE endpoint accepts either, but the
    SDK only exposes ``project_name``). The cascade has ``project_id``
    handy from ``source_experiment.project_id`` and per-trace
    ``trace.project_id``; resolving once and caching means we pay at most
    one ``client.get_project(id=...)`` per distinct project per
    experiment (typically 1).
    """
    cached = cache.get(project_id)
    if cached is not None:
        return cached
    project = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
        lambda: client.get_project(id=project_id),
        operation_name="get_project (project_id -> name resolution)",
    )
    name = project.name
    cache[project_id] = name
    return name


def _compute_span_time_window(
    traces: Dict[str, TracePublic],
) -> Optional[Tuple[datetime, datetime]]:
    """Derive a ``(from_time, to_time)`` window from a batch of traces.

    The window spans ``min(start_time) - buffer`` to
    ``max(end_time, last_updated_at) + buffer`` so a bulk
    ``search_spans(from_time=…, to_time=…)`` call can fetch every span
    parented by these traces in one round-trip. ``last_updated_at`` is
    the fallback when ``end_time`` is missing (the trace never completed
    cleanly, or the BE has a different shape than expected).

    Returns ``None`` when no trace has any usable timestamp -- the
    caller should treat that as "no time bound" and pass ``from_time``
    / ``to_time`` as ``None`` (the BE then returns all matching spans
    in the project, which the caller already filters client-side by
    ``trace_id``).
    """
    starts: List[datetime] = []
    ends: List[datetime] = []
    for trace in traces.values():
        if trace.start_time is not None:
            starts.append(_as_aware(trace.start_time))
        upper = trace.end_time or trace.last_updated_at
        if upper is not None:
            ends.append(_as_aware(upper))
    if not starts and not ends:
        return None
    # If only one side is populated, anchor the missing side to it so the
    # window is still bounded.
    earliest = min(starts) if starts else min(ends)
    latest = max(ends) if ends else max(starts)
    return (earliest - _SPAN_BULK_WINDOW_BUFFER, latest + _SPAN_BULK_WINDOW_BUFFER)


def _as_aware(value: datetime) -> datetime:
    """Coerce a naive datetime to UTC-aware.

    BE timestamps are always UTC; the SDK wire types sometimes deserialize
    them as naive datetimes depending on the version. ``min`` / ``max``
    across mixed naive/aware datetimes raises a TypeError, so we normalize
    once at the boundary.
    """
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value


def _bulk_fetch_spans_for_experiment(
    client: opik.Opik,
    *,
    source_traces_by_id: Dict[str, TracePublic],
    traces_by_project: Dict[str, Set[str]],
    project_id_to_name_cache: Dict[str, str],
    expected_trace_ids: Set[str],
) -> Dict[str, List[SpanPublic]]:
    """Fetch every span parented by ``expected_trace_ids`` in bulk.

    One ``client.search_spans`` call per distinct project the
    experiment's traces live in. The BE's ``search_spans`` clamps
    ``project_id = :project_id`` on the outer SQL, so a single call can
    only cover traces in one project. ``traces_by_project`` (computed
    earlier from ``streamExperimentItems``) tells us where each trace
    actually lives, so this loop covers cross-project experiments
    losslessly.

    Each per-project call uses a ``filter_string="start_time >= … AND
    start_time <= …"`` clause derived from THAT project's traces'
    start/end timestamps. The BE filter grammar accepts the bounds via
    the date-time filter operators on ``SpanField.START_TIME``. Spans
    from concurrent activity in the same project + time window are
    discarded by the client-side ``span.trace_id in expected_trace_ids``
    filter.

    Returns a dict ``{trace_id: [spans]}`` covering every id in
    ``expected_trace_ids``. Missing entries are present with empty lists
    so the caller can detect zero-bucket cases and log them. A trace with
    no spans is a legitimate case (no LLM call, no child operations), so
    we don't error on empty buckets -- only log.

    Replaces the prior ``N × search_spans(trace_id=…)`` loop, which on
    workspaces with a strict ``search_spans:{workspaceId}`` bucket
    produced a 30-then-pause throttle pattern for large experiments.
    """
    spans_by_trace: Dict[str, List[SpanPublic]] = {
        tid: [] for tid in expected_trace_ids
    }

    for project_id, trace_ids_in_project in traces_by_project.items():
        project_name = _resolve_project_name(
            client, project_id=project_id, cache=project_id_to_name_cache
        )

        # Narrow the time window to just THIS project's traces -- shrinks
        # over-fetch from concurrent activity unrelated to the experiment.
        per_project_traces = {
            tid: source_traces_by_id[tid]
            for tid in trace_ids_in_project
            if tid in source_traces_by_id
        }
        window = _compute_span_time_window(per_project_traces)
        filter_string: Optional[str] = None
        if window is not None:
            from_time, to_time = window
            # ISO 8601 with explicit ``Z`` UTC suffix matches the BE
            # filter grammar's date-time literal format (see the
            # search_spans docstring on ``client.search_spans``:
            # "use ISO 8601 format, e.g., '2024-01-01T00:00:00Z'").
            # ``SpanField.END_TIME`` is filterable too but using
            # ``start_time`` for both bounds keeps the filter AST simple
            # and lets the BE's primary-key range scan stay tight.
            filter_string = (
                f'start_time >= "{_to_iso_z(from_time)}" '
                f'AND start_time <= "{_to_iso_z(to_time)}"'
            )

        all_spans = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            operation_name="search_spans (experiment bulk read)",
            rest_callable=lambda pn=project_name, fs=filter_string: client.search_spans(
                project_name=pn,
                filter_string=fs,
                max_results=sys.maxsize,
                truncate=False,
            ),
        )

        for span in all_spans:
            tid = span.trace_id
            # Filter twice: must be one of the experiment's expected
            # trace_ids AND must belong to this project's subset (the
            # outer ``project_name`` filter already restricts the
            # returned spans, but checking here too makes the contract
            # explicit and defends against any BE quirk).
            if (
                tid is not None
                and tid in spans_by_trace
                and tid in trace_ids_in_project
            ):
                spans_by_trace[tid].append(span)

    # Surface zero-bucket traces. Could be legitimate (genuinely no spans
    # on the source trace) OR the bulk window missed them. We don't
    # distinguish today -- the destination trace is still copied, just
    # without spans -- but we log so an operator can spot mass misses.
    empty_bucket_ids = [tid for tid, spans in spans_by_trace.items() if not spans]
    if empty_bucket_ids:
        LOGGER.warning(
            "Bulk span read for experiment returned zero spans for %d/%d "
            "expected trace_ids. Either those traces genuinely have no spans, "
            "or the [from_time, to_time] window missed late-arriving spans. "
            "Destination traces are still copied but without their spans. "
            "Example missing trace_ids: %s",
            len(empty_bucket_ids),
            len(expected_trace_ids),
            empty_bucket_ids[:5],
        )

    return spans_by_trace


def _to_iso_z(value: datetime) -> str:
    """Format a UTC datetime in the BE's filter-grammar date-time literal.

    The BE expects strings like ``"2024-01-01T00:00:00Z"``. Python's
    ``isoformat()`` produces ``"2024-01-01T00:00:00+00:00"`` for an
    aware UTC datetime; we swap the offset for the ``Z`` suffix so the
    filter parser accepts it without extra coercion. Microseconds are
    preserved when present.
    """
    iso = value.astimezone(timezone.utc).isoformat()
    # ``.isoformat()`` for an aware UTC datetime ends with ``"+00:00"``.
    if iso.endswith("+00:00"):
        iso = iso[: -len("+00:00")] + "Z"
    return iso


def _to_error_info_dict(error_info: Any) -> Optional[ErrorInfoDict]:
    """Convert a wire-shape ``ErrorInfoPublic`` (or already-dict) to the
    ``ErrorInfoDict`` TypedDict shape the streamer / high-level API expects.

    Returns ``None`` when there's no error info, so callers can pass it
    through verbatim without nil-handling. ``cast`` at the boundary
    because the runtime shape (``exception_type`` + ``traceback`` plus
    optional ``message``) already matches the TypedDict's required keys --
    BE writes always populate them -- but mypy can't infer that from a
    generic dict / a ``model_dump`` call.
    """
    if error_info is None:
        return None
    if isinstance(error_info, dict):
        return cast(ErrorInfoDict, error_info)
    dump = getattr(error_info, "model_dump", None)
    if dump is not None:
        return cast(ErrorInfoDict, dump(exclude_none=True))
    return cast(ErrorInfoDict, dict(getattr(error_info, "__dict__", {})))


def _emit_spans_for_trace(
    client: opik.Opik,
    *,
    source_spans: List[SpanPublic],
    new_trace_id: str,
    target_project_name: str,
) -> Tuple[int, List[BatchFeedbackScoreDict]]:
    """Mint new ids preserving the parent tree and emit destination spans
    via direct ``client._streamer.put(CreateSpanMessage(...))`` calls.
    Returns ``(spans_emitted, span_feedback_scores)``.

    ``source_spans`` is the pre-fetched bucket for ONE trace, populated
    by the experiment-level bulk read in
    ``_bulk_fetch_spans_for_experiment``. Previously fetched per-trace
    via ``search_spans(trace_id=…)``; the bulk-read refactor moved that
    out to amortize the rate-limit cost across the whole experiment.

    Why bypass ``client.span(...)``: the public method routes through
    ``span_client.create_span()`` which calls
    ``helpers.add_usage_to_metadata`` -- a user-facing convenience that
    merges ``usage`` into ``metadata["usage"]`` for fresh writes. The
    cascade needs strict round-trip metadata fidelity (source has
    ``usage`` and ``metadata`` as distinct fields, the merge would
    diverge the destination). The streamer's ``CreateSpanMessage``
    accepts ``usage`` and ``metadata`` as separate fields and serializes
    verbatim, so building the message directly preserves both.

    Span_id remap stays per-trace -- parents must precede children
    within a trace tree, and span ids only collide within a tree.

    Span-level feedback scores are returned for the caller to batch via
    ``client.log_spans_feedback_scores`` after the spans are flushed.
    """
    from opik import datetime_helpers
    from opik.message_processing import messages

    if not source_spans:
        return 0, []

    # Topological order: parents before children, so the parent_span_id
    # remap entry for every child is always populated by the time we
    # process it. ``sort_spans_topologically`` operates on dicts; we
    # convert through model_dump and back.
    span_dicts = [span.model_dump() for span in source_spans]
    span_dicts = sort_spans_topologically(span_dicts)

    span_id_remap: Dict[str, str] = {}
    feedback_scores: List[BatchFeedbackScoreDict] = []
    spans_emitted = 0
    for span_dict in span_dicts:
        original_id = span_dict.get("id")
        new_span_id = id_helpers_module.generate_id()
        if original_id:
            span_id_remap[original_id] = new_span_id

        original_parent = span_dict.get("parent_span_id")
        new_parent = span_id_remap.get(original_parent) if original_parent else None

        # Build CreateSpanMessage directly; bypasses span_client.create_span's
        # add_usage_to_metadata merge. Field-for-field mapping from
        # SpanPublic -> CreateSpanMessage.
        msg = messages.CreateSpanMessage(
            span_id=new_span_id,
            trace_id=new_trace_id,
            project_name=target_project_name,
            parent_span_id=new_parent,
            name=span_dict.get("name"),
            type=span_dict.get("type") or "general",
            start_time=span_dict.get("start_time")
            or datetime_helpers.local_timestamp(),
            end_time=span_dict.get("end_time"),
            input=span_dict.get("input"),
            output=span_dict.get("output"),
            metadata=span_dict.get("metadata"),
            tags=span_dict.get("tags"),
            usage=span_dict.get("usage"),
            model=span_dict.get("model"),
            provider=span_dict.get("provider"),
            error_info=_to_error_info_dict(span_dict.get("error_info")),
            total_cost=span_dict.get("total_estimated_cost"),
            last_updated_at=span_dict.get("last_updated_at"),
            source=span_dict.get("source") or "experiment",
        )
        client._streamer.put(msg)
        spans_emitted += 1

        # Collect per-span feedback scores keyed by the new span id so
        # the caller can batch-emit them via log_spans_feedback_scores
        # after the spans flush.
        for score in span_dict.get("feedback_scores") or []:
            entry: BatchFeedbackScoreDict = {
                "id": new_span_id,
                "project_name": target_project_name,
                "name": score["name"],
                "value": score["value"],
            }
            if score.get("reason") is not None:
                entry["reason"] = score["reason"]
            if score.get("category_name") is not None:
                entry["category_name"] = score["category_name"]
            feedback_scores.append(entry)

    return spans_emitted, feedback_scores


def _build_experiment_data(
    source: ExperimentPublic, items: List[experiment_item.ExperimentItemContent]
) -> ExperimentData:
    """Adapt the REST ``ExperimentPublic`` + items into the
    ``ExperimentData`` dataclass that ``recreate_experiment`` consumes.

    The disk-export shape stores experiment metadata as a flat dict matching
    the BE schema field names; we mirror that here so ``recreate_experiment``
    can read fields like ``type`` / ``evaluation_method`` / ``optimization_id``
    / ``tags`` / ``metadata`` / ``dataset_name`` verbatim.

    Per-item payload carries only the FK fields. The BE's ``ExperimentItem``
    Write view accepts only ``id`` / ``experiment_id`` / ``dataset_item_id``
    / ``trace_id`` (plus ``project_name``); every other per-item field
    surfaced on the Compare view (``input`` / ``output`` /
    ``feedback_scores`` / ``assertion_results`` / ``execution_policy`` /
    ``description`` / ``status`` / ``usage`` / ``total_estimated_cost`` /
    ``duration``) is READ-ONLY and computed/aggregated from the underlying
    trace + span + assertion-result entities. The cascade ensures those
    underlying entities are populated correctly at the destination (traces
    + spans copied with feedback scores; assertion results copied via the
    dedicated ``assertion_results.store_assertions_batch`` endpoint scoped
    to the new trace id); the BE surfaces the rest on read.
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
    items_dicts = [
        {
            "id": item.id,
            "trace_id": item.trace_id,
            "dataset_item_id": item.dataset_item_id,
        }
        for item in items
    ]
    return ExperimentData(experiment=experiment_dict, items=items_dicts)
