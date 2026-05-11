"""End-to-end tests for ``opik migrate dataset`` against a real Opik backend.

Covers the plain-dataset path: full version replay, plus the experiment +
trace + span cascade that rides along with the dataset. The test-suite
path lives in ``test_migrate_test_suite_e2e.py``.

Each test:

1. Seeds a multi-version source dataset directly via the REST API (so we
   control every per-version delta the migration has to replay) and,
   where the test calls for it, an experiment + traces + spans attached
   to one of the source versions
2. Runs ``opik migrate dataset`` as a subprocess so the actual CLI
   entrypoint, Click group, and exit-code handling are exercised
3. Reads back the target via the raw REST stream + wire type (the SDK
   helper drops per-item tags) and asserts per-version content +
   display-order fidelity, plus -- where relevant -- destination
   experiment + trace + span fidelity and FK remapping

Shared helpers live in ``conftest.py``.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterator

import pytest

import opik

from ...conftest import random_chars
from ...testlib import generate_project_name
from ._cascade_comparison import compare_cascade
from .conftest import (
    apply_changes,
    chronological_versions,
    create_dataset_shell,
    destination_experiment_items,
    destination_feedback_scores_for_trace,
    destination_spans_for_trace,
    display_order,
    find_destination_experiment,
    item_hashes,
    run_migrate_cli,
    seed_experiment_with_trace_tree,
    stream_items_wire,
)

# Per ``sdks/python/AGENTS.md``: every e2e module sources PROJECT_NAME from
# ``generate_project_name("e2e", __name__)`` so backend project names are
# isolated per test module + the autouse ``configure_e2e_tests_env`` fixture
# can patch ``OPIK_PROJECT_NAME`` to match.
PROJECT_NAME = generate_project_name("e2e", __name__)


@pytest.fixture
def dataset_name() -> Iterator[str]:
    yield f"e2e-migrate-{random_chars()}"


class TestMigrateDatasetVersionReplay:
    """Default ``opik migrate dataset`` flow: full version-history replay.

    Pin the slice 2 contract: target version count == source version
    count, per-version content set-equal under hash, display order
    preserved at every version.
    """

    def test_three_version_dataset_with_mixed_deltas_round_trips(
        self,
        opik_client: opik.Opik,
        source_project_name: str,
        target_project_name: str,
        dataset_name: str,
        tmp_path: Path,
    ) -> None:
        rest = opik_client.rest_client

        # Seed: 3 versions exercising every delta kind.
        # v1: adds Q1, Q2, Q3.
        # v2: edits Q1 (data change), adds Q4.
        # v3: edits Q3 (data change), deletes Q2, adds Q5.
        source_id = create_dataset_shell(rest, dataset_name, source_project_name)

        # v1 of a plain dataset is created via the REST insert path (mirrors
        # what ``Dataset.insert`` does at higher level). Use create_or_update
        # so we get exactly one BE version with all three items.
        from opik import id_helpers
        from opik.rest_api.types.dataset_item_write import DatasetItemWrite

        rest.datasets.create_or_update_dataset_items(
            dataset_id=source_id,
            items=[
                DatasetItemWrite(source="manual", data={"q": "Q1", "a": "A1"}),
                DatasetItemWrite(source="manual", data={"q": "Q2", "a": "A2"}),
                DatasetItemWrite(source="manual", data={"q": "Q3", "a": "A3"}),
            ],
            batch_group_id=id_helpers.generate_id(),
        )
        v1 = rest.datasets.list_dataset_versions(id=source_id, page=1, size=1).content[
            0
        ]
        v1_items = stream_items_wire(
            rest,
            dataset_name=dataset_name,
            project_name=source_project_name,
            version_hash=v1.version_hash,
        )
        by_q = {item.data["q"]: item for item in v1_items if item.data}

        # v2: edit Q1's answer + add Q4.
        v2_id = apply_changes(
            rest,
            source_id,
            base_version_id=v1.id,
            edited_items=[
                {"id": by_q["Q1"].id, "data": {"q": "Q1", "a": "A1-EDITED"}},
            ],
            added_items=[{"data": {"q": "Q4", "a": "A4"}, "source": "manual"}],
            change_description="v2 — edit Q1, add Q4",
        )

        # v3: edit Q3, delete Q2, add Q5.
        apply_changes(
            rest,
            source_id,
            base_version_id=v2_id,
            edited_items=[
                {"id": by_q["Q3"].id, "data": {"q": "Q3", "a": "A3-EDITED"}},
            ],
            deleted_ids=[by_q["Q2"].id],
            added_items=[{"data": {"q": "Q5", "a": "A5"}, "source": "manual"}],
            change_description="v3 — delete Q2, edit Q3, add Q5",
        )

        # Snapshot source expectations per version.
        src_versions = chronological_versions(rest, source_id)
        assert len(src_versions) == 3
        expected_hashes = []
        expected_orders = []
        for v in src_versions:
            items = stream_items_wire(
                rest,
                dataset_name=dataset_name,
                project_name=source_project_name,
                version_hash=v.version_hash,
            )
            expected_hashes.append(item_hashes(items))
            expected_orders.append(display_order(items))

        # ── Seed an experiment on v1 items so the cascade has something to
        # round-trip. Regular-dataset experiments carry per-trace feedback
        # scores (test suites carry assertion_results -- covered in
        # test_migrate_test_suite_e2e.py). Each item gets one trace with a
        # root + 1 LLM child span and a feedback score on the trace. ──
        experiment_name = f"e2e-exp-{random_chars()}"
        v1_item_ids = [by_q["Q1"].id, by_q["Q2"].id, by_q["Q3"].id]
        cascade_seed = seed_experiment_with_trace_tree(
            rest,
            experiment_name=experiment_name,
            dataset_name=dataset_name,
            dataset_id=source_id,
            dataset_version_id=v1.id,
            project_name=source_project_name,
            item_ids=v1_item_ids,
            experiment_config={"runner": "e2e-cascade-test"},
            experiment_tags=["e2e", "cascade"],
            spans_per_trace=2,  # root + 1 LLM child -> exercises parent_span_id remap
            feedback_scores_per_trace=[
                {"name": "correctness", "value": 0.9, "reason": "matches reference"},
                {"name": "latency_p95", "value": 230.5},
            ],
        )

        # Run the migration.
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

        # Verify target: same version count, per-version content set-equal
        # under hash, display order matches at every version.
        target = rest.datasets.get_dataset_by_identifier(
            dataset_name=dataset_name, project_name=target_project_name
        )
        tgt_versions = chronological_versions(rest, target.id)
        assert len(tgt_versions) == len(src_versions), (
            f"target version count {len(tgt_versions)} != source {len(src_versions)} "
            "— Slice 2 contract requires N=N"
        )

        for src_v, tgt_v, exp_hashes, exp_order in zip(
            src_versions, tgt_versions, expected_hashes, expected_orders
        ):
            items = stream_items_wire(
                rest,
                dataset_name=dataset_name,
                project_name=target_project_name,
                version_hash=tgt_v.version_hash,
            )
            actual_hashes = item_hashes(items)
            actual_order = display_order(items)
            assert actual_hashes == exp_hashes, (
                f"version {tgt_v.version_name}: target items don't match source. "
                f"Missing on target: {exp_hashes - actual_hashes}; "
                f"extra on target: {actual_hashes - exp_hashes}"
            )
            assert actual_order == exp_order, (
                f"version {tgt_v.version_name}: display order diverged "
                f"(source: {exp_order}, target: {actual_order})"
            )

        # Audit log records one per-version entry per replayed source version.
        audit = json.loads(audit_path.read_text())
        per_version_records = [
            a for a in audit["actions"] if a["type"] == "replay_dataset_version"
        ]
        assert len(per_version_records) == len(src_versions)
        # Per-version deltas: v1=(3 adds), v2=(1 add, 1 mod), v3=(1 add, 1 mod, 1 del).
        assert (
            per_version_records[0]["items_added"],
            per_version_records[0]["items_modified"],
            per_version_records[0]["items_deleted"],
        ) == (3, 0, 0)
        assert (
            per_version_records[1]["items_added"],
            per_version_records[1]["items_modified"],
            per_version_records[1]["items_deleted"],
        ) == (1, 1, 0)
        assert (
            per_version_records[2]["items_added"],
            per_version_records[2]["items_modified"],
            per_version_records[2]["items_deleted"],
        ) == (1, 1, 1)

        # ── Cascade fidelity ──
        # The destination project should now have a copy of the source
        # experiment with: a remapped dataset_version_id, fresh item ids
        # carrying remapped trace ids, traces+spans landing under the
        # destination project, feedback scores re-emitted on the destination
        # traces, and per-item write-side fidelity (input/output) preserved.
        dest_exp = find_destination_experiment(
            rest,
            destination_dataset_id=target.id,
            experiment_name=experiment_name,
        )
        # FKs remapped.
        assert dest_exp.id != cascade_seed["experiment_id"]
        assert dest_exp.dataset_id == target.id
        # The destination experiment must reference one of the target
        # versions (the cascade picks the remap of v1).
        target_version_ids = {v.id for v in tgt_versions}
        assert dest_exp.dataset_version_id in target_version_ids

        # Items: one per source item, with FRESH trace ids (disjoint from
        # source). Per-item input/output/usage/cost are READ-ONLY on the BE
        # (computed/aggregated from the underlying trace + span entities);
        # we assert the trace + span fidelity below instead.
        dest_items = destination_experiment_items(
            rest,
            experiment_id=dest_exp.id,
            dataset_id=target.id,
        )
        assert len(dest_items) == len(v1_item_ids)
        dest_trace_ids = {it.trace_id for it in dest_items}
        assert dest_trace_ids.isdisjoint(set(cascade_seed["trace_ids"])), (
            "destination experiment items should reference new trace ids, "
            "not the source's"
        )

        # Each destination trace exists under the target project and has the
        # same span shape as the source (root + 1 child = 2 spans).
        for new_trace_id in dest_trace_ids:
            dest_spans = destination_spans_for_trace(
                rest,
                trace_id=new_trace_id,
                project_name=target_project_name,
            )
            assert len(dest_spans) == 2, (
                f"trace {new_trace_id} should have 2 spans (root + child), "
                f"got {len(dest_spans)}"
            )
            # Topological remap: exactly one root (parent_span_id=None),
            # the other span points at the root via parent_span_id.
            roots = [s for s in dest_spans if s.parent_span_id is None]
            assert len(roots) == 1, f"trace {new_trace_id} should have one root span"
            children = [s for s in dest_spans if s.parent_span_id is not None]
            assert all(c.parent_span_id == roots[0].id for c in children), (
                f"trace {new_trace_id} child spans should remap parent_span_id "
                "to the new root id"
            )

            # Trace-level feedback scores re-emitted on the destination trace.
            dest_scores = destination_feedback_scores_for_trace(
                rest, trace_id=new_trace_id
            )
            score_names = {s.name for s in dest_scores}
            assert score_names == {"correctness", "latency_p95"}, (
                f"trace {new_trace_id}: expected feedback score names "
                f"{{'correctness', 'latency_p95'}}, got {score_names}"
            )

        # ── Deep-equal source vs. destination ──
        # Verify field-by-field that experiment + items + traces + spans
        # round-trip the cascade modulo remapped IDs. Pairing strategy:
        # both sides sorted by trace ``name`` (assigned by the seed as
        # "task-0", "task-1", "task-2" and carried verbatim through the
        # cascade), guaranteeing stable positional correspondence.
        src_exp = find_destination_experiment(
            rest,
            destination_dataset_id=source_id,
            experiment_name=experiment_name,
        )
        src_items_compare = destination_experiment_items(
            rest,
            experiment_id=cascade_seed["experiment_id"],
            dataset_id=source_id,
        )
        # Sort both sides by trace name for stable pairing. Build a
        # trace_id -> name map by reading each trace once.
        src_trace_names = {
            it.trace_id: rest.traces.get_trace_by_id(id=it.trace_id).name
            for it in src_items_compare
        }
        dst_trace_names = {
            it.trace_id: rest.traces.get_trace_by_id(id=it.trace_id).name
            for it in dest_items
        }
        src_items_compare.sort(key=lambda it: src_trace_names[it.trace_id])
        dest_items_sorted = sorted(
            dest_items, key=lambda it: dst_trace_names[it.trace_id]
        )
        src_trace_ids_sorted = [it.trace_id for it in src_items_compare]
        dst_trace_ids_sorted = [it.trace_id for it in dest_items_sorted]

        compare_cascade(
            rest_client=rest,
            source_experiment=src_exp,
            destination_experiment=dest_exp,
            source_item_ids=v1_item_ids,
            destination_item_ids=[it.dataset_item_id for it in dest_items_sorted],
            source_trace_ids=src_trace_ids_sorted,
            destination_trace_ids=dst_trace_ids_sorted,
            source_items_compare=src_items_compare,
            destination_items_compare=dest_items_sorted,
        )
