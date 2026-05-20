"""Stress repro for OPIK-6602: single-process ``opik migrate`` should not
manufacture concurrent writes against the same workspace.

Manifests as silent data loss against the current ``main`` branch:

* ``qa-v1-stress-traces-5k``: 100 dataset items + 5000 trace experiment
  items -> 50/100 dataset items, 2500/5000 experiment items survived.
* ``qa-v1-stress-mega``: 1468 dataset items across 27 versions ->
  833/1468 dataset items, 19/27 versions diverged.

Each parametrize shape mirrors one of those QA setups. Assertions are
exact: target item count == source item count at every layer the QA
failure surfaced -- per-version ``items_total``, per-version streamed
item count, dataset-level ``dataset_items_count``, experiment item count.
Anything less is the failure mode the ticket describes.

Notes on shape selection:

* The ``mega`` shape seeds 27 sequential versions on the same dataset to
  exercise the carry-over copy path (``COPY_VERSION_ITEMS`` in
  ``DatasetItemVersionDAO``). The OPIK-6601 visibility window fires
  on this path; a single-version seed can't reach it.
* The ``traces-5k`` shape stresses the experiment cascade (5k traces
  streamed while migrate is mid-flight on the dataset rename / replay
  / experiment-recreate sequence).

Seeding chunks every bulk-write to stay under the BE's per-request caps
(``DatasetItemBatch.@Size(max=1000)``, ``TraceBatch.@Size(max=1000)``,
``SpanBatch.@Size(max=1000)``).
"""

from __future__ import annotations

import datetime as dt
from pathlib import Path
from typing import Iterator, List, TypeVar

import pytest

import opik
from opik import id_helpers
from opik.rest_api import OpikApi
from opik.rest_api.types.dataset_item_write import DatasetItemWrite
from opik.rest_api.types.experiment_item import ExperimentItem
from opik.rest_api.types.span_write import SpanWrite
from opik.rest_api.types.trace_write import TraceWrite

from ...conftest import random_chars
from ...testlib import generate_project_name
from .conftest import (
    apply_changes,
    chronological_versions,
    create_dataset_shell,
    destination_experiment_items,
    find_destination_experiment,
    run_migrate_cli,
    stream_items_wire,
)

PROJECT_NAME = generate_project_name("e2e", __name__)

# BE per-request caps (DatasetItemBatch / TraceBatch / SpanBatch / ExperimentItem
# batch all share the same @Size(max=1000) constraint at the time of writing).
_BULK_INSERT_CHUNK = 1000

T = TypeVar("T")


def _chunked(items: List[T], size: int) -> Iterator[List[T]]:
    for start in range(0, len(items), size):
        yield items[start : start + size]


@pytest.fixture
def dataset_name() -> Iterator[str]:
    yield f"e2e-migrate-stress-{random_chars()}"


# Parametrize shapes.
#
#   (num_versions, items_added_per_version, num_trace_experiment_items)
#
# ``smoke``       — small enough to run in roughly a minute on a local
#                   backend, still multi-version + cascade.
# ``mega``        — mirrors qa-v1-stress-mega: 27 versions, ~54 items each
#                   (= 1458 total dataset items, close to 1468). Exercises
#                   the OPIK-6601 carry-over visibility race.
# ``traces-5k``   — mirrors qa-v1-stress-traces-5k: 100 items, 5000 trace
#                   experiment items. Exercises the experiment cascade.
_STRESS_SHAPES = [
    pytest.param(3, 20, 100, id="smoke"),
    pytest.param(27, 54, 0, marks=pytest.mark.slow, id="mega"),
    pytest.param(1, 100, 5000, marks=pytest.mark.slow, id="traces-5k"),
]


def _seed_experiment_with_traces(
    rest: OpikApi,
    *,
    experiment_name: str,
    dataset_name: str,
    dataset_id: str,
    dataset_version_id: str,
    project_name: str,
    experiment_item_ids: List[str],
) -> tuple[str, List[str]]:
    """Create one trace + one root span per ``experiment_item_id``, then
    attach them as ``experiment_items`` rows on a fresh experiment.

    Returns ``(experiment_id, trace_ids)``. Every bulk write is chunked at
    the BE @Size(max=1000) cap so this scales past the conftest helper's
    one-shot calls.
    """
    if not experiment_item_ids:
        # Caller wants an experiment-less stress shape.
        return "", []

    now = dt.datetime.now(dt.timezone.utc)

    trace_writes: List[TraceWrite] = []
    span_writes: List[SpanWrite] = []
    trace_ids: List[str] = []

    for index, item_id in enumerate(experiment_item_ids):
        trace_id = id_helpers.generate_id()
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
                tags=["e2e-stress"],
            )
        )
        span_writes.append(
            SpanWrite(
                id=id_helpers.generate_id(),
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

    for chunk in _chunked(trace_writes, _BULK_INSERT_CHUNK):
        rest.traces.create_traces(traces=chunk)
    for chunk in _chunked(span_writes, _BULK_INSERT_CHUNK):
        rest.spans.create_spans(spans=chunk)

    new_experiment_id = id_helpers.generate_id()
    rest.experiments.create_experiment(
        id=new_experiment_id,
        name=experiment_name,
        dataset_name=dataset_name,
        type="regular",
        evaluation_method="dataset",
        dataset_version_id=dataset_version_id,
        project_name=project_name,
    )

    experiment_items = [
        ExperimentItem(
            id=id_helpers.generate_id(),
            experiment_id=new_experiment_id,
            dataset_item_id=item_id,
            trace_id=trace_id,
        )
        for item_id, trace_id in zip(experiment_item_ids, trace_ids)
    ]
    for chunk in _chunked(experiment_items, _BULK_INSERT_CHUNK):
        rest.experiments.create_experiment_items(experiment_items=chunk)

    return new_experiment_id, trace_ids


@pytest.mark.parametrize(
    ("num_versions", "items_added_per_version", "num_trace_experiment_items"),
    _STRESS_SHAPES,
)
def test_migrate_dataset__stress__no_silent_data_loss(
    opik_client: opik.Opik,
    source_project_name: str,
    target_project_name: str,
    dataset_name: str,
    tmp_path: Path,
    num_versions: int,
    items_added_per_version: int,
    num_trace_experiment_items: int,
) -> None:
    """Seed a multi-version source dataset (optionally with a large
    experiment) and assert NO items are silently dropped on the destination.

    The QA-failure dimensions verified at each layer:

    * Dataset-level: ``dataset_items_count`` matches source.
    * Version chain: target version count == source version count.
    * Per-version: ``items_total`` matches at every version, AND the
      streamed item count at each version matches what the source
      streamed for that version.
    * Experiment cascade: destination experiment items count == source
      experiment items count (when the shape includes an experiment).
    """
    rest = opik_client.rest_client
    total_source_items = num_versions * items_added_per_version

    # ── Seed source dataset with N versions. ──
    source_id = create_dataset_shell(rest, dataset_name, source_project_name)

    # v1: single batch_group_id -> one BE version.
    v1_payloads = [
        DatasetItemWrite(
            source="manual",
            data={"q": f"v1-i{i}", "a": f"A1-{i}"},
        )
        for i in range(items_added_per_version)
    ]
    batch_group_id = id_helpers.generate_id()
    for chunk in _chunked(v1_payloads, _BULK_INSERT_CHUNK):
        rest.datasets.create_or_update_dataset_items(
            dataset_id=source_id,
            items=chunk,
            batch_group_id=batch_group_id,
        )
    base_version_id = (
        rest.datasets.list_dataset_versions(id=source_id, page=1, size=1).content[0].id
    )

    # v2..vN: each ``apply_dataset_item_changes`` with added_items drives
    # the BE's carry-over copy path that OPIK-6601's visibility race fires
    # on. We capture the new version id after each apply for the next call.
    for v in range(2, num_versions + 1):
        added_items = [
            {"data": {"q": f"v{v}-i{i}", "a": f"A{v}-{i}"}, "source": "manual"}
            for i in range(items_added_per_version)
        ]
        assert items_added_per_version <= _BULK_INSERT_CHUNK
        base_version_id = apply_changes(
            rest,
            source_id,
            base_version_id=base_version_id,
            added_items=added_items,
            change_description=f"v{v}",
        )

    # ── Pre-migration: verify the source itself is healthy. ──
    # If the source seed itself diverged (BE race fired during seeding),
    # the test would falsely blame the migration. Catch that up-front so
    # a failing assertion further down is unambiguously about migrate.
    src_dataset_row = rest.datasets.get_dataset_by_identifier(
        dataset_name=dataset_name, project_name=source_project_name
    )
    src_versions = chronological_versions(rest, src_dataset_row.id)
    assert len(src_versions) == num_versions, (
        f"source has {len(src_versions)} versions, expected {num_versions} "
        "— seeding itself diverged before we ran migrate"
    )
    assert src_dataset_row.dataset_items_count == total_source_items, (
        f"source dataset_items_count={src_dataset_row.dataset_items_count}, "
        f"expected {total_source_items} — seeding itself diverged "
        "(possible OPIK-6600/6601 BE race during the seed)"
    )
    for idx, sv in enumerate(src_versions):
        expected_total = (idx + 1) * items_added_per_version
        assert sv.items_total == expected_total, (
            f"source v{idx + 1} items_total={sv.items_total}, "
            f"expected {expected_total} — seeding itself diverged"
        )

    # Latest-version streamed items: build the source-side ground truth
    # for the per-version stream comparison below.
    src_items_per_version = []
    for sv in src_versions:
        src_items = stream_items_wire(
            rest,
            dataset_name=dataset_name,
            project_name=source_project_name,
            version_hash=sv.version_hash,
        )
        src_items_per_version.append(len(src_items))

    # ── Optional: seed an experiment with N traces. ──
    item_ids_in_latest = [
        it.id
        for it in stream_items_wire(
            rest,
            dataset_name=dataset_name,
            project_name=source_project_name,
            version_hash=src_versions[-1].version_hash,
        )
        if it.id is not None
    ]
    experiment_name = f"e2e-stress-{random_chars()}"
    source_experiment_id, trace_ids = "", []
    if num_trace_experiment_items > 0:
        assert item_ids_in_latest, "no dataset item ids to seed against"
        # Rotate item ids if the experiment is larger than the dataset.
        experiment_item_ids = [
            item_ids_in_latest[i % len(item_ids_in_latest)]
            for i in range(num_trace_experiment_items)
        ]
        source_experiment_id, trace_ids = _seed_experiment_with_traces(
            rest,
            experiment_name=experiment_name,
            dataset_name=dataset_name,
            dataset_id=source_id,
            dataset_version_id=src_versions[-1].id,
            project_name=source_project_name,
            experiment_item_ids=experiment_item_ids,
        )
        assert len(trace_ids) == num_trace_experiment_items

    # ── Run the migration. ──
    audit_path = tmp_path / "audit.json"
    result = run_migrate_cli(
        [
            "dataset",
            dataset_name,
            "--to-project",
            target_project_name,
        ],
        audit_log_path=str(audit_path),
    )
    assert result.returncode == 0, (
        f"opik migrate exited non-zero ({result.returncode}).\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )

    # ── Verify destination: version count, dataset_items_count, per-version. ──
    tgt_dataset_row = rest.datasets.get_dataset_by_identifier(
        dataset_name=dataset_name, project_name=target_project_name
    )
    tgt_versions = chronological_versions(rest, tgt_dataset_row.id)
    assert len(tgt_versions) == num_versions, (
        f"target version count {len(tgt_versions)} != source {num_versions}"
    )
    assert tgt_dataset_row.dataset_items_count == total_source_items, (
        f"target dataset_items_count={tgt_dataset_row.dataset_items_count}, "
        f"expected {total_source_items}. "
        f"Lost {total_source_items - tgt_dataset_row.dataset_items_count} items "
        "— this is the OPIK-6602 self-concurrency failure mode."
    )
    for idx, (sv, tv) in enumerate(zip(src_versions, tgt_versions)):
        expected_total = (idx + 1) * items_added_per_version
        assert tv.items_total == expected_total, (
            f"target v{idx + 1} items_total={tv.items_total}, "
            f"expected {expected_total} (source v{idx + 1} had "
            f"items_total={sv.items_total}). "
            "OPIK-6601-style carry-over truncation on the destination."
        )
        tgt_v_items = stream_items_wire(
            rest,
            dataset_name=dataset_name,
            project_name=target_project_name,
            version_hash=tv.version_hash,
        )
        assert len(tgt_v_items) == src_items_per_version[idx], (
            f"target v{idx + 1} streamed {len(tgt_v_items)} items, "
            f"source streamed {src_items_per_version[idx]} — "
            "items vanished between source and target at the per-version stream layer."
        )

    # ── Optional: verify destination experiment item count. ──
    if num_trace_experiment_items > 0:
        dest_exp = find_destination_experiment(
            rest,
            destination_dataset_id=tgt_dataset_row.id,
            experiment_name=experiment_name,
        )
        dest_items = destination_experiment_items(
            rest,
            experiment_id=dest_exp.id,
            dataset_id=tgt_dataset_row.id,
        )
        assert len(dest_items) == num_trace_experiment_items, (
            f"target experiment items {len(dest_items)} != source "
            f"{num_trace_experiment_items}. "
            f"Lost {num_trace_experiment_items - len(dest_items)} experiment items — "
            "OPIK-6602 self-concurrency failure mode."
        )
        # Sanity: destination experiment is a fresh entity (not the source).
        assert dest_exp.id != source_experiment_id
