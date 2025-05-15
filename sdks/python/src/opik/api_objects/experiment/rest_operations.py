from typing import List

from ... import exceptions
from .. import rest_stream_parser
from ...rest_api import OpikApi
from ...rest_api.types import experiment_public


def get_experiment_data_by_name(
    rest_client: OpikApi, name: str
) -> experiment_public.ExperimentPublic:
    # TODO: this method is deprecated and should be removed after
    #  deprecated Opik.get_experiment_by_name() will be removed.
    #  This function should not be used anywhere else except for deprecated logic as it is confusing and misleading

    experiments = get_experiments_data_by_name(rest_client, name)
    for experiment in experiments:
        if experiment.name == name:
            return experiment

    raise exceptions.ExperimentNotFound(
        f"Experiment with the name '{name}' is not found."
    )


def get_experiments_data_by_name(
    rest_client: OpikApi, name: str
) -> List[experiment_public.ExperimentPublic]:
    experiments_stream = rest_client.experiments.stream_experiments(name=name)
    experiments = rest_stream_parser.read_and_parse_stream(
        stream=experiments_stream, item_class=experiment_public.ExperimentPublic
    )

    if len(experiments) == 0:
        raise exceptions.ExperimentNotFound(
            f"Experiment(s) with the name '{name}' is not found."
        )

    return experiments
