"""
E2E tests for ``opik.evaluate_resume`` against a real Opik backend.

Each test follows the same narrative, top to bottom:

    1. Build a dataset.
    2. Run ``opik.evaluate()`` — sometimes with a task that crashes mid-way
       to simulate an interruption.
    3. Verify the original run's outcome via the ``EvaluationResult`` it
       returned, or — when the run raised — via the experiment record.
    4. Run ``opik.evaluate_resume()`` with a working task and the same
       metrics + scoring_key_mapping the user originally supplied.
    5. Verify that resume re-ran only the missing items, and that the
       experiment converged to the expected final state.

All assertions go through user-facing API: the ``EvaluationResult``
returned by ``evaluate`` / ``evaluate_resume``, ``verify_experiment(...)``,
and ``verify_experiment_items_completed(...)``. Local checkpoint files and
internal resume state are implementation details and are never inspected
directly.
"""

from typing import Any, Dict, Set

import pytest

import opik
from opik import id_helpers
from opik.evaluation import metrics, samplers
from opik.evaluation.metrics import base_metric, score_result

from .. import verifiers
from ...testlib import generate_project_name

PROJECT_NAME = generate_project_name("e2e", __name__)


# --- helpers --------------------------------------------------------------


def _items_with_labels(labels):
    """
    Build dataset.insert payload + label↔uuid maps.

    The backend requires dataset item ``id`` to be a real UUID. Tests need
    stable labels (``item-0``, ``item-3``, ...) for readable assertions
    about which items crashed / got resumed / etc. This helper bridges the
    two: each label gets a generated UUID stored under ``id``, and the
    label travels alongside as part of the item content so tasks can
    reference it.
    """
    ids_by_label = {label: id_helpers.generate_id() for label in labels}
    labels_by_id = {uid: label for label, uid in ids_by_label.items()}
    payload = [
        {
            "id": ids_by_label[label],
            "input": {"text": label},
            "expected_output": label,
        }
        for label in labels
    ]
    return payload, ids_by_label, labels_by_id


def _experiment_id_after_failed_evaluate(opik_client, experiment_name) -> str:
    """
    Recover the experiment id when the original ``evaluate()`` raised — the
    engine re-raises the first task exception, so the ``EvaluationResult``
    is unavailable. The experiment record itself is created before task
    execution, so it exists even when the run crashed mid-way.
    """
    experiments = opik_client.get_experiments_by_name(
        experiment_name, project_name=PROJECT_NAME
    )
    assert len(experiments) == 1, (
        f"Expected 1 experiment named {experiment_name}, got {len(experiments)}"
    )
    return experiments[0].id


# === Core scenarios =======================================================


def test_evaluate_resume__happy_path__metrics_and_mapping_round_trip(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Original ``evaluate()`` completes every item with an ``Equals`` metric
    and a ``scoring_key_mapping`` that renames ``expected_output`` to
    ``reference``. ``evaluate_resume()`` finds nothing pending — the task
    is never invoked, and the experiment is unchanged.
    """
    # 1. Dataset: 3 items whose `expected_output` matches what `echo_task`
    #    will return — every Equals score is 1.0.
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    dataset.insert(
        [
            {"input": {"text": "hello"}, "expected_output": "hello"},
            {"input": {"text": "world"}, "expected_output": "world"},
            {"input": {"text": "test"}, "expected_output": "test"},
        ]
    )

    def echo_task(item: Dict[str, Any]):
        return {"output": item["input"]["text"]}

    scoring_key_mapping = {"reference": "expected_output"}
    expected_all_ids: Set[str] = {item["id"] for item in dataset.get_items()}

    # 2. Original evaluate — every item runs to completion.
    result = opik.evaluate(
        dataset=dataset,
        task=echo_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        experiment_name=experiment_name,
        task_threads=1,
        verbose=0,
    )

    # 3. Verify: 3 test results, each with an Equals score of 1.0.
    assert len(result.test_results) == 3
    for test_result in result.test_results:
        assert len(test_result.score_results) == 1
        assert test_result.score_results[0].value == 1.0

    verifiers.verify_experiment_items_completed(
        opik_client,
        result.experiment_id,
        expected_completed_dataset_item_ids=expected_all_ids,
    )

    # 4. Resume — re-supply the metrics and mapping (the framework cannot
    #    persist them: they are user-side Python objects).
    resume_invocations = []

    def resume_task(item: Dict[str, Any]):
        resume_invocations.append(item["id"])
        return {"output": item["input"]["text"]}

    resume_result = opik.evaluate_resume(
        experiment_id=result.experiment_id,
        task=resume_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    # 5. Verify: resume was a no-op on the task side, but the returned
    #    EvaluationResult describes the full experiment — all 3 items are
    #    present (reconstructed from their stored scores), each still 1.0.
    assert resume_invocations == []
    assert len(resume_result.test_results) == 3
    for test_result in resume_result.test_results:
        assert test_result.score_results[0].value == 1.0
    verifiers.verify_experiment_items_completed(
        opik_client,
        result.experiment_id,
        expected_completed_dataset_item_ids=expected_all_ids,
    )


def test_evaluate_resume__failure_during_evaluate__continue_works(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Flow 1: original ``evaluate()`` crashes on a subset of items. A single
    ``evaluate_resume()`` call completes the missing items and the
    experiment converges to "all items completed".
    """
    # 1. 5-item dataset. Labels (``item-N``) double as the input text so
    #    tasks can pick them out without touching the UUID ``id`` field.
    labels = [f"item-{i}" for i in range(5)]
    items, ids_by_label, labels_by_id = _items_with_labels(labels)
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    dataset.insert(items)
    all_uuids = set(ids_by_label.values())
    failed_labels = {"item-3", "item-4"}
    failed_uuids = {ids_by_label[label] for label in failed_labels}

    def crashing_task(item: Dict[str, Any]):
        if item["input"]["text"] in failed_labels:
            raise RuntimeError(f"simulated crash on {item['input']['text']}")
        return {"output": item["input"]["text"]}

    scoring_key_mapping = {"reference": "expected_output"}

    # 2. Original evaluate — expected to raise after the first crash.
    try:
        opik.evaluate(
            dataset=dataset,
            task=crashing_task,
            scoring_metrics=[metrics.Equals()],
            scoring_key_mapping=scoring_key_mapping,
            experiment_name=experiment_name,
            task_threads=1,
            verbose=0,
        )
    except Exception:
        pass  # see _experiment_id_after_failed_evaluate docstring

    experiment_id = _experiment_id_after_failed_evaluate(opik_client, experiment_name)

    # 3. Verify partial state: only the 3 non-crashing items completed.
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=all_uuids - failed_uuids,
    )

    # 4. Resume with a working task.
    resume_invocations = []

    def working_task(item: Dict[str, Any]):
        resume_invocations.append(item["input"]["text"])
        return {"output": item["input"]["text"]}

    resume_result = opik.evaluate_resume(
        experiment_id=experiment_id,
        task=working_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    # 5. Verify: only the failed items were re-invoked by the task, but the
    #    returned EvaluationResult describes the full experiment — all 5
    #    items appear, every score is 1.0.
    assert set(resume_invocations) == failed_labels
    assert len(resume_result.test_results) == 5
    for test_result in resume_result.test_results:
        assert test_result.score_results[0].value == 1.0
    assert {tr.test_case.dataset_item_id for tr in resume_result.test_results} == (
        all_uuids
    )

    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=all_uuids,
    )


def test_evaluate_resume__failure_during_continue__second_continue_works(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Flow 2: original ``evaluate()`` fails on two items; the first
    ``evaluate_resume()`` fixes one of them but crashes on the other; a
    second ``evaluate_resume()`` finishes the remaining item.

    This verifies that resume reads its state fresh from the experiment on
    every call — there is no in-memory "we already tried this" state that
    would prevent a second resume from picking up the still-pending item.
    """
    # 1. 5-item dataset; labels stand in for ids in task-side logic.
    labels = [f"item-{i}" for i in range(5)]
    items, ids_by_label, labels_by_id = _items_with_labels(labels)
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    dataset.insert(items)
    all_uuids = set(ids_by_label.values())

    def uuids_of(label_set):
        return {ids_by_label[label] for label in label_set}

    scoring_key_mapping = {"reference": "expected_output"}

    # 2. Original evaluate — items 3 and 4 crash.
    def original_task(item: Dict[str, Any]):
        label = item["input"]["text"]
        if label in {"item-3", "item-4"}:
            raise RuntimeError(f"original crash on {label}")
        return {"output": label}

    try:
        opik.evaluate(
            dataset=dataset,
            task=original_task,
            scoring_metrics=[metrics.Equals()],
            scoring_key_mapping=scoring_key_mapping,
            experiment_name=experiment_name,
            task_threads=1,
            verbose=0,
        )
    except Exception:
        pass

    experiment_id = _experiment_id_after_failed_evaluate(opik_client, experiment_name)

    # 3. After the original run: items 0, 1, 2 are done; items 3 and 4 are pending.
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=uuids_of({"item-0", "item-1", "item-2"}),
    )

    # 4a. First resume — fixes item-3, but a different bug crashes item-4.
    first_resume_invocations = []

    def first_resume_task(item: Dict[str, Any]):
        label = item["input"]["text"]
        first_resume_invocations.append(label)
        if label == "item-4":
            raise RuntimeError("still flaky on item-4")
        return {"output": label}

    try:
        opik.evaluate_resume(
            experiment_id=experiment_id,
            task=first_resume_task,
            scoring_metrics=[metrics.Equals()],
            scoring_key_mapping=scoring_key_mapping,
            verbose=0,
        )
    except Exception:
        pass

    # First resume saw exactly the two previously-pending items; item-3
    # finished, item-4 still pending.
    assert set(first_resume_invocations) == {"item-3", "item-4"}
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=uuids_of(
            {"item-0", "item-1", "item-2", "item-3"}
        ),
    )

    # 4b. Second resume — bug fixed, item-4 completes.
    second_resume_invocations = []

    def second_resume_task(item: Dict[str, Any]):
        second_resume_invocations.append(item["input"]["text"])
        return {"output": item["input"]["text"]}

    opik.evaluate_resume(
        experiment_id=experiment_id,
        task=second_resume_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    # 5. Verify: second resume only touched the still-pending item-4, and
    #    the experiment now shows every item completed.
    assert second_resume_invocations == ["item-4"]
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=all_uuids,
    )


def test_evaluate_resume__nonexistent_experiment__raises(
    opik_client: opik.Opik,
):
    """Clean error path: resuming an id that does not exist raises."""
    with pytest.raises(opik.exceptions.ExperimentNotFound):
        opik.evaluate_resume(
            experiment_id=id_helpers.generate_id(),
            task=lambda _item: {"output": "x"},
            verbose=0,
        )


# === Iteration config variants ============================================


def test_evaluate_resume__dataset_filter_string__filter_replayed(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    The original run filtered to ``category = "geo"``. Resume must replay
    the same filter; items outside the filter must stay out of scope.
    """
    # 1. 4 items in two categories.
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    dataset.insert(
        [
            {"input": {"text": "q1"}, "expected_output": "q1", "category": "geo"},
            {"input": {"text": "q2"}, "expected_output": "q2", "category": "math"},
            {"input": {"text": "q3"}, "expected_output": "q3", "category": "geo"},
            {"input": {"text": "q4"}, "expected_output": "q4", "category": "math"},
        ]
    )

    def echo_task(item: Dict[str, Any]):
        return {"output": item["input"]["text"]}

    scoring_key_mapping = {"reference": "expected_output"}

    # 2. Original evaluate — filter selects 2 of 4 items.
    result = opik.evaluate(
        dataset=dataset,
        task=echo_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        dataset_filter_string='data.category = "geo"',
        experiment_name=experiment_name,
        task_threads=1,
        verbose=0,
    )

    # 3. Verify exactly 2 items processed; capture their ids for the
    #    converged-state check.
    assert len(result.test_results) == 2
    selected_ids = {tr.test_case.dataset_item_id for tr in result.test_results}
    assert len(selected_ids) == 2
    verifiers.verify_experiment_items_completed(
        opik_client,
        result.experiment_id,
        expected_completed_dataset_item_ids=selected_ids,
    )

    # 4. Resume — same filter is replayed; both items already done.
    resume_invocations = []

    def task_for_resume(item: Dict[str, Any]):
        resume_invocations.append(item["id"])
        return {"output": item["input"]["text"]}

    opik.evaluate_resume(
        experiment_id=result.experiment_id,
        task=task_for_resume,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    # 5. The 2 math items must never reach the task; the completed set is
    #    unchanged.
    assert resume_invocations == []
    verifiers.verify_experiment_items_completed(
        opik_client,
        result.experiment_id,
        expected_completed_dataset_item_ids=selected_ids,
    )


def test_evaluate_resume__dataset_item_ids__only_selected_items_resumed(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    When the original run passed explicit ``dataset_item_ids``, resume must
    iterate the same ids — items outside the selection must stay out of
    scope even though they exist in the dataset.
    """
    # 1. 4 items; we'll select the first two by id.
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    ids = [id_helpers.generate_id() for _ in range(4)]
    dataset.insert(
        [
            {"id": ids[i], "input": {"text": f"v{i}"}, "expected_output": f"v{i}"}
            for i in range(4)
        ]
    )

    selected_ids = ids[:2]
    failed_id = ids[1]
    successful_selected_id = ids[0]

    def crashing_task(item: Dict[str, Any]):
        if item["id"] == failed_id:
            raise RuntimeError("crash on selected id")
        return {"output": item["input"]["text"]}

    scoring_key_mapping = {"reference": "expected_output"}

    # 2. Original evaluate runs only the selected ids; one crashes.
    try:
        opik.evaluate(
            dataset=dataset,
            task=crashing_task,
            dataset_item_ids=selected_ids,
            scoring_metrics=[metrics.Equals()],
            scoring_key_mapping=scoring_key_mapping,
            experiment_name=experiment_name,
            task_threads=1,
            verbose=0,
        )
    except Exception:
        pass

    experiment_id = _experiment_id_after_failed_evaluate(opik_client, experiment_name)

    # 3. Only the non-failing selected id is completed so far.
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids={successful_selected_id},
    )

    # 4. Resume — only the failed selected id should run again; the two
    #    unselected items must never reach the task.
    resume_invocations = []

    def working_task(item: Dict[str, Any]):
        resume_invocations.append(item["id"])
        return {"output": item["input"]["text"]}

    opik.evaluate_resume(
        experiment_id=experiment_id,
        task=working_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    # 5. Verify: only the failed selected id was re-run; both selected ids
    #    are now completed; the two unselected ids never entered scope.
    assert resume_invocations == [failed_id]
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=set(selected_ids),
    )


def test_evaluate_resume__random_sampler__only_sampled_items_resumed(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Original run sampled 3 items out of 10 with a ``RandomDatasetSampler``.
    Resume must iterate the exact same 3 sampled items.
    """
    # 1. 10-item dataset.
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    ids = [id_helpers.generate_id() for _ in range(10)]
    dataset.insert(
        [
            {"id": ids[i], "input": {"text": f"v{i}"}, "expected_output": f"v{i}"}
            for i in range(10)
        ]
    )

    def echo_task(item: Dict[str, Any]):
        return {"output": item["input"]["text"]}

    scoring_key_mapping = {"reference": "expected_output"}

    # 2. Original evaluate samples 3 of 10.
    result = opik.evaluate(
        dataset=dataset,
        task=echo_task,
        dataset_sampler=samplers.RandomDatasetSampler(max_samples=3, seed=42),
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        experiment_name=experiment_name,
        task_threads=1,
        verbose=0,
    )

    # 3. Verify only 3 items processed; capture their ids.
    assert len(result.test_results) == 3
    sampled_ids = {tr.test_case.dataset_item_id for tr in result.test_results}
    assert len(sampled_ids) == 3
    verifiers.verify_experiment_items_completed(
        opik_client,
        result.experiment_id,
        expected_completed_dataset_item_ids=sampled_ids,
    )

    # 4. Resume — same 3 sampled ids replayed; all already done.
    resume_invocations = []

    def task_for_resume(item: Dict[str, Any]):
        resume_invocations.append(item["id"])
        return {"output": item["input"]["text"]}

    opik.evaluate_resume(
        experiment_id=result.experiment_id,
        task=task_for_resume,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    # 5. The 7 unsampled items must never reach the task; converged set
    #    remains the same 3.
    assert resume_invocations == []
    verifiers.verify_experiment_items_completed(
        opik_client,
        result.experiment_id,
        expected_completed_dataset_item_ids=sampled_ids,
    )


def test_evaluate_resume__nb_samples__only_sampled_count_replayed(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Original run capped iteration at ``nb_samples=3`` against a 5-item
    dataset. Resume must replay the same cap against the same
    (version-pinned) dataset; the unsampled items must stay out of scope.
    """
    # 1. 5-item dataset (labels carried in input.text for readability).
    labels = [f"item-{i}" for i in range(5)]
    items, _ids_by_label, _labels_by_id = _items_with_labels(labels)
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    dataset.insert(items)

    def echo_task(item: Dict[str, Any]):
        return {"output": item["input"]["text"]}

    scoring_key_mapping = {"reference": "expected_output"}

    # 2. Original evaluate limits to 3 items.
    result = opik.evaluate(
        dataset=dataset,
        task=echo_task,
        nb_samples=3,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        experiment_name=experiment_name,
        task_threads=1,
        verbose=0,
    )

    # 3. Verify only 3 items processed.
    assert len(result.test_results) == 3
    capped_ids = {tr.test_case.dataset_item_id for tr in result.test_results}
    verifiers.verify_experiment_items_completed(
        opik_client,
        result.experiment_id,
        expected_completed_dataset_item_ids=capped_ids,
    )

    # 4. Resume — nb_samples=3 replayed against the pinned version; same 3
    #    items returned by the stream; all already done.
    resume_invocations = []

    def task_for_resume(item: Dict[str, Any]):
        resume_invocations.append(item["input"]["text"])
        return {"output": item["input"]["text"]}

    opik.evaluate_resume(
        experiment_id=result.experiment_id,
        task=task_for_resume,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    # 5. No re-runs — the 2 unsampled items must stay out of scope.
    assert resume_invocations == []
    verifiers.verify_experiment_items_completed(
        opik_client,
        result.experiment_id,
        expected_completed_dataset_item_ids=capped_ids,
    )


# === Trials ===============================================================


def test_evaluate_resume__trial_count__partial_item_has_all_trials_redone(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Partial-trial contract: with ``trial_count=3``, the original task
    succeeds on the first trial then crashes on the second — the item is
    *partially* evaluated (1 of 3). Resume must redo **all 3** trials from
    scratch (not just the missing 2), so the merged result never mixes
    outputs produced by the buggy original task with the fixed resume task.
    """
    # 1. Single-item dataset (keeps the trial bookkeeping simple). Backend
    #    requires UUIDs for the ``id`` field, so we generate one upfront
    #    and pin the verifier to it.
    the_item_id = id_helpers.generate_id()
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    dataset.insert(
        [{"id": the_item_id, "input": {"text": "value"}, "expected_output": "value"}]
    )

    # Task that succeeds on its first invocation and crashes thereafter.
    call_counter = {"count": 0}

    def flaky_task(item: Dict[str, Any]):
        call_counter["count"] += 1
        if call_counter["count"] > 1:
            raise RuntimeError("crash on later trial")
        return {"output": item["input"]["text"]}

    scoring_key_mapping = {"reference": "expected_output"}

    # 2. Original evaluate with trial_count=3 — first trial succeeds, the
    #    second crashes and the engine re-raises.
    try:
        opik.evaluate(
            dataset=dataset,
            task=flaky_task,
            trial_count=3,
            scoring_metrics=[metrics.Equals()],
            scoring_key_mapping=scoring_key_mapping,
            experiment_name=experiment_name,
            task_threads=1,
            verbose=0,
        )
    except Exception:
        pass

    experiment_id = _experiment_id_after_failed_evaluate(opik_client, experiment_name)

    # 3. The item has at least one completed trial (the first one).
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids={the_item_id},
    )

    # 4. Resume with a non-crashing task. Because the item is partial
    #    (1 of 3), resume must re-run all 3 trials, not just 2.
    resume_invocations = []

    def working_task(item: Dict[str, Any]):
        resume_invocations.append(item["id"])
        return {"output": item["input"]["text"]}

    resume_result = opik.evaluate_resume(
        experiment_id=experiment_id,
        task=working_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    # 5. The same item ran 3 more times — every trial redone end-to-end.
    assert resume_invocations == [the_item_id, the_item_id, the_item_id], (
        "Partial items must have all trials redone end-to-end; got "
        f"{resume_invocations}"
    )

    # The merged EvaluationResult contains only the 3 fresh trials. The
    # original (partial) trial is intentionally excluded, since mixing
    # outputs from the buggy and fixed task would be confusing.
    assert len(resume_result.test_results) == 3
    assert all(
        tr.test_case.dataset_item_id == the_item_id for tr in resume_result.test_results
    )
    assert all(tr.score_results[0].value == 1.0 for tr in resume_result.test_results)
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids={the_item_id},
    )


def test_evaluate_resume__mixed_partial_and_fully_completed_items(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    With ``trial_count=2`` over three items, the original run leaves a mix:
      - item-0 fully completed (2 of 2 trials)
      - item-1 partially done (1 of 2 trials — second trial crashed)
      - item-2 fully completed (2 of 2 trials)

    The engine submits every trial up front and the executor only re-raises
    the first failure after collecting all results, so item-1's crash does
    not prevent item-2's trials from running. The interesting partial state
    is item-1.

    Resume must:
      - leave item-0 alone (no task invocations; stored trials reconstructed)
      - redo all 2 trials for item-1 (partial → from scratch)
      - leave item-2 alone (no task invocations; stored trials reconstructed)
    The merged result has 4 reconstructed + 2 fresh = 6 trials.
    """
    # 1. Three items. Labels carried as input text so the task can pick
    #    them out without touching the UUID ``id`` field.
    labels = [f"item-{i}" for i in range(3)]
    items, ids_by_label, _labels_by_id = _items_with_labels(labels)
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    dataset.insert(items)

    # Original task: crashes on item-1's SECOND call; everything else
    # succeeds (including all of item-2's trials).
    call_log = []

    def flaky_task(item: Dict[str, Any]):
        label = item["input"]["text"]
        call_log.append(label)
        is_item_1_second_call = label == "item-1" and call_log.count("item-1") == 2
        if is_item_1_second_call:
            raise RuntimeError("crash on item-1 second trial")
        return {"output": label}

    scoring_key_mapping = {"reference": "expected_output"}

    # 2. Original evaluate — single-threaded so the trial order is
    #    deterministic and item-1 fails on its 2nd trial as designed.
    try:
        opik.evaluate(
            dataset=dataset,
            task=flaky_task,
            trial_count=2,
            scoring_metrics=[metrics.Equals()],
            scoring_key_mapping=scoring_key_mapping,
            experiment_name=experiment_name,
            task_threads=1,
            verbose=0,
        )
    except Exception:
        pass

    experiment_id = _experiment_id_after_failed_evaluate(opik_client, experiment_name)

    # 3. All three items have at least one successful trial logged — the
    #    failure on item-1's second trial does not stop the executor from
    #    completing item-2's trials.
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=set(ids_by_label.values()),
    )

    # 4. Resume with a working task.
    resume_invocations = []

    def working_task(item: Dict[str, Any]):
        resume_invocations.append(item["input"]["text"])
        return {"output": item["input"]["text"]}

    resume_result = opik.evaluate_resume(
        experiment_id=experiment_id,
        task=working_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    # 5. item-0 fully completed (2/2 successful) → no resume invocations.
    #    item-1 partial (1/2 successful) → both trials redone.
    #    item-2 fully completed (2/2 successful) → no resume invocations.
    counts_by_label = {label: resume_invocations.count(label) for label in labels}
    assert counts_by_label == {"item-0": 0, "item-1": 2, "item-2": 0}, (
        f"Unexpected resume task invocation distribution: {counts_by_label}"
    )

    # Merged result: 2 reconstructed for item-0 + 2 fresh for item-1 +
    # 2 reconstructed for item-2 = 6 trials total.
    assert len(resume_result.test_results) == 6
    counts_in_result = {
        label: sum(
            1
            for tr in resume_result.test_results
            if tr.test_case.dataset_item_id == ids_by_label[label]
        )
        for label in labels
    }
    assert counts_in_result == {"item-0": 2, "item-1": 2, "item-2": 2}

    # All three items end up in the converged completed set.
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=set(ids_by_label.values()),
    )


# === Marker-design failure modes ==========================================


class _MetricRaisingBaseException(base_metric.BaseMetric):
    """
    Metric that succeeds on most items but raises ``BaseException`` on a
    chosen subset. ``BaseException`` (not ``Exception``) escapes the
    per-metric ``except Exception`` handler inside the engine, so the
    failure propagates past scoring even though the task itself returned
    cleanly. End result: the trial's trace is written with ``output`` set
    (task succeeded) and the pending marker still at ``True`` (scoring
    never reached the happy-path-only line that clears it).

    This is the failure mode the marker design exists to detect — the old
    ``evaluation_task_output is not None`` predicate would have classified
    the trial as fully completed and resume would have skipped it.
    """

    def __init__(self, failing_labels: Set[str]) -> None:
        super().__init__(name="raises_on_subset")
        self._failing_labels = failing_labels

    def score(
        self, output: str, reference: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        if output in self._failing_labels:
            # SystemExit is a BaseException; the engine's per-metric
            # except-clause catches Exception only, so this escapes.
            raise SystemExit(f"simulated scoring crash on label={output!r}")
        return score_result.ScoreResult(
            name=self.name,
            value=1.0 if output == reference else 0.0,
        )


def test_evaluate_resume__scoring_crash_after_task_success__trial_replayed(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    The case the marker design exists for: the task succeeds (so the
    trace's ``output`` is set), but a metric raises ``BaseException``
    mid-scoring. The trial is recorded with output set but the marker
    still at ``True``. Resume must read the marker and replay.

    Under the pre-marker predicate (``evaluation_task_output is not None``)
    these items would be misclassified as fully completed and silently
    skipped on resume.
    """
    # 1. 3-item dataset. Single-threaded scoring keeps the failure
    #    deterministic regardless of submission order.
    labels = [f"item-{i}" for i in range(3)]
    items, ids_by_label, _ = _items_with_labels(labels)
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    dataset.insert(items)
    scoring_will_fail = {"item-1"}
    fully_ok_uuids = {
        ids_by_label[label] for label in labels if label not in scoring_will_fail
    }

    def working_task(item: Dict[str, Any]):
        return {"output": item["input"]["text"]}

    scoring_key_mapping = {"reference": "expected_output", "output": "output"}

    # 2. Original evaluate — task is healthy, but the metric raises on
    #    ``item-1``. The simulated crash is ``SystemExit`` (a
    #    ``BaseException`` subclass) so it escapes the engine's
    #    ``except Exception`` handler; we catch it narrowly here so any
    #    unrelated ``KeyboardInterrupt`` / ``GeneratorExit`` is not
    #    silently swallowed.
    try:
        opik.evaluate(
            dataset=dataset,
            task=working_task,
            scoring_metrics=[
                _MetricRaisingBaseException(failing_labels=scoring_will_fail)
            ],
            scoring_key_mapping=scoring_key_mapping,
            experiment_name=experiment_name,
            task_threads=1,
            verbose=0,
        )
    except SystemExit:
        pass

    experiment_id = _experiment_id_after_failed_evaluate(opik_client, experiment_name)

    # 3. Verify the partial state from the marker's point of view:
    #    item-0 and item-2 reached the happy-path line and count as
    #    completed; item-1 did not (its scoring crashed) and is excluded
    #    even though its task wrote output to the trace.
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=fully_ok_uuids,
    )

    # 4. Resume with a healthy task + a metric that never raises.
    resume_invocations: list = []

    def resume_task(item: Dict[str, Any]):
        resume_invocations.append(item["input"]["text"])
        return {"output": item["input"]["text"]}

    resume_result = opik.evaluate_resume(
        experiment_id=experiment_id,
        task=resume_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    # 5. Only the scoring-failed item was replayed; the two items that
    #    cleared their happy-path line were left alone.
    assert resume_invocations == ["item-1"], (
        f"Only the scoring-failed item should be replayed; got {resume_invocations}"
    )
    assert len(resume_result.test_results) == 3
    assert all(tr.score_results[0].value == 1.0 for tr in resume_result.test_results)
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=set(ids_by_label.values()),
    )


def test_evaluate_resume__metric_scoring_failed_inside_loop__not_replayed(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Counterpart to the BaseException case: when a metric raises a regular
    ``Exception`` (or returns ``scoring_failed=True``), the engine catches
    it inside the per-metric loop and the scoring step still reaches the
    happy-path line. The trial is fully completed (marker flipped to
    ``False``), and resume must NOT replay it — even though the stored
    feedback score is missing or marked as failed.

    This regression-guards the "scoring loop reached its end" semantics
    against future changes to the marker logic.
    """
    labels = [f"item-{i}" for i in range(3)]
    items, ids_by_label, _ = _items_with_labels(labels)
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    dataset.insert(items)
    metric_will_fail_on = {"item-1"}

    class _MetricRaisingException(base_metric.BaseMetric):
        def __init__(self) -> None:
            super().__init__(name="raises_caught_by_engine")

        def score(
            self, output: str, reference: str, **ignored_kwargs: Any
        ) -> score_result.ScoreResult:
            if output in metric_will_fail_on:
                raise RuntimeError(f"caught simulated failure on {output!r}")
            return score_result.ScoreResult(
                name=self.name,
                value=1.0 if output == reference else 0.0,
            )

    def working_task(item: Dict[str, Any]):
        return {"output": item["input"]["text"]}

    scoring_key_mapping = {"reference": "expected_output", "output": "output"}

    # 2. Evaluate runs to completion — RuntimeError is caught inside the
    #    metric loop (engine converts it to ``ScoreResult(scoring_failed=True)``),
    #    so the scoring step still returns and the happy-path marker is
    #    cleared on every trial.
    evaluate_result = opik.evaluate(
        dataset=dataset,
        task=working_task,
        scoring_metrics=[_MetricRaisingException()],
        scoring_key_mapping=scoring_key_mapping,
        experiment_name=experiment_name,
        task_threads=1,
        verbose=0,
    )

    assert len(evaluate_result.test_results) == 3
    experiment_id = evaluate_result.experiment_id

    # 3. All three items have cleared markers; resume should treat the
    #    experiment as fully completed.
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=set(ids_by_label.values()),
    )

    # 4. Resume with a healthy metric — none of the items should be
    #    re-invoked, even item-1 whose only stored score is failed.
    resume_invocations: list = []

    def resume_task(item: Dict[str, Any]):
        resume_invocations.append(item["input"]["text"])
        return {"output": item["input"]["text"]}

    resume_result = opik.evaluate_resume(
        experiment_id=experiment_id,
        task=resume_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    assert resume_invocations == [], (
        "Items with a cleared marker must not be replayed even when the "
        f"stored score is failed; got resume invocations: {resume_invocations}"
    )
    assert len(resume_result.test_results) == 3


def test_evaluate_resume__mixed_task_and_scoring_failures__only_failed_items_replayed(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Combined coverage: one item fails in the task, one fails in scoring
    (BaseException), one completes happily. Resume must replay exactly the
    two failed items — distinguishing them from the happy one purely via
    the marker.
    """
    labels = ["task_fails", "scoring_fails", "all_good"]
    items, ids_by_label, _ = _items_with_labels(labels)
    dataset = opik_client.create_dataset(dataset_name, project_name=PROJECT_NAME)
    dataset.insert(items)

    def task_failing_for_one(item: Dict[str, Any]):
        if item["input"]["text"] == "task_fails":
            raise RuntimeError("simulated task crash on task_fails")
        return {"output": item["input"]["text"]}

    scoring_key_mapping = {"reference": "expected_output", "output": "output"}

    # ``_MetricRaisingBaseException`` raises ``SystemExit`` (a
    # ``BaseException`` subclass) on the scoring-failure label; the task
    # raises ``RuntimeError`` on the task-failure label. Catch the scoring
    # crash narrowly so we don't mask unrelated ``KeyboardInterrupt`` /
    # ``GeneratorExit``; the ``RuntimeError`` is consumed inside the
    # engine and does not escape.
    try:
        opik.evaluate(
            dataset=dataset,
            task=task_failing_for_one,
            scoring_metrics=[
                _MetricRaisingBaseException(failing_labels={"scoring_fails"})
            ],
            scoring_key_mapping=scoring_key_mapping,
            experiment_name=experiment_name,
            task_threads=1,
            verbose=0,
        )
    except SystemExit:
        pass

    experiment_id = _experiment_id_after_failed_evaluate(opik_client, experiment_name)

    # Only the all-good item finished the happy path.
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids={ids_by_label["all_good"]},
    )

    resume_invocations: list = []

    def resume_task(item: Dict[str, Any]):
        resume_invocations.append(item["input"]["text"])
        return {"output": item["input"]["text"]}

    resume_result = opik.evaluate_resume(
        experiment_id=experiment_id,
        task=resume_task,
        scoring_metrics=[metrics.Equals()],
        scoring_key_mapping=scoring_key_mapping,
        verbose=0,
    )

    assert set(resume_invocations) == {"task_fails", "scoring_fails"}
    assert len(resume_result.test_results) == 3
    verifiers.verify_experiment_items_completed(
        opik_client,
        experiment_id,
        expected_completed_dataset_item_ids=set(ids_by_label.values()),
    )
