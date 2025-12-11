import logging
from typing import List, Optional

from opik.api_objects import dataset, experiment, opik_client
from opik.types import FeedbackScoreDict
from . import test_case
from .metrics import score_result
from .types import ScoringKeyMappingType

LOGGER = logging.getLogger(__name__)


def get_experiment_with_unique_name(
    client: opik_client.Opik, experiment_name: str
) -> experiment.Experiment:
    experiments = client.get_experiments_by_name(name=experiment_name)

    if len(experiments) == 0:
        raise ValueError(f"Experiment with name {experiment_name} not found")
    elif len(experiments) > 1:
        raise ValueError(
            f"Found multiple experiments with name {experiment_name}. Try to use `experiment_id` instead"
        )

    experiment_ = experiments[0]
    return experiment_


def get_trace_project_name(client: opik_client.Opik, trace_id: str) -> str:
    # We first need to get the project_id for the trace
    traces = client.get_trace_content(id=trace_id)
    project_id = traces.project_id

    # Then we can get the project name
    project_metadata = client.get_project(id=project_id)
    return project_metadata.name


def get_experiment_test_cases(
    experiment_: experiment.Experiment,
    dataset_: dataset.Dataset,
    scoring_key_mapping: Optional[ScoringKeyMappingType],
) -> List[test_case.TestCase]:
    experiment_items = experiment_.get_items()

    # Fetch dataset items to get input data for bulk-uploaded experiment items
    dataset_items_by_id = {item["id"]: item for item in dataset_.get_items()}

    test_cases = []
    for item in experiment_items:
        dataset_item_data = dataset_items_by_id.get(item.dataset_item_id)

        if dataset_item_data is None:
            LOGGER.error(
                f"Unexpected error: Dataset item with id {item.dataset_item_id} not found, skipping experiment item {item.id}"
            )
            continue

        if item.evaluation_task_output is None:
            LOGGER.error(
                f"Unexpected error: Evaluation task output is None for experiment item {item.id}, skipping experiment item"
            )
            continue

        test_cases.append(
            test_case.TestCase(
                trace_id=item.trace_id,
                dataset_item_id=item.dataset_item_id,
                task_output=item.evaluation_task_output,
                dataset_item_content=dataset_item_data,
            )
        )

    return test_cases


def log_test_result_feedback_scores(
    client: opik_client.Opik,
    score_results: List[score_result.ScoreResult],
    trace_id: str,
    project_name: Optional[str],
) -> None:
    all_trace_scores: List[FeedbackScoreDict] = []

    for score_result_ in score_results:
        if score_result_.scoring_failed:
            continue

        trace_score = FeedbackScoreDict(
            id=trace_id,
            name=score_result_.name,
            value=score_result_.value,
            reason=score_result_.reason,
        )
        all_trace_scores.append(trace_score)

    if len(all_trace_scores) > 0:
        client.log_traces_feedback_scores(
            scores=all_trace_scores, project_name=project_name
        )
