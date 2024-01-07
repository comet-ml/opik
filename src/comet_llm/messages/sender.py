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

from . import messages
from .. import experiment_api, convert, version

class MessageSender:
    def __init__(self) -> None:
        pass

    def send(self, message: messages.BaseMessage):
        pass

    def _send_prompt(self, message: messages.PromptMessage):
        experiment_api_ = experiment_api.ExperimentAPI.create_new(
            api_key=message.experiment_information.api_key,
            workspace=message.experiment_information.workspace, 
            project_name=message.experiment_information.api_key,
        )

        call_data = convert.call_data_to_dict(
            prompt=message.prompt,
            outputs=message.output,
            metadata=message.metadata,
            prompt_template=message.prompt_template,
            prompt_template_variables=message.prompt_template_variables,
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