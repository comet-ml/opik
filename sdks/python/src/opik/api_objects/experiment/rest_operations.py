from typing import List, Optional

import opik.exceptions as exceptions
from .. import rest_stream_parser
from opik.rest_api import OpikApi
from opik.rest_api.types import experiment_public


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

    raise exceptions.ExperimentNotFound(f"No experiment found with the name '{name}'.")


def get_experiments_data_by_name(
    rest_client: OpikApi,
    name: str,
    max_results: Optional[int] = None,
) -> List[experiment_public.ExperimentPublic]:
    experiments = rest_stream_parser.read_and_parse_full_stream(
        read_source=lambda current_batch_size,
        last_retrieved_id: rest_client.experiments.stream_experiments(
            name=name,
            limit=current_batch_size,
            last_retrieved_id=last_retrieved_id,
        ),
        max_results=max_results,
        parsed_item_class=experiment_public.ExperimentPublic,
    )

    if len(experiments) == 0:
        raise exceptions.ExperimentNotFound(
            f"No experiment(s) found with the name '{name}'."
        )

    return experiments
