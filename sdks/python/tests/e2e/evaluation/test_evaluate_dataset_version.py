from typing import Dict, Any

import opik
from opik import synchronization
from opik.evaluation.metrics import score_result

from .. import verifiers


def _wait_for_version(dataset, expected_version: str, timeout: float = 10) -> None:
    """Wait for dataset to have the expected version, fail if not reached."""
    success = synchronization.until(
        lambda: dataset.get_current_version_name() == expected_version,
        max_try_seconds=timeout,
    )
    assert success, f"Expected version '{expected_version}' was not created in time"


def test_evaluate__with_dataset_version__evaluates_version_items_only__happyflow(
    opik_client: opik.Opik, dataset_name: str
):
    """Test that opik.evaluate works with DatasetVersion and only evaluates items from that version."""
    dataset = opik_client.create_dataset(dataset_name)

    # Insert first batch - creates v1 with 2 items
    dataset.insert(
        [
            {"input": {"question": "Q1"}, "expected_output": {"answer": "A1"}},
            {"input": {"question": "Q2"}, "expected_output": {"answer": "A2"}},
        ]
    )
    _wait_for_version(dataset, "v1")

    # Insert second batch - creates v2 with 4 items total
    dataset.insert(
        [
            {"input": {"question": "Q3"}, "expected_output": {"answer": "A3"}},
            {"input": {"question": "Q4"}, "expected_output": {"answer": "A4"}},
        ]
    )
    _wait_for_version(dataset, "v2")

    # Get v1 view - should have only 2 items
    v1_view = dataset.get_version_view("v1")
    assert v1_view.items_total == 2

    # Simple task that returns the expected output
    def task(item: Dict[str, Any]) -> Dict[str, Any]:
        return item["expected_output"]

    # Simple scoring function
    def scoring_function(
        dataset_item: Dict[str, Any], task_outputs: Dict[str, Any]
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(name="test_score", value=1.0)

    # Evaluate using DatasetVersion (v1)
    result = opik.evaluate(
        dataset=v1_view,
        task=task,
        scoring_functions=[scoring_function],
        verbose=0,
    )

    opik.flush_tracker()

    # Should have evaluated only 2 items (from v1), not 4 (from v2/current)
    assert len(result.test_results) == 2

    # Verify the items evaluated were from v1
    evaluated_questions = {
        tr.test_case.dataset_item_content["input"]["question"]
        for tr in result.test_results
    }
    assert evaluated_questions == {"Q1", "Q2"}

    # Verify the experiment is linked to v1's version ID
    v1_version_info = v1_view.get_version_info()
    verifiers.verify_experiment(
        opik_client=opik_client,
        id=result.experiment_id,
        experiment_name=result.experiment_name,
        experiment_metadata=None,
        traces_amount=2,
        feedback_scores_amount=1,
        dataset_version_id=v1_version_info.id,
    )
