from opik.rest_api import OpikApi
from opik.rest_api.types import experiment_public
from opik import exceptions


def get_experiment_data_by_name(
    rest_client: OpikApi, name: str
) -> experiment_public.ExperimentPublic:
    page = 0

    while True:
        page += 1
        experiment_page_public = rest_client.experiments.find_experiments(name=name)
        if len(experiment_page_public.content) == 0:
            raise exceptions.ExperimentNotFound(
                f"Experiment with the name {name} not found."
            )

        for experiment in experiment_page_public.content:
            if experiment.name == name:
                return experiment
