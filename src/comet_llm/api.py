# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at https://www.comet.com
#  Copyright (C) 2015-2023 Comet ML INC
#  This file can not be copied and/or distributed without the express
#  permission of Comet ML Inc.
# *******************************************************

import io
import json
from typing import Any, Dict, Optional

import flatten_dict

from . import convert, experiment_api, experiment_info
from .types import JSONEncodable

ASSET_FORMAT_VERSION = 1


def log_prompt(
    prompt: JSONEncodable,
    outputs: JSONEncodable,
    workspace: Optional[str] = None,
    project: Optional[str] = None,
    api_key: Optional[str] = None,
    prompt_template: Optional[JSONEncodable] = None,
    prompt_template_variables: Optional[Dict[str, JSONEncodable]] = None,
    metadata: Optional[Dict[str, JSONEncodable]] = None,
    start_timestamp: Optional[float] = None,
    end_timestamp: Optional[float] = None,
    duration: Optional[float] = None,
) -> None:
    """
    Returns a Comet API Key Secret that can be used instead of a clear-text API Key when creating an
    Experiment or API object. The Comet API Key Secret is a string that represents the location of
    the secret in the GCP Secret Manager without containing the API Key. This means
    `get_api_key_from_secret_manager` doesn't need permission or access to GCP Secret Manager.

    Args:
        prompt: JSONEncodable (required) input prompt to LLM.
        outputs: JSONEncodable (required), outputs from LLM.
        workspace: str (optional) comet workspace to use for logging.
        project: str (optional) project name to create in comet workspace.
        api_key: str (optional) comet API key.
        prompt_template: JSONEncodable (optional) user-defined template used for creating a prompt.
        prompt_template_variables: Dict[str, JSONEncodable] (optional) dictionary with data used
            in prompt_template to build a prompt.
        metadata: Dict[str, JSONEncodable] (optional) user-defined dictionary with additional
            metadata to the call.
        start_timestamp: float (optional) start timestamp of prompt call
        end_timestamp: float (optional) end timestamp of prompt call
        duration: float (optional) duration of prompt call
    Example:

    ```python
    log_prompt(
        prompt="Answer the question and if the question can't be answered, say \"I don't know\"\n\n---\n\nQuestion: What is your name?\nAnswer:",
        metadata={
            "prompt": {
                "model": "text-davinci-003",
                "provider": "openai",
                "temperature": 0.95,
                "presence_penalty": -1,
            },
            "output": {
                "id": "cmpl-76ehKGbEMC0BIwTPH0m1W4YoY3oPV",
                "object": "text-completion",
                "index": 0,
                "logprobs": None,
                "finish_reason": "stop",
                "usage": {"prompt_tokens": 34, "completion_tokens": 7, "total_tokens": 41},
            },
        },
        prompt_template="Answer the question and if the question can't be answered, say \"I don't know\"\n\n---\n\nQuestion: {{question}}?\nAnswer:",
        prompt_template_variables={"question": "What is your name?"},
        outputs=" My name is [your name].",
        duration=16.598,
    )

    ```

    Returns: None.
    """
    LOG_PROMPT_API_KEY_NOT_FOUND_MESSAGE = """
    CometLLM requires an API key. Please provide it as the
    api_key argument to log_prompt or as an environment
    variable named COMET_API_KEY
    """
    info = experiment_info.get(
        api_key,
        workspace,
        project,
        api_key_not_found_message=LOG_PROMPT_API_KEY_NOT_FOUND_MESSAGE,
    )
    experiment_api_ = experiment_api.ExperimentAPI(
        api_key=info.api_key, workspace=info.workspace, project_name=info.project_name
    )

    call_data = convert.call_data_to_dict(
        id=0,
        prompt=prompt,
        outputs=outputs,
        metadata=metadata,
        prompt_template=prompt_template,
        prompt_template_variables=prompt_template_variables,
        start_timestamp=start_timestamp,
        end_timestamp=end_timestamp,
        duration=duration,
    )

    asset_data = {
        "_version": ASSET_FORMAT_VERSION,
        "chain_nodes": [call_data],
        "chain_edges": [],
        "chain_context": {"parent_context_id": {}},
        "chain_inputs": {
            "final_prompt": prompt,
            "prompt_template": prompt_template,
            "prompt_template_variables": prompt_template_variables,
        },
        "chain_outputs": {"output": outputs},
        "metadata": {},
        "start_timestamp": start_timestamp,
        "end_timestamp": end_timestamp,
        "duration": duration,
    }

    experiment_api_.log_asset_with_io(
        name="comet_llm_data.json", file=io.StringIO(json.dumps(asset_data))
    )

    parameters = _prepare_parameters(metadata, start_timestamp, end_timestamp, duration)
    for name, value in parameters.items():
        experiment_api_.log_parameter(name, value)


def _prepare_parameters(
    metadata: Optional[Dict[str, Any]],
    start_timestamp: Optional[float],
    end_timestamp: Optional[float],
    duration: Optional[float],
) -> Dict[str, Any]:

    timestamp_parameters = {
        "start_timestamp": start_timestamp,
        "end_timestamp": end_timestamp,
        "duration": duration,
    }
    metadata_parameters = (
        flatten_dict.flatten(metadata, reducer="dot") if metadata is not None else {}
    )

    result = {
        key: value
        for key, value in {**timestamp_parameters, **metadata_parameters}.items()
        if value is not None
    }

    return result
