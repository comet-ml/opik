from typing import List

from opik import exceptions
from opik.rest_api import OpikApi
from opik.rest_api.types import experiment_public


def get_experiment_data_by_name(
    rest_client: OpikApi, name: str
) -> experiment_public.ExperimentPublic:
    # TODO: this method is deprecated and should be removed after
    #  deprecated Opik.get_experiment_by_name() will be removed.
    #  This function should not be used anywhere else except for deprecated logic as it is confusing and misleading

    while True:
        experiment_page_public = rest_client.experiments.find_experiments(name=name)
        if len(experiment_page_public.content) == 0:
            raise exceptions.ExperimentNotFound(
                f"Experiment with the name {name} not found."
            )

        for experiment in experiment_page_public.content:
            if experiment.name == name:
                return experiment


def get_experiments_data_by_name(
    rest_client: OpikApi, name: str
) -> List[experiment_public.ExperimentPublic]:
    experiment_page_public = rest_client.experiments.find_experiments(name=name)
    if len(experiment_page_public.content) == 0:
        raise exceptions.ExperimentNotFound(
            f"Experiment with the name {name} not found."
        )

    return experiment_page_public.content
