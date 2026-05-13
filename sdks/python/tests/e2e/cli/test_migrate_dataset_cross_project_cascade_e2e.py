"""End-to-end test for ``opik migrate dataset`` with a cross-project experiment.

``opik.evaluate`` co-locates an experiment's traces in one project, but the
BE permits ``experiment_items`` rows that point at traces living in a
*different* project from the experiment's own project. The BE populates
``experiment_items.project_id`` from ``traces.project_id`` at write time
(``ExperimentItemService.populateProjectIdFromTraces``), so the rows are
the authoritative source of truth for "which projects do this experiment's
traces actually live in".

This test pins the cascade's cross-project contract: regardless of how
many distinct source projects the experiment's traces are spread across,
the migration funnels every one of them into the single ``--to-project``
destination.

Shape (three distinct source projects per experiment, plus the target):

  1. Source dataset lives in project A (``source_project_name``)
  2. ``seed_experiment_with_trace_tree`` seeds the experiment + 2 traces in A
  3. We mint a trace + span in project B (``cross_project_name_b``) and
     another in project C (``cross_project_name_c``), then append two
     ``experiment_items`` rows pointing the same experiment at them. The
     experiment now has traces spread across A, B, and C — the user only
     "sees" A (the experiment's own project) but the cascade must reach
     into B and C as well.
  4. Run ``opik migrate dataset ... --to-project=<destination>``
  5. Assert: destination experiment has all 4 items, every destination
     trace lives under the destination project, and the source trace ids
     (across all three source projects) are not reused.
"""

from __future__ import annotations

import datetime as dt
from pathlib import Path
from typing import Any, Iterator, List

import pytest

import opik
import opik.id_helpers as id_helpers_module
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types.experiment_item import ExperimentItem
from opik.rest_api.types.span_write import SpanWrite
from opik.rest_api.types.trace_write import TraceWrite

from ...conftest import random_chars
from ...testlib import generate_project_name
from .. import verifiers
from .conftest import (
    create_dataset_shell,
    destination_experiment_items,
    destination_spans_for_trace,
    find_destination_experiment,
    run_migrate_cli,
    seed_experiment_with_trace_tree,
    stream_items_wire,
)

PROJECT_NAME = generate_project_name("e2e", __name__)


def _make_cross_project(opik_client: opik.Opik, label: str) -> Iterator[str]:
    name = f"e2e-cli-migrate-cross-{label}-{random_chars()}"
    rest = opik_client.rest_client
    rest.projects.create_project(name=name)
    yield name
    try:
        project_id = rest.projects.retrieve_project(name=name).id
        rest.projects.delete_project_by_id(project_id)
    except ApiError:
        pass


@pytest.fixture
def cross_project_name_b(opik_client: opik.Opik) -> Iterator[str]:
    yield from _make_cross_project(opik_client, "b")


@pytest.fixture
def cross_project_name_c(opik_client: opik.Opik) -> Iterator[str]:
    yield from _make_cross_project(opik_client, "c")


@pytest.fixture
def dataset_name() -> Iterator[str]:
    yield f"e2e-migrate-cross-{random_chars()}"


def _mint_cross_project_trace(
    rest: Any,
    *,
    project_name: str,
    label: str,
) -> str:
    """Create one trace + one root span in ``project_name`` and return the trace id."""
    now = dt.datetime.now(dt.timezone.utc)
    trace_id = id_helpers_module.generate_id()
    span_id = id_helpers_module.generate_id()
    rest.traces.create_traces(
        traces=[
            TraceWrite(
                id=trace_id,
                project_name=project_name,
                name=f"task-cross-{label}",
                start_time=now,
                end_time=now + dt.timedelta(milliseconds=10),
                input={"q": f"Q-cross-{label}"},
                output={"answer": f"from-{label}"},
                tags=["e2e-cross"],
            )
        ]
    )
    rest.spans.create_spans(
        spans=[
            SpanWrite(
                id=span_id,
                project_name=project_name,
                trace_id=trace_id,
                parent_span_id=None,
                name=f"root-{label}",
                type="general",
                start_time=now,
                end_time=now + dt.timedelta(milliseconds=10),
                input={"q": f"Q-cross-{label}"},
                output={"answer": f"from-{label}"},
            )
        ]
    )
    return trace_id


def test_migrate_dataset__cross_project_experiment__all_traces_land_in_target(
    opik_client: opik.Opik,
    source_project_name: str,
    target_project_name: str,
    cross_project_name_b: str,
    cross_project_name_c: str,
    dataset_name: str,
    tmp_path: Path,
) -> None:
    rest = opik_client.rest_client

    # ── Seed the source dataset in project A ──
    # All items (same-project + both cross-project) are added to a single
    # v1 so the experiment's items all resolve under the same dataset
    # version -- mixing experiment_items rows against different dataset
    # versions causes the Compare endpoint to surface incomplete rows
    # (missing ``source``). That's a known corner case unrelated to the
    # cross-project plumbing under test, so we sidestep it by seeding a
    # single version.
    from opik import id_helpers
    from opik.rest_api.types.dataset_item_write import DatasetItemWrite

    source_id = create_dataset_shell(rest, dataset_name, source_project_name)
    rest.datasets.create_or_update_dataset_items(
        dataset_id=source_id,
        items=[
            DatasetItemWrite(source="manual", data={"q": "Q1", "a": "A1"}),
            DatasetItemWrite(source="manual", data={"q": "Q2", "a": "A2"}),
            DatasetItemWrite(source="manual", data={"q": "Q-cross-b", "a": "A-b"}),
            DatasetItemWrite(source="manual", data={"q": "Q-cross-c", "a": "A-c"}),
        ],
        batch_group_id=id_helpers.generate_id(),
    )
    v1 = rest.datasets.list_dataset_versions(id=source_id, page=1, size=1).content[0]

    v1_items = stream_items_wire(
        rest,
        dataset_name=dataset_name,
        project_name=source_project_name,
        version_hash=v1.version_hash,
    )
    by_q = {(it.data or {}).get("q"): it.id for it in v1_items}
    same_project_item_ids = [by_q["Q1"], by_q["Q2"]]
    cross_b_item_id = by_q["Q-cross-b"]
    cross_c_item_id = by_q["Q-cross-c"]
    assert all(same_project_item_ids) and cross_b_item_id and cross_c_item_id

    # ── Seed the experiment with same-project traces in A ──
    experiment_name = f"e2e-cross-{random_chars()}"
    cascade_seed = seed_experiment_with_trace_tree(
        rest,
        experiment_name=experiment_name,
        dataset_name=dataset_name,
        dataset_id=source_id,
        dataset_version_id=v1.id,
        project_name=source_project_name,
        item_ids=same_project_item_ids,
        spans_per_trace=2,
    )
    source_experiment_id = cascade_seed["experiment_id"]
    same_project_trace_ids = cascade_seed["trace_ids"]

    # ── Mint cross-project traces in two distinct projects (B and C) ──
    # The experiment now has traces in 3 projects total: A (same-project,
    # 2 traces), B (1 trace), and C (1 trace). The user only "sees" A as
    # the experiment's project; the cascade has to discover B and C via
    # ``streamExperimentItems``' per-item ``project_id`` field and issue a
    # ``search_traces`` / ``search_spans`` against each.
    cross_b_trace_id = _mint_cross_project_trace(
        rest, project_name=cross_project_name_b, label="b"
    )
    cross_c_trace_id = _mint_cross_project_trace(
        rest, project_name=cross_project_name_c, label="c"
    )

    rest.experiments.create_experiment_items(
        experiment_items=[
            ExperimentItem(
                id=id_helpers_module.generate_id(),
                experiment_id=source_experiment_id,
                dataset_item_id=cross_b_item_id,
                trace_id=cross_b_trace_id,
            ),
            ExperimentItem(
                id=id_helpers_module.generate_id(),
                experiment_id=source_experiment_id,
                dataset_item_id=cross_c_item_id,
                trace_id=cross_c_trace_id,
            ),
        ]
    )

    # ── Run the migration to the destination project ──
    audit_path = tmp_path / "audit.json"
    result = run_migrate_cli(
        [
            "dataset",
            dataset_name,
            "--from-project",
            source_project_name,
            "--to-project",
            target_project_name,
        ],
        audit_log_path=str(audit_path),
    )
    assert result.returncode == 0, result.stdout + result.stderr

    # ── Verify destination ─────────────────────────────────────────────
    dest_dataset = rest.datasets.get_dataset_by_identifier(
        dataset_name=dataset_name, project_name=target_project_name
    )
    dest_exp = find_destination_experiment(
        rest,
        destination_dataset_id=dest_dataset.id,
        experiment_name=experiment_name,
    )

    # All 4 source experiment items should round-trip (2 same-project +
    # 1 from project B + 1 from project C).
    expected_count = len(same_project_trace_ids) + 2
    dest_items = destination_experiment_items(
        rest, experiment_id=dest_exp.id, dataset_id=dest_dataset.id
    )
    assert len(dest_items) == expected_count, (
        f"expected {expected_count} destination experiment items "
        f"(same-project + 2 cross-project), got {len(dest_items)}"
    )

    # Every destination trace must live in the target project — including
    # the ones that originally lived in project B and project C. This is
    # the contract the cross-project plumbing protects: without
    # ``_discover_trace_projects``, the search_traces call would scope to
    # the experiment's own project (A) only and both cross-project traces
    # would be silently dropped, leaving 2 fewer items at the destination.
    dest_trace_ids: List[str] = []
    for it in dest_items:
        assert it.trace_id is not None, "destination experiment item missing trace_id"
        dest_trace_ids.append(it.trace_id)
        verifiers.verify_trace(
            opik_client=opik_client,
            trace_id=it.trace_id,
            project_name=target_project_name,
        )

    # Per-span fidelity per source project: spans live under the same project
    # as their parent trace and are discovered+read by the cascade in the
    # same per-project loop as traces. So the cross-project safety we just
    # verified for traces only holds if the per-project ``search_spans``
    # loop also fired for B and C; if it didn't, the cross-project traces
    # would land at the destination but with zero spans attached.
    #
    # Trace ``name`` is the stable handle for classifying which source
    # project a destination trace came from:
    #   * ``task-N``        -> same-project (A), seeded with root + 1 LLM child
    #   * ``task-cross-b``  -> cross-project from B, seeded with 1 root span
    #   * ``task-cross-c``  -> cross-project from C, seeded with 1 root span
    expected_spans_by_trace_name = {"task-cross-b": 1, "task-cross-c": 1}
    for i in range(len(same_project_trace_ids)):
        expected_spans_by_trace_name[f"task-{i}"] = 2  # root + 1 LLM child

    for it in dest_items:
        dest_trace = rest.traces.get_trace_by_id(id=it.trace_id)
        assert dest_trace.name in expected_spans_by_trace_name, (
            f"unexpected destination trace name {dest_trace.name!r} -- the "
            f"cross-project trace naming contract changed and this test "
            f"doesn't know which source project to attribute it to"
        )
        dest_spans = destination_spans_for_trace(
            rest, trace_id=it.trace_id, project_name=target_project_name
        )
        expected = expected_spans_by_trace_name[dest_trace.name]
        assert len(dest_spans) == expected, (
            f"destination trace {dest_trace.name!r} should have {expected} "
            f"span(s) at the destination, got {len(dest_spans)} -- if 0 for "
            f"a cross-project trace, the per-project search_spans loop didn't "
            f"fire for that source project"
        )

    # Fresh destination ids — migrate is copy-not-move. No source trace id
    # (from any of the 3 source projects) may be reused at the destination.
    source_trace_ids = set(same_project_trace_ids) | {
        cross_b_trace_id,
        cross_c_trace_id,
    }
    overlap = source_trace_ids & set(dest_trace_ids)
    assert not overlap, (
        f"destination trace ids must be fresh, but found source ids reused: {overlap}"
    )
