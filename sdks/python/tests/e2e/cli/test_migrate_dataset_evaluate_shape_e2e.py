"""End-to-end test for ``opik migrate dataset`` using the natural
``opik.evaluate(...)`` flow as seeding (rather than direct REST writes).

Complements ``test_migrate_dataset_e2e.py`` (precise per-version delta
coverage via REST seeding) by exercising the realistic shape that real
users produce:

  1. Create a dataset, ``dataset.insert(...)`` a first batch of items
  2. Run ``opik.evaluate(...)`` (experiment E1) under the dataset's project
  3. Insert more items
  4. Run ``opik.evaluate(...)`` (experiment E2) -- E1 saw 2 items, E2 sees 3
  5. ``opik migrate dataset ... --to-project=<dest>``
  6. Verify both experiments + items + traces + spans + feedback scores
     round-trip with fresh destination ids

In Opik V2 ``opik.evaluate(...)`` always lands its traces in the dataset's
project (the per-evaluate ``project_name`` override is deprecated). The
per-trace project_id scoping that ``_copy_traces_and_spans`` does is
defense-in-depth for BE shapes the SDK doesn't produce today; that
contract is covered by the unit test
``test_span_read_uses_trace_project_not_experiment_project``.
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any, Callable, Dict, Iterator

import pytest

import opik
from opik.evaluation.metrics import score_result

from ...testlib import generate_project_name
from .. import verifiers
from .conftest import (
    destination_experiment_items,
    find_destination_experiment,
    run_migrate_cli,
)

PROJECT_NAME = generate_project_name("e2e", __name__)


# ``dataset_name`` and ``experiment_name`` fixtures come from
# ``tests/e2e/conftest.py`` (shared per AGENTS.md). For the second
# experiment we derive a sibling name off the standard fixture rather
# than introducing a parallel per-test fixture.


@pytest.fixture
def experiment_name_two(experiment_name: str) -> Iterator[str]:
    yield f"{experiment_name}-second"


def _llm_task(item: Dict[str, Any]) -> Dict[str, Any]:
    """Deterministic stand-in for an LLM call.

    Returns ``"Paris"`` regardless of input so the equality scorer
    produces a deterministic 1.0/0.0 split based on the dataset items'
    expected outputs.
    """
    return {"output": "Paris"}


def _equals_score(
    dataset_item: Dict[str, Any], task_outputs: Dict[str, Any]
) -> score_result.ScoreResult:
    reference = dataset_item.get("expected", {}).get("output")
    prediction = task_outputs["output"]
    value = 1.0 if reference == prediction else 0.0
    return score_result.ScoreResult(
        name="equals_scoring_function",
        value=value,
        reason="match" if value == 1.0 else "mismatch",
    )


def _wait_until(
    predicate: Callable[[], Any],
    timeout_seconds: int = 30,
    poll_seconds: float = 1.0,
) -> Any:
    """Poll ``predicate()`` until it returns truthy or the timeout elapses.

    The streamer flushes traces / spans / feedback scores asynchronously,
    so we poll the BE for the artifacts we care about rather than racing
    a hardcoded ``sleep()``. Returns the predicate's final value so the
    caller can capture e.g. the experiment items in one go.
    """
    deadline = time.time() + timeout_seconds
    last: Any = None
    while time.time() < deadline:
        last = predicate()
        if last:
            return last
        time.sleep(poll_seconds)
    return last


def test_migrate_dataset__evaluate_shape__round_trips(
    opik_client: opik.Opik,
    source_project_name: str,
    target_project_name: str,
    dataset_name: str,
    experiment_name: str,
    experiment_name_two: str,
    tmp_path: Path,
) -> None:
    # ── Seed via the natural opik.evaluate(...) flow ──
    # Dataset and both evaluate() runs land in source_project_name. We
    # add items between runs so E1 sees 2 items and E2 sees 3 -- this
    # exercises the "experiments reference different dataset version
    # snapshots" case during migration.
    dataset = opik_client.create_dataset(dataset_name, project_name=source_project_name)
    dataset.insert(
        [
            {
                "input": {"question": "Capital of France?"},
                "expected": {"output": "Paris"},
            },
            {
                "input": {"question": "Capital of Italy?"},
                "expected": {"output": "Rome"},
            },
        ]
    )

    # Experiment 1 -- 2 items.
    eval1 = opik.evaluate(
        dataset=dataset,
        task=_llm_task,
        scoring_functions=[_equals_score],
        experiment_name=experiment_name,
        experiment_config={"phase": "first-eval"},
    )

    # Add a third item between runs.
    dataset.insert(
        [
            {
                "input": {"question": "Capital of Spain?"},
                "expected": {"output": "Madrid"},
            },
        ]
    )

    # Experiment 2 -- 3 items.
    eval2 = opik.evaluate(
        dataset=dataset,
        task=_llm_task,
        scoring_functions=[_equals_score],
        experiment_name=experiment_name_two,
        experiment_config={"phase": "second-eval"},
    )

    # Flush the SDK streamer so all traces / spans / feedback scores land
    # on the BE before the migration reads them.
    opik.flush_tracker()

    # Belt-and-suspenders: poll until both experiments are visible on the
    # BE (the streamer's flush returns once the local queue drains, but
    # the BE's eventual-consistency window can lag a beat).
    rest = opik_client.rest_client

    def _experiments_ready() -> bool:
        e1 = rest.experiments.get_experiment_by_id(id=eval1.experiment_id)
        e2 = rest.experiments.get_experiment_by_id(id=eval2.experiment_id)
        return e1 is not None and e2 is not None

    assert _wait_until(_experiments_ready), (
        "experiments not visible on BE after flush; streamer may have failed"
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

    # ── Verify the destination via the shared verifier layer ─────────────
    # ``verifiers.verify_experiment`` / ``verify_trace`` have built-in
    # ``synchronization.until`` retry loops for the BE's eventual-consistency
    # window right after the migrate completes -- spurious failures from
    # not-yet-readable rows are eliminated.
    #
    # Two name->id lookups remain: the destination dataset id (cascade keeps
    # the source name at the destination after the rename) and each
    # destination experiment id (cascade is name-preserving on experiments,
    # so we resolve by name). Everything else routes through verifiers.
    dest_dataset = rest.datasets.get_dataset_by_identifier(
        dataset_name=dataset_name, project_name=target_project_name
    )
    dest_dataset_id = dest_dataset.id

    dest_e1 = find_destination_experiment(
        rest,
        destination_dataset_id=dest_dataset_id,
        experiment_name=experiment_name,
    )
    dest_e2 = find_destination_experiment(
        rest,
        destination_dataset_id=dest_dataset_id,
        experiment_name=experiment_name_two,
    )

    # Fresh destination ids -- migrate is copy-not-move; new experiments
    # must have new ids.
    assert dest_e1.id != eval1.experiment_id, (
        "destination experiment 1 must have a fresh id, not the source's"
    )
    assert dest_e2.id != eval2.experiment_id, (
        "destination experiment 2 must have a fresh id, not the source's"
    )

    # Experiment-level shape via the shared verifier (with eventual-
    # consistency retry baked in). ``recreate_experiment`` injects
    # ``project_name`` into the destination experiment's metadata
    # (intentional -- it's recorded so future imports/migrations can
    # re-derive the project context). The verifier's metadata check is
    # exact-equality, so include it in the expected shape.
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=dest_e1.id,
        experiment_name=experiment_name,
        experiment_metadata={
            "phase": "first-eval",
            "project_name": target_project_name,
        },
        feedback_scores_amount=1,  # the equals scorer emits one aggregate
        traces_amount=2,
        project_name=target_project_name,
    )
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=dest_e2.id,
        experiment_name=experiment_name_two,
        experiment_metadata={
            "phase": "second-eval",
            "project_name": target_project_name,
        },
        feedback_scores_amount=1,
        traces_amount=3,
        project_name=target_project_name,
    )

    # Per-item shape: every destination experiment item has a fresh
    # trace_id + dataset_item_id, and the trace exists under the
    # destination project (verified via ``verify_trace`` for its built-in
    # eventual-consistency retry). The trace's feedback_scores value /
    # reason varies per item (the equals scorer's mismatch produces 0.0
    # for some items and 1.0 for others), so per-trace feedback scores
    # aren't asserted by exact value here -- ``verify_experiment(
    # feedback_scores_amount=1)`` above already pins that the cascade
    # re-emitted the equals_scoring_function at the experiment-aggregate
    # level.
    dest_e1_items = destination_experiment_items(
        rest, experiment_id=dest_e1.id, dataset_id=dest_dataset_id
    )
    dest_e2_items = destination_experiment_items(
        rest, experiment_id=dest_e2.id, dataset_id=dest_dataset_id
    )
    for it in dest_e1_items + dest_e2_items:
        assert it.trace_id is not None, "destination experiment item missing trace_id"
        assert it.dataset_item_id is not None, (
            "destination item missing dataset_item_id"
        )
        verifiers.verify_trace(
            opik_client=opik_client,
            trace_id=it.trace_id,
            project_name=target_project_name,
        )
