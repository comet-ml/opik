import threading
from typing import Dict, Any, List
from unittest import mock

import opik
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
    2. Patching the streaming generator to track when items are yielded
    3. Patching the task execution to track when tasks start
    4. Verifying that the first task starts before the last item is yielded
    """
    # Create dataset with multiple items
    dataset = opik_client.create_dataset(dataset_name)
    dataset.insert(DATASET_ITEMS)

    # Track the sequence of events: 'yield' or 'task'
    events: List[str] = []
    events_lock = threading.Lock()

    # Store original streaming method
    original_stream_method = (
        dataset.__internal_api__stream_items_as_dataclasses__.__func__
    )

    def tracked_streaming_generator(
        self, nb_samples=None, batch_size=None, dataset_item_ids=None
    ):
        """Wrapper that tracks when items are yielded."""
        for item in original_stream_method(
            self, nb_samples, batch_size, dataset_item_ids
        ):
            with events_lock:
                events.append("yield")
            yield item

    def tracked_task(item: Dict[str, Any]) -> Dict[str, Any]:
        """Wrapper that tracks when tasks start executing."""
        with events_lock:
            events.append("task")
        return simple_task(item)

    # Patch the streaming method to track yields
    with mock.patch.object(
        type(dataset),
        "__internal_api__stream_items_as_dataclasses__",
        tracked_streaming_generator,
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
