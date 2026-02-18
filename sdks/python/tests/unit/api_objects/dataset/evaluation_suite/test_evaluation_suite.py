"""Unit tests for EvaluationSuite validation and result building."""

import pytest
from unittest import mock

from opik.api_objects.dataset.evaluation_suite import evaluation_suite
from opik.api_objects.dataset.evaluation_suite import suite_result_constructor
from opik.api_objects.dataset.evaluation_suite import types as suite_types
from opik.api_objects.dataset import validators
from opik.api_objects.dataset import dataset_item
from opik.evaluation import suite_evaluators
from opik.evaluation import metrics
from opik.evaluation import evaluation_result
from opik.evaluation import test_result
from opik.evaluation import test_case
from opik.evaluation.metrics import score_result


def _create_mock_dataset():
    """Helper to create a mock dataset."""
    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__insert_items_as_dataclasses__",
            "__internal_api__stream_items_as_dataclasses__",
        ]
    )
    mock_dataset.__internal_api__stream_items_as_dataclasses__ = mock.MagicMock(
        return_value=iter([])
    )
    mock_dataset.__internal_api__insert_items_as_dataclasses__ = mock.MagicMock()
    return mock_dataset


class TestEvaluatorValidation:
    def test_validate_evaluators__with_non_llm_judge__raises_type_error(self):
        """Test that non-LLMJudge evaluators raise TypeError."""
        equals_metric = metrics.Equals()

        with pytest.raises(TypeError) as exc_info:
            validators.validate_evaluators([equals_metric], "suite-level evaluators")

        assert "Evaluation suites only support LLMJudge evaluators" in str(
            exc_info.value
        )
        assert "Equals" in str(exc_info.value)

    def test_validate_evaluators__with_llm_judge__succeeds(self):
        """Test that LLMJudge evaluators pass validation."""
        llm_judge = suite_evaluators.LLMJudge(
            assertions=["Response is helpful"],
            track=False,
        )

        # Should not raise
        validators.validate_evaluators([llm_judge], "suite-level evaluators")

    def test_init__stores_dataset_reference(self):
        """Test that suite stores the dataset reference."""
        mock_dataset = _create_mock_dataset()

        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        assert suite.dataset is mock_dataset
        assert suite.name == "test_suite"

    def test_add_item__with_non_llm_judge_evaluator__raises_type_error(self):
        """Test that non-LLMJudge evaluators raise TypeError on add_item."""
        mock_dataset = _create_mock_dataset()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        equals_metric = metrics.Equals()

        with pytest.raises(TypeError) as exc_info:
            suite.add_item(
                data={"input": "test"},
                evaluators=[equals_metric],
            )

        assert "Evaluation suites only support LLMJudge evaluators" in str(
            exc_info.value
        )
        assert "item-level evaluators" in str(exc_info.value)

    def test_add_item__with_llm_judge_evaluator__succeeds(self):
        """Test that LLMJudge evaluators are accepted in add_item."""
        mock_dataset = _create_mock_dataset()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        llm_judge = suite_evaluators.LLMJudge(
            assertions=["Response is polite"],
            track=False,
        )

        # Should not raise
        suite.add_item(
            data={"input": "test"},
            evaluators=[llm_judge],
        )

    def test_add_item__with_no_evaluators__succeeds(self):
        """Test that items can be added without evaluators."""
        mock_dataset = _create_mock_dataset()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        # Should not raise
        suite.add_item(data={"input": "test"})

    def test_validate_evaluators__with_mixed_evaluators__raises_type_error(self):
        """Test that mixing LLMJudge with other evaluators raises TypeError."""
        llm_judge = suite_evaluators.LLMJudge(assertions=["Test"], track=False)
        equals_metric = metrics.Equals()

        with pytest.raises(TypeError) as exc_info:
            validators.validate_evaluators(
                [llm_judge, equals_metric], "suite-level evaluators"
            )

        assert "Evaluation suites only support LLMJudge evaluators" in str(
            exc_info.value
        )


def _make_test_result(
    dataset_item_id: str,
    trial_id: int,
    scores: list[tuple[str, bool]],
    execution_policy: dict | None = None,
) -> test_result.TestResult:
    """Helper to create a TestResult with score results."""
    ds_item = None
    if execution_policy is not None:
        ds_item = dataset_item.DatasetItem(
            id=dataset_item_id,
            execution_policy=dataset_item.ExecutionPolicyItem(
                runs_per_item=execution_policy.get("runs_per_item"),
                pass_threshold=execution_policy.get("pass_threshold"),
            ),
        )

    return test_result.TestResult(
        test_case=test_case.TestCase(
            trace_id=f"trace-{dataset_item_id}-{trial_id}",
            dataset_item_id=dataset_item_id,
            task_output={"output": "test"},
            dataset_item_content={},
            dataset_item=ds_item,
        ),
        score_results=[
            score_result.ScoreResult(name=name, value=value) for name, value in scores
        ],
        trial_id=trial_id,
    )


def _make_evaluation_result(
    test_results: list[test_result.TestResult],
) -> evaluation_result.EvaluationResult:
    """Helper to create an EvaluationResult."""
    return evaluation_result.EvaluationResult(
        experiment_id="exp-123",
        dataset_id="dataset-123",
        experiment_name="test-experiment",
        test_results=test_results,
        experiment_url="http://example.com/exp",
        trial_count=1,
    )


class TestBuildSuiteResult:
    """Unit tests for build_suite_result function."""

    def test_build_suite_result__single_item_all_assertions_pass__item_passes(self):
        """Single item with 3 assertions, all pass -> item passes."""
        test_results = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[
                    ("Assertion 1", True),
                    ("Assertion 2", True),
                    ("Assertion 3", True),
                ],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            )
        ]
        eval_result = _make_evaluation_result(test_results)

        suite_result = suite_result_constructor.build_suite_result(eval_result)

        assert suite_result.all_items_passed is True
        assert suite_result.items_passed == 1
        assert suite_result.items_total == 1
        assert suite_result.pass_rate == 1.0
        assert suite_result.item_results["item-1"].passed is True
        assert suite_result.item_results["item-1"].runs_passed == 1

    def test_build_suite_result__single_item_one_assertion_fails__run_fails(self):
        """Single item with 3 assertions, one fails -> run fails -> item fails."""
        test_results = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[
                    ("Assertion 1", True),
                    ("Assertion 2", False),  # One fails
                    ("Assertion 3", True),
                ],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            )
        ]
        eval_result = _make_evaluation_result(test_results)

        suite_result = suite_result_constructor.build_suite_result(eval_result)

        assert suite_result.all_items_passed is False
        assert suite_result.items_passed == 0
        assert suite_result.items_total == 1
        assert suite_result.pass_rate == 0.0
        assert suite_result.item_results["item-1"].passed is False
        assert suite_result.item_results["item-1"].runs_passed == 0

    def test_build_suite_result__multiple_runs_pass_threshold_met__item_passes(self):
        """
        3 runs, pass_threshold=2.
        Run 1: all pass -> run passes
        Run 2: one fails -> run fails
        Run 3: all pass -> run passes
        Result: 2 runs passed >= pass_threshold(2) -> item passes
        """
        test_results = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[("A1", True), ("A2", True), ("A3", True)],
                execution_policy={"runs_per_item": 3, "pass_threshold": 2},
            ),
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=1,
                scores=[("A1", True), ("A2", False), ("A3", True)],
                execution_policy={"runs_per_item": 3, "pass_threshold": 2},
            ),
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=2,
                scores=[("A1", True), ("A2", True), ("A3", True)],
                execution_policy={"runs_per_item": 3, "pass_threshold": 2},
            ),
        ]
        eval_result = _make_evaluation_result(test_results)

        suite_result = suite_result_constructor.build_suite_result(eval_result)

        assert suite_result.all_items_passed is True
        assert suite_result.items_passed == 1
        item_result = suite_result.item_results["item-1"]
        assert item_result.passed is True
        assert item_result.runs_passed == 2
        assert item_result.runs_total == 3
        assert item_result.pass_threshold == 2

    def test_build_suite_result__multiple_runs_pass_threshold_not_met__item_fails(self):
        """
        3 runs, pass_threshold=2.
        Run 1: one fails -> run fails
        Run 2: one fails -> run fails
        Run 3: all pass -> run passes
        Result: 1 run passed < pass_threshold(2) -> item fails
        """
        test_results = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[("A1", False), ("A2", True), ("A3", True)],
                execution_policy={"runs_per_item": 3, "pass_threshold": 2},
            ),
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=1,
                scores=[("A1", True), ("A2", False), ("A3", True)],
                execution_policy={"runs_per_item": 3, "pass_threshold": 2},
            ),
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=2,
                scores=[("A1", True), ("A2", True), ("A3", True)],
                execution_policy={"runs_per_item": 3, "pass_threshold": 2},
            ),
        ]
        eval_result = _make_evaluation_result(test_results)

        suite_result = suite_result_constructor.build_suite_result(eval_result)

        assert suite_result.all_items_passed is False
        assert suite_result.items_passed == 0
        item_result = suite_result.item_results["item-1"]
        assert item_result.passed is False
        assert item_result.runs_passed == 1
        assert item_result.runs_total == 3
        assert item_result.pass_threshold == 2

    def test_build_suite_result__no_scores__run_passes_by_default(self):
        """Items with no evaluators (no scores) pass by default."""
        test_results = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            )
        ]
        eval_result = _make_evaluation_result(test_results)

        suite_result = suite_result_constructor.build_suite_result(eval_result)

        assert suite_result.all_items_passed is True
        assert suite_result.item_results["item-1"].passed is True
        assert suite_result.item_results["item-1"].runs_passed == 1

    def test_build_suite_result__multiple_items__calculates_pass_rate(self):
        """Suite with 3 items: 2 pass, 1 fails -> pass_rate = 2/3."""
        test_results = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[("A1", True)],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            ),
            _make_test_result(
                dataset_item_id="item-2",
                trial_id=0,
                scores=[("A1", False)],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            ),
            _make_test_result(
                dataset_item_id="item-3",
                trial_id=0,
                scores=[("A1", True)],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            ),
        ]
        eval_result = _make_evaluation_result(test_results)

        suite_result = suite_result_constructor.build_suite_result(eval_result)

        assert suite_result.all_items_passed is False  # Not all items passed
        assert suite_result.items_passed == 2
        assert suite_result.items_total == 3
        assert suite_result.pass_rate == pytest.approx(2 / 3)

    def test_build_suite_result__integer_scores__treats_1_as_pass_0_as_fail(self):
        """Scores with integer values: 1 = pass, 0 = fail."""
        ds_item = dataset_item.DatasetItem(
            id="item-1",
            execution_policy=dataset_item.ExecutionPolicyItem(
                runs_per_item=1,
                pass_threshold=1,
            ),
        )
        test_results = [
            test_result.TestResult(
                test_case=test_case.TestCase(
                    trace_id="trace-1",
                    dataset_item_id="item-1",
                    task_output={"output": "test"},
                    dataset_item_content={},
                    dataset_item=ds_item,
                ),
                score_results=[
                    score_result.ScoreResult(name="A1", value=1),
                    score_result.ScoreResult(name="A2", value=1),
                ],
                trial_id=0,
            )
        ]
        eval_result = _make_evaluation_result(test_results)

        suite_result = suite_result_constructor.build_suite_result(eval_result)

        assert suite_result.item_results["item-1"].passed is True
        assert suite_result.item_results["item-1"].runs_passed == 1

    def test_build_suite_result__integer_score_zero__run_fails(self):
        """Scores with integer value 0 should fail the run."""
        ds_item = dataset_item.DatasetItem(
            id="item-1",
            execution_policy=dataset_item.ExecutionPolicyItem(
                runs_per_item=1,
                pass_threshold=1,
            ),
        )
        test_results = [
            test_result.TestResult(
                test_case=test_case.TestCase(
                    trace_id="trace-1",
                    dataset_item_id="item-1",
                    task_output={"output": "test"},
                    dataset_item_content={},
                    dataset_item=ds_item,
                ),
                score_results=[
                    score_result.ScoreResult(name="A1", value=1),
                    score_result.ScoreResult(name="A2", value=0),  # Fails
                ],
                trial_id=0,
            )
        ]
        eval_result = _make_evaluation_result(test_results)

        suite_result = suite_result_constructor.build_suite_result(eval_result)

        assert suite_result.item_results["item-1"].passed is False
        assert suite_result.item_results["item-1"].runs_passed == 0


class TestEvaluationSuiteResultPassRate:
    """Unit tests for EvaluationSuiteResult.pass_rate property."""

    def test_pass_rate__all_items_pass__returns_1(self):
        result = suite_types.EvaluationSuiteResult(
            items_passed=5,
            items_total=5,
            item_results={},
            evaluation_result_=mock.MagicMock(),
        )

        assert result.pass_rate == 1.0

    def test_pass_rate__no_items_pass__returns_0(self):
        result = suite_types.EvaluationSuiteResult(
            items_passed=0,
            items_total=5,
            item_results={},
            evaluation_result_=mock.MagicMock(),
        )

        assert result.pass_rate == 0.0

    def test_pass_rate__partial_pass__returns_ratio(self):
        result = suite_types.EvaluationSuiteResult(
            items_passed=3,
            items_total=10,
            item_results={},
            evaluation_result_=mock.MagicMock(),
        )

        assert result.pass_rate == 0.3

    def test_pass_rate__zero_items__returns_1(self):
        """Edge case: no items evaluated should return 1.0 (vacuously true)."""
        result = suite_types.EvaluationSuiteResult(
            items_passed=0,
            items_total=0,
            item_results={},
            evaluation_result_=mock.MagicMock(),
        )

        assert result.pass_rate == 1.0
