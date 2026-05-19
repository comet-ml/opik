"""End-to-end test for ``opik migrate dataset`` Slice 5 — optimization cascade.

Optimization is a real BE entity, not a logical group-by: it has its own
UUID, its own row in ``OptimizationDAO``, and its own ``dataset_id`` /
``project_id`` FKs. The Optimization Studio "group" view in the UI is
computed by the BE at read time from each experiment's ``optimization_id``
FK plus the optimization's aggregated stats (``numTrials``, etc.); the
persisted shape is one Optimization row + N Experiment rows where
``Experiment.optimization_id == Optimization.id``.

Slice 5's contract: when ``opik migrate dataset`` runs, every optimization
referencing the source dataset is recreated under the destination project
with a fresh id, and every experiment carrying that ``optimization_id``
is recreated at the destination with its FK re-pointed at the new
destination optimization id. The destination's Optimization Studio shows
the same rows + trial groupings as the source.

Shape pinned by this test:

  1. Source dataset in project A with 3 items
  2. One Optimization (``opt``) on the source dataset
  3. Two TRIAL experiments seeded as ``opt``'s trials (each has 1 item)
  4. One CONTROL ``regular`` experiment with no optimization_id (1 item)
  5. Run ``opik migrate dataset ... --to-project=<destination>``
  6. Assert:
     a. Destination has exactly one optimization tied to the
        destination dataset, with a fresh id (NOT the source id).
     b. Both trial experiments are recreated under the destination
        project with their ``optimization_id`` pointing at the
        destination optimization (NOT the source optimization id).
     c. The control experiment has no ``optimization_id`` (untouched).
     d. The destination optimization's fidelity fields
        (``name`` / ``objective_name`` / ``status``) match the source.
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterator

import pytest

import opik
import opik.id_helpers as id_helpers_module
from opik.rest_api import OpikApi

from ...conftest import random_chars
from ...testlib import generate_project_name
from .conftest import (
    create_dataset_shell,
    find_destination_experiment,
    run_migrate_cli,
    seed_experiment_with_trace_tree,
    stream_items_wire,
)

PROJECT_NAME = generate_project_name("e2e", __name__)


@pytest.fixture
def dataset_name() -> Iterator[str]:
    yield f"e2e-migrate-opt-{random_chars()}"


def _create_source_optimization(
    rest: OpikApi,
    *,
    dataset_name: str,
    project_name: str,
    optimization_name: str,
) -> str:
    """Create an Optimization tied to ``dataset_name`` in ``project_name``
    and return its id. The cascade will discover this via
    ``find_optimizations(dataset_id=...)``.
    """
    optimization_id = id_helpers_module.generate_id()
    rest.optimizations.create_optimization(
        id=optimization_id,
        name=optimization_name,
        dataset_name=dataset_name,
        project_name=project_name,
        objective_name="accuracy",
        status="completed",
    )
    return optimization_id


def test_migrate_dataset__optimization_cascade__trials_grouped_at_destination(
    opik_client: opik.Opik,
    source_project_name: str,
    target_project_name: str,
    dataset_name: str,
    tmp_path: Path,
) -> None:
    rest = opik_client.rest_client

    # ── Seed the source dataset with 3 items (2 for trials, 1 for control) ──
    from opik import id_helpers
    from opik.rest_api.types.dataset_item_write import DatasetItemWrite

    source_dataset_id = create_dataset_shell(rest, dataset_name, source_project_name)
    rest.datasets.create_or_update_dataset_items(
        dataset_id=source_dataset_id,
        items=[
            DatasetItemWrite(source="manual", data={"q": "trial-a", "a": "A"}),
            DatasetItemWrite(source="manual", data={"q": "trial-b", "a": "B"}),
            DatasetItemWrite(source="manual", data={"q": "control", "a": "C"}),
        ],
        batch_group_id=id_helpers.generate_id(),
    )
    v1 = rest.datasets.list_dataset_versions(
        id=source_dataset_id, page=1, size=1
    ).content[0]

    v1_items = stream_items_wire(
        rest,
        dataset_name=dataset_name,
        project_name=source_project_name,
        version_hash=v1.version_hash,
    )
    by_q = {(it.data or {}).get("q"): it.id for it in v1_items}
    trial_a_item_id = by_q["trial-a"]
    trial_b_item_id = by_q["trial-b"]
    control_item_id = by_q["control"]
    assert trial_a_item_id and trial_b_item_id and control_item_id

    # ── Create the source optimization ──
    optimization_name = f"e2e-opt-{random_chars()}"
    source_optimization_id = _create_source_optimization(
        rest,
        dataset_name=dataset_name,
        project_name=source_project_name,
        optimization_name=optimization_name,
    )

    # ── Seed two trial experiments pointing at the optimization ──
    # ``experiment_type="trial"`` is what the Optimization Studio UI
    # filters on for the trial sub-list under an optimization row.
    trial_a_name = f"trial-a-{random_chars()}"
    trial_b_name = f"trial-b-{random_chars()}"
    seed_experiment_with_trace_tree(
        rest,
        experiment_name=trial_a_name,
        dataset_name=dataset_name,
        dataset_id=source_dataset_id,
        dataset_version_id=v1.id,
        project_name=source_project_name,
        item_ids=[trial_a_item_id],
        experiment_type="trial",
        optimization_id=source_optimization_id,
    )
    seed_experiment_with_trace_tree(
        rest,
        experiment_name=trial_b_name,
        dataset_name=dataset_name,
        dataset_id=source_dataset_id,
        dataset_version_id=v1.id,
        project_name=source_project_name,
        item_ids=[trial_b_item_id],
        experiment_type="trial",
        optimization_id=source_optimization_id,
    )

    # ── Seed a control regular experiment with no optimization_id ──
    # Pins that the cascade only touches experiments that actually had
    # an optimization_id; the control experiment must round-trip with
    # no optimization_id at the destination.
    control_name = f"control-{random_chars()}"
    seed_experiment_with_trace_tree(
        rest,
        experiment_name=control_name,
        dataset_name=dataset_name,
        dataset_id=source_dataset_id,
        dataset_version_id=v1.id,
        project_name=source_project_name,
        item_ids=[control_item_id],
        experiment_type="regular",
        optimization_id=None,
    )

    # ── Run the migration ──
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

    # ── Verify destination optimization fidelity ──
    dest_dataset = rest.datasets.get_dataset_by_identifier(
        dataset_name=dataset_name, project_name=target_project_name
    )
    dest_optimizations_page = rest.optimizations.find_optimizations(
        dataset_id=dest_dataset.id, page=1, size=10
    )
    dest_optimizations = list(dest_optimizations_page.content or [])
    assert len(dest_optimizations) == 1, (
        f"expected exactly one destination optimization tied to dataset "
        f"{dest_dataset.id}, got {len(dest_optimizations)}"
    )
    dest_optimization = dest_optimizations[0]

    # Fresh destination id -- migrate is copy-not-move; source id must
    # not be reused.
    assert dest_optimization.id != source_optimization_id, (
        "destination optimization id must be fresh; source id reused"
    )
    # Fidelity fields round-trip verbatim.
    assert dest_optimization.name == optimization_name
    assert dest_optimization.objective_name == "accuracy"
    assert dest_optimization.status == "completed"
    assert dest_optimization.dataset_id == dest_dataset.id

    # ── Verify trial experiments are re-pointed at the destination optimization ──
    dest_trial_a = find_destination_experiment(
        rest,
        destination_dataset_id=dest_dataset.id,
        experiment_name=trial_a_name,
    )
    dest_trial_b = find_destination_experiment(
        rest,
        destination_dataset_id=dest_dataset.id,
        experiment_name=trial_b_name,
    )
    assert dest_trial_a.optimization_id == dest_optimization.id, (
        f"trial a should FK to destination optimization {dest_optimization.id}, "
        f"got {dest_trial_a.optimization_id}"
    )
    assert dest_trial_b.optimization_id == dest_optimization.id, (
        f"trial b should FK to destination optimization {dest_optimization.id}, "
        f"got {dest_trial_b.optimization_id}"
    )
    # Defence-in-depth: neither trial should still point at the source id.
    for dest_trial, label in ((dest_trial_a, "a"), (dest_trial_b, "b")):
        assert dest_trial.optimization_id != source_optimization_id, (
            f"trial {label}'s destination optimization_id must NOT be the "
            f"source id (cascade failed to remap)"
        )

    # ── Verify control experiment is untouched (no optimization_id) ──
    dest_control = find_destination_experiment(
        rest,
        destination_dataset_id=dest_dataset.id,
        experiment_name=control_name,
    )
    assert not dest_control.optimization_id, (
        f"control experiment must not have an optimization_id at the "
        f"destination, got {dest_control.optimization_id!r}"
    )

    # ── Audit log carries per-optimization records ──
    import json

    audit = json.loads(audit_path.read_text())
    migrate_optimization_records = [
        a for a in audit["actions"] if a["type"] == "migrate_optimization"
    ]
    assert len(migrate_optimization_records) == 1
    record = migrate_optimization_records[0]
    assert record["status"] == "ok"
    assert record["source_id"] == source_optimization_id
    assert record["destination_id"] == dest_optimization.id
