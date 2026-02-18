"""E2E tests for EvaluationSuite API.

These tests verify the core evaluation suite functionality:
1. Item-level evaluators stored in dataset items (main flow)
2. Execution policy handling (runs_per_item, pass_threshold)
3. Pass/fail determination based on LLMJudge assertion results

Key concepts:
- Evaluation suites only support LLMJudge evaluators
- Evaluators and execution_policy are stored in dataset item content under
  __evaluation_config__ key (will become backend fields via OPIK-4222/4223)
- Items without evaluators pass by default (no assertions to fail)
- Pass/fail is determined by: runs_passed >= pass_threshold
"""

from typing import Dict, Any

import pytest

import opik
from opik.evaluation.suite_evaluators import LLMJudge
from .. import verifiers
from ...testlib import environment


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
    Main flow: Items have their own LLMJudge evaluators stored in dataset item content.

    This is the primary use case for evaluation suites - each item can have
    different assertions to verify. The evaluators are stored under
    __evaluation_config__.evaluators in the dataset item content.

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

    # Item 1: Geography question with specific assertion
    geography_judge = LLMJudge(
        name="geography_judge",
        assertions=[geography_assertion],
    )
    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
        evaluators=[geography_judge],
    )

    # Item 2: Math question with specific assertion
    math_judge = LLMJudge(
        name="math_judge",
        assertions=[math_assertion],
    )
    suite.add_item(
        data={"input": {"question": "What is 2 + 2?"}},
        evaluators=[math_judge],
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

    # Both items should pass since responses are correct
    assert suite_result.items_total == 2
    assert suite_result.items_passed == 2
    assert suite_result.all_items_passed is True

    # Verify feedback scores were created
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items = retrieved_experiment.get_items()

    assert len(experiment_items) == 2

    # Collect all score names across items
    all_score_names = set()
    for exp_item in experiment_items:
        assert exp_item.feedback_scores is not None, "Expected feedback scores"
        assert len(exp_item.feedback_scores) == 1, (
            f"Expected 1 feedback score per item, got {len(exp_item.feedback_scores)}"
        )

        score = exp_item.feedback_scores[0]
        all_score_names.add(score["name"])

        # Verify score value is boolean
        assert score["value"] in [0.0, 1.0, True, False], (
            f"Score value should be boolean, got {score['value']}"
        )

    # Verify both assertion names are present (one per item)
    assert geography_assertion in all_score_names, (
        f"Expected geography assertion '{geography_assertion}' not found in {all_score_names}"
    )
    assert math_assertion in all_score_names, (
        f"Expected math assertion '{math_assertion}' not found in {all_score_names}"
    )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__multiple_assertions_per_item__all_scores_created(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that multiple assertions in a single LLMJudge create multiple feedback scores.

    Expected behavior:
    - Each assertion in the LLMJudge creates a separate feedback score
    - Score names are the assertion text (used as identifier)
    - All scores are evaluated independently
    """
    assertion_1 = "The response is factually correct"
    assertion_2 = "The response is concise and clear"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test multiple assertions per item",
    )

    multi_assertion_judge = LLMJudge(
        name="quality_judge",
        assertions=[assertion_1, assertion_2],
    )
    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
        evaluators=[multi_assertion_judge],
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        return {"input": item["input"], "output": "Paris is the capital of France."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # Verify suite result
    assert suite_result.items_total == 1
    assert suite_result.items_passed == 1
    assert suite_result.all_items_passed is True

    # Verify 2 feedback scores were created (one per assertion)
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items = retrieved_experiment.get_items()

    assert len(experiment_items) == 1
    exp_item = experiment_items[0]

    assert exp_item.feedback_scores is not None, "Expected feedback scores"
    assert len(exp_item.feedback_scores) == 2, (
        f"Expected 2 feedback scores (one per assertion), got {len(exp_item.feedback_scores)}"
    )

    # Verify score names match the assertion texts
    score_names = {score["name"] for score in exp_item.feedback_scores}
    assert assertion_1 in score_names, (
        f"Expected score name '{assertion_1}' not found in {score_names}"
    )
    assert assertion_2 in score_names, (
        f"Expected score name '{assertion_2}' not found in {score_names}"
    )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__suite_level_evaluators__applied_to_all_items(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that suite-level evaluators are applied to all items.

    Expected behavior:
    - Suite-level evaluators are applied to every item in the suite
    - Each item gets feedback scores from suite-level evaluators
    - This is useful for common assertions that apply to all test cases
    """
    suite_assertion = "The response is helpful and informative"

    suite_judge = LLMJudge(
        name="suite_judge",
        assertions=[suite_assertion],
    )

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test suite-level evaluators",
        evaluators=[suite_judge],
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

    assert suite_result.items_total == 2

    # Verify each item has feedback scores from suite-level evaluator
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items = retrieved_experiment.get_items()

    assert len(experiment_items) == 2

    for exp_item in experiment_items:
        assert exp_item.feedback_scores is not None, "Expected feedback scores"
        assert len(exp_item.feedback_scores) == 1, (
            "Expected 1 feedback score per item from suite-level evaluator"
        )

        # Verify score name matches the suite-level assertion
        score = exp_item.feedback_scores[0]
        assert score["name"] == suite_assertion, (
            f"Expected score name '{suite_assertion}', got '{score['name']}'"
        )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__combined_suite_and_item_level_evaluators__all_scores_created(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that suite-level and item-level evaluators are combined for an item.

    Expected behavior:
    - Item gets feedback scores from both suite-level and item-level evaluators
    - Total feedback scores = suite-level assertions + item-level assertions
    - Both evaluators are applied independently
    """
    suite_assertion = "The response is helpful and informative"
    item_assertion = "The response correctly identifies Paris as the capital"

    # Suite-level evaluator with 1 assertion (applied to all items)
    suite_judge = LLMJudge(
        name="suite_judge",
        assertions=[suite_assertion],
    )

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test combined suite and item level evaluators",
        evaluators=[suite_judge],
    )

    # Item-level evaluator with 1 assertion (specific to this item)
    item_judge = LLMJudge(
        name="item_judge",
        assertions=[item_assertion],
    )
    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
        evaluators=[item_judge],
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        return {"input": item["input"], "output": "The capital of France is Paris."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    assert suite_result.items_total == 1

    # Verify item has feedback scores from BOTH evaluators (1 suite + 1 item = 2 total)
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items = retrieved_experiment.get_items()

    assert len(experiment_items) == 1
    exp_item = experiment_items[0]

    assert exp_item.feedback_scores is not None, "Expected feedback scores"
    assert len(exp_item.feedback_scores) == 2, (
        f"Expected 2 feedback scores (1 suite-level + 1 item-level), "
        f"got {len(exp_item.feedback_scores)}"
    )

    # Verify score names match both assertions
    score_names = {score["name"] for score in exp_item.feedback_scores}
    assert suite_assertion in score_names, (
        f"Expected suite-level score name '{suite_assertion}' not found in {score_names}"
    )
    assert item_assertion in score_names, (
        f"Expected item-level score name '{item_assertion}' not found in {score_names}"
    )


# =============================================================================
# EDGE CASE: Items without evaluators
# =============================================================================


def test_evaluation_suite__no_evaluators__items_pass_by_default(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Edge case: Items without any evaluators pass by default.

    Expected behavior:
    - When an item has no evaluators (no assertions to check), it passes
    - This is because there are no assertions that could fail
    - The suite passes if all items pass (even with no evaluators)
    - No feedback scores are created for items without evaluators
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test items without evaluators",
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

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        return {"input": item["input"], "output": "Some response"}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # Items without evaluators pass by default (no assertions to fail)
    assert suite_result.all_items_passed is True, (
        "Suite should pass when items have no evaluators (nothing to fail)"
    )
    assert suite_result.items_passed == 2
    assert suite_result.items_total == 2

    # Verify no feedback scores were created
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=2,
        feedback_scores_amount=0,  # No evaluators = no feedback scores
    )


# =============================================================================
# EXECUTION POLICY: runs_per_item and pass_threshold
# =============================================================================


def test_evaluation_suite__execution_policy_runs_per_item__task_called_multiple_times(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that runs_per_item in execution policy causes multiple task executions.

    Expected behavior:
    - With runs_per_item=N, the task is called N times for each item
    - Each run creates a separate trace
    - pass_threshold determines how many runs must pass for item to pass
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test runs_per_item execution policy",
        execution_policy={"runs_per_item": 2, "pass_threshold": 1},
    )

    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
    )

    call_count = {"value": 0}

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        call_count["value"] += 1
        return {"input": item["input"], "output": "Paris"}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # Task should be called twice (runs_per_item=2)
    assert call_count["value"] == 2, (
        f"Expected task to be called 2 times, but was called {call_count['value']} times"
    )

    # Verify suite result structure
    assert suite_result.all_items_passed is True
    assert suite_result.items_total == 1

    item_result = list(suite_result.item_results.values())[0]
    assert item_result.runs_total == 2
    assert item_result.pass_threshold == 1

    # Verify 2 traces were created (one per run)
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=2,
        feedback_scores_amount=0,
    )


def test_evaluation_suite__item_level_execution_policy__overrides_suite_policy(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that item-level execution policy overrides suite-level policy.

    Expected behavior:
    - Suite-level execution_policy is the default for all items
    - Item-level execution_policy overrides suite-level for that specific item
    - Each item uses its own effective execution policy
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

    call_counts = {"france": 0, "germany": 0}

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        question = item["input"]["question"]
        if "France" in question:
            call_counts["france"] += 1
        elif "Germany" in question:
            call_counts["germany"] += 1
        return {"input": item["input"], "output": "Answer"}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # France: 1 call (suite-level policy)
    assert call_counts["france"] == 1, (
        f"Expected France task called 1 time, got {call_counts['france']}"
    )

    # Germany: 3 calls (item-level policy override)
    assert call_counts["germany"] == 3, (
        f"Expected Germany task called 3 times, got {call_counts['germany']}"
    )

    # Verify suite result
    assert suite_result.all_items_passed is True
    assert suite_result.items_total == 2
    assert suite_result.items_passed == 2

    # Verify item-level pass_threshold is used
    for item_result in suite_result.item_results.values():
        if item_result.runs_total == 3:
            assert item_result.pass_threshold == 2, (
                "Germany should use item-level threshold"
            )
        else:
            assert item_result.pass_threshold == 1, (
                "France should use suite-level threshold"
            )

    # Total traces: 1 + 3 = 4
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=4,
        feedback_scores_amount=0,
    )


def test_evaluation_suite__default_execution_policy__single_run_per_item(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that default execution policy runs each item once with pass_threshold=1.

    Expected behavior:
    - Default execution_policy is {"runs_per_item": 1, "pass_threshold": 1}
    - Each item is evaluated exactly once
    - Item passes if that single run passes
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test default execution policy",
    )

    suite.add_item(data={"input": {"question": "Question 1"}})
    suite.add_item(data={"input": {"question": "Question 2"}})

    call_count = {"value": 0}

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        call_count["value"] += 1
        return {"input": item["input"], "output": "Answer"}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # Default: runs_per_item=1, so 2 items = 2 calls
    assert call_count["value"] == 2, (
        f"Expected 2 task calls (one per item), got {call_count['value']}"
    )

    # Verify suite result
    assert suite_result.all_items_passed is True
    assert suite_result.items_total == 2
    assert suite_result.items_passed == 2

    # Verify default pass_threshold=1
    for item_result in suite_result.item_results.values():
        assert item_result.runs_total == 1
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

    Expected behavior:
    - If any assertion in an item's evaluators fails, the item fails
    - Failed items contribute to suite failure
    - Feedback scores show which assertions passed/failed
    """
    failing_assertion = "The response correctly states that 2 + 2 equals 5"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test assertion failure",
    )

    # This assertion should fail because the response is wrong
    wrong_answer_judge = LLMJudge(
        name="wrong_judge",
        assertions=[failing_assertion],
    )
    suite.add_item(
        data={"input": {"question": "What is 2 + 2?"}},
        evaluators=[wrong_answer_judge],
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        # Correct answer, but assertion expects wrong answer
        return {"input": item["input"], "output": "2 + 2 equals 4."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # Item should fail because assertion expects wrong answer
    assert suite_result.items_total == 1
    assert suite_result.items_passed == 0, "Item should fail when assertion fails"
    assert suite_result.all_items_passed is False, (
        "Suite should fail when any item fails"
    )

    # Verify feedback score was created with failing value
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items = retrieved_experiment.get_items()

    assert len(experiment_items) == 1
    exp_item = experiment_items[0]

    assert exp_item.feedback_scores is not None
    assert len(exp_item.feedback_scores) == 1

    # Verify score name and that it indicates failure
    score = exp_item.feedback_scores[0]
    assert score["name"] == failing_assertion, (
        f"Expected score name '{failing_assertion}', got '{score['name']}'"
    )
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

    Expected behavior:
    - With runs_per_item=3 and pass_threshold=2, item needs 2+ passing runs
    - If only 1 run passes, item fails (1 < 2)
    - Suite fails if any item fails
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

    call_count = {"value": 0}

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        call_count["value"] += 1
        # Only first run returns correct answer
        if call_count["value"] == 1:
            return {"input": item["input"], "output": "2 + 2 equals 4."}
        return {"input": item["input"], "output": "I don't know."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # Item should fail: only 1 run passes, but threshold is 2
    assert suite_result.all_items_passed is False, (
        "Suite should fail when threshold not met"
    )
    assert suite_result.items_passed == 0
    assert suite_result.items_total == 1

    item_result = list(suite_result.item_results.values())[0]
    assert item_result.passed is False
    assert item_result.runs_total == 3
    assert item_result.pass_threshold == 2
    # Note: runs_passed depends on LLM evaluation, but should be < 2


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__multiple_assertions_multiple_runs__pass_threshold_logic(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Comprehensive test for pass/fail logic with multiple assertions and runs.

    Scenario:
    - 1 item with 3 assertions
    - execution_policy: runs_per_item=3, pass_threshold=2
    - Task returns consistent correct answers

    Pass/fail logic:
    1. A RUN passes if ALL assertions in that run pass
    2. An ITEM passes if runs_passed >= pass_threshold
    3. The SUITE passes if all items pass

    Expected behavior:
    - Each of the 3 runs should have all 3 assertions evaluated
    - With correct answers, all assertions should pass in each run
    - All 3 runs pass -> runs_passed=3 >= pass_threshold=2 -> item passes
    - suite.pass_rate = items_passed / items_total = 1/1 = 1.0
    """
    assertion_1 = "The response mentions Paris"
    assertion_2 = "The response mentions France"
    assertion_3 = "The response is factually correct"

    judge = LLMJudge(
        name="geography_judge",
        assertions=[assertion_1, assertion_2, assertion_3],
    )

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test multiple assertions with multiple runs",
        evaluators=[judge],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(data={"input": {"question": "What is the capital of France?"}})

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "input": item["input"],
            "output": "The capital of France is Paris.",
        }

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # Verify suite-level results
    assert suite_result.items_total == 1
    assert suite_result.items_passed == 1, "Item should pass when threshold is met"
    assert suite_result.all_items_passed is True, (
        "Suite should pass when all items pass"
    )
    assert suite_result.pass_rate == 1.0, "Pass rate should be 1.0 when all items pass"

    # Verify item-level results
    item_result = list(suite_result.item_results.values())[0]
    assert item_result.runs_total == 3, "Should have 3 runs per item"
    assert item_result.pass_threshold == 2
    assert item_result.runs_passed >= 2, (
        f"At least 2 runs should pass, got {item_result.runs_passed}"
    )
    assert item_result.passed is True

    # Verify feedback scores: 3 assertions * 3 runs = 9 feedback scores
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items = retrieved_experiment.get_items()

    # Should have 3 experiment items (one per run)
    assert len(experiment_items) == 3, (
        f"Expected 3 experiment items (one per run), got {len(experiment_items)}"
    )

    # Each experiment item should have 3 feedback scores (one per assertion)
    for exp_item in experiment_items:
        assert exp_item.feedback_scores is not None
        assert len(exp_item.feedback_scores) == 3, (
            f"Expected 3 feedback scores per run, got {len(exp_item.feedback_scores)}"
        )

        score_names = {s["name"] for s in exp_item.feedback_scores}
        assert assertion_1 in score_names, f"Missing score for '{assertion_1}'"
        assert assertion_2 in score_names, f"Missing score for '{assertion_2}'"
        assert assertion_3 in score_names, f"Missing score for '{assertion_3}'"


# =============================================================================
# PERSISTENCE: Create, get, and run suite from BE
# =============================================================================


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__create_get_and_run__end_to_end(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    End-to-end test: create a suite with evaluators/execution_policy,
    retrieve it via get_evaluation_suite(), then run it.

    This verifies that suite-level config is persisted to the backend
    and correctly reconstructed when loading the suite.
    """
    suite_assertion = "The response correctly identifies Paris as the capital of France"

    suite_judge = LLMJudge(
        name="geography_judge",
        assertions=[suite_assertion],
    )

    # 1. Create suite with evaluators + execution_policy
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Persistence test suite",
        evaluators=[suite_judge],
        execution_policy={"runs_per_item": 2, "pass_threshold": 1},
    )

    # 2. Add items
    suite.add_item(data={"input": {"question": "What is the capital of France?"}})
    suite.add_item(data={"input": {"question": "What is the capital of Germany?"}})

    # 3. Retrieve the suite from backend (simulates a fresh client loading existing suite)
    retrieved_suite = opik_client.get_evaluation_suite(name=dataset_name)

    # 3a. Verify get_evaluators() returns suite-level evaluators from BE
    evaluators = retrieved_suite.get_evaluators()
    assert len(evaluators) == 1, (
        f"Expected 1 suite-level evaluator, got {len(evaluators)}"
    )
    assert isinstance(evaluators[0], LLMJudge)

    # 3b. Verify get_execution_policy() returns persisted policy from BE
    policy = retrieved_suite.get_execution_policy()
    assert policy["runs_per_item"] == 2, (
        f"Expected runs_per_item=2, got {policy['runs_per_item']}"
    )
    assert policy["pass_threshold"] == 1, (
        f"Expected pass_threshold=1, got {policy['pass_threshold']}"
    )

    # 3c. Verify get_items() returns items with data
    items = retrieved_suite.get_items()
    assert len(items) == 2, f"Expected 2 items, got {len(items)}"
    for item in items:
        assert "data" in item
        assert "evaluators" in item
        assert "execution_policy" in item

    # 4. Run the retrieved suite — evaluators/execution_policy come from BE
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

    # Verify suite ran with correct execution policy (runs_per_item=2)
    assert suite_result.items_total == 2

    for item_result in suite_result.item_results.values():
        assert item_result.runs_total == 2, (
            f"Expected 2 runs per item (from BE execution_policy), got {item_result.runs_total}"
        )
        assert item_result.pass_threshold == 1

    # Verify feedback scores were created (evaluators loaded from BE)
    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items = retrieved_experiment.get_items()

    # 2 items * 2 runs = 4 experiment items
    assert len(experiment_items) == 4, (
        f"Expected 4 experiment items (2 items * 2 runs), got {len(experiment_items)}"
    )

    # Each experiment item should have 1 feedback score from suite-level evaluator
    for exp_item in experiment_items:
        assert exp_item.feedback_scores is not None, (
            "Expected feedback scores from BE evaluators"
        )
        assert len(exp_item.feedback_scores) == 1
        assert exp_item.feedback_scores[0]["name"] == suite_assertion


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

    # Delete the first item by getting its ID from the dataset
    dataset_items = suite.dataset.get_items()
    item_id_to_delete = dataset_items[0]["id"]

    suite.delete_items([item_id_to_delete])

    # Verify only 2 items remain
    remaining_items = suite.get_items()
    assert len(remaining_items) == 2


def test_evaluation_suite__get_evaluators__returns_llm_judge_instances(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_evaluators() returns LLMJudge instances from suite-level config.
    """
    judge_1 = LLMJudge(
        name="judge_1",
        assertions=["Response is helpful"],
    )
    judge_2 = LLMJudge(
        name="judge_2",
        assertions=["Response is accurate", "Response is concise"],
    )

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test get_evaluators",
        evaluators=[judge_1, judge_2],
    )

    # Retrieve from BE to verify persistence
    retrieved_suite = opik_client.get_evaluation_suite(name=dataset_name)

    evaluators = retrieved_suite.get_evaluators()
    assert len(evaluators) == 2
    assert all(isinstance(e, LLMJudge) for e in evaluators)

    # Verify evaluator names
    evaluator_names = {e.name for e in evaluators}
    assert "judge_1" in evaluator_names
    assert "judge_2" in evaluator_names


def test_evaluation_suite__get_execution_policy__returns_persisted_policy(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_execution_policy() returns the persisted execution policy.
    """
    suite = opik_client.create_evaluation_suite(
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

    item_judge = LLMJudge(
        name="item_judge",
        assertions=["Response is correct"],
    )
    suite.add_item(
        data={"input": {"question": "What is 2 + 2?"}},
        evaluators=[item_judge],
    )
    suite.add_item(
        data={"input": {"question": "What is 3 + 3?"}},
    )

    items = suite.get_items()
    assert len(items) == 2

    # Find the item with evaluators
    items_with_evaluators = [i for i in items if len(i["evaluators"]) > 0]
    items_without_evaluators = [i for i in items if len(i["evaluators"]) == 0]

    assert len(items_with_evaluators) == 1
    assert len(items_without_evaluators) == 1

    # Verify evaluator is an LLMJudge instance
    item_evaluators = items_with_evaluators[0]["evaluators"]
    assert len(item_evaluators) == 1
    assert isinstance(item_evaluators[0], LLMJudge)


def test_get_or_create_evaluation_suite__existing__returns_existing(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_or_create_evaluation_suite returns an existing suite
    without creating a new one.
    """
    # 1. Create suite
    opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Original suite",
    )

    # 2. get_or_create should return the existing one
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

    # Verify it was actually created by retrieving it
    retrieved = opik_client.get_evaluation_suite(name=dataset_name)
    assert retrieved.name == dataset_name
