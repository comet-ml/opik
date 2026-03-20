"""Unit tests for evaluation suite result file generation."""

import json
import os


from opik.api_objects.dataset.evaluation_suite import (
    suite_result_constructor,
    types as suite_types,
)
from opik.api_objects.dataset.evaluation_suite.report_processors import file_writer
from opik.api_objects.dataset import dataset_item
from opik.evaluation import evaluation_result, test_result, test_case
from opik.evaluation.metrics import score_result


def _make_test_result(
    dataset_item_id: str,
    trial_id: int,
    scores: list[tuple[str, float]],
    task_output: dict | None = None,
    dataset_item_content: dict | None = None,
    execution_policy: dict | None = None,
    task_execution_time: float | None = None,
    scoring_time: float | None = None,
) -> test_result.TestResult:
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
            task_output=task_output or {"input": "test", "output": "result"},
            dataset_item_content=dataset_item_content or {"question": "What?"},
            dataset_item=ds_item,
        ),
        score_results=[
            score_result.ScoreResult(name=name, value=value) for name, value in scores
        ],
        trial_id=trial_id,
        task_execution_time=task_execution_time,
        scoring_time=scoring_time,
    )


def _make_suite_result(
    test_results_list: list[test_result.TestResult],
    suite_name: str | None = None,
    total_time: float | None = None,
) -> suite_types.EvaluationSuiteResult:
    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp-123",
        dataset_id="dataset-456",
        experiment_name="my-experiment",
        test_results=test_results_list,
        experiment_url="http://example.com/experiment/exp-123",
        trial_count=1,
    )
    return suite_result_constructor.build_suite_result(
        eval_result,
        suite_name=suite_name,
        total_time=total_time,
    )


class TestToReportDict:
    def test_to_report_dict__single_item_with_mixed_scores__returns_correct_structure(
        self,
    ):
        test_results_list = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[("Is polite", True), ("Is helpful", False)],
                task_output={"input": "hi", "output": "hello"},
                dataset_item_content={"question": "hi"},
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
                task_execution_time=1.234,
                scoring_time=0.567,
            )
        ]
        suite_result = _make_suite_result(test_results_list, suite_name="My Suite")

        result = suite_result.to_report_dict()

        assert result["suite_passed"] is False
        assert result["items_passed"] == 0
        assert result["items_total"] == 1
        assert result["pass_rate"] == 0.0
        assert result["experiment_id"] == "exp-123"
        assert result["experiment_name"] == "my-experiment"
        assert result["experiment_url"] == "http://example.com/experiment/exp-123"
        assert result["suite_name"] == "My Suite"
        assert "generated_at" in result

        assert len(result["items"]) == 1
        item = result["items"][0]
        assert item["dataset_item_id"] == "item-1"
        assert item["passed"] is False
        assert item["runs_passed"] == 0
        assert item["execution_policy"] == {"runs_per_item": 1, "pass_threshold": 1}

        assert len(item["runs"]) == 1
        run = item["runs"][0]
        assert run["trial_id"] == 0
        assert run["passed"] is False
        assert run["input"] == "hi"
        assert run["output"] == "hello"
        assert run["trace_id"] == "trace-item-1-0"
        assert run["task_execution_time_seconds"] == 1.234
        assert run["scoring_time_seconds"] == 0.567

        assert len(run["assertions"]) == 2
        assert run["assertions"][0]["name"] == "Is polite"
        assert run["assertions"][0]["passed"] is True
        assert run["assertions"][1]["name"] == "Is helpful"
        assert run["assertions"][1]["passed"] is False

    def test_to_report_dict__all_items_pass__suite_passed_true(self):
        test_results_list = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[("A1", True)],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            ),
            _make_test_result(
                dataset_item_id="item-2",
                trial_id=0,
                scores=[("A1", True)],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            ),
        ]
        suite_result = _make_suite_result(test_results_list)

        result = suite_result.to_report_dict()

        assert result["suite_passed"] is True
        assert result["items_passed"] == 2
        assert result["pass_rate"] == 1.0

    def test_to_report_dict__with_total_time__includes_rounded_value(self):
        test_results_list = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[("A1", True)],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            ),
        ]
        suite_result = _make_suite_result(test_results_list, total_time=12.3456)

        result = suite_result.to_report_dict()

        assert result["total_time_seconds"] == 12.346

    def test_to_report_dict__scoring_failed__marks_assertion_failed_with_reason(self):
        test_results_list = [
            test_result.TestResult(
                test_case=test_case.TestCase(
                    trace_id="trace-1",
                    dataset_item_id="item-1",
                    task_output={"output": "test"},
                    dataset_item_content={},
                    dataset_item=dataset_item.DatasetItem(
                        id="item-1",
                        execution_policy=dataset_item.ExecutionPolicyItem(
                            runs_per_item=1,
                            pass_threshold=1,
                        ),
                    ),
                ),
                score_results=[
                    score_result.ScoreResult(
                        name="A1",
                        value=0,
                        scoring_failed=True,
                        reason="Model error",
                    ),
                ],
                trial_id=0,
            )
        ]
        suite_result = _make_suite_result(test_results_list)

        result = suite_result.to_report_dict()

        assertion = result["items"][0]["runs"][0]["assertions"][0]
        assert assertion["passed"] is False
        assert assertion["scoring_failed"] is True
        assert assertion["reason"] == "Model error"


class TestSaveReport:
    def test_save_report__valid_input__writes_json_file(self, tmp_path):
        test_results_list = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[("A1", True)],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            ),
        ]
        suite_result = _make_suite_result(
            test_results_list, suite_name="Test Suite", total_time=5.0
        )
        output_path = str(tmp_path / "report.json")

        result_path = file_writer.save_report(suite_result, output_path)

        assert result_path == output_path
        assert os.path.exists(output_path)

        with open(output_path) as f:
            data = json.load(f)

        assert data["suite_name"] == "Test Suite"
        assert data["suite_passed"] is True
        assert len(data["items"]) == 1

    def test_save_report__no_path__uses_experiment_name(self, tmp_path, monkeypatch):
        monkeypatch.chdir(tmp_path)

        test_results_list = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[("A1", True)],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            ),
        ]
        suite_result = _make_suite_result(test_results_list)

        result_path = file_writer.save_report(suite_result)

        assert "my-experiment" in os.path.basename(result_path)
        assert result_path.endswith(".json")
        assert os.path.exists(result_path)

    def test_save_report__nested_path__creates_parent_directories(self, tmp_path):
        output_path = str(tmp_path / "nested" / "dir" / "report.json")

        test_results_list = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[("A1", True)],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            ),
        ]
        suite_result = _make_suite_result(test_results_list)

        result_path = file_writer.save_report(suite_result, output_path)

        assert os.path.exists(result_path)


class TestBuildDefaultReportPath:
    def test_build_default_report_path__unsafe_characters__replaces_with_underscore(
        self,
    ):
        path = file_writer.build_default_report_path("my/suite:name")
        assert os.path.basename(path) == "my_suite_name.json"

    def test_build_default_report_path__safe_characters__keeps_unchanged(self):
        path = file_writer.build_default_report_path("my-suite_v1.0")
        assert os.path.basename(path) == "my-suite_v1.0.json"

    def test_build_default_report_path__spaces__replaces_with_underscore(self):
        path = file_writer.build_default_report_path("my suite")
        assert os.path.basename(path) == "my_suite.json"


class TestEvaluationSuiteResultMethods:
    def test_to_dict__passing_suite__returns_dict_with_suite_passed_true(self):
        test_results_list = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[("A1", True)],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            ),
        ]
        suite_result = _make_suite_result(test_results_list)

        result = suite_result.to_dict()

        assert isinstance(result, dict)
        assert result["suite_passed"] is True
        assert "items" in result

    def test_to_report_dict__save_to_file__produces_valid_json(self, tmp_path):
        test_results_list = [
            _make_test_result(
                dataset_item_id="item-1",
                trial_id=0,
                scores=[("A1", True)],
                execution_policy={"runs_per_item": 1, "pass_threshold": 1},
            ),
        ]
        suite_result = _make_suite_result(test_results_list)
        output_path = str(tmp_path / "result.json")

        path = file_writer.save_report(suite_result, output_path)

        assert os.path.exists(path)
        with open(path) as f:
            data = json.load(f)
        assert data["suite_passed"] is True
