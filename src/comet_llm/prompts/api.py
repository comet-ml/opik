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
#  This source code is licensed under the MIT license found in the
#  LICENSE file in the root directory of this package.
# *******************************************************

import io
import json
from typing import Dict, List, Optional, Union

import comet_llm.convert

from .. import (
    app,
    config,
    exceptions,
    experiment_api,
    experiment_info,
    llm_result,
    logging_messages,
)
from ..chains import version
from . import convert, preprocess


@exceptions.filter(allow_raising=config.raising_enabled(), summary=app.SUMMARY)
def log_prompt(
    prompt: str,
    output: str,
    workspace: Optional[str] = None,
    project: Optional[str] = None,
    tags: Optional[List[str]] = None,
    api_key: Optional[str] = None,
    prompt_template: Optional[str] = None,
    prompt_template_variables: Optional[
        Dict[str, Union[str, bool, float, None]]
    ] = None,
    metadata: Optional[Dict[str, Union[str, bool, float, None]]] = None,
    timestamp: Optional[float] = None,
    duration: Optional[float] = None,
) -> llm_result.LLMResult:
    """
    Logs a single prompt and output to Comet platform.

    Args:
        prompt: str (required) input prompt to LLM.
        output: str (required), output from LLM.
        workspace: str (optional) comet workspace to use for logging.
        project: str (optional) project name to create in comet workspace.
        tags: List[str] (optional), user-defined tags attached to a prompt call.
        api_key: str (optional) comet API key.
        prompt_template: str (optional) user-defined template used for creating a prompt.
        prompt_template_variables: Dict[str, str] (optional) dictionary with data used
            in prompt_template to build a prompt.
        metadata: Dict[str, Union[str, bool, float, None]] (optional) user-defined
            dictionary with additional metadata to the call.
        timestamp: float (optional) timestamp of prompt call in seconds
        duration: float (optional) duration of prompt call

    Example:

    ```python
    log_prompt(
        prompt="Answer the question and if the question can't be answered, say \"I don't know\"\n\n---\n\nQuestion: What is your name?\nAnswer:",
        metadata={
            "input.type": "completions",
            "input.model": "text-davinci-003",
            "input.provider": "openai",
            "output.index": 0,
            "output.logprobs": None,
            "output.finish_reason": "length",
            "usage.prompt_tokens": 5,
            "usage.completion_tokens": 7,
            "usage.total_tokens": 12,
        },
        prompt_template="Answer the question and if the question can't be answered, say \"I don't know\"\n\n---\n\nQuestion: {{question}}?\nAnswer:",
        prompt_template_variables={"question": "What is your name?"},
        output=" My name is [your name].",
        duration=16.598,
    )

    ```

    Returns: LLMResult.
    """

    timestamp = preprocess.timestamp(timestamp)

    info = experiment_info.get(
        api_key,
        workspace,
        project,
        api_key_not_found_message=logging_messages.API_KEY_NOT_FOUND_MESSAGE
        % "log_prompt",
    )
    experiment_api_ = experiment_api.ExperimentAPI.create_new(
        api_key=info.api_key, workspace=info.workspace, project_name=info.project_name
    )

    call_data = convert.call_data_to_dict(
        prompt=prompt,
        outputs=output,
        metadata=metadata,
        prompt_template=prompt_template,
        prompt_template_variables=prompt_template_variables,
        start_timestamp=timestamp,
        end_timestamp=timestamp,
        duration=duration,
    )

    asset_data = {
        "version": version.ASSET_FORMAT_VERSION,
        "chain_nodes": [call_data],
        "chain_inputs": {
            "final_prompt": prompt,
            "prompt_template": prompt_template,
            "prompt_template_variables": prompt_template_variables,
        },
        "chain_outputs": {"output": output},
        "category": "single_prompt",
        "metadata": metadata,
        "start_timestamp": timestamp,
        "end_timestamp": timestamp,
        "chain_duration": duration,
    }

    experiment_api_.log_asset_with_io(
        name="comet_llm_data.json",
        file=io.StringIO(json.dumps(asset_data)),
        asset_type="llm_data",
    )

    if tags is not None:
        experiment_api_.log_tags(tags)

    if duration is not None:
        experiment_api_.log_metric("chain_duration", duration)

    parameters = comet_llm.convert.chain_metadata_to_flat_parameters(metadata)

    for name, value in parameters.items():
        experiment_api_.log_parameter(name, value)

    app.SUMMARY.add_log(experiment_api_.project_url, "prompt")

    return llm_result.LLMResult(
        id=experiment_api_.id, project_url=experiment_api_.project_url
    )
