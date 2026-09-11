"""End-to-end test for ``opik migrate dataset`` checkpoint/resume (OPIK-7168).

The unit tests prove the resume *logic* against a stand-in client. This test
proves the thing that actually matters for the feature: a **real process
death** mid-cascade leaves a checkpoint on disk that a **real re-run** picks
up, against a **real backend** whose trace-delete cascades spans — ending with
a duplicate-free destination.

Shape:

  1. Seed a source dataset (one version) and N experiments, each with its own
     trace + span tree, all referencing that dataset.
  2. Run ``opik migrate dataset`` with a test-only ``sitecustomize.py`` seam on
     ``PYTHONPATH`` (nothing test-only lives in the product) that ``os._exit(137)``s
     partway through one experiment — after its destination traces are written
     and recorded on the checkpoint but before the experiment row is recreated.
     This is the uncatchable-kill (OOM-like) interruption the feature exists for;
     a clean ``except`` never runs, so only the incrementally-flushed checkpoint
     survives. The seam also redirects the checkpoint dir into tmp_path.
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
import os
from pathlib import Path
from typing import Iterator, List

import pytest

import opik
from opik import id_helpers, synchronization
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

# Number of source experiments and the count of experiments completed before
# the first run hard-exits. Crashing after 2 completed experiments (mid the 3rd)
# leaves a non-trivial done-set to skip AND an in-flight experiment to clean up
# + re-migrate on resume.
_NUM_EXPERIMENTS = 5
_CRASH_AFTER_N_EXPERIMENTS = 2


@pytest.fixture
def dataset_name() -> Iterator[str]:
    yield f"e2e-migrate-resume-{random_chars()}"


def _write_test_seam(
    seam_dir: Path, *, crash_after_n: int, checkpoint_dir: Path
) -> None:
    """Write a test-only ``sitecustomize.py`` the migrate subprocess auto-imports.

    Python imports ``sitecustomize`` at interpreter startup, so dropping it on
    the subprocess's ``PYTHONPATH`` lets the test inject behaviour **without any
    test-only logic living in the product**. Two patches, both test-owned:

    * Redirect the checkpoint dir to a tmp path (via ``checkpoint_dir``) so the
      test can find/assert the checkpoint without touching the developer's real
      ``~/.opik`` and without repointing ``HOME`` (which would break the SDK's
      backend-config resolution the E2E run relies on).
    * Wrap ``_copy_traces_and_spans`` so the real one runs first (destination
      traces written + recorded on the checkpoint), then ``os._exit(137)`` after
      the Nth experiment — reproducing the exact "traces written, experiment row
      not yet created" partial state an OOM leaves mid-cascade. ``crash_after_n``
      < 0 disables the crash (used for the resume run).
    """
    seam_dir.mkdir(parents=True, exist_ok=True)
    (seam_dir / "sitecustomize.py").write_text(
        f"""
import os
from pathlib import Path
from opik.cli.migrate import checkpoint as _cp
from opik.cli.migrate.datasets import experiments as _exp

_cp.checkpoint_dir = lambda: Path({str(checkpoint_dir)!r})

_crash_after = {crash_after_n}
if _crash_after >= 0:
    _seen = {{"n": 0}}
    _real_copy = _exp._copy_traces_and_spans

    def _copy_then_maybe_crash(*args, **kwargs):
        result = _real_copy(*args, **kwargs)
        _seen["n"] += 1
        if _seen["n"] > _crash_after:
            os._exit(137)  # 128 + SIGKILL(9): uncatchable, mimics an OOM kill
        return result

    _exp._copy_traces_and_spans = _copy_then_maybe_crash
"""
    )


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
    # Names derive from the (already-random) ``dataset_name`` fixture so they're
    # unique per run yet reproducible from the fixture, per sdks/python/AGENTS.md.
    experiment_names: List[str] = []
    for i in range(_NUM_EXPERIMENTS):
        name = f"{dataset_name}-exp-{i}"
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

    # Test seam: a sitecustomize.py the migrate subprocess auto-imports. It
    # redirects the checkpoint dir into tmp_path (hermetic; no real ~/.opik) and
    # injects the deterministic mid-cascade crash — all test-owned, nothing in
    # the product. PYTHONPATH must keep the SDK's own src on it (the E2E run
    # imports opik from source), so the seam dir is prepended.
    checkpoint_dir = tmp_path / "checkpoints"
    seam_dir = tmp_path / "seam"
    existing_pythonpath = os.environ.get("PYTHONPATH", "")

    def _seam_env(crash_after_n: int) -> dict:
        _write_test_seam(
            seam_dir, crash_after_n=crash_after_n, checkpoint_dir=checkpoint_dir
        )
        return {
            "PYTHONPATH": os.pathsep.join(
                [str(seam_dir)] + ([existing_pythonpath] if existing_pythonpath else [])
            )
        }

    # ── Run 1: crash deterministically mid-cascade ──
    crashed = run_migrate_cli(
        migrate_args,
        audit_log_path=str(audit_path),
        extra_env=_seam_env(_CRASH_AFTER_N_EXPERIMENTS),
    )
    # os._exit(137) is a hard, uncatchable exit — not a clean CLI exit code.
    assert crashed.returncode == 137, (
        f"expected hard-exit 137, got {crashed.returncode}\n"
        f"stdout={crashed.stdout}\nstderr={crashed.stderr}"
    )

    # The incrementally-flushed checkpoint must have survived the kill and show
    # partial progress: the experiments before the crash completed, and the one
    # at the crash is in flight with recorded destination trace ids (its traces
    # were written to the backend before the crash).
    checkpoints = list(checkpoint_dir.glob("opik-migrate-checkpoint-*.json"))
    assert len(checkpoints) == 1, f"expected one checkpoint file, got {checkpoints}"
    checkpoint_data = json.loads(checkpoints[0].read_text())
    assert (
        len(checkpoint_data["completed_experiment_ids"]) == _CRASH_AFTER_N_EXPERIMENTS
    )
    # The dataset phase (create-temp/replay/optimizations) finished before the
    # cascade started, so resume must skip it and reconstruct the remaps from
    # the temp destination rather than re-running (and duplicating) it. Under the
    # OPIK-7162 ordering the source keeps its original name until the run
    # succeeds, and the destination is still under the temp name at crash time —
    # the checkpoint records both so resume can re-resolve them.
    assert checkpoint_data["dataset_phase_done"] is True
    assert checkpoint_data["source_name"] == dataset_name
    assert checkpoint_data["temp_dest_name"] == f"{dataset_name}__migrating"
    in_flight = checkpoint_data["in_flight"]
    assert in_flight is not None, "interrupted experiment must be recorded in flight"
    assert in_flight["dest_trace_ids"], (
        "the in-flight experiment's destination trace ids must be recorded "
        "before the crash so resume can delete them"
    )
    # Crash landed before the experiment row was created.
    assert in_flight["dest_experiment_id"] is None

    # ── Run 2: resume to completion (same seam, crash disabled) ──
    resumed = run_migrate_cli(
        migrate_args, audit_log_path=str(audit_path), extra_env=_seam_env(-1)
    )
    assert resumed.returncode == 0, resumed.stdout + resumed.stderr
    assert "Resuming migration" in resumed.stdout, (
        "resumed run should announce it is resuming"
    )

    # ── Verify destination: exactly one experiment per source, no duplicates ──
    dest_dataset = rest.datasets.get_dataset_by_identifier(
        dataset_name=dataset_name, project_name=target_project_name
    )

    # Migrated experiments become readable asynchronously, so poll until the
    # destination shows the full set before asserting — otherwise the read can
    # race ahead of eventual consistency and flake. ``until`` returns False on
    # timeout; the count assertion below then fails with the observed number.
    def _all_experiments_present() -> bool:
        page = rest.experiments.find_experiments(
            dataset_id=dest_dataset.id, page=1, size=100
        )
        return len(page.content or []) == _NUM_EXPERIMENTS

    synchronization.until(_all_experiments_present, max_try_seconds=30)

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
    assert list(checkpoint_dir.glob("opik-migrate-checkpoint-*.json")) == []
