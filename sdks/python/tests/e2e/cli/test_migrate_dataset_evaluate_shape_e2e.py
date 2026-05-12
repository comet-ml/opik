"""End-to-end test for ``opik migrate dataset`` using the natural
``opik.evaluate(...)`` flow as seeding (rather than direct REST writes).

Complements ``test_migrate_dataset_e2e.py`` (precise per-version delta
coverage via REST seeding) by exercising the realistic shape that real
users produce:

  1. Create a dataset, ``dataset.insert(...)`` a first batch of items
  2. Run ``opik.evaluate(...)`` (experiment E1) -- traces under PROJECT_NAME
  3. Insert more items
  4. Run ``opik.evaluate(...)`` (experiment E2) under a DIFFERENT project so
     E2's traces land in a separate project from E1's
  5. ``opik migrate dataset ... --to-project=<dest>``
  6. Verify everything round-trips: both experiments, all traces (from
     both source projects), all spans, feedback scores

This pins the cross-project cascade behavior: cross-project experiments
referencing the same source dataset cascade to ``--to-project``
regardless of which project they were originally in, and per-trace
project_id scoping pulls each trace's spans from the right place.
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any, Callable, Dict, Iterator

import pytest

import opik
from opik.evaluation.metrics import score_result

from ...conftest import random_chars
from ...testlib import generate_project_name
from .conftest import (
    destination_experiment_items,
    destination_feedback_scores_for_trace,
    find_destination_experiment,
    run_migrate_cli,
)

PROJECT_NAME = generate_project_name("e2e", __name__)


@pytest.fixture
def dataset_name() -> Iterator[str]:
    yield f"e2e-migrate-eval-{random_chars()}"


@pytest.fixture
def experiment_name_one() -> Iterator[str]:
    yield f"e2e-migrate-eval-exp1-{random_chars()}"


@pytest.fixture
def experiment_name_two() -> Iterator[str]:
    yield f"e2e-migrate-eval-exp2-{random_chars()}"


@pytest.fixture
def trace_project_name_two(opik_client: opik.Opik) -> Iterator[str]:
    """Second source project for experiment 2's traces.

    Created so experiment 2's traces land in a DIFFERENT project from
    experiment 1's. The dataset itself lives in ``source_project_name``
    (the standard fixture); only E2's evaluator writes its traces here.
    Best-effort cleanup at teardown.
    """
    name = f"e2e-migrate-eval-trace-proj-{random_chars()}"
    opik_client.rest_client.projects.create_project(name=name)
    yield name
    try:
        project_id = opik_client.rest_client.projects.retrieve_project(name=name).id
        opik_client.rest_client.projects.delete_project_by_id(project_id)
    except Exception:
        pass


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


def test_migrate_dataset__evaluate_shape_with_cross_project_traces__round_trips(
    opik_client: opik.Opik,
    source_project_name: str,
    target_project_name: str,
    trace_project_name_two: str,
    dataset_name: str,
    experiment_name_one: str,
    experiment_name_two: str,
    tmp_path: Path,
) -> None:
    # ── Seed via the natural opik.evaluate(...) flow ──
    # Dataset lives in source_project_name. Two evaluate() runs:
    # E1's traces land in source_project_name, E2's in trace_project_name_two.
    # Both experiments reference the same source dataset, so the cascade
    # has to follow them both to --to-project regardless of original project.
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

    # Experiment 1 — traces under source_project_name (same as the dataset).
    eval1 = opik.evaluate(
        dataset=dataset,
        task=_llm_task,
        scoring_functions=[_equals_score],
        experiment_name=experiment_name_one,
        experiment_config={"phase": "first-eval"},
        project_name=source_project_name,
    )

    # Add more items to the dataset between runs to exercise the "experiments
    # reference different snapshots" case — E1 saw 2 items, E2 sees 3.
    dataset.insert(
        [
            {
                "input": {"question": "Capital of Spain?"},
                "expected": {"output": "Madrid"},
            },
        ]
    )

    # Experiment 2 — traces under trace_project_name_two (DIFFERENT project
    # from the dataset's and from E1's traces).
    eval2 = opik.evaluate(
        dataset=dataset,
        task=_llm_task,
        scoring_functions=[_equals_score],
        experiment_name=experiment_name_two,
        experiment_config={"phase": "second-eval"},
        project_name=trace_project_name_two,
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

    # ── Verify the destination ──
    # Look up the new destination dataset id (cascade keeps the same name
    # at the destination; source was renamed to <name>_v1 by the migrate).
    dest_dataset = rest.datasets.get_dataset_by_identifier(
        dataset_name=dataset_name, project_name=target_project_name
    )
    dest_dataset_id = dest_dataset.id

    # Both experiments must have been recreated under target_project_name,
    # carrying their original names.
    dest_e1 = find_destination_experiment(
        rest,
        destination_dataset_id=dest_dataset_id,
        experiment_name=experiment_name_one,
    )
    dest_e2 = find_destination_experiment(
        rest,
        destination_dataset_id=dest_dataset_id,
        experiment_name=experiment_name_two,
    )
    assert dest_e1.id != eval1.experiment_id, (
        "destination experiment 1 must have a fresh id, not the source's"
    )
    assert dest_e2.id != eval2.experiment_id, (
        "destination experiment 2 must have a fresh id, not the source's"
    )

    # Each experiment's items round-trip with the right item count. E1 saw
    # the dataset at 2 items; E2 saw it at 3.
    dest_e1_items = destination_experiment_items(
        rest, experiment_id=dest_e1.id, dataset_id=dest_dataset_id
    )
    dest_e2_items = destination_experiment_items(
        rest, experiment_id=dest_e2.id, dataset_id=dest_dataset_id
    )
    assert len(dest_e1_items) == 2, (
        f"E1 should have 2 items (dataset had 2 at first evaluate); "
        f"got {len(dest_e1_items)}"
    )
    assert len(dest_e2_items) == 3, (
        f"E2 should have 3 items (dataset had 3 at second evaluate); "
        f"got {len(dest_e2_items)}"
    )

    # Each item has a fresh destination trace_id (not equal to any source id).
    for it in dest_e1_items + dest_e2_items:
        assert it.trace_id is not None, "destination experiment item missing trace_id"
        assert it.dataset_item_id is not None, (
            "destination item missing dataset_item_id"
        )

    # Per-trace feedback scores survive the cascade. The equals scorer
    # emits exactly one score per trace; the cascade must re-emit it on
    # the destination trace via score_batch_of_traces.
    for it in dest_e1_items:
        scores = destination_feedback_scores_for_trace(rest, trace_id=it.trace_id)
        score_names = {s.name for s in scores}
        assert "equals_scoring_function" in score_names, (
            f"E1 trace {it.trace_id} missing the equals_scoring_function "
            f"feedback score; saw {score_names}"
        )
    for it in dest_e2_items:
        scores = destination_feedback_scores_for_trace(rest, trace_id=it.trace_id)
        score_names = {s.name for s in scores}
        assert "equals_scoring_function" in score_names, (
            f"E2 trace {it.trace_id} (originally in {trace_project_name_two}) "
            f"missing the equals_scoring_function feedback score; "
            f"saw {score_names}"
        )
