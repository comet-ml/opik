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
from .conftest import (
    apply_changes,
    chronological_versions,
    create_dataset_shell,
    display_order,
    item_hashes,
    run_migrate_cli,
    stream_items_wire,
)

PROJECT_NAME = "e2e-cli-migrate-dataset"  # consumed by configure_e2e_tests_env


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
