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
        """Test that non-LLMJudge evaluators raise TypeError via resolve_evaluators."""
        equals_metric = metrics.Equals()

        with pytest.raises(TypeError) as exc_info:
            validators.resolve_evaluators(
                assertions=None,
                evaluators=[equals_metric],
                context="item-level assertions",
            )

        assert "Evaluation suites only support LLMJudge evaluators" in str(
            exc_info.value
        )

    def test_add_item__with_assertions__succeeds(self):
        """Test that assertions shorthand is accepted in add_item."""
        mock_dataset = _create_mock_dataset()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        # Should not raise
        suite.add_item(
            data={"input": "test"},
            assertions=["Response is polite"],
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

    def test_add_item__with_assertions_shorthand__creates_evaluator_items(self):
        """Test that assertions shorthand builds LLMJudge and creates evaluator items."""
        mock_dataset = _create_mock_dataset()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        suite.add_item(
            data={"input": "test"},
            assertions=["Response is polite", "Response is helpful"],
        )

        mock_dataset.__internal_api__insert_items_as_dataclasses__.assert_called_once()
        inserted_items = (
            mock_dataset.__internal_api__insert_items_as_dataclasses__.call_args[0][0]
        )
        assert len(inserted_items) == 1
        item = inserted_items[0]
        assert item.evaluators is not None
        assert len(item.evaluators) == 1
        assert item.evaluators[0].type == "llm_judge"

    def test_resolve_evaluators__with_both_assertions_and_evaluators__raises_value_error(
        self,
    ):
        """Test that providing both assertions and evaluators raises ValueError."""
        llm_judge = suite_evaluators.LLMJudge(
            assertions=["Response is polite"],
            track=False,
        )

        with pytest.raises(ValueError, match="Cannot specify both"):
            validators.resolve_evaluators(
                assertions=["Response is helpful"],
                evaluators=[llm_judge],
                context="item-level assertions",
            )

    def test_resolve_evaluators__with_assertions__returns_llm_judge(self):
        """Test that resolve_evaluators builds LLMJudge from assertions."""
        result = validators.resolve_evaluators(
            assertions=["Response is polite"],
            evaluators=None,
            context="test",
        )

        assert result is not None
        assert len(result) == 1
        assert isinstance(result[0], suite_evaluators.LLMJudge)
        assert result[0].assertions == ["Response is polite"]

    def test_resolve_evaluators__with_neither__returns_none(self):
        """Test that resolve_evaluators returns None when neither is provided."""
        result = validators.resolve_evaluators(
            assertions=None,
            evaluators=None,
            context="test",
        )

        assert result is None

    def test_resolve_evaluators__with_empty_assertions__returns_empty_list(self):
        result = validators.resolve_evaluators(
            assertions=[],
            evaluators=None,
            context="test",
        )

        assert result is not None
        assert result == []


class TestValidateSuiteItems:
    def test_valid_items__passes(self):
        validators.validate_suite_items(
            [
                {"data": {"question": "Hello"}},
                {"data": {"input": "test"}, "assertions": ["Is polite"]},
            ]
        )

    def test_item_not_dict__raises_type_error(self):
        with pytest.raises(TypeError, match="Item at index 1 must be a dict"):
            validators.validate_suite_items(
                [
                    {"data": {"question": "Hello"}},
                    "not a dict",
                ]
            )

    def test_item_missing_data__raises_value_error(self):
        with pytest.raises(
            ValueError, match="Item at index 0 is missing required key 'data'"
        ):
            validators.validate_suite_items(
                [
                    {"assertions": ["Is polite"]},
                ]
            )

    def test_data_not_dict__raises_type_error(self):
        with pytest.raises(TypeError, match="Item at index 0 'data' must be a dict"):
            validators.validate_suite_items(
                [
                    {"data": "not a dict"},
                ]
            )

    def test_empty_list__passes(self):
        validators.validate_suite_items([])

    def test_item_with_all_optional_fields__passes(self):
        validators.validate_suite_items(
            [
                {
                    "data": {"question": "Hello"},
                    "assertions": ["Is polite"],
                    "description": "Test case",
                    "execution_policy": {"runs_per_item": 3, "pass_threshold": 2},
                }
            ]
        )

    def test_unknown_item_keys__raises_value_error(self):
        with pytest.raises(ValueError, match="unknown keys"):
            validators.validate_suite_items(
                [
                    {"data": {"q": "Hello"}, "foo": "bar"},
                ]
            )

    def test_assertions_not_list_of_strings__raises_type_error(self):
        with pytest.raises(TypeError, match="'assertions' must be a list of strings"):
            validators.validate_suite_items(
                [
                    {"data": {"q": "Hello"}, "assertions": [123]},
                ]
            )

    def test_assertions_not_list__raises_type_error(self):
        with pytest.raises(TypeError, match="'assertions' must be a list of strings"):
            validators.validate_suite_items(
                [
                    {"data": {"q": "Hello"}, "assertions": "not a list"},
                ]
            )

    def test_execution_policy_not_dict__raises_type_error(self):
        with pytest.raises(TypeError, match="must be a dict"):
            validators.validate_suite_items(
                [
                    {"data": {"q": "Hello"}, "execution_policy": "bad"},
                ]
            )

    def test_execution_policy_unknown_keys__raises_value_error(self):
        with pytest.raises(ValueError, match="unknown keys"):
            validators.validate_suite_items(
                [
                    {"data": {"q": "Hello"}, "execution_policy": {"bad_key": 1}},
                ]
            )

    def test_execution_policy_non_int_value__raises_type_error(self):
        with pytest.raises(TypeError, match="must be an int"):
            validators.validate_suite_items(
                [
                    {
                        "data": {"q": "Hello"},
                        "execution_policy": {
                            "runs_per_item": "3",
                            "pass_threshold": 1,
                        },
                    },
                ]
            )


class TestValidateExecutionPolicy:
    def test_valid_policy__passes(self):
        validators.validate_execution_policy({"runs_per_item": 3, "pass_threshold": 2})

    def test_partial_policy__raises_value_error(self):
        with pytest.raises(ValueError, match="missing required keys"):
            validators.validate_execution_policy({"runs_per_item": 5})

    def test_not_dict__raises_type_error(self):
        with pytest.raises(TypeError, match="must be a dict"):
            validators.validate_execution_policy("bad")

    def test_unknown_keys__raises_value_error(self):
        with pytest.raises(ValueError, match="unknown keys"):
            validators.validate_execution_policy({"runs_per_item": 3, "retry": True})

    def test_non_int_value__raises_type_error(self):
        with pytest.raises(TypeError, match="must be an int"):
            validators.validate_execution_policy(
                {"runs_per_item": 3.5, "pass_threshold": 1}
            )

    def test_string_value__raises_type_error(self):
        with pytest.raises(TypeError, match="must be an int"):
            validators.validate_execution_policy(
                {"runs_per_item": 1, "pass_threshold": "2"}
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


class TestValidateTaskResult:
    def test_non_dict__raises_type_error(self):
        with pytest.raises(
            TypeError, match="must return a dict with 'input' and 'output'"
        ):
            evaluation_suite.validate_task_result("just a string")

    def test_missing_keys__raises_value_error(self):
        with pytest.raises(ValueError, match="missing.*output"):
            evaluation_suite.validate_task_result({"input": "hello"})

    def test_missing_both_keys__raises_value_error(self):
        with pytest.raises(ValueError, match="missing"):
            evaluation_suite.validate_task_result({"response": "hello"})

    def test_valid_result__returns_dict(self):
        result = evaluation_suite.validate_task_result(
            {"input": "hello", "output": "world"}
        )
        assert result == {"input": "hello", "output": "world"}


class TestInternalRunOptimizationSuite:
    """Tests for __internal_api__run_optimization_suite__."""

    def test_passes_optimization_params_to_run_suite_evaluation(self):
        mock_dataset = _create_mock_dataset()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        fake_result = mock.MagicMock(spec=suite_types.EvaluationSuiteResult)

        with mock.patch(
            "opik.evaluation.evaluator.evaluate_suite",
            return_value=fake_result,
        ) as mock_run:
            result = suite.__internal_api__run_optimization_suite__(
                task=lambda data: {"input": data, "output": "resp"},
                experiment_name="opt-exp",
                verbose=0,
                optimization_id="opt-123",
                experiment_type="mini-batch",
                dataset_item_ids=["item-1", "item-2"],
                dataset_filter_string='input = "hello"',
            )

        assert result is fake_result
        mock_run.assert_called_once()
        call_kwargs = mock_run.call_args[1]
        assert call_kwargs["optimization_id"] == "opt-123"
        assert call_kwargs["experiment_type"] == "mini-batch"
        assert call_kwargs["dataset_item_ids"] == ["item-1", "item-2"]
        assert call_kwargs["dataset_filter_string"] == 'input = "hello"'
        assert call_kwargs["dataset"] is mock_dataset

    def test_without_optimization_params__defaults_to_none(self):
        mock_dataset = _create_mock_dataset()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        fake_result = mock.MagicMock(spec=suite_types.EvaluationSuiteResult)

        with mock.patch(
            "opik.evaluation.evaluator.evaluate_suite",
            return_value=fake_result,
        ) as mock_run:
            suite.__internal_api__run_optimization_suite__(
                task=lambda data: {"input": data, "output": "resp"},
                experiment_name="basic-exp",
                verbose=0,
            )

        call_kwargs = mock_run.call_args[1]
        assert call_kwargs["optimization_id"] is None
        assert call_kwargs["experiment_type"] is None
        assert call_kwargs["dataset_item_ids"] is None
        assert call_kwargs["dataset_filter_string"] is None

    def test_passes_client_through(self):
        mock_dataset = _create_mock_dataset()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )
        mock_client = mock.MagicMock()
        fake_result = mock.MagicMock(spec=suite_types.EvaluationSuiteResult)

        with mock.patch(
            "opik.evaluation.evaluator.evaluate_suite",
            return_value=fake_result,
        ) as mock_run:
            suite.__internal_api__run_optimization_suite__(
                task=lambda data: {"input": data, "output": "resp"},
                experiment_name="client-exp",
                verbose=0,
                client=mock_client,
            )

        call_kwargs = mock_run.call_args[1]
        assert call_kwargs["client"] is mock_client

    def test_run_delegates_to_internal_api(self):
        mock_dataset = _create_mock_dataset()
        suite = evaluation_suite.EvaluationSuite(
            name="test_suite",
            dataset_=mock_dataset,
        )

        fake_result = mock.MagicMock(spec=suite_types.EvaluationSuiteResult)

        with mock.patch(
            "opik.evaluation.evaluator.evaluate_suite",
            return_value=fake_result,
        ) as mock_run:
            result = suite.run(
                task=lambda data: {"input": data, "output": "resp"},
                experiment_name="run-exp",
                verbose=0,
            )

        assert result is fake_result
        mock_run.assert_called_once()


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

    def test_pass_rate__zero_items__returns_none(self):
        """Edge case: no items evaluated should return None."""
        result = suite_types.EvaluationSuiteResult(
            items_passed=0,
            items_total=0,
            item_results={},
            evaluation_result_=mock.MagicMock(),
        )

        assert result.pass_rate is None
