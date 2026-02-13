"""E2E tests for EvaluationSuite API."""

from typing import Dict, Any

import pytest

import opik
from opik.evaluation.suite_evaluators import LLMJudge
from .. import verifiers
from ...testlib import environment


def test_evaluation_suite__basic_run__happyflow(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test basic evaluation suite creation and run."""
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test evaluation suite",
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
        question = item["input"]["question"]
        if "France" in question:
            return {"input": item["input"], "output": "Paris"}
        if "Germany" in question:
            return {"input": item["input"], "output": "Berlin"}
        return {"input": item["input"], "output": "Unknown"}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # Verify EvaluationSuiteResult structure
    assert suite_result.passed is True, "Suite should pass when no metrics fail"
    assert suite_result.items_passed == 2
    assert suite_result.items_total == 2
    assert len(suite_result.item_results) == 2

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=2,
        feedback_scores_amount=0,
    )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__with_suite_level_llm_judge__scores_computed(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test evaluation suite with suite-level LLMJudge evaluator."""
    llm_judge = LLMJudge(
        name="answer_judge",
        model="openai/gpt-4o-mini",
        assertions=[
            "The response correctly answers the geography question",
        ],
    )

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test evaluation suite with LLMJudge",
        evaluators=[llm_judge],
    )

    suite.add_item(
        data={
            "input": {"question": "What is the capital of France?"},
        }
    )
    suite.add_item(
        data={
            "input": {"question": "What is the capital of Germany?"},
        }
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        question = item["input"]["question"]
        if "France" in question:
            return {"input": item["input"], "output": "The capital of France is Paris."}
        if "Germany" in question:
            return {"input": item["input"], "output": "I have no idea what the capital is."}
        return {"input": item["input"], "output": "Unknown"}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # France item should pass, Germany item should fail
    assert suite_result.items_total == 2

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=2,
        feedback_scores_amount=1,  # 1 assertion per LLMJudge
    )

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()

    assert len(experiment_items_contents) == 2, (
        f"Expected 2 experiment items, but got {len(experiment_items_contents)}"
    )

    # Each item should have feedback scores from the LLMJudge assertion
    for exp_item in experiment_items_contents:
        assert exp_item.feedback_scores is not None, "Expected feedback scores"
        assert len(exp_item.feedback_scores) == 1, (
            f"Expected 1 feedback score per item, got {len(exp_item.feedback_scores)}"
        )


def test_evaluation_suite__with_execution_policy_runs_per_item__multiple_runs(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test evaluation suite with runs_per_item > 1 in execution policy."""
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test evaluation suite with multiple runs",
        execution_policy={"runs_per_item": 2, "pass_threshold": 1},
    )

    suite.add_item(
        data={
            "input": {"question": "What is the capital of France?"},
            "reference": "Paris",
        }
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

    # With runs_per_item=2, the task should be called twice for the single item
    assert call_count["value"] == 2, (
        f"Expected task to be called 2 times, but was called {call_count['value']} times"
    )

    # Verify suite result
    assert suite_result.passed is True
    assert suite_result.items_passed == 1
    assert suite_result.items_total == 1

    # Verify item result shows 2 runs
    item_result = list(suite_result.item_results.values())[0]
    assert item_result.runs_total == 2
    assert item_result.runs_passed == 2
    assert item_result.pass_threshold == 1

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=2,  # 1 item × 2 runs
        feedback_scores_amount=0,
    )


def test_evaluation_suite__with_item_level_execution_policy__overrides_suite_policy(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test that item-level execution policy overrides suite-level policy."""
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test item-level execution policy override",
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    # Item 1: use suite-level policy (runs_per_item=1)
    suite.add_item(
        data={
            "input": {"question": "What is the capital of France?"},
            "reference": "Paris",
        }
    )

    # Item 2: override with item-level policy (runs_per_item=3)
    suite.add_item(
        data={
            "input": {"question": "What is the capital of Germany?"},
            "reference": "Berlin",
        },
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    call_counts = {"france": 0, "germany": 0}

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        question = item["input"]["question"]
        if "France" in question:
            call_counts["france"] += 1
            return {"input": item["input"], "output": "Paris"}
        if "Germany" in question:
            call_counts["germany"] += 1
            return {"input": item["input"], "output": "Berlin"}
        return {"input": item["input"], "output": "Unknown"}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # France item should be called 1 time (suite-level policy)
    assert call_counts["france"] == 1, (
        f"Expected France task to be called 1 time, but was called {call_counts['france']} times"
    )

    # Germany item should be called 3 times (item-level policy override)
    assert call_counts["germany"] == 3, (
        f"Expected Germany task to be called 3 times, but was called {call_counts['germany']} times"
    )

    # Verify suite result
    assert suite_result.passed is True
    assert suite_result.items_passed == 2
    assert suite_result.items_total == 2

    # Verify item-level pass_threshold is used
    for item_result in suite_result.item_results.values():
        if item_result.runs_total == 3:
            # Germany item with item-level policy
            assert item_result.pass_threshold == 2
        else:
            # France item with suite-level policy
            assert item_result.pass_threshold == 1

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=4,  # 1 + 3 = 4 total runs
        feedback_scores_amount=0,
    )


def test_evaluation_suite__default_execution_policy__single_run_per_item(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test that default execution policy runs each item once."""
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test default execution policy",
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

    # Default policy is runs_per_item=1, so 2 items = 2 calls
    assert call_count["value"] == 2, (
        f"Expected task to be called 2 times, but was called {call_count['value']} times"
    )

    # Verify suite result
    assert suite_result.passed is True
    assert suite_result.items_passed == 2
    assert suite_result.items_total == 2

    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=2,
        feedback_scores_amount=0,
    )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__with_llm_judge_evaluator__assertions_evaluated(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test evaluation suite with LLMJudge evaluator using string assertions."""
    # Use string assertions - names will be auto-generated
    llm_judge = LLMJudge(
        name="quality_judge",
        model="openai/gpt-4o-mini",
        assertions=[
            "The response directly answers the question asked",
            "The response contains factually correct information",
        ],
    )

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test evaluation suite with LLMJudge",
        evaluators=[llm_judge],
    )

    suite.add_item(
        data={
            "input": {"question": "What is the capital of France?"},
            "expected_answer": "Paris",
        }
    )
    suite.add_item(
        data={
            "input": {"question": "What is 2 + 2?"},
            "expected_answer": "4",
        }
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        question = item["input"]["question"]
        if "France" in question:
            return {"input": item["input"], "output": "The capital of France is Paris."}
        if "2 + 2" in question:
            return {"input": item["input"], "output": "2 + 2 equals 4."}
        return {"input": item["input"], "output": "I don't know."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # Each item should have 2 assertion scores from the LLMJudge
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=suite_result.experiment_id,
        experiment_name=suite_result.experiment_name,
        experiment_metadata=None,
        traces_amount=2,
        feedback_scores_amount=2,  # 2 assertions per LLMJudge
    )

    retrieved_experiment = opik_client.get_experiment_by_name(experiment_name)
    experiment_items_contents = retrieved_experiment.get_items()

    assert len(experiment_items_contents) == 2, (
        f"Expected 2 experiment items, but got {len(experiment_items_contents)}"
    )

    # Each item should have feedback scores from the LLMJudge assertions
    for item in experiment_items_contents:
        assert item.feedback_scores is not None, "Expected feedback scores"
        assert len(item.feedback_scores) == 2, (
            f"Expected 2 feedback scores per item, got {len(item.feedback_scores)}"
        )

        # Verify auto-generated score names
        score_names = {score["name"] for score in item.feedback_scores}
        assert "quality_judge_the_response_directly_answers" in score_names, (
            f"Expected auto-generated score name, got {score_names}"
        )
        assert "quality_judge_the_response_contains_factually" in score_names, (
            f"Expected auto-generated score name, got {score_names}"
        )


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__pass_threshold_not_met__item_fails(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """Test that items fail when pass_threshold is not met."""
    llm_judge = LLMJudge(
        name="math_judge",
        model="openai/gpt-4o-mini",
        assertions=[
            "The response correctly states that 2 + 2 equals 4",
        ],
    )

    # Require 2 out of 3 runs to pass
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test pass threshold failure",
        evaluators=[llm_judge],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.add_item(
        data={
            "input": {"question": "What is 2 + 2?"},
        }
    )

    call_count = {"value": 0}

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        call_count["value"] += 1
        # Only the first run returns correct answer, rest fail
        if call_count["value"] == 1:
            return {"input": item["input"], "output": "2 + 2 equals 4."}
        return {"input": item["input"], "output": "I don't know the answer."}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )

    opik.flush_tracker()

    # Suite should fail because item doesn't meet pass_threshold
    assert suite_result.passed is False, "Suite should fail when item doesn't meet threshold"
    assert suite_result.items_passed == 0
    assert suite_result.items_total == 1

    # Verify item result details
    item_result = list(suite_result.item_results.values())[0]
    assert item_result.passed is False
    assert item_result.runs_passed == 1, "Only first run should pass"
    assert item_result.runs_total == 3
    assert item_result.pass_threshold == 2
