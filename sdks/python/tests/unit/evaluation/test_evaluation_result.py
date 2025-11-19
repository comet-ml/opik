import pytest
from opik.evaluation import evaluation_result, test_result, test_case
from opik.evaluation.metrics import score_result


def test_group_by_dataset_item_view__happyflow():
    """Test core functionality: single dataset item with multiple trials."""
    # Create 3 trials with different accuracy scores
    test_results_list = []
    accuracy_values = [0.7, 0.8, 0.9]

    for trial_id, accuracy_value in enumerate(accuracy_values, 1):
        score = score_result.ScoreResult(
            name="accuracy", value=accuracy_value, reason="Test"
        )
        test_case_obj = test_case.TestCase(
            trace_id=f"trace{trial_id}",
            dataset_item_id="item1",
            scoring_inputs={"input": "test"},
            task_output={"output": f"result{trial_id}"},
        )
        test_result_obj = test_result.TestResult(
            test_case=test_case_obj, score_results=[score], trial_id=trial_id
        )
        test_results_list.append(test_result_obj)

    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Test Experiment",
        test_results=test_results_list,
        experiment_url="http://test.com",
        trial_count=3,
    )

    # Test the core functionality
    view = eval_result.group_by_dataset_item_view()

    # Verify structure
    assert isinstance(view, evaluation_result.EvaluationResultGroupByDatasetItemsView)
    assert len(view.dataset_items) == 1

    # Verify aggregated statistics
    item_results = view.dataset_items["item1"]
    accuracy_stats = item_results.scores["accuracy"]

    assert accuracy_stats.mean == pytest.approx(0.8, rel=1e-9)  # (0.7 + 0.8 + 0.9) / 3
    assert accuracy_stats.max == 0.9
    assert accuracy_stats.min == 0.7
    assert accuracy_stats.values == [0.7, 0.8, 0.9]
    assert accuracy_stats.std == pytest.approx(0.1, rel=1e-1)


def test_group_by_dataset_item_view__multiple_metrics_and_items():
    """Test with multiple dataset items and multiple metrics per trial."""
    test_results_list = []

    # Dataset item 1: accuracy and precision scores
    accuracy_score1 = score_result.ScoreResult(
        name="accuracy", value=0.8, reason="Good"
    )
    precision_score1 = score_result.ScoreResult(
        name="precision", value=0.7, reason="Okay"
    )
    test_case1 = test_case.TestCase(
        trace_id="trace1",
        dataset_item_id="item1",
        scoring_inputs={"input": "test1"},
        task_output={"output": "result1"},
    )
    test_result1 = test_result.TestResult(
        test_case=test_case1,
        score_results=[accuracy_score1, precision_score1],
        trial_id=1,
    )
    test_results_list.append(test_result1)

    # Dataset item 2: only recall score
    recall_score = score_result.ScoreResult(
        name="recall", value=0.95, reason="Excellent"
    )
    test_case2 = test_case.TestCase(
        trace_id="trace2",
        dataset_item_id="item2",
        scoring_inputs={"input": "test2"},
        task_output={"output": "result2"},
    )
    test_result2 = test_result.TestResult(
        test_case=test_case2, score_results=[recall_score], trial_id=1
    )
    test_results_list.append(test_result2)

    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Test Experiment",
        test_results=test_results_list,
        experiment_url="http://test.com",
        trial_count=1,
    )

    # Test multiple items and metrics
    view = eval_result.group_by_dataset_item_view()

    # Should have both dataset items
    assert len(view.dataset_items) == 2

    # Item1 should have 2 metrics
    item1_scores = view.dataset_items["item1"].scores
    assert len(item1_scores) == 2
    assert item1_scores["accuracy"].values == [0.8]
    assert item1_scores["precision"].values == [0.7]

    # Item2 should have 1 metric
    item2_scores = view.dataset_items["item2"].scores
    assert len(item2_scores) == 1
    assert item2_scores["recall"].values == [0.95]


def test_group_by_dataset_item_view__failed_and_invalid_scores():
    """Test that failed and invalid scores are properly excluded."""
    # Create test data with various score types
    valid_score = score_result.ScoreResult(
        name="accuracy", value=0.8, scoring_failed=False
    )
    failed_score = score_result.ScoreResult(
        name="accuracy", value=0.0, scoring_failed=True
    )
    nan_score = score_result.ScoreResult(
        name="accuracy", value=float("nan"), scoring_failed=False
    )
    inf_score = score_result.ScoreResult(
        name="accuracy", value=float("inf"), scoring_failed=False
    )
    another_valid_score = score_result.ScoreResult(
        name="accuracy", value=0.9, scoring_failed=False
    )

    test_case_obj = test_case.TestCase(
        trace_id="trace1",
        dataset_item_id="item1",
        scoring_inputs={"input": "test"},
        task_output={"output": "result"},
    )

    test_result_obj = test_result.TestResult(
        test_case=test_case_obj,
        score_results=[
            valid_score,
            failed_score,
            nan_score,
            inf_score,
            another_valid_score,
        ],
        trial_id=1,
    )

    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Test Experiment",
        test_results=[test_result_obj],
        experiment_url="http://test.com",
        trial_count=1,
    )

    # Test that only valid scores are included
    view = eval_result.group_by_dataset_item_view()
    accuracy_stats = view.dataset_items["item1"].scores["accuracy"]

    # Should only include the two valid scores
    assert accuracy_stats.values == [0.8, 0.9]
    assert accuracy_stats.mean == pytest.approx(0.85, rel=1e-9)


def test_group_by_dataset_item_view__empty_results():
    """Test edge case with no test results."""
    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Empty Test",
        test_results=[],
        experiment_url="http://test.com",
        trial_count=0,
    )

    # Test empty case
    view = eval_result.group_by_dataset_item_view()

    # Should return empty view with correct metadata
    assert len(view.dataset_items) == 0
    assert view.experiment_id == "exp1"
    assert view.dataset_id == "dataset1"


def test_group_by_dataset_item_view__standard_deviation():
    """Test standard deviation calculation with known values."""
    # Use simple values: [1, 2, 3] -> mean=2, std=1
    test_values = [1.0, 2.0, 3.0]
    test_results_list = []

    for trial_id, value in enumerate(test_values, 1):
        score = score_result.ScoreResult(name="test_metric", value=value, reason="Test")
        test_case_obj = test_case.TestCase(
            trace_id=f"trace{trial_id}",
            dataset_item_id="item1",
            scoring_inputs={"input": "test"},
            task_output={"output": f"result{trial_id}"},
        )
        test_result_obj = test_result.TestResult(
            test_case=test_case_obj, score_results=[score], trial_id=trial_id
        )
        test_results_list.append(test_result_obj)

    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Test Experiment",
        test_results=test_results_list,
        experiment_url="http://test.com",
        trial_count=3,
    )

    # Test standard deviation
    view = eval_result.group_by_dataset_item_view()
    stats = view.dataset_items["item1"].scores["test_metric"]

    assert stats.mean == 2.0
    assert stats.values == [1.0, 2.0, 3.0]
    assert stats.std == pytest.approx(1.0, rel=1e-2)  # Sample standard deviation

    # Test single value case (no std)
    single_score = score_result.ScoreResult(
        name="single_metric", value=5.0, reason="Test"
    )
    single_test_case = test_case.TestCase(
        trace_id="single_trace",
        dataset_item_id="item2",
        scoring_inputs={"input": "test"},
        task_output={"output": "result"},
    )
    single_test_result = test_result.TestResult(
        test_case=single_test_case, score_results=[single_score], trial_id=1
    )

    eval_result_single = evaluation_result.EvaluationResult(
        experiment_id="exp2",
        dataset_id="dataset2",
        experiment_name="Single Test",
        test_results=[single_test_result],
        experiment_url="http://test.com",
        trial_count=1,
    )

    view_single = eval_result_single.group_by_dataset_item_view()
    single_stats = view_single.dataset_items["item2"].scores["single_metric"]

    assert single_stats.values == [5.0]
    assert single_stats.std is None  # No std for single value
