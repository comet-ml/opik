import threading
from typing import Dict, Any, List
from unittest import mock

import opik
from opik.api_objects import rest_stream_parser
from opik.evaluation.engine import engine
from opik.evaluation.metrics import score_result
from opik.types import FeedbackScoreDict


# Simple dataset items for testing
DATASET_ITEMS = [
    {
        "input": {"question": f"Question {i}"},
        "expected_output": {"answer": f"Answer {i}"},
    }
    for i in range(20)  # Create 20 items to test streaming behavior
]

# Batch size for streaming - must be less than len(DATASET_ITEMS) to test multi-batch streaming
TEST_BATCH_SIZE = 5


def simple_task(item: Dict[str, Any]) -> Dict[str, Any]:
    """Simple task that echoes the expected output."""
    return item["expected_output"]


def simple_scoring_function(
    dataset_item: Dict[str, Any], task_outputs: Dict[str, Any]
) -> score_result.ScoreResult:
    """Simple scoring function that always returns 1.0."""
    return score_result.ScoreResult(
        name="simple_score",
        value=1.0,
        reason="Test score",
    )


def test_streaming_starts_evaluation_before_complete_download(
    opik_client: opik.Opik, dataset_name: str, experiment_name: str
):
    """
    Test that streaming evaluation starts processing items before all items are downloaded.

    This test verifies the core streaming behavior by:
    1. Creating a dataset with multiple items
    2. Setting a small batch size to force multiple batches
    3. Patching read_and_parse_stream to track when items are yielded
    4. Patching the task execution to track when tasks start
    5. Verifying that the first task starts before the last item is yielded
    """
    # Create dataset with multiple items
    dataset = opik_client.create_dataset(dataset_name)
    dataset.insert(DATASET_ITEMS)

    # Track the sequence of events: 'yield' or 'task'
    events: List[str] = []
    events_lock = threading.Lock()

    # Store original read_and_parse_stream function
    original_read_and_parse_stream = rest_stream_parser.read_and_parse_stream

    def tracked_read_and_parse_stream(stream, item_class, nb_samples=None):
        """Wrapper that tracks when items are yielded from the stream."""
        items = original_read_and_parse_stream(stream, item_class, nb_samples)
        tracked_items = []
        for item in items:
            with events_lock:
                events.append("yield")
            tracked_items.append(item)
        return tracked_items

    def tracked_task(item: Dict[str, Any]) -> Dict[str, Any]:
        """Wrapper that tracks when tasks start executing."""
        with events_lock:
            events.append("task")
        return simple_task(item)

    # Patch both read_and_parse_stream to track yields and EVALUATION_STREAM_DATASET_BATCH_SIZE
    # to ensure we stream in multiple batches
    with (
        mock.patch.object(
            rest_stream_parser,
            "read_and_parse_stream",
            side_effect=tracked_read_and_parse_stream,
        ),
        mock.patch.object(
            engine, "EVALUATION_STREAM_DATASET_BATCH_SIZE", TEST_BATCH_SIZE
        ),
    ):
        # Run evaluation with streaming
        evaluation_result = opik.evaluate(
            dataset=dataset,
            task=tracked_task,
            scoring_functions=[simple_scoring_function],
            experiment_name=experiment_name,
            verbose=1,
        )

        opik.flush_tracker()

    # Verify evaluation completed successfully
    assert evaluation_result.dataset_id == dataset.id
    assert len(events) == len(DATASET_ITEMS) * 2  # Each item has yield + task

    # Count yields and tasks
    yield_count = events.count("yield")
    task_count = events.count("task")
    assert yield_count == len(DATASET_ITEMS)
    assert task_count == len(DATASET_ITEMS)

    # Critical assertion: Tasks should be interleaved with yields
    # This proves streaming is working - we don't wait for all yields before starting tasks
    # Find the index of the last yield
    last_yield_index = len(events) - 1 - events[::-1].index("yield")
    # Find the index of the first task
    first_task_index = events.index("task")

    assert first_task_index < last_yield_index, (
        f"Streaming not working! First task appeared at index {first_task_index}, "
        f"but last yield appeared at index {last_yield_index}. "
        f"Tasks should start before all items are yielded. "
        f"Event sequence: {events}"
    )

    # Verify experiment was created correctly
    retrieved_experiment = opik_client.get_experiment_by_id(
        evaluation_result.experiment_id
    )
    experiment_items = retrieved_experiment.get_items()
    assert len(experiment_items) == len(DATASET_ITEMS)

    # Verify scoring output: each item should have a score with name "simple_score" and value 1.0
    for item in experiment_items:
        # Check that feedback_scores exists and is not empty
        assert (
            item.feedback_scores is not None and len(item.feedback_scores) == 1
        ), f"Experiment item {item.id} should have exactly 1 feedback score, got {len(item.feedback_scores) if item.feedback_scores else 0}"

        # Verify the score matches expected structure
        expected_score = FeedbackScoreDict(
            category_name=None,
            name="simple_score",
            reason="Test score",
            value=1.0,
        )

        actual_score = item.feedback_scores[0]
        assert actual_score == expected_score, (
            f"Experiment item {item.id} has incorrect feedback score. "
            f"Expected: {expected_score}, Got: {actual_score}"
        )
