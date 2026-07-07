"""End-to-end test for ``opik migrate dataset`` checkpoint/resume (OPIK-7168).

The unit tests prove the resume *logic* against a stand-in client. This test
proves the thing that actually matters for the feature: a **real process
death** mid-cascade leaves a checkpoint on disk that a **real re-run** picks
up, against a **real backend** whose trace-delete cascades spans — ending with
a duplicate-free destination.

Shape:

  1. Seed a source dataset (one version) and N experiments, each with its own
     trace + span tree, all referencing that dataset.
  2. Run ``opik migrate dataset`` with ``OPIK_MIGRATE_CRASH_AFTER_EXPERIMENT``
     set so the child ``os._exit(137)``s partway through one experiment —
     after its destination traces are written and recorded on the checkpoint
     but before the experiment row is recreated. This is the uncatchable-kill
     (OOM-like) interruption the feature exists for; a clean ``except`` never
     runs, so only the incrementally-flushed checkpoint survives.
  3. Assert the run died hard and the checkpoint on disk shows partial progress
     (some experiments done, one in flight with recorded dest trace ids).
  4. Re-run the *same* command with no crash env. Assert it exits 0, resumes
     (skips the already-done experiments, cleans up the interrupted one's
     partial traces, re-migrates it), and the destination ends with exactly one
     experiment per source experiment — no duplicates — and the checkpoint file
     is gone.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterator, List

import pytest

import opik
from opik import id_helpers
from opik.rest_api.types.dataset_item_write import DatasetItemWrite

from ...conftest import random_chars
from .conftest import (
    create_dataset_shell,
    destination_experiment_items,
    find_destination_experiment,
    run_migrate_cli,
    seed_experiment_with_trace_tree,
    stream_items_wire,
)

# Number of source experiments and the zero-based position at which the first
# run hard-exits. Crashing after 2 completed experiments (i.e. mid-experiment
# index 2, the 3rd) leaves a non-trivial done-set to skip AND an in-flight
# experiment to clean up + re-migrate on resume.
_NUM_EXPERIMENTS = 5
_CRASH_AT_INDEX = 2


@pytest.fixture
def dataset_name() -> Iterator[str]:
    yield f"e2e-migrate-resume-{random_chars()}"


def _checkpoint_files(audit_path: Path) -> List[Path]:
    return list(audit_path.parent.glob("opik-migrate-checkpoint-*.json"))


def test_migrate_dataset__crash_mid_cascade__resumes_without_duplicates(
    opik_client: opik.Opik,
    source_project_name: str,
    target_project_name: str,
    dataset_name: str,
    tmp_path: Path,
) -> None:
    rest = opik_client.rest_client

    # ── Seed the source dataset (single version, one item per experiment) ──
    source_id = create_dataset_shell(rest, dataset_name, source_project_name)
    rest.datasets.create_or_update_dataset_items(
        dataset_id=source_id,
        items=[
            DatasetItemWrite(source="manual", data={"q": f"Q{i}", "a": f"A{i}"})
            for i in range(_NUM_EXPERIMENTS)
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
    item_id_by_q = {(it.data or {}).get("q"): it.id for it in v1_items}

    # ── Seed N experiments, each referencing one dataset item ──
    experiment_names: List[str] = []
    for i in range(_NUM_EXPERIMENTS):
        name = f"e2e-resume-exp-{i}-{random_chars()}"
        experiment_names.append(name)
        seed_experiment_with_trace_tree(
            rest,
            experiment_name=name,
            dataset_name=dataset_name,
            dataset_id=source_id,
            dataset_version_id=v1.id,
            project_name=source_project_name,
            item_ids=[item_id_by_q[f"Q{i}"]],
            spans_per_trace=2,
        )

    audit_path = tmp_path / "audit.json"
    migrate_args = ["dataset", dataset_name, "--to-project", target_project_name]

    # ── Run 1: crash deterministically mid-cascade ──
    crashed = run_migrate_cli(
        migrate_args,
        audit_log_path=str(audit_path),
        extra_env={"OPIK_MIGRATE_CRASH_AFTER_EXPERIMENT": str(_CRASH_AT_INDEX)},
    )
    # os._exit(137) is a hard, uncatchable exit — not a clean CLI exit code.
    assert crashed.returncode == 137, (
        f"expected hard-exit 137, got {crashed.returncode}\n"
        f"stdout={crashed.stdout}\nstderr={crashed.stderr}"
    )

    # The incrementally-flushed checkpoint must have survived the kill and show
    # partial progress: the experiments before the crash index completed, and
    # the one at the crash index is in flight with recorded destination trace
    # ids (its traces were written to the backend before the crash).
    checkpoints = _checkpoint_files(audit_path)
    assert len(checkpoints) == 1, f"expected one checkpoint file, got {checkpoints}"
    checkpoint_data = json.loads(checkpoints[0].read_text())
    assert len(checkpoint_data["completed_experiment_ids"]) == _CRASH_AT_INDEX
    in_flight = checkpoint_data["in_flight"]
    assert in_flight is not None, "interrupted experiment must be recorded in flight"
    assert in_flight["dest_trace_ids"], (
        "the in-flight experiment's destination trace ids must be recorded "
        "before the crash so resume can delete them"
    )
    # Crash landed before the experiment row was created.
    assert in_flight["dest_experiment_id"] is None

    # ── Run 2: resume to completion (no crash env) ──
    resumed = run_migrate_cli(migrate_args, audit_log_path=str(audit_path))
    assert resumed.returncode == 0, resumed.stdout + resumed.stderr
    assert "Resuming migration" in resumed.stdout, (
        "resumed run should announce it is resuming"
    )

    # ── Verify destination: exactly one experiment per source, no duplicates ──
    dest_dataset = rest.datasets.get_dataset_by_identifier(
        dataset_name=dataset_name, project_name=target_project_name
    )
    for name in experiment_names:
        # find_destination_experiment RAISES if zero or >1 match — so this is
        # the duplicate check: the interrupted experiment must not have been
        # migrated twice (once by the crashed run, once by the resume).
        dest_exp = find_destination_experiment(
            rest,
            destination_dataset_id=dest_dataset.id,
            experiment_name=name,
        )
        dest_items = destination_experiment_items(
            rest, experiment_id=dest_exp.id, dataset_id=dest_dataset.id
        )
        assert len(dest_items) == 1, (
            f"experiment {name!r} should have exactly 1 item at the "
            f"destination, got {len(dest_items)}"
        )

    # Total destination experiments equals the source count — no orphaned
    # partial experiment left over from the crashed run.
    all_dest = rest.experiments.find_experiments(
        dataset_id=dest_dataset.id, page=1, size=100
    )
    assert len(all_dest.content or []) == _NUM_EXPERIMENTS, (
        f"expected {_NUM_EXPERIMENTS} destination experiments, "
        f"got {len(all_dest.content or [])}"
    )

    # Checkpoint deleted on successful completion.
    assert _checkpoint_files(audit_path) == []
