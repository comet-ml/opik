"""E2E tests for EvaluationSuite API.

These tests verify the core evaluation suite functionality:
1. Item-level assertions stored as dataset item fields
2. Suite-level assertions applied to all items
3. Execution policy handling (runs_per_item, pass_threshold)
4. Pass/fail determination based on assertion results
5. Persistence: create, get, update, delete operations

Key concepts:
- Assertions are checked by an LLM (internally using LLMJudge)
- Suite-level assertions and execution_policy are stored at dataset version level
- Item-level assertions and execution_policy are stored as dataset item fields
- Items without assertions pass by default (no assertions to fail)
- Pass/fail is determined by: runs_passed >= pass_threshold
"""

from typing import Dict, Any

import pytest

import opik
from .. import verifiers
from ...testlib import environment, ThreadSafeCounter


# =============================================================================
# MAIN FLOW: Item-level assertions
# =============================================================================


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__item_level_assertions__feedback_scores_created(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Main flow: Items have their own assertions.

    Each item can have different assertions to verify.

    Expected behavior:
    - Each item is evaluated using its own assertions
    - Feedback scores are created with assertion text as the score name
    - Score values are boolean (True=1.0, False=0.0)
    """
    geography_assertion = (
        "The response correctly identifies Paris as the capital of France"
    )
    math_assertion = "The response correctly states that 2 + 2 equals 4"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test item-level assertions",
    )

    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
        assertions=[geography_assertion],
    )
    suite.add_item(
        data={"input": {"question": "What is 2 + 2?"}},
        assertions=[math_assertion],
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
    Test that multiple assertions on a single item create multiple
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
        assertions=[assertion_1, assertion_2],
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
def test_evaluation_suite__suite_level_assertions__applied_to_all_items(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that suite-level assertions are applied to every item.
    """
    suite_assertion = "The response is helpful and informative"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test suite-level assertions",
        assertions=[suite_assertion],
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
def test_evaluation_suite__combined_suite_and_item_level_assertions__all_scores_created(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that suite-level and item-level assertions are combined:
    total feedback scores = suite-level assertions + item-level assertions.
    """
    suite_assertion = "The response is helpful and informative"
    item_assertion = "The response correctly identifies Paris as the capital"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test combined suite and item level assertions",
        assertions=[suite_assertion],
    )

    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
        assertions=[item_assertion],
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
# EDGE CASE: Items without assertions + default execution policy
# =============================================================================


def test_evaluation_suite__no_assertions_default_policy__items_pass_with_single_run(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Edge case: Items without assertions pass by default, and the default
    execution policy runs each item exactly once with pass_threshold=1.

    Expected behavior:
    - No assertions to check -> items pass
    - Default runs_per_item=1, pass_threshold=1
    - No feedback scores created
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test items without assertions and default policy",
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
    Test that items fail when assertions fail.
    """
    failing_assertion = "The response correctly states that 2 + 2 equals 5"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test assertion failure",
    )

    suite.add_item(
        data={"input": {"question": "What is 2 + 2?"}},
        assertions=[failing_assertion],
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
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test pass threshold failure",
        assertions=["The response correctly states that 2 + 2 equals 4"],
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
        assertions=[assertion_1, assertion_2, assertion_3],
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
    item_assertion = "Response is correct"

    # 1. Create suite with assertions + execution_policy
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Persistence test suite",
        assertions=[suite_assertion],
        execution_policy={"runs_per_item": 2, "pass_threshold": 1},
    )

    suite.add_item(
        data={"input": {"question": "What is the capital of France?"}},
        assertions=[item_assertion],
        description="Geography: France capital",
    )
    suite.add_item(
        data={"input": {"question": "What is the capital of Germany?"}},
        description="Geography: Germany capital",
    )

    # 2. Retrieve from backend (simulates a fresh client loading existing suite)
    retrieved_suite = opik_client.get_evaluation_suite(name=dataset_name)

    # Verify item descriptions survived the round-trip
    retrieved_items = retrieved_suite.get_items()
    retrieved_descriptions = {i["description"] for i in retrieved_items}
    assert "Geography: France capital" in retrieved_descriptions
    assert "Geography: Germany capital" in retrieved_descriptions

    # Verify item-level assertions survived the round-trip
    items_with_assertions = [i for i in retrieved_items if len(i["assertions"]) > 0]
    assert len(items_with_assertions) == 1
    assert items_with_assertions[0]["assertions"] == [item_assertion]

    # 3. Run the retrieved suite — assertions/execution_policy come from BE
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
        total_feedback_scores=6,  # France: 2 runs * 2 assertions + Germany: 2 runs * 1 assertion
        expected_score_names={suite_assertion, item_assertion},
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

    suite.add_item(
        data={"input": {"question": "Question 1"}},
        description="First question",
    )
    suite.add_item(
        data={"input": {"question": "Question 2"}},
        description="Second question",
    )
    suite.add_item(data={"input": {"question": "Question 3"}})

    items = suite.get_items()
    assert len(items) == 3

    # Verify descriptions are present before deletion
    descriptions = {i["description"] for i in items}
    assert "First question" in descriptions
    assert "Second question" in descriptions
    assert None in descriptions

    item_id_to_delete = items[0]["id"]

    suite.delete_items([item_id_to_delete])

    remaining_items = suite.get_items()
    assert len(remaining_items) == 2


def test_evaluation_suite__get_assertions__returns_assertion_strings(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_assertions() returns assertion strings from suite-level config.
    """
    opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test get_assertions",
        assertions=[
            "Response is helpful",
            "Response is accurate",
            "Response is concise",
        ],
    )

    # Retrieve from BE to verify persistence
    retrieved_suite = opik_client.get_evaluation_suite(name=dataset_name)

    assertions = retrieved_suite.get_assertions()
    assert set(assertions) == {
        "Response is helpful",
        "Response is accurate",
        "Response is concise",
    }


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


def test_evaluation_suite__update__changes_assertions_and_policy(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that update() changes suite-level assertions and execution policy.
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test update",
        assertions=["Response is helpful"],
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    # Verify initial state
    assertions = suite.get_assertions()
    assert set(assertions) == {"Response is helpful"}

    policy = suite.get_execution_policy()
    assert policy["runs_per_item"] == 1

    # Update with new assertions and policy
    suite.update(
        assertions=["Response is accurate", "Response is concise"],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    # Retrieve from BE to verify persistence
    retrieved_suite = opik_client.get_evaluation_suite(name=dataset_name)

    updated_assertions = retrieved_suite.get_assertions()
    assert set(updated_assertions) == {
        "Response is accurate",
        "Response is concise",
    }

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


def test_get_or_create_evaluation_suite__with_new_assertions__creates_new_version(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_or_create_evaluation_suite with new assertions on an
    existing suite creates a new version with those assertions.
    """
    opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Original suite",
        assertions=["Response is helpful"],
    )

    opik_client.get_or_create_evaluation_suite(
        name=dataset_name,
        assertions=["Response is accurate", "Response is concise"],
    )

    retrieved = opik_client.get_evaluation_suite(name=dataset_name)
    assertions = retrieved.get_assertions()
    assert set(assertions) == {
        "Response is accurate",
        "Response is concise",
    }


def test_get_or_create_evaluation_suite__with_new_policy__creates_new_version(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_or_create_evaluation_suite with a new execution_policy
    on an existing suite creates a new version, keeping existing assertions.
    """
    opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Original suite",
        assertions=["Response is helpful"],
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    opik_client.get_or_create_evaluation_suite(
        name=dataset_name,
        execution_policy={"runs_per_item": 5, "pass_threshold": 3},
    )

    retrieved = opik_client.get_evaluation_suite(name=dataset_name)

    policy = retrieved.get_execution_policy()
    assert policy["runs_per_item"] == 5
    assert policy["pass_threshold"] == 3

    assertions = retrieved.get_assertions()
    assert set(assertions) == {"Response is helpful"}


def test_evaluation_suite__update_assertions_only__keeps_existing_policy(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that update() with only assertions keeps the existing execution policy.
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test partial update",
        assertions=["Response is helpful"],
        execution_policy={"runs_per_item": 3, "pass_threshold": 2},
    )

    suite.update(assertions=["Response is accurate"])

    retrieved = opik_client.get_evaluation_suite(name=dataset_name)

    assertions = retrieved.get_assertions()
    assert set(assertions) == {"Response is accurate"}

    policy = retrieved.get_execution_policy()
    assert policy["runs_per_item"] == 3
    assert policy["pass_threshold"] == 2


def test_evaluation_suite__update_policy_only__keeps_existing_assertions(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that update() with only execution_policy keeps existing assertions.
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test partial update",
        assertions=["Response is helpful", "Response is accurate"],
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    suite.update(execution_policy={"runs_per_item": 5, "pass_threshold": 3})

    retrieved = opik_client.get_evaluation_suite(name=dataset_name)

    policy = retrieved.get_execution_policy()
    assert policy["runs_per_item"] == 5
    assert policy["pass_threshold"] == 3

    assertions = retrieved.get_assertions()
    assert set(assertions) == {
        "Response is helpful",
        "Response is accurate",
    }


def test_evaluation_suite__update_with_empty_assertions__clears_assertions(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that update(assertions=[]) clears all suite-level assertions.
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test clearing assertions",
        assertions=["Response is helpful", "Response is accurate"],
        execution_policy={"runs_per_item": 1, "pass_threshold": 1},
    )

    assert len(suite.get_assertions()) == 2

    suite.update(assertions=[])

    retrieved = opik_client.get_evaluation_suite(name=dataset_name)
    assert retrieved.get_assertions() == []

    policy = retrieved.get_execution_policy()
    assert policy["runs_per_item"] == 1
    assert policy["pass_threshold"] == 1


# =============================================================================
# TAGS
# =============================================================================


def test_evaluation_suite__create_with_tags__tags_persisted(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that tags passed to create_evaluation_suite are persisted
    and can be retrieved via get_evaluation_suite().
    """
    opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Suite with tags",
        tags=["regression", "v2"],
    )

    suite = opik_client.get_evaluation_suite(dataset_name)
    assert sorted(suite.get_tags()) == ["regression", "v2"]


def test_evaluation_suite__update_tags__tags_updated(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that tags can be updated on an existing evaluation suite
    and verified via get_evaluation_suite().
    """
    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Suite for tag update test",
        tags=["initial"],
    )

    suite.update(tags=["updated", "new-tag"])

    suite = opik_client.get_evaluation_suite(dataset_name)
    assert sorted(suite.get_tags()) == ["new-tag", "updated"]


def test_get_or_create_evaluation_suite__with_tags__tags_persisted(
    opik_client: opik.Opik, dataset_name: str
):
    """
    Test that get_or_create passes tags on creation and updates.
    """
    opik_client.get_or_create_evaluation_suite(
        name=dataset_name,
        tags=["v1"],
    )

    suite = opik_client.get_evaluation_suite(dataset_name)
    assert suite.get_tags() == ["v1"]

    opik_client.get_or_create_evaluation_suite(
        name=dataset_name,
        tags=["v2", "production"],
    )

    suite = opik_client.get_evaluation_suite(dataset_name)
    assert sorted(suite.get_tags()) == ["production", "v2"]


@pytest.mark.skipif(
    not environment.has_openai_api_key(), reason="OPENAI_API_KEY is not set"
)
def test_evaluation_suite__add_items_batch__all_items_persisted(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that add_items() adds multiple items in a single batch.
    """
    assertion = "The response is factually correct"

    suite = opik_client.create_evaluation_suite(
        name=dataset_name,
        description="Test batch add_items",
    )

    suite.add_items(
        [
            {
                "data": {"input": {"question": "What is the capital of France?"}},
                "assertions": [assertion],
            },
            {
                "data": {"input": {"question": "What is the capital of Germany?"}},
                "assertions": [assertion],
            },
            {
                "data": {"input": {"question": "What is the capital of Spain?"}},
                "assertions": [assertion],
            },
        ]
    )

    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        answers = {
            "What is the capital of France?": "Paris",
            "What is the capital of Germany?": "Berlin",
            "What is the capital of Spain?": "Madrid",
        }
        question = item["input"]["question"]
        return {"input": item["input"], "output": answers.get(question, "Unknown")}

    suite_result = suite.run(
        task=task,
        experiment_name=experiment_name,
        verbose=0,
    )
    opik.flush_tracker()

    verifiers.verify_evaluation_suite_result(
        opik_client=opik_client,
        suite_result=suite_result,
        items_total=3,
        items_passed=3,
        experiment_items_count=3,
        total_feedback_scores=3,
        expected_score_names={assertion},
    )
