"""
Reconstruct TestResult objects for items completed before a resume call.

``evaluate_resume`` executes only the items that still need work. To present
callers with an ``EvaluationResult`` that matches the full experiment — the
same shape a fresh ``evaluate()`` would have returned — we rebuild
TestResult objects for items already completed by prior runs and merge them
with the freshly-executed slice.

Reconstruction is **read-only**: stored ``evaluation_task_output`` and
``feedback_scores`` are copied from existing experiment items; metrics are
not re-evaluated and no new feedback scores are written to the backend.
"""

import logging
from typing import List

from ...api_objects.dataset import dataset
from ...api_objects.experiment import experiment as experiment_module
from .. import test_case as test_case_module
from .. import test_result as test_result_module
from ..metrics import score_result

LOGGER = logging.getLogger(__name__)


def reconstruct_previous_test_results(
    experiment: experiment_module.Experiment,
    dataset_: dataset.DatasetVersion,
) -> List[test_result_module.TestResult]:
    """
    Build TestResult objects from every run that completed cleanly before
    this resume call.

    Trials of the same item are independent: resume only replays the
    missing runs, so completed runs from partially-finished items are
    reconstructed alongside completed runs from fully-finished items. A
    run counts as "completed cleanly" when its experiment item has a
    non-None ``evaluation_task_output`` (i.e. the engine reached the
    happy-path-only line that writes ``trace.output``).

    Reconstruction is read-only: stored ``evaluation_task_output`` and
    ``feedback_scores`` are copied from existing experiment items; no
    metrics are re-evaluated and no new feedback scores are written.

    Experiment items whose dataset item has since been removed from the
    dataset are skipped with a debug log — same policy as
    ``evaluate_experiment``.
    """
    dataset_items_by_id = {item["id"]: item for item in dataset_.get_items()}
    results: List[test_result_module.TestResult] = []

    for experiment_item_content in experiment.get_items():
        # Direct ``output is None`` check (rather than going through
        # ``is_trial_fully_completed``) so mypy can narrow the type for
        # the ``TestCase(...)`` call below.
        task_output = experiment_item_content.evaluation_task_output
        if task_output is None:
            continue

        dataset_item_data = dataset_items_by_id.get(
            experiment_item_content.dataset_item_id
        )
        if dataset_item_data is None:
            LOGGER.debug(
                "Skipping experiment item %s during resume merge: dataset "
                "item %s is no longer in the dataset",
                experiment_item_content.id,
                experiment_item_content.dataset_item_id,
            )
            continue

        results.append(
            test_result_module.TestResult(
                test_case=test_case_module.TestCase(
                    trace_id=experiment_item_content.trace_id,
                    dataset_item_id=experiment_item_content.dataset_item_id,
                    task_output=task_output,
                    dataset_item_content=dataset_item_data,
                ),
                score_results=[
                    score_result.ScoreResult(
                        name=feedback["name"],
                        value=feedback["value"],
                        reason=feedback.get("reason"),
                        category_name=feedback.get("category_name"),
                    )
                    for feedback in experiment_item_content.feedback_scores
                ],
                trial_id=0,
            )
        )

    return results
