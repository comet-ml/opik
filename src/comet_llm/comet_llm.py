# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at https://www.comet.com
#  Copyright (C) 2015-2024 Comet ML INC
#  This source code is licensed under the MIT license found in the
#  LICENSE file in the root directory of this package.
# *******************************************************


from typing import Dict, List, Optional, Union

from . import experiment_info, logging_messages
from .background_processing import messages, streamer
from .chains import version as chains_version
from .prompts import convert as prompts_convert, preprocess as prompts_preprocess


class CometLLM:
    def __init__(self) -> None:
        self._streamer = streamer.get()

    def log_prompt(
        self,
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
    ) -> None:
        timestamp = prompts_preprocess.timestamp(timestamp)

        info = experiment_info.get(
            api_key,
            workspace,
            project,
            api_key_not_found_message=logging_messages.API_KEY_NOT_FOUND_MESSAGE
            % "log_prompt",
        )

        call_data = prompts_convert.call_data_to_dict(
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
            "version": chains_version.ASSET_FORMAT_VERSION,
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
            experiment_information=info,
            prompt_asset_data=asset_data,
            duration=duration,
            metadata=metadata,
            tags=tags,
        )

        self._streamer.put(message)

    def end(self, timeout: float = 10) -> None:
        self._streamer.close(timeout)
