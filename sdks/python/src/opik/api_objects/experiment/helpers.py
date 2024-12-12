from typing import Optional, Dict, Mapping, Tuple, Any
from .. import prompt
import logging
from opik import jsonable_encoder
from opik.rest_api import OpikApi
from opik.rest_api.types import experiment_public
from opik import exceptions

LOGGER = logging.getLogger(__name__)


def build_metadata_and_prompt_version(
    experiment_config: Optional[Dict[str, Any]], prompt: Optional[prompt.Prompt]
) -> Tuple[Optional[Dict[str, Any]], Optional[Dict[str, str]]]:
    metadata = None
    prompt_version: Optional[Dict[str, str]] = None

    if experiment_config is None:
        experiment_config = {}

    if not isinstance(experiment_config, Mapping):
        LOGGER.error(
            "Experiment config must be dictionary, but %s was provided. Provided config will be ignored.",
            experiment_config,
        )
        experiment_config = {}

    if prompt is not None and "prompt" in experiment_config:
        LOGGER.warning(
            "The prompt parameter will not be added to experiment since there is already `prompt` specified in experiment_config"
        )
        return (experiment_config, None)

    if prompt is not None:
        prompt_version = {"id": prompt.__internal_api__version_id__}
        experiment_config["prompt"] = prompt.prompt

    if experiment_config == {}:
        return None, None

    metadata = jsonable_encoder.jsonable_encoder(experiment_config)

    return metadata, prompt_version


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
