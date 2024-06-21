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

from typing import Dict, List, Optional, Union

from .. import app, config, exceptions, experiment_info, llm_result, logging_messages
from ..chains import version
from ..message_processing import api as message_processing_api, messages
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
) -> Optional[llm_result.LLMResult]:
    """
    Logs a single prompt and output to Comet platform.

    Args:
        prompt: Input prompt to LLM.
        output: Output from LLM.
        workspace: Comet workspace to use for logging.
        project: Project name to create in comet workspace.
        tags: User-defined tags attached to a prompt call.
        api_key: Comet API key.
        prompt_template: User-defined template used for creating a prompt.
        prompt_template_variables: Dictionary with data used
            in prompt_template to build a prompt.
        metadata: User-defined
            dictionary with additional metadata to the call.
        timestamp: Timestamp of prompt call in seconds
        duration: Duration of prompt call

    Example:
        ```python linenums="1"
        log_prompt(
            prompt='Answer the question and if the question can't be answered, say "I do not know"\\n\\n---\\n\\nQuestion: What is your name?\\nAnswer:',
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
            prompt_template='Answer the question and if the question can't be answered, say "I do not know"\\n\\n---\\n\\nQuestion: {{question}}?\\nAnswer:',
            prompt_template_variables={"question": "What is your name?"},
            output=" My name is [your name].",
            duration=16.598,
        )
        ```

    Returns: LLMResult.
    """

    timestamp = preprocess.timestamp(timestamp)
    MESSAGE = (
        None
        if config.offline_enabled()
        else (logging_messages.API_KEY_NOT_FOUND_MESSAGE % "log_prompt")
    )

    info = experiment_info.get(
        api_key, workspace, project, api_key_not_found_message=MESSAGE
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

    message = messages.PromptMessage(
        id=messages.generate_id(),
        experiment_info_=info,
        prompt_asset_data=asset_data,
        duration=duration,
        metadata=metadata,
        tags=tags,
    )

    result = message_processing_api.MESSAGE_PROCESSOR.process(message)

    if result is not None:
        app.SUMMARY.add_log(result.project_url, "prompt")

    return result
