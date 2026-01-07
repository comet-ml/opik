import time
from typing import Dict, Any
from unittest import mock

import opik
from opik.evaluation.metrics import score_result


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

    # Track timing of item yields and task executions
    item_yield_times = []
    task_start_times = []

    # Store original streaming method
    original_stream_method = (
        dataset.__internal_api__stream_items_as_dataclasses__.__func__
    )

    def tracked_streaming_generator(self, nb_samples=None):
        """Wrapper that tracks when items are yielded."""
        for item in original_stream_method(self, nb_samples):
            item_yield_times.append(time.time())
            # Add small delay to simulate network latency
            time.sleep(0.05)
            yield item

    def tracked_task(item: Dict[str, Any]) -> Dict[str, Any]:
        """Wrapper that tracks when tasks start executing."""
        task_start_times.append(time.time())
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
    assert len(item_yield_times) == len(DATASET_ITEMS)
    assert len(task_start_times) == len(DATASET_ITEMS)

    # Critical assertion: First task should start before last item is yielded
    # This proves that evaluation is streaming and not waiting for all items
    first_task_start = min(task_start_times)
    last_item_yield = max(item_yield_times)

    assert first_task_start < last_item_yield, (
        f"Streaming not working! First task started at {first_task_start}, "
        f"but last item was yielded at {last_item_yield}. "
        f"Tasks should start before all items are downloaded."
    )

    # Verify experiment was created correctly
    retrieved_experiment = opik_client.get_experiment_by_id(
        evaluation_result.experiment_id
    )
    experiment_items = retrieved_experiment.get_items()
    assert len(experiment_items) == len(DATASET_ITEMS)
