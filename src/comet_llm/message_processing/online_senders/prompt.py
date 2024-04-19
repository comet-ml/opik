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

from comet_llm import app, convert, experiment_api, llm_result, url_helpers
from comet_llm.experiment_api import comet_api_client

from .. import messages
from . import constants


def send(message: messages.PromptMessage) -> llm_result.LLMResult:
    client = comet_api_client.get(message.experiment_info_.api_key)

    if client.backend_version >= constants.V2_BACKEND_VERSION:
        return _send_v2(message, client)

    return _send_v1(message)


def _send_v1(message: messages.PromptMessage) -> llm_result.LLMResult:
    experiment_api_ = experiment_api.ExperimentAPI.create_new(
        api_key=message.experiment_info_.api_key,
        workspace=message.experiment_info_.workspace,
        project_name=message.experiment_info_.project_name,
    )

    experiment_api_.log_asset_with_io(
        name="comet_llm_data.json",
        file=io.StringIO(json.dumps(message.prompt_asset_data)),
        asset_type="llm_data",
    )

    if message.tags is not None:
        experiment_api_.log_tags(message.tags)

    if message.duration is not None:
        experiment_api_.log_metric("chain_duration", message.duration)

    parameters = convert.chain_metadata_to_flat_parameters(message.metadata)

    for name, value in parameters.items():
        experiment_api_.log_parameter(name, value)

    return llm_result.LLMResult(
        id=experiment_api_.id, project_url=experiment_api_.project_url
    )


def _send_v2(
    message: messages.PromptMessage, client: comet_api_client.CometAPIClient
) -> llm_result.LLMResult:
    metrics = {"chain_duration": message.duration}
    parameters = convert.chain_metadata_to_flat_parameters(message.metadata)

    response = client.log_chain(
        experiment_key=message.id,
        chain_asset=message.prompt_asset_data,
        workspace=message.experiment_info_.workspace,
        project=message.experiment_info_.project_name,
        tags=message.tags,
        metrics=metrics,
        parameters=parameters,
    )
    project_url: str = url_helpers.experiment_to_project_url(response["link"])

    return llm_result.LLMResult(id=message.id, project_url=project_url)
