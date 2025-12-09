import copy
import logging
from typing import Any, Dict, List, Mapping, Optional, Tuple

from opik import id_helpers
import opik.jsonable_encoder as jsonable_encoder

from ..prompt import base_prompt


LOGGER = logging.getLogger(__name__)

PromptVersion = Dict[str, str]


def build_metadata_and_prompt_versions(
    experiment_config: Optional[Dict[str, Any]],
    prompts: Optional[List[base_prompt.BasePrompt]],
) -> Tuple[Optional[Dict[str, Any]], Optional[List[PromptVersion]]]:
    prompt_versions: Optional[List[PromptVersion]] = None

    if experiment_config is None:
        experiment_config = {}
    else:
        experiment_config = copy.deepcopy(experiment_config)

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
        experiment_config["prompts"] = {}

        for prompt_obj in prompts:
            prompt_versions.append({"id": prompt_obj.__internal_api__version_id__})
            # Use __internal_api__to_info_dict__() to get the prompt content in a consistent way
            prompt_info = prompt_obj.__internal_api__to_info_dict__()
            # Extract the template/messages from the version dict
            if "version" in prompt_info:
                if "template" in prompt_info["version"]:
                    experiment_config["prompts"][prompt_obj.name] = prompt_info[
                        "version"
                    ]["template"]
                elif "messages" in prompt_info["version"]:
                    experiment_config["prompts"][prompt_obj.name] = prompt_info[
                        "version"
                    ]["messages"]

    if experiment_config == {}:
        return None, None

    metadata = jsonable_encoder.encode(experiment_config)

    return metadata, prompt_versions


def handle_prompt_args(
    prompt: Optional[base_prompt.BasePrompt] = None,
    prompts: Optional[List[base_prompt.BasePrompt]] = None,
) -> Optional[List[base_prompt.BasePrompt]]:
    if prompts is not None and len(prompts) > 0 and prompt is not None:
        LOGGER.warning(
            "Arguments `prompt` and `prompts` are mutually exclusive, `prompts` will be used`."
        )
    elif prompt is not None:
        prompts = [prompt]
    elif prompts is not None and len(prompts) == 0:
        prompts = None

    return prompts


def generate_unique_experiment_name(experiment_name_prefix: Optional[str]) -> str:
    if experiment_name_prefix is None:
        return id_helpers.generate_random_alphanumeric_string(12)

    return (
        f"{experiment_name_prefix}-{id_helpers.generate_random_alphanumeric_string(6)}"
    )
