"""E2E tests for EvaluationSuite API.

These tests verify the core evaluation suite functionality:
1. Item-level evaluators stored as dataset item fields
2. Suite-level evaluators applied to all items
3. Execution policy handling (runs_per_item, pass_threshold)
4. Pass/fail determination based on LLMJudge assertion results
5. Persistence: create, get, update, delete operations

Key concepts:
- Evaluation suites only support LLMJudge evaluators
- Suite-level evaluators and execution_policy are stored at dataset version level
- Item-level evaluators and execution_policy are stored as dataset item fields
- Items without evaluators pass by default (no assertions to fail)
- Pass/fail is determined by: runs_passed >= pass_threshold
"""

from typing import Dict, Any

import pytest

import opik
from opik.evaluation.suite_evaluators import LLMJudge
from .. import verifiers
from ...testlib import environment, ThreadSafeCounter


# =============================================================================
# MAIN FLOW: Item-level evaluators with LLMJudge
# =============================================================================


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__item_level_evaluators__feedback_scores_created(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Main flow: Items have their own LLMJudge evaluators.

    Each item can have different assertions to verify.

    Expected behavior:
    - Each item is evaluated using its own LLMJudge evaluators
    - Feedback scores are created with assertion text as the score name
    - Score values are boolean (True=1.0, False=0.0)
    """
    geography_assertion = (
        "The response correctly identifies Paris as the capital of France"
    )
    math_assertion = "The response correctly states that 2 + 2 equals 4"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test item-level evaluators",
    )

    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
        evaluators=[LLMJudge(name="geography_judge", assertions=[geography_assertion])],
    )
    suite.add_item(
        data={"input": {"question": "What is 2 + 2?"}},
        evaluators=[LLMJudge(name="math_judge", assertions=[math_assertion])],
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        question = item["input"]["question"]
        if "France" in question:
            return {"input": item["input"], "output": "The capital of France is Paris."}
        if "2 + 2" in question:
            return {"input": item["input"], "output": "2 + 2 equals 4."}
        return {"input": item["input"], "output": "Unknown"}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=2,
        items_passed=2,
        experiment_items_count=2,
        total_feedback_scores=2,  # 1 assertion per item * 2 items
        expected_score_names={geography_assertion, math_assertion},
    )

    # Verify score values are boolean (True=1.0, False=0.0)
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    for exp_item in retrieved_experiment.get_items():
        for score in exp_item.feedback_scores:
            assert score["value"] in [0.0, 1.0, True, False], (
                f"Score value should be boolean, got {score['value']}"
            )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__multiple_assertions_per_item__all_scores_created(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that multiple assertions in a single LLMJudge create multiple
    feedback scores, each evaluated independently.
    """
    assertion_1 = "The response is factually correct"
    assertion_2 = "The response is concise and clear"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test multiple assertions per item",
    )

    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
        evaluators=[
            LLMJudge(name="quality_judge", assertions=[assertion_1, assertion_2])
        ],
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        return {"input": item["input"], "output": "Paris is the capital of France."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=1,
        items_passed=1,
        experiment_items_count=1,
        total_feedback_scores=2,  # 2 assertions on 1 experiment item
        expected_score_names={assertion_1, assertion_2},
    )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__suite_level_evaluators__applied_to_all_items(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that suite-level evaluators are applied to every item.
    """
    suite_assertion = "The response is helpful and informative"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test suite-level evaluators",
        evaluators=[LLMJudge(name="suite_judge", assertions=[suite_assertion])],
    )

    suite.add_item(data={"input": {"question": "What is the capital of France?"}})
    suite.add_item(data={"input": {"question": "What is 2 + 2?"}})

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        question = item["input"]["question"]
        if "France" in question:
            return {"input": item["input"], "output": "The capital of France is Paris."}
        return {"input": item["input"], "output": "2 + 2 equals 4."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=2,
        experiment_items_count=2,
        total_feedback_scores=2,  # 1 assertion * 2 experiment items
        expected_score_names={suite_assertion},
    )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__combined_suite_and_item_level_evaluators__all_scores_created(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that suite-level and item-level evaluators are combined:
    total feedback scores = suite-level assertions + item-level assertions.
    """
    suite_assertion = "The response is helpful and informative"
    item_assertion = "The response correctly identifies Paris as the capital"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test combined suite and item level evaluators",
        evaluators=[LLMJudge(name="suite_judge", assertions=[suite_assertion])],
    )

    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
        evaluators=[LLMJudge(name="item_judge", assertions=[item_assertion])],
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        return {"input": item["input"], "output": "The capital of France is Paris."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=1,
        experiment_items_count=1,
        total_feedback_scores=2,  # 1 suite + 1 item assertion
        expected_score_names={suite_assertion, item_assertion},
    )


# =============================================================================
# EDGE CASE: Items without evaluators + default execution policy
# =============================================================================


def test_evaluation_suite__no_evaluators_default_policy__items_pass_with_single_run(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Edge case: Items without evaluators pass by default, and the default
    execution policy runs each item exactly once with pass_threshold=1.

    Expected behavior:
    - No assertions to check -> items pass
    - Default runs_per_item=1, pass_threshold=1
    - No feedback scores created
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test items without evaluators and default policy",
    )

    suite.add_item(
        data={
            "input": {"question": "What is the capital of France?"},
            "reference": "Paris",
        }
    )
    suite.add_item(
        data={
            "input": {"question": "What is the capital of Germany?"},
            "reference": "Berlin",
        }
    )

    call_count = ThreadSafeCounter()

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        call_count.increment()
        return {"input": item["input"], "output": "Some response"}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    # Default: runs_per_item=1, so 2 items = 2 calls
    assert call_count.value == 2

    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=2,
        items_passed=2,
        experiment_items_count=2,
        total_feedback_scores=0,
    )

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=2,
        feedback_scores_amount=0,
    )

    # Verify default pass_threshold=1 and runs_total=1
    for item_result in suite_result.item_results.values():
        assert item_result.runs_total == 1
        assert item_result.pass_threshold == 1


# =============================================================================
# EXECUTION POLICY: runs_per_item and pass_threshold
# =============================================================================


def test_evaluation_suite__execution_policy_runs_per_item__task_called_multiple_times(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that runs_per_item causes multiple task executions per item.
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test runs_per_item execution policy",
        execution_policy={"runs_per_item": 2, "pass_threshold": 1},
    )

    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
    )

    call_count = ThreadSafeCounter()

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        call_count.increment()
        return {"input": item["input"], "output": "Paris"}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    assert call_count.value == 2

    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=1,
        items_passed=1,
        experiment_items_count=2,
        total_feedback_scores=0,
    )

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=2,
        feedback_scores_amount=0,
    )

    item_result = list(suite_result.item_results.values())[0]
    assert item_result.runs_total == 2
    assert item_result.pass_threshold == 1


def test_evaluation_suite__item_level_execution_policy__overrides_suite_policy(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that item-level execution policy overrides suite-level policy.
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test item-level execution policy override",
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    # Item 1: uses suite-level policy (runs_per_item=1)
    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
    )

    # Item 2: overrides with item-level policy (runs_per_item=3)
    suite.add_item(
        data={"input": {"question": "What is the capital of Germany?"}},
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    france_count = ThreadSafeCounter()
    germany_count = ThreadSafeCounter()

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        question = item["input"]["question"]
        if "France" in question:
            france_count.increment()
        elif "Germany" in question:
            germany_count.increment()
        return {"input": item["input"], "output": "Answer"}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    assert france_count.value == 1
    assert germany_count.value == 3

    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=2,
        items_passed=2,
        experiment_items_count=4,  # 1 + 3
        total_feedback_scores=0,
    )

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=4,
        feedback_scores_amount=0,
    )

    # Verify item-level pass_threshold is used
    for item_result in suite_result.item_results.values():
        if item_result.runs_total == 3:
            assert item_result.pass_threshold == 2
        else:
            assert item_result.pass_threshold == 1


# =============================================================================
# PASS/FAIL DETERMINATION
# =============================================================================


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__assertion_fails__item_fails(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that items fail when LLMJudge assertions fail.
    """
    failing_assertion = "The response correctly states that 2 + 2 equals 5"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test assertion failure",
    )

    suite.add_item(
        data={"input": {"question": "What is 2 + 2?"}},
        evaluators=[LLMJudge(name="wrong_judge", assertions=[failing_assertion])],
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        return {"input": item["input"], "output": "2 + 2 equals 4."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=1,
        items_passed=0,
        experiment_items_count=1,
        total_feedback_scores=1,
        expected_score_names={failing_assertion},
    )

    # Additionally verify the score value indicates failure
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    score = retrieved_experiment.get_items()[0].feedback_scores[0]
    assert score["value"] in [0.0, False], (
        f"Expected failing score (0.0 or False), got {score['value']}"
    )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__pass_threshold_not_met__item_fails(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that items fail when pass_threshold is not met across multiple runs.

    With runs_per_item=3, pass_threshold=2: only the first run returns a
    correct answer, so at most 1 run passes (< threshold of 2).
    """
    judge = LLMJudge(
        name="math_judge",
        assertions=["The response correctly states that 2 + 2 equals 4"],
    )

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test pass threshold failure",
        evaluators=[judge],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(data={"input": {"question": "What is 2 + 2?"}})

    call_count = ThreadSafeCounter()

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        n = call_count.increment()
        # Only first run returns correct answer
        if n == 1:
            return {"input": item["input"], "output": "2 + 2 equals 4."}
        return {"input": item["input"], "output": "I don't know."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=1,
        items_passed=0,
    )

    item_result = list(suite_result.item_results.values())[0]
    assert item_result.passed is False
    assert item_result.runs_total == 3
    assert item_result.pass_threshold == 2


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__multiple_assertions_multiple_runs__pass_threshold_logic(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Comprehensive pass/fail logic test:
    - 1 item, 3 assertions, runs_per_item=3, pass_threshold=2
    - Consistent correct answers -> all runs pass -> item passes

    Pass/fail logic:
    1. A RUN passes if ALL assertions in that run pass
    2. An ITEM passes if runs_passed >= pass_threshold
    3. The SUITE passes if all items pass
    """
    assertion_1 = "The response mentions Paris"
    assertion_2 = "The response mentions France"
    assertion_3 = "The response is factually correct"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test multiple assertions with multiple runs",
        evaluators=[
            LLMJudge(
                name="geography_judge",
                assertions=[assertion_1, assertion_2, assertion_3],
            )
        ],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(data={"input": {"question": "What is the capital of France?"}})

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        return {"input": item["input"], "output": "The capital of France is Paris."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    assert suite_result.pass_rate == 1.0

    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=1,
        items_passed=1,
        experiment_items_count=3,  # 1 item * 3 runs
        total_feedback_scores=9,  # 3 assertions * 3 runs
        expected_score_names={assertion_1, assertion_2, assertion_3},
    )

    item_result = list(suite_result.item_results.values())[0]
    assert item_result.runs_total == 3
    assert item_result.pass_threshold == 2
    assert item_result.runs_passed >= 2
    assert item_result.passed is True

    # Verify each experiment item has exactly 3 scores (one per assertion)
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    for exp_item in retrieved_experiment.get_items():
        assert exp_item.feedback_scores is not None
        assert len(exp_item.feedback_scores) == 3, (
            f"Expected 3 feedback scores per run, got {len(exp_item.feedback_scores)}"
        )
        score_names = {s["name"] for s in exp_item.feedback_scores}
        assert score_names == {assertion_1, assertion_2, assertion_3}, (
            f"Expected all 3 assertion names on each run, got {score_names}"
        )


# =============================================================================
# PERSISTENCE: Create, get, update, delete
# =============================================================================


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__create_get_and_run__end_to_end(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    End-to-end test: create a suite, retrieve it via get_evaluation_suite(),
    then run it. Verifies that suite-level config survives the round-trip.
    """
    suite_assertion = "The response correctly identifies Paris as the capital of France"

    # 1. Create suite with evaluators + execution_policy
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Persistence test suite",
        evaluators=[LLMJudge(name="geography_judge", assertions=[suite_assertion])],
        execution_policy={"runs_per_item": 2, "pass_threshold": 1},
    )

    suite.add_item(data={"input": {"question": "What is the capital of France?"}})
    suite.add_item(data={"input": {"question": "What is the capital of Germany?"}})

    # 2. Retrieve from backend (simulates a fresh client loading existing suite)
    retrieved_suite = opik_client.get_evaluation_suite(name=dataset_name)

    # 3. Run the retrieved suite — evaluators/execution_policy come from BE
    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        question = item["input"]["question"]
        if "France" in question:
            return {"input": item["input"], "output": "The capital of France is Paris."}
        return {"input": item["input"], "output": "The capital of Germany is Berlin."}

    suite_result = retrieved_suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    # Verify suite ran with persisted execution policy (runs_per_item=2)
    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=2,
        experiment_items_count=4,  # 2 items * 2 runs
        total_feedback_scores=4,  # 1 assertion * 4 experiment items
        expected_score_names={suite_assertion},
    )

    for item_result in suite_result.item_results.values():
        assert item_result.runs_total == 2
        assert item_result.pass_threshold == 1


def test_evaluation_suite__delete_items__items_removed(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that delete_items() removes items from the suite.
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test delete items",
    )

    suite.add_item(data={"input": {"question": "Question 1"}})
    suite.add_item(data={"input": {"question": "Question 2"}})
    suite.add_item(data={"input": {"question": "Question 3"}})

    items = suite.get_items()
    assert len(items) == 3

    item_id_to_delete = items[0]["id"]

    suite.delete_items([item_id_to_delete])

    remaining_items = suite.get_items()
    assert len(remaining_items) == 2


def test_evaluation_suite__get_evaluators__returns_llm_judge_instances(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_evaluators() returns LLMJudge instances from suite-level config.
    """
    opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test get_evaluators",
        evaluators=[
            LLMJudge(name="judge_1", assertions=["Response is helpful"]),
            LLMJudge(
                name="judge_2",
                assertions=["Response is accurate", "Response is concise"],
            ),
        ],
    )

    # Retrieve from BE to verify persistence
    retrieved_suite = opik_client.get_evaluation_suite(name=dataset_name)

    evaluators = retrieved_suite.get_evaluators()
    assert len(evaluators) == 2
    assert all(isinstance(e, LLMJudge) for e in evaluators)

    evaluator_names = {e.name for e in evaluators}
    assert "judge_1" in evaluator_names
    assert "judge_2" in evaluator_names


def test_evaluation_suite__get_execution_policy__returns_persisted_policy(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_execution_policy() returns the persisted execution policy.
    """
    opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test get_execution_policy",
        execution_policy={"runs_per_item": 5, "pass_threshold": 3},
    )

    # Retrieve from BE to verify persistence
    retrieved_suite = opik_client.get_evaluation_suite(name=dataset_name)

    policy = retrieved_suite.get_execution_policy()
    assert policy["runs_per_item"] == 5
    assert policy["pass_threshold"] == 3


def test_evaluation_suite__get_execution_policy__default_when_not_set(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_execution_policy() returns default policy when none was set.
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test default execution policy",
    )

    policy = suite.get_execution_policy()
    assert policy["runs_per_item"] == 1
    assert policy["pass_threshold"] == 1


def test_evaluation_suite__get_items__returns_items_with_evaluators(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_items() returns items with evaluators as LLMJudge instances.
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test get_items",
    )

    suite.add_item(
        data={"input": {"question": "What is 2 + 2?"}},
        evaluators=[LLMJudge(name="item_judge", assertions=["Response is correct"])],
    )
    suite.add_item(
        data={"input": {"question": "What is 3 + 3?"}},
    )

    items = suite.get_items()
    assert len(items) == 2
    assert all("id" in item for item in items)

    items_with_evaluators = [i for i in items if len(i["evaluators"]) > 0]
    items_without_evaluators = [i for i in items if len(i["evaluators"]) == 0]

    assert len(items_with_evaluators) == 1
    assert len(items_without_evaluators) == 1

    item_evaluators = items_with_evaluators[0]["evaluators"]
    assert len(item_evaluators) == 1
    assert isinstance(item_evaluators[0], LLMJudge)


def test_evaluation_suite__update__changes_evaluators_and_policy(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that update() changes suite-level evaluators and execution policy.
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test update",
        evaluators=[LLMJudge(name="initial_judge", assertions=["Response is helpful"])],
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    # Verify initial state
    evaluators = suite.get_evaluators()
    assert len(evaluators) == 1
    assert evaluators[0].name == "initial_judge"

    policy = suite.get_execution_policy()
    assert policy["runs_per_item"] == 1

    # Update with new evaluators and policy
    suite.update(
        evaluators=[
            LLMJudge(name="updated_judge_1", assertions=["Response is accurate"]),
            LLMJudge(name="updated_judge_2", assertions=["Response is concise"]),
        ],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    # Retrieve from BE to verify persistence
    retrieved_suite = opik_client.get_evaluation_suite(name=dataset_name)

    updated_evaluators = retrieved_suite.get_evaluators()
    assert len(updated_evaluators) == 2
    evaluator_names = {e.name for e in updated_evaluators}
    assert "updated_judge_1" in evaluator_names
    assert "updated_judge_2" in evaluator_names

    updated_policy = retrieved_suite.get_execution_policy()
    assert updated_policy["runs_per_item"] == 3
    assert updated_policy["pass_threshold"] == 2


def test_get_or_create_evaluation_suite__existing__returns_existing(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_or_create_evaluation_suite returns an existing suite
    without creating a new one.
    """
    opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Original suite",
    )

    suite = opik_client.get_or_create_evaluation_suite(
        name=dataset_name,
        description="Should be ignored",
    )

    assert suite.name == dataset_name


def test_get_or_create_evaluation_suite__new__creates_suite(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_or_create_evaluation_suite creates a new suite when
    none exists with the given name.
    """
    suite = opik_client.get_or_create_evaluation_suite(
        name=dataset_name,
        description="New suite via get_or_create",
    )

    assert suite.name == dataset_name

    retrieved = opik_client.get_evaluation_suite(name=dataset_name)
    assert retrieved.name == dataset_name
