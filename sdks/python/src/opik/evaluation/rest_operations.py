from typing import List, Optional

from opik.api_objects import experiment, opik_client
from opik.types import FeedbackScoreDict
from . import test_case, test_result
from .metrics import arguments_helpers
from .types import ScoringKeyMappingType


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
    client: opik_client.Opik,
    experiment_id: str,
    dataset_id: str,
    scoring_key_mapping: Optional[ScoringKeyMappingType],
) -> List[test_case.TestCase]:
    test_cases = []
    page = 1

    while True:
        experiment_items_page = (
            client._rest_client.datasets.find_dataset_items_with_experiment_items(
                id=dataset_id, experiment_ids=f'["{experiment_id}"]', page=page
            )
        )
        if len(experiment_items_page.content) == 0:
            break

        for item in experiment_items_page.content:
            experiment_item = item.experiment_items[0]

            test_cases += [
                test_case.TestCase(
                    trace_id=experiment_item.trace_id,
                    dataset_item_id=experiment_item.dataset_item_id,
                    task_output=experiment_item.output,
                    scoring_inputs=arguments_helpers.create_scoring_inputs(
                        dataset_item=experiment_item.input,
                        task_output=experiment_item.output,
                        scoring_key_mapping=scoring_key_mapping,
                    ),
                )
            ]

        page += 1

    return test_cases


def log_test_result_scores(
    client: opik_client.Opik,
    test_result: test_result.TestResult,
    project_name: Optional[str],
) -> None:
    all_trace_scores: List[FeedbackScoreDict] = []

    for score_result in test_result.score_results:
        if score_result.scoring_failed:
            continue

        trace_score = FeedbackScoreDict(
            id=test_result.test_case.trace_id,
            name=score_result.name,
            value=score_result.value,
            reason=score_result.reason,
        )
        all_trace_scores.append(trace_score)

    client.log_traces_feedback_scores(
        scores=all_trace_scores, project_name=project_name
    )
