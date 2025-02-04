import logging
from typing import Any, Dict, List, Mapping, Optional, Tuple

from opik import exceptions, jsonable_encoder
from opik.rest_api import OpikApi
from opik.rest_api.types import experiment_public
from .. import prompt

LOGGER = logging.getLogger(__name__)

PromptVersion = Dict[str, str]


def build_metadata_and_prompt_versions(
    experiment_config: Optional[Dict[str, Any]],
    prompts: Optional[List[prompt.Prompt]],
) -> Tuple[Optional[Dict[str, Any]], Optional[List[PromptVersion]]]:
    prompt_versions: Optional[List[PromptVersion]] = None

    if experiment_config is None:
        experiment_config = {}

    if not isinstance(experiment_config, Mapping):
        LOGGER.error(
            "Experiment config must be dictionary, but %s was provided. Provided config will be ignored.",
            experiment_config,
        )
        experiment_config = {}

    if prompts is not None and len(prompts) > 0 and "prompts" in experiment_config:
        LOGGER.warning(
            "The `prompts` parameter will not be added to experiment since there is already `prompts` specified in experiment_config"
        )
        return experiment_config, None

    if prompts is not None and len(prompts) > 0:
        prompt_versions = []
        experiment_config["prompts"] = []

        for prompt in prompts:
            prompt_versions.append({"id": prompt.__internal_api__version_id__})
            experiment_config["prompts"].append(prompt.prompt)

    if experiment_config == {}:
        return None, None

    metadata = jsonable_encoder.jsonable_encoder(experiment_config)

    return metadata, prompt_versions


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


def handle_prompt_args(
    prompt: Optional[prompt.Prompt] = None,
    prompts: Optional[List[prompt.Prompt]] = None,
) -> Optional[List[prompt.Prompt]]:
    if prompts is not None and len(prompts) > 0 and prompt is not None:
        LOGGER.warning(
            "Arguments `prompt` and `prompts` are mutually exclusive, `prompts` will be used`."
        )
    elif prompt is not None:
        prompts = [prompt]
    elif prompts is not None and len(prompts) == 0:
        prompts = None

    return prompts
