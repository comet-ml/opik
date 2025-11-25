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
            mapped_scoring_inputs={"input": "test"},
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
        experiment_url="http://test.comet.com",
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
        mapped_scoring_inputs={"input": "test1"},
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
        mapped_scoring_inputs={"input": "test2"},
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
        experiment_url="http://test.comet.com",
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
        mapped_scoring_inputs={"input": "test"},
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
        experiment_url="http://test.comet.com",
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
        experiment_url="http://test.comet.com",
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
            mapped_scoring_inputs={"input": "test"},
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
        experiment_url="http://test.comet.com",
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
        mapped_scoring_inputs={"input": "test"},
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
        experiment_url="http://test.comet.com",
        trial_count=1,
    )

    view_single = eval_result_single.group_by_dataset_item_view()
    single_stats = view_single.dataset_items["item2"].scores["single_metric"]

    assert single_stats.values == [5.0]
    assert single_stats.std is None  # No std for single value


def test_aggregate_evaluation_scores__single_metric_multiple_results():
    """Test aggregation of a single metric across multiple test results."""
    test_results_list = []
    accuracy_values = [0.6, 0.8, 0.7, 0.9, 0.5]

    for trial_id, accuracy_value in enumerate(accuracy_values, 1):
        score = score_result.ScoreResult(
            name="accuracy", value=accuracy_value, reason="Test"
        )
        test_case_obj = test_case.TestCase(
            trace_id=f"trace{trial_id}",
            dataset_item_id=f"item{trial_id}",
            mapped_scoring_inputs={"input": "test"},
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
        experiment_url="http://test.comet.com",
        trial_count=5,
    )

    # Test aggregation
    aggregated_view = eval_result.aggregate_evaluation_scores()

    # Verify view properties
    assert aggregated_view.experiment_id == "exp1"
    assert aggregated_view.dataset_id == "dataset1"
    assert aggregated_view.experiment_name == "Test Experiment"
    assert aggregated_view.experiment_url == "http://test.comet.com"
    assert aggregated_view.trial_count == 5

    # Verify aggregated scores
    assert len(aggregated_view.aggregated_scores) == 1
    accuracy_stats = aggregated_view.aggregated_scores["accuracy"]

    assert accuracy_stats.mean == pytest.approx(
        0.7, rel=1e-9
    )  # (0.6+0.8+0.7+0.9+0.5) / 5
    assert accuracy_stats.max == 0.9
    assert accuracy_stats.min == 0.5
    assert accuracy_stats.values == [0.6, 0.8, 0.7, 0.9, 0.5]
    assert accuracy_stats.std == pytest.approx(0.1581, rel=1e-3)  # Sample std dev


def test_aggregate_evaluation_scores__multiple_metrics():
    """Test aggregation of multiple metrics across test results."""
    test_results_list = []

    # First test result with accuracy and precision
    score1 = score_result.ScoreResult(name="accuracy", value=0.8, reason="Good")
    score2 = score_result.ScoreResult(name="precision", value=0.75, reason="Good")
    test_case1 = test_case.TestCase(
        trace_id="trace1",
        dataset_item_id="item1",
        mapped_scoring_inputs={"input": "test1"},
        task_output={"output": "result1"},
    )
    test_result1 = test_result.TestResult(
        test_case=test_case1, score_results=[score1, score2], trial_id=1
    )
    test_results_list.append(test_result1)

    # Second test result with accuracy and recall
    score3 = score_result.ScoreResult(name="accuracy", value=0.9, reason="Great")
    score4 = score_result.ScoreResult(name="recall", value=0.85, reason="Great")
    test_case2 = test_case.TestCase(
        trace_id="trace2",
        dataset_item_id="item2",
        mapped_scoring_inputs={"input": "test2"},
        task_output={"output": "result2"},
    )
    test_result2 = test_result.TestResult(
        test_case=test_case2, score_results=[score3, score4], trial_id=2
    )
    test_results_list.append(test_result2)

    # Third test result with precision and recall
    score5 = score_result.ScoreResult(name="precision", value=0.82, reason="Good")
    score6 = score_result.ScoreResult(name="recall", value=0.78, reason="Good")
    test_case3 = test_case.TestCase(
        trace_id="trace3",
        dataset_item_id="item3",
        mapped_scoring_inputs={"input": "test3"},
        task_output={"output": "result3"},
    )
    test_result3 = test_result.TestResult(
        test_case=test_case3, score_results=[score5, score6], trial_id=3
    )
    test_results_list.append(test_result3)

    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Multi-metric Test",
        test_results=test_results_list,
        experiment_url="http://test.comet.com",
        trial_count=3,
    )

    # Test aggregation
    aggregated_view = eval_result.aggregate_evaluation_scores()

    # Should have 3 metrics
    assert len(aggregated_view.aggregated_scores) == 3

    # Test accuracy aggregation (2 values: 0.8, 0.9)
    accuracy_stats = aggregated_view.aggregated_scores["accuracy"]
    assert accuracy_stats.mean == pytest.approx(0.85, rel=1e-9)
    assert accuracy_stats.max == 0.9
    assert accuracy_stats.min == 0.8
    assert accuracy_stats.values == [0.8, 0.9]
    assert accuracy_stats.std == pytest.approx(0.0707, rel=1e-3)

    # Test precision aggregation (2 values: 0.75, 0.82)
    precision_stats = aggregated_view.aggregated_scores["precision"]
    assert precision_stats.mean == pytest.approx(0.785, rel=1e-9)
    assert precision_stats.max == 0.82
    assert precision_stats.min == 0.75
    assert precision_stats.values == [0.75, 0.82]

    # Test recall aggregation (2 values: 0.85, 0.78)
    recall_stats = aggregated_view.aggregated_scores["recall"]
    assert recall_stats.mean == pytest.approx(0.815, rel=1e-9)
    assert recall_stats.max == 0.85
    assert recall_stats.min == 0.78
    assert recall_stats.values == [0.85, 0.78]


def test_aggregate_evaluation_scores__failed_and_invalid_scores():
    """Test that failed and invalid scores are excluded from aggregation."""
    test_results_list = []

    # Create scores with various states
    valid_score1 = score_result.ScoreResult(
        name="accuracy", value=0.8, scoring_failed=False
    )
    valid_score2 = score_result.ScoreResult(
        name="accuracy", value=0.9, scoring_failed=False
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
    neg_inf_score = score_result.ScoreResult(
        name="accuracy", value=float("-inf"), scoring_failed=False
    )

    test_case_obj = test_case.TestCase(
        trace_id="trace1",
        dataset_item_id="item1",
        mapped_scoring_inputs={"input": "test"},
        task_output={"output": "result"},
    )

    test_result_obj = test_result.TestResult(
        test_case=test_case_obj,
        score_results=[
            valid_score1,
            valid_score2,
            failed_score,
            nan_score,
            inf_score,
            neg_inf_score,
        ],
        trial_id=1,
    )
    test_results_list.append(test_result_obj)

    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Test Experiment",
        test_results=test_results_list,
        experiment_url="http://test.comet.com",
        trial_count=1,
    )

    # Test aggregation
    aggregated_view = eval_result.aggregate_evaluation_scores()

    # Should only include valid scores (0.8, 0.9)
    assert len(aggregated_view.aggregated_scores) == 1
    accuracy_stats = aggregated_view.aggregated_scores["accuracy"]

    assert accuracy_stats.values == [0.8, 0.9]
    assert accuracy_stats.mean == pytest.approx(0.85, rel=1e-9)
    assert accuracy_stats.max == 0.9
    assert accuracy_stats.min == 0.8


def test_aggregate_evaluation_scores__empty_results():
    """Test aggregation with no test results."""
    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Empty Test",
        test_results=[],
        experiment_url="http://test.comet.com",
        trial_count=0,
    )

    # Test aggregation
    aggregated_view = eval_result.aggregate_evaluation_scores()

    # Verify view properties
    assert aggregated_view.experiment_id == "exp1"
    assert aggregated_view.dataset_id == "dataset1"
    assert aggregated_view.experiment_name == "Empty Test"
    assert aggregated_view.trial_count == 0

    # Should have no aggregated scores
    assert len(aggregated_view.aggregated_scores) == 0


def test_aggregate_evaluation_scores__single_value_no_std():
    """Test that single values have no standard deviation."""
    score = score_result.ScoreResult(name="f1_score", value=0.75, reason="Test")
    test_case_obj = test_case.TestCase(
        trace_id="trace1",
        dataset_item_id="item1",
        mapped_scoring_inputs={"input": "test"},
        task_output={"output": "result"},
    )
    test_result_obj = test_result.TestResult(
        test_case=test_case_obj, score_results=[score], trial_id=1
    )

    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Single Value Test",
        test_results=[test_result_obj],
        experiment_url="http://test.comet.com",
        trial_count=1,
    )

    # Test aggregation
    aggregated_view = eval_result.aggregate_evaluation_scores()

    # Verify single value statistics
    assert len(aggregated_view.aggregated_scores) == 1
    f1_stats = aggregated_view.aggregated_scores["f1_score"]

    assert f1_stats.mean == 0.75
    assert f1_stats.max == 0.75
    assert f1_stats.min == 0.75
    assert f1_stats.values == [0.75]
    assert f1_stats.std is None  # No std for a single value


def test_aggregate_evaluation_scores__zero_and_negative_values():
    """Test aggregation with zero and negative score values."""
    test_results_list = []
    values = [-0.5, 0.0, 0.3, -0.2, 0.1]

    for trial_id, value in enumerate(values, 1):
        score = score_result.ScoreResult(
            name="custom_metric", value=value, reason="Test"
        )
        test_case_obj = test_case.TestCase(
            trace_id=f"trace{trial_id}",
            dataset_item_id=f"item{trial_id}",
            mapped_scoring_inputs={"input": "test"},
            task_output={"output": f"result{trial_id}"},
        )
        test_result_obj = test_result.TestResult(
            test_case=test_case_obj, score_results=[score], trial_id=trial_id
        )
        test_results_list.append(test_result_obj)

    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="Zero/Negative Test",
        test_results=test_results_list,
        experiment_url="http://test.comet.com",
        trial_count=5,
    )

    # Test aggregation
    aggregated_view = eval_result.aggregate_evaluation_scores()

    # Verify aggregation handles negative and zero values correctly
    assert len(aggregated_view.aggregated_scores) == 1
    custom_stats = aggregated_view.aggregated_scores["custom_metric"]

    expected_mean = sum(values) / len(values)  # -0.06
    assert custom_stats.mean == pytest.approx(expected_mean, rel=1e-9)
    assert custom_stats.max == 0.3
    assert custom_stats.min == -0.5
    assert custom_stats.values == values


def test_aggregate_evaluation_scores__all_scores_filtered_out():
    """Test when all scores are invalid or failed - should result in empty aggregation."""
    failed_score1 = score_result.ScoreResult(
        name="accuracy", value=0.5, scoring_failed=True
    )
    failed_score2 = score_result.ScoreResult(
        name="accuracy", value=0.8, scoring_failed=True
    )
    nan_score = score_result.ScoreResult(
        name="precision", value=float("nan"), scoring_failed=False
    )

    test_case_obj = test_case.TestCase(
        trace_id="trace1",
        dataset_item_id="item1",
        mapped_scoring_inputs={"input": "test"},
        task_output={"output": "result"},
    )

    test_result_obj = test_result.TestResult(
        test_case=test_case_obj,
        score_results=[failed_score1, failed_score2, nan_score],
        trial_id=1,
    )

    eval_result = evaluation_result.EvaluationResult(
        experiment_id="exp1",
        dataset_id="dataset1",
        experiment_name="All Invalid Test",
        test_results=[test_result_obj],
        experiment_url="http://test.comet.com",
        trial_count=1,
    )

    # Test aggregation
    aggregated_view = eval_result.aggregate_evaluation_scores()

    # Should have no aggregated scores since all were filtered out
    assert len(aggregated_view.aggregated_scores) == 0


def test_evaluation_result_on_dict_items__aggregate_evaluation_scores__happyflow():
    """Test EvaluationResultOnDictItems.aggregate_evaluation_scores with multiple items and metrics."""
    # Create test results with multiple metrics
    test_results_list = []

    # Item 1: accuracy=0.8, precision=0.9
    score1_accuracy = score_result.ScoreResult(
        name="accuracy", value=0.8, reason="Good accuracy"
    )
    score1_precision = score_result.ScoreResult(
        name="precision", value=0.9, reason="High precision"
    )
    test_case1 = test_case.TestCase(
        trace_id="trace1",
        dataset_item_id="item1",
        mapped_scoring_inputs={"input": "test1"},
        task_output={"output": "result1"},
    )
    test_result1 = test_result.TestResult(
        test_case=test_case1,
        score_results=[score1_accuracy, score1_precision],
        trial_id=0,
    )
    test_results_list.append(test_result1)

    # Item 2: accuracy=0.9, precision=0.95
    score2_accuracy = score_result.ScoreResult(
        name="accuracy", value=0.9, reason="Excellent accuracy"
    )
    score2_precision = score_result.ScoreResult(
        name="precision", value=0.95, reason="Excellent precision"
    )
    test_case2 = test_case.TestCase(
        trace_id="trace2",
        dataset_item_id="item2",
        mapped_scoring_inputs={"input": "test2"},
        task_output={"output": "result2"},
    )
    test_result2 = test_result.TestResult(
        test_case=test_case2,
        score_results=[score2_accuracy, score2_precision],
        trial_id=0,
    )
    test_results_list.append(test_result2)

    # Item 3: accuracy=0.7 (only one metric)
    score3_accuracy = score_result.ScoreResult(
        name="accuracy", value=0.7, reason="Moderate accuracy"
    )
    test_case3 = test_case.TestCase(
        trace_id="trace3",
        dataset_item_id="item3",
        mapped_scoring_inputs={"input": "test3"},
        task_output={"output": "result3"},
    )
    test_result3 = test_result.TestResult(
        test_case=test_case3,
        score_results=[score3_accuracy],
        trial_id=0,
    )
    test_results_list.append(test_result3)

    eval_result = evaluation_result.EvaluationResultOnDictItems(
        test_results=test_results_list
    )

    # Test aggregation
    aggregated = eval_result.aggregate_evaluation_scores()

    # Verify structure
    assert len(aggregated) == 2  # accuracy and precision
    assert "accuracy" in aggregated
    assert "precision" in aggregated

    # Verify accuracy aggregation (0.8, 0.9, 0.7)
    accuracy_stats = aggregated["accuracy"]
    assert accuracy_stats.mean == pytest.approx(0.8, rel=1e-9)  # (0.8 + 0.9 + 0.7) / 3
    assert accuracy_stats.max == pytest.approx(0.9, rel=1e-9)
    assert accuracy_stats.min == pytest.approx(0.7, rel=1e-9)
    assert accuracy_stats.values == [0.8, 0.9, 0.7]
    assert accuracy_stats.std == pytest.approx(0.1, rel=1e-1)

    # Verify precision aggregation (0.9, 0.95)
    precision_stats = aggregated["precision"]
    assert precision_stats.mean == pytest.approx(0.925, rel=1e-9)  # (0.9 + 0.95) / 2
    assert precision_stats.max == pytest.approx(0.95, rel=1e-9)
    assert precision_stats.min == pytest.approx(0.9, rel=1e-9)
    assert precision_stats.values == [0.9, 0.95]
    assert precision_stats.std == pytest.approx(
        0.03536, rel=1e-2
    )  # Standard deviation of [0.9, 0.95]


def test_evaluation_result_on_dict_items__aggregate_evaluation_scores__empty_results():
    """Test EvaluationResultOnDictItems.aggregate_evaluation_scores with empty test results."""
    eval_result = evaluation_result.EvaluationResultOnDictItems(test_results=[])

    # Test aggregation
    aggregated = eval_result.aggregate_evaluation_scores()

    # Should have no aggregated scores
    assert len(aggregated) == 0


def test_evaluation_result_on_dict_items__aggregate_evaluation_scores__single_item():
    """Test EvaluationResultOnDictItems.aggregate_evaluation_scores with single item."""
    # Create test result with single metric
    score = score_result.ScoreResult(name="f1_score", value=0.85, reason="Good F1")
    test_case_obj = test_case.TestCase(
        trace_id="trace1",
        dataset_item_id="item1",
        mapped_scoring_inputs={"input": "test"},
        task_output={"output": "result"},
    )
    test_result_obj = test_result.TestResult(
        test_case=test_case_obj,
        score_results=[score],
        trial_id=0,
    )

    eval_result = evaluation_result.EvaluationResultOnDictItems(
        test_results=[test_result_obj]
    )

    # Test aggregation
    aggregated = eval_result.aggregate_evaluation_scores()

    # Verify structure
    assert len(aggregated) == 1
    assert "f1_score" in aggregated

    # Verify statistics for single value
    f1_stats = aggregated["f1_score"]
    assert f1_stats.mean == 0.85
    assert f1_stats.max == 0.85
    assert f1_stats.min == 0.85
    assert f1_stats.values == [0.85]
    assert f1_stats.std is None  # std is None for single value
