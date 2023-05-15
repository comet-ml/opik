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

from . import converter, experiment_api
from .types import JSONEncodable

ASSET_FORMAT_VERSION = 1


def log_prompt(
    prompt: JSONEncodable,
    outputs: JSONEncodable,
    workspace: Optional[str] = None,
    project: Optional[str] = None,
    api_key: Optional[str] = None,
    prompt_template: Optional[JSONEncodable] = None,
    prompt_template_variables: Optional[Dict[str, Any]] = None,
    metadata: Optional[Dict[str, Any]] = None,
    start_timestamp: Optional[int] = None,
    end_timestamp: Optional[int] = None,
    duration: Optional[int] = None,
):
    experiment_api_ = experiment_api.ExperimentAPI(
        api_key=api_key, workspace=workspace, project_name=project
    )

    call_data = converter.call_data_to_dict(
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
        "chain_context": {},
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

    experiment_api_.log_asset(
        file_name="prompt_call.json", file_data=io.StringIO(json.dumps(asset_data))
    )

    parameters = _prepare_parameters(metadata, start_timestamp, end_timestamp, duration)
    for name, value in parameters.items():
        experiment_api_.log_parameter(name, value)


def _prepare_parameters(
    metadata, start_timestamp, end_timestamp, duration
) -> Dict[str, Any]:
    timestamp_parameters = {
        "start_timestamp": start_timestamp,
        "end_timestamp": end_timestamp,
        "duration": duration,
    }
    metadata_parameters = flatten_dict.flatten(metadata, reducer="dot")
    return {**timestamp_parameters, **metadata_parameters}
