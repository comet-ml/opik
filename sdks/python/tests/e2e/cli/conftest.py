"""Shared fixtures + helpers for ``opik migrate`` e2e tests.

These tests drive ``opik migrate dataset`` against a real backend
(localhost during dev, the CI-provisioned Opik in CI). They verify
per-version fidelity end-to-end: items, item-level fields (data,
description, tags, evaluators, execution_policy, source), version-level
fields (suite evaluators, execution_policy, user tags, metadata), and
display order.

Helpers live here so individual test files stay focused on the scenarios
they exercise. Wire-type item reads (via ``rest_stream_parser`` directly)
mirror what ``cli/migrate/datasets/version_replay.py`` does in production
— the SDK dataclass strips per-item ``tags`` during reconstruction, so
asserting tag fidelity requires the wire type.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from typing import Any, Dict, Iterator, List, Optional, Set

import pytest

import opik
from opik.api_objects import rest_stream_parser
from opik.rest_api import OpikApi
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types import dataset_item_public, dataset_version_public

from ...conftest import random_chars


# ---------------------------------------------------------------------------
# Project fixtures — ephemeral source + target, deleted on teardown
# ---------------------------------------------------------------------------


@pytest.fixture
def source_project_name(opik_client: opik.Opik) -> Iterator[str]:
    """Create an ephemeral source project for the migration test.

    Deleted on teardown (best-effort — tolerates already-deleted state).
    Each test gets its own project so parallel runs don't collide on
    dataset-name uniqueness (datasets are workspace-scoped, not
    project-scoped, in Opik's BE — see Slice 1's collision pre-flight).
    """
    name = f"e2e-cli-migrate-source-{random_chars()}"
    opik_client.rest_client.projects.create_project(name=name)
    yield name
    _best_effort_delete_project(opik_client.rest_client, name)


@pytest.fixture
def target_project_name(opik_client: opik.Opik) -> Iterator[str]:
    """Create an ephemeral target project for the migration test."""
    name = f"e2e-cli-migrate-target-{random_chars()}"
    opik_client.rest_client.projects.create_project(name=name)
    yield name
    _best_effort_delete_project(opik_client.rest_client, name)


def _best_effort_delete_project(rest_client: OpikApi, name: str) -> None:
    try:
        project_id = rest_client.projects.retrieve_project(name=name).id
        rest_client.projects.delete_project_by_id(project_id)
    except ApiError:
        # Already gone (404) or insufficient permissions — either way
        # cleanup is non-blocking; leave the project for the next run to
        # garbage-collect or for a maintenance task to clean up.
        pass


# ---------------------------------------------------------------------------
# CLI invocation
# ---------------------------------------------------------------------------


def run_migrate_cli(
    args: List[str], audit_log_path: Optional[str] = None
) -> subprocess.CompletedProcess:
    """Invoke ``opik migrate`` via the installed CLI entrypoint.

    Uses subprocess (not Click's ``CliRunner``) so the test exercises the
    same code path real users hit — module import, Click group setup,
    config-chain resolution, exit-code handling, stderr routing. Returns
    the completed process so the caller can assert on ``returncode``,
    ``stdout``, ``stderr``.

    ``--audit-log`` is appended when provided. Tests typically write to a
    tmp_path so the JSON can be re-read and asserted on.
    """
    cmd = [sys.executable, "-m", "opik.cli", "migrate"] + args
    if audit_log_path is not None:
        cmd.extend(["--audit-log", audit_log_path])
    return subprocess.run(cmd, capture_output=True, text=True)


# ---------------------------------------------------------------------------
# Multi-version source seeding
# ---------------------------------------------------------------------------


def create_dataset_shell(
    rest_client: OpikApi,
    name: str,
    project_name: str,
    *,
    type: Optional[str] = None,
) -> str:
    """Create an empty dataset (or test suite) and return its id.

    ``type='evaluation_suite'`` produces a test suite (carries version-
    level evaluators + execution_policy); omit for a plain dataset.
    Caller is responsible for seeding versions via ``apply_changes``.
    """
    kwargs: Dict[str, Any] = {"name": name, "project_name": project_name}
    if type is not None:
        kwargs["type"] = type
    rest_client.datasets.create_dataset(**kwargs)
    ds = rest_client.datasets.get_dataset_by_identifier(
        dataset_name=name, project_name=project_name
    )
    return ds.id


def apply_changes(
    rest_client: OpikApi,
    dataset_id: str,
    *,
    base_version_id: Optional[str],
    added_items: Optional[List[Dict[str, Any]]] = None,
    edited_items: Optional[List[Dict[str, Any]]] = None,
    deleted_ids: Optional[List[str]] = None,
    change_description: Optional[str] = None,
    suite_evaluators: Optional[List[Dict[str, Any]]] = None,
    suite_execution_policy: Optional[Dict[str, int]] = None,
    metadata: Optional[Dict[str, str]] = None,
    user_tags: Optional[List[str]] = None,
    override: bool = False,
) -> str:
    """Send ``apply_dataset_item_changes`` and return the new version id.

    Thin wrapper over the raw REST endpoint that mirrors the BE schema's
    field names. Used to seed multi-version source datasets for migration
    tests. ``override=True`` is required for the first version (when
    ``base_version_id=None``); see the BE validation in
    ``DatasetItemService.applyDeltaChanges``.
    """
    request: Dict[str, Any] = {}
    if change_description is not None:
        request["change_description"] = change_description
    if base_version_id is not None:
        request["base_version"] = base_version_id
    if added_items:
        request["added_items"] = added_items
    if edited_items:
        request["edited_items"] = edited_items
    if deleted_ids:
        request["deleted_ids"] = deleted_ids
    if suite_evaluators is not None:
        request["evaluators"] = suite_evaluators
    if suite_execution_policy is not None:
        request["execution_policy"] = suite_execution_policy
    if metadata is not None:
        request["metadata"] = metadata
    if user_tags is not None:
        request["tags"] = user_tags
    new_version = rest_client.datasets.apply_dataset_item_changes(
        id=dataset_id, request=request, override=override
    )
    return new_version.id


# ---------------------------------------------------------------------------
# Verification helpers (read side)
# ---------------------------------------------------------------------------


def chronological_versions(
    rest_client: OpikApi, dataset_id: str
) -> List[dataset_version_public.DatasetVersionPublic]:
    """Return every version of ``dataset_id`` oldest-first.

    The REST endpoint returns newest-first; we paginate to exhaustion and
    reverse so tests can iterate alongside source-version order for per-
    version comparisons.
    """
    out: List[dataset_version_public.DatasetVersionPublic] = []
    page = 1
    while True:
        resp = rest_client.datasets.list_dataset_versions(
            id=dataset_id, page=page, size=100
        )
        if not resp.content:
            break
        out.extend(resp.content)
        if len(resp.content) < 100:
            break
        page += 1
    out.reverse()
    return out


def stream_items_wire(
    rest_client: OpikApi,
    *,
    dataset_name: str,
    project_name: Optional[str],
    version_hash: Optional[str],
) -> List[dataset_item_public.DatasetItemPublic]:
    """Read items at ``version_hash`` via the raw REST stream + wire type.

    The SDK helper ``rest_operations.stream_dataset_items`` drops per-item
    ``tags`` during dataclass reconstruction, so tests that assert tag
    fidelity must go through the wire type directly. Mirrors the same
    approach used by ``cli/migrate/datasets/version_replay.py`` in
    production.
    """
    raw_stream = rest_client.datasets.stream_dataset_items(
        dataset_name=dataset_name,
        project_name=project_name,
        dataset_version=version_hash,
    )
    return rest_stream_parser.read_and_parse_stream(
        stream=raw_stream,
        item_class=dataset_item_public.DatasetItemPublic,
    )


def item_content_hash(item: dataset_item_public.DatasetItemPublic) -> str:
    """Full-fidelity per-item hash covering every persisted user field.

    Mirrors the production hash in ``cli/migrate/datasets/version_replay._content_hash_for``
    so source-version vs target-version set-equality checks behave the
    same way the migration code does internally (i.e. any field change
    is treated as a content change).
    """
    content: Dict[str, Any] = {"data": dict(item.data) if item.data else {}}
    if item.description is not None:
        content["description"] = item.description
    if item.tags is not None:
        content["tags"] = sorted(item.tags)
    if item.evaluators is not None:
        content["evaluators"] = [
            {"name": e.name, "type": e.type, "config": e.config}
            for e in item.evaluators
        ]
    if item.execution_policy is not None:
        content["execution_policy"] = {
            "runs_per_item": item.execution_policy.runs_per_item,
            "pass_threshold": item.execution_policy.pass_threshold,
        }
    if item.source is not None:
        content["source"] = item.source
    return hashlib.sha256(
        json.dumps(content, sort_keys=True, default=str).encode()
    ).hexdigest()


def item_hashes(items: List[dataset_item_public.DatasetItemPublic]) -> Set[str]:
    return {item_content_hash(it) for it in items}


def display_order(
    items: List[dataset_item_public.DatasetItemPublic], key: str = "q"
) -> List[Optional[Any]]:
    """Extract one ``data`` field per item in stream order (newest-first).

    The stream's order *is* the UI's display order, so two versions' lists
    of (e.g.) ``q`` values match iff the visible order matches.
    """
    return [(item.data.get(key) if item.data else None) for item in items]


def normalize_evaluators(evals: Optional[List[Any]]) -> List[Dict[str, Any]]:
    """Compare-friendly form of a suite evaluator list.

    Strips wire-type wrapping and sorts by name so identical
    configurations hash equal regardless of how the BE happened to
    serialise them.
    """
    if not evals:
        return []
    return sorted(
        ({"name": e.name, "type": e.type, "config": e.config} for e in evals),
        key=lambda d: d["name"],
    )


def normalize_policy(pol: Any) -> Optional[Dict[str, int]]:
    """Compare-friendly form of an execution_policy."""
    if pol is None:
        return None
    return {
        "runs_per_item": pol.runs_per_item,
        "pass_threshold": pol.pass_threshold,
    }


def strip_be_managed_version_tags(
    tags: Optional[List[str]],
) -> List[str]:
    """Drop the BE-managed ``'latest'`` marker so source/target tag lists compare equal.

    The BE auto-injects ``'latest'`` on the newest version of any dataset
    on read; the migration code filters it out before forwarding to avoid
    409 conflicts. Tests strip it on both sides for the same reason.
    """
    return sorted(t for t in (tags or []) if t != "latest")


# ---------------------------------------------------------------------------
# Cascade seeding (Slice 3: experiment + traces + spans)
#
# Tests seed an experiment + its trace data directly via REST. We do this
# rather than going through ``opik.evaluate`` because the BE-side wire
# shapes are what the cascade reads from, and we want full control over
# trace ids, span tree topology, and feedback score payloads.
# ---------------------------------------------------------------------------


def seed_experiment_with_trace_tree(
    rest_client: OpikApi,
    *,
    experiment_name: str,
    dataset_name: str,
    dataset_id: str,
    dataset_version_id: Optional[str],
    project_name: str,
    item_ids: List[str],
    experiment_config: Optional[Dict[str, Any]] = None,
    experiment_type: str = "regular",
    evaluation_method: str = "dataset",
    experiment_tags: Optional[List[str]] = None,
    spans_per_trace: int = 2,
    feedback_scores_per_trace: Optional[List[Dict[str, Any]]] = None,
    per_item_extras: Optional[List[Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    """Create a source experiment + one trace per ``item_id`` + a small span
    tree per trace, then attach everything via ``create_experiment_items``.

    Returns a dict the cascade tests assert against:

      {
        "experiment_id": str,
        "trace_ids": [str, ...],          # one per item_id, same order
        "span_ids_by_trace": {trace_id: [root_span_id, child_span_id, ...]},
        "feedback_scores_by_trace": {trace_id: [score_dicts...]},
      }

    ``spans_per_trace`` controls the tree size; ``spans_per_trace >= 2``
    produces a root + child(ren) layout so the cascade has to remap
    parent_span_id. We deliberately do NOT use ``opik.evaluate`` here -- we
    want the wire shape, deterministic ids, and to assert on it without
    flush/streamer timing concerns.
    """
    import datetime as dt
    import opik.id_helpers as id_helpers_module
    from opik.rest_api.types.experiment_item import ExperimentItem
    from opik.rest_api.types.feedback_score_batch_item import (
        FeedbackScoreBatchItem,
    )
    from opik.rest_api.types.span_write import SpanWrite
    from opik.rest_api.types.trace_write import TraceWrite

    if spans_per_trace < 1:
        raise ValueError("spans_per_trace must be >= 1")

    now = dt.datetime.now(dt.timezone.utc)

    trace_ids: List[str] = []
    span_ids_by_trace: Dict[str, List[str]] = {}
    feedback_scores_by_trace: Dict[str, List[Dict[str, Any]]] = {}

    trace_writes: List[TraceWrite] = []
    span_writes: List[SpanWrite] = []
    feedback_batch: List[FeedbackScoreBatchItem] = []

    for index, item_id in enumerate(item_ids):
        trace_id = id_helpers_module.generate_id()
        trace_ids.append(trace_id)
        trace_writes.append(
            TraceWrite(
                id=trace_id,
                project_name=project_name,
                name=f"task-{index}",
                start_time=now,
                end_time=now + dt.timedelta(milliseconds=10),
                input={"item": item_id},
                output={"answer": f"output-{index}"},
                metadata={"item_id": item_id},
                tags=["e2e-cascade"],
            )
        )

        # Span tree: root + (spans_per_trace - 1) children of the root.
        # Children all parent on the root so the cascade has to remap
        # parent_span_id at least once.
        root_span_id = id_helpers_module.generate_id()
        span_ids_by_trace[trace_id] = [root_span_id]
        span_writes.append(
            SpanWrite(
                id=root_span_id,
                project_name=project_name,
                trace_id=trace_id,
                parent_span_id=None,
                name=f"root-{index}",
                type="general",
                start_time=now,
                end_time=now + dt.timedelta(milliseconds=10),
                input={"item": item_id},
                output={"answer": f"output-{index}"},
            )
        )
        for child_index in range(spans_per_trace - 1):
            child_span_id = id_helpers_module.generate_id()
            span_ids_by_trace[trace_id].append(child_span_id)
            span_writes.append(
                SpanWrite(
                    id=child_span_id,
                    project_name=project_name,
                    trace_id=trace_id,
                    parent_span_id=root_span_id,
                    name=f"llm-call-{index}-{child_index}",
                    type="llm",
                    start_time=now + dt.timedelta(milliseconds=1),
                    end_time=now + dt.timedelta(milliseconds=9),
                    input={"prompt": "..."},
                    output={"completion": f"output-{index}"},
                    model="gpt-mock",
                    provider="mock",
                    usage={"prompt_tokens": 5, "completion_tokens": 10},
                )
            )

        # Optional: attach feedback scores to the trace.
        if feedback_scores_per_trace:
            scores_for_this_trace: List[Dict[str, Any]] = []
            for score in feedback_scores_per_trace:
                feedback_batch.append(
                    FeedbackScoreBatchItem(
                        id=trace_id,
                        project_name=project_name,
                        name=score["name"],
                        value=score["value"],
                        reason=score.get("reason"),
                        source="sdk",
                    )
                )
                scores_for_this_trace.append(score)
            feedback_scores_by_trace[trace_id] = scores_for_this_trace

    rest_client.traces.create_traces(traces=trace_writes)
    rest_client.spans.create_spans(spans=span_writes)
    if feedback_batch:
        rest_client.traces.score_batch_of_traces(scores=feedback_batch)

    # Create the experiment, then attach experiment items wiring item_id
    # to trace_id 1:1.
    import opik.id_helpers as _id_helpers

    new_experiment_id = _id_helpers.generate_id()
    rest_client.experiments.create_experiment(
        id=new_experiment_id,
        name=experiment_name,
        dataset_name=dataset_name,
        type=experiment_type,
        evaluation_method=evaluation_method,
        tags=experiment_tags,
        metadata=experiment_config,
        dataset_version_id=dataset_version_id,
        project_name=project_name,
    )

    extras_list = per_item_extras or [{} for _ in item_ids]
    if len(extras_list) != len(item_ids):
        raise ValueError("per_item_extras must have the same length as item_ids")

    # ``assertion_results`` are persisted via the dedicated
    # ``assertion_results.store_assertions_batch(entity_type='TRACE', ...)``
    # endpoint -- the ``ExperimentItem.assertion_results`` field is dropped
    # silently on the BE Write view (it's READ-ONLY on the Compare view,
    # computed from the underlying assertion-results entity table). Same
    # for the other per-item fidelity fields like input/output -- those
    # are BE-computed read aggregates.
    #
    # The seed builds a separate assertion-batch from each item's extras
    # before constructing the ExperimentItem write (which only carries the
    # FK fields). This mirrors how the cascade itself writes assertions.
    from opik.rest_api.types.assertion_result_batch_item import (
        AssertionResultBatchItem,
    )

    assertion_batch: List[AssertionResultBatchItem] = []
    assertion_results_by_trace: Dict[str, List[Dict[str, Any]]] = {}
    experiment_items_to_create: List[ExperimentItem] = []
    for item_id, trace_id, extras in zip(item_ids, trace_ids, extras_list):
        per_item_assertions = extras.get("assertion_results") or []
        for ar in per_item_assertions:
            value = (
                ar.get("value") if isinstance(ar, dict) else getattr(ar, "value", None)
            )
            passed = (
                ar.get("passed")
                if isinstance(ar, dict)
                else getattr(ar, "passed", None)
            )
            reason = (
                ar.get("reason")
                if isinstance(ar, dict)
                else getattr(ar, "reason", None)
            )
            if value is None or passed is None:
                continue
            assertion_batch.append(
                AssertionResultBatchItem(
                    entity_id=trace_id,
                    project_name=project_name,
                    name=value,
                    status="passed" if passed else "failed",
                    reason=reason,
                    source="sdk",
                )
            )
            assertion_results_by_trace.setdefault(trace_id, []).append(
                {"value": value, "passed": passed, "reason": reason}
            )

        # The remaining extras are READ-ONLY on the BE; we don't write
        # them. Forwarding them on the ExperimentItem create payload would
        # be silently dropped (BE Write view doesn't include them).
        experiment_items_to_create.append(
            ExperimentItem(
                id=_id_helpers.generate_id(),
                experiment_id=new_experiment_id,
                dataset_item_id=item_id,
                trace_id=trace_id,
            )
        )

    rest_client.experiments.create_experiment_items(
        experiment_items=experiment_items_to_create
    )

    if assertion_batch:
        rest_client.assertion_results.store_assertions_batch(
            entity_type="TRACE",
            assertion_results=assertion_batch,
        )

    return {
        "experiment_id": new_experiment_id,
        "trace_ids": trace_ids,
        "span_ids_by_trace": span_ids_by_trace,
        "feedback_scores_by_trace": feedback_scores_by_trace,
        "assertion_results_by_trace": assertion_results_by_trace,
    }


def find_destination_experiment(
    rest_client: OpikApi,
    *,
    destination_dataset_id: str,
    experiment_name: str,
) -> Any:
    """Locate the cascaded experiment at the destination by name + dataset.

    Returns the ``ExperimentPublic``. Raises if zero or multiple match;
    the cascade is supposed to recreate one experiment per source
    experiment, so neither outcome is silently acceptable.
    """
    page = rest_client.experiments.find_experiments(
        dataset_id=destination_dataset_id,
        page=1,
        size=100,
        name=experiment_name,
    )
    matched = [e for e in (page.content or []) if e.name == experiment_name]
    if len(matched) != 1:
        raise AssertionError(
            f"expected exactly one destination experiment named "
            f"{experiment_name!r} under dataset {destination_dataset_id}, "
            f"got {len(matched)}"
        )
    return matched[0]


def destination_experiment_items(
    rest_client: OpikApi,
    *,
    experiment_id: str,
    dataset_id: str,
) -> List[Any]:
    """Materialise the destination experiment's items via the Compare view.

    The cascade's source-side read uses
    ``datasets.find_dataset_items_with_experiment_items`` because only the
    Compare view surfaces ``assertion_results`` / ``feedback_scores`` /
    ``input`` / ``output``. We use the same endpoint for destination
    verification so tests can assert on those fields directly (the slim
    ``stream_experiment_items`` Public view drops them).

    Returns a flat list of ``ExperimentItemCompare`` -- one per source
    experiment item.
    """
    experiment_ids_filter = json.dumps([experiment_id])
    collected: List[Any] = []
    page = 1
    while True:
        resp = rest_client.datasets.find_dataset_items_with_experiment_items(
            id=dataset_id,
            experiment_ids=experiment_ids_filter,
            page=page,
            size=100,
        )
        content = resp.content or []
        if not content:
            break
        for ds_item in content:
            for ei in ds_item.experiment_items or []:
                if ei.experiment_id == experiment_id:
                    collected.append(ei)
        if len(content) < 100:
            break
        page += 1
    return collected


def destination_spans_for_trace(
    rest_client: OpikApi, *, trace_id: str, project_name: str
) -> List[Any]:
    """Read all destination spans for one destination trace, paginating.

    ``get_spans_by_project`` requires ``project_name`` (or ``project_id``)
    on the request; without it the BE 400s. We pass the destination
    project name explicitly.
    """
    out: List[Any] = []
    page = 1
    while True:
        resp = rest_client.spans.get_spans_by_project(
            project_name=project_name,
            trace_id=trace_id,
            page=page,
            size=100,
        )
        if not resp.content:
            break
        out.extend(resp.content)
        if len(resp.content) < 100:
            break
        page += 1
    return out


def destination_feedback_scores_for_trace(
    rest_client: OpikApi, *, trace_id: str
) -> List[Any]:
    """Read feedback scores on a destination trace.

    The trace's ``feedback_scores`` field on read is the authoritative
    source -- the cascade copies them implicitly because trace metadata
    isn't the only place they live (per-trace ``feedback_scores`` table).
    Today's cascade does NOT explicitly re-emit feedback scores; this
    helper lets a test assert that as an explicit known-gap or as
    "preserved if and only if the cascade adds the copy".
    """
    trace = rest_client.traces.get_trace_by_id(id=trace_id)
    return list(trace.feedback_scores or [])
