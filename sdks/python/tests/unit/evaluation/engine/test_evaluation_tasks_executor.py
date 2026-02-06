from unittest.mock import Mock, patch
import pytest
from opik.evaluation.engine.evaluation_tasks_executor import StreamingExecutor
from opik.evaluation.test_result import TestResult
from opik.evaluation.test_case import TestCase
from opik.evaluation.metrics.score_result import ScoreResult


@pytest.fixture
def mock_progress_bar():
    """Create a mock progress bar that tracks method calls."""
    progress_bar = Mock()
    progress_bar.set_postfix = Mock()
    progress_bar.update = Mock()
    progress_bar.close = Mock()
    return progress_bar


@pytest.fixture
def mock_tqdm(mock_progress_bar):
    with patch("opik.evaluation.engine.evaluation_tasks_executor._tqdm") as mock:
        mock.return_value = mock_progress_bar
        yield mock_progress_bar


def test_streaming_executor_displays_running_scores(mock_tqdm):
    """Test that StreamingExecutor displays running score averages in progress bar."""
    executor = StreamingExecutor(
        workers=2, verbose=1, desc="Test Evaluation", total=3
    )

    test_results = [
        TestResult(
            test_case=TestCase(
                trace_id="trace1",
                dataset_item_id="item1",
                task_output={"output": "result1"},
                dataset_item_content={"input": "input1"},
            ),
            score_results=[
                ScoreResult(name="accuracy", value=0.8, scoring_failed=False),
                ScoreResult(name="relevance", value=0.9, scoring_failed=False),
            ],
            trial_id=0,
        ),
        TestResult(
            test_case=TestCase(
                trace_id="trace2",
                dataset_item_id="item2",
                task_output={"output": "result2"},
                dataset_item_content={"input": "input2"},
            ),
            score_results=[
                ScoreResult(name="accuracy", value=0.9, scoring_failed=False),
                ScoreResult(name="relevance", value=0.85, scoring_failed=False),
            ],
            trial_id=0,
        ),
        TestResult(
            test_case=TestCase(
                trace_id="trace3",
                dataset_item_id="item3",
                task_output={"output": "result3"},
                dataset_item_content={"input": "input3"},
            ),
            score_results=[
                ScoreResult(name="accuracy", value=0.7, scoring_failed=False),
                ScoreResult(name="relevance", value=0.95, scoring_failed=False),
            ],
            trial_id=0,
        ),
    ]

    with executor:
        for result in test_results:
            executor.submit(lambda r=result: r)

        results = executor.get_results()

    assert len(results) == 3
    assert mock_tqdm.set_postfix.called
    call_args_list = mock_tqdm.set_postfix.call_args_list

    # Check that postfix was called with running averages
    assert len(call_args_list) == 3

    # First call should have averages for one result
    first_call = call_args_list[0][0][0]
    assert "accuracy" in first_call
    assert "relevance" in first_call

    # Last call should have final averages
    last_call = call_args_list[-1][0][0]
    assert "accuracy" in last_call
    assert "relevance" in last_call


def test_streaming_executor_handles_scoring_failed(mock_tqdm):
    """Test that StreamingExecutor correctly handles scoring_failed=True cases."""
    executor = StreamingExecutor(
        workers=2, verbose=1, desc="Test Evaluation", total=2
    )

    test_results = [
        TestResult(
            test_case=TestCase(
                trace_id="trace1",
                dataset_item_id="item1",
                task_output={"output": "result1"},
                dataset_item_content={"input": "input1"},
            ),
            score_results=[
                ScoreResult(name="accuracy", value=0.8, scoring_failed=False),
                ScoreResult(name="relevance", value=0.0, scoring_failed=True),
            ],
            trial_id=0,
        ),
        TestResult(
            test_case=TestCase(
                trace_id="trace2",
                dataset_item_id="item2",
                task_output={"output": "result2"},
                dataset_item_content={"input": "input2"},
            ),
            score_results=[
                ScoreResult(name="accuracy", value=0.9, scoring_failed=False),
                ScoreResult(name="relevance", value=0.85, scoring_failed=False),
            ],
            trial_id=0,
        ),
    ]

    with executor:
        for result in test_results:
            executor.submit(lambda r=result: r)

        results = executor.get_results()

    assert len(results) == 2
    assert mock_tqdm.set_postfix.called

    # Check that failed scores were excluded from averages
    last_call = mock_tqdm.set_postfix.call_args_list[-1][0][0]
    assert "accuracy" in last_call
    assert "relevance" in last_call
    # Average of 0.85 (only one valid relevance score)
    assert float(last_call["relevance"]) == pytest.approx(0.85, abs=0.01)


def test_streaming_executor_no_scores(mock_tqdm):
    """Test that StreamingExecutor handles results without score_results attribute."""
    executor = StreamingExecutor(
        workers=2, verbose=1, desc="Test Evaluation", total=2
    )

    # Simulate results without score_results attribute
    simple_results = ["result1", "result2"]

    with executor:
        for result in simple_results:
            executor.submit(lambda r=result: r)

        results = executor.get_results()

    assert len(results) == 2
    # set_postfix should not be called when there are no scores
    assert not mock_tqdm.set_postfix.called


def test_streaming_executor_verbose_off(mock_tqdm):
    """Test that StreamingExecutor respects verbose=0."""
    executor = StreamingExecutor(
        workers=2, verbose=0, desc="Test Evaluation", total=2
    )

    test_results = [
        TestResult(
            test_case=TestCase(
                trace_id="trace1",
                dataset_item_id="item1",
                task_output={"output": "result1"},
                dataset_item_content={"input": "input1"},
            ),
            score_results=[
                ScoreResult(name="accuracy", value=0.8, scoring_failed=False),
            ],
            trial_id=0,
        ),
    ]

    with executor:
        for result in test_results:
            executor.submit(lambda r=result: r)

        results = executor.get_results()

    assert len(results) == 1
