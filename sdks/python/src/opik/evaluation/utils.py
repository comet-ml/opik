from typing import List, Optional, Dict, Union, Any, Callable

from ..api_objects import opik_client
from . import test_case
from ..rest_api.experiments.client import ExperimentPublic
from .metrics import arguments_helpers


def get_experiment_by_name(
    client: opik_client.Opik, experiment_name: str
) -> ExperimentPublic:
    experiments = client._rest_client.experiments.find_experiments(name=experiment_name)

    if len(experiments.content) == 0:
        raise ValueError(f"Experiment with name {experiment_name} not found")
    elif len(experiments.content) > 1:
        raise ValueError(f"Found multiple experiments with name {experiment_name}")

    experiment = experiments.content[0]
    return experiment


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
    scoring_key_mapping: Optional[
        Dict[str, Union[str, Callable[[Dict[str, Any]], Any]]]
    ],
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
