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
from typing import Dict, List, Optional

from .. import (
    app,
    config,
    convert,
    exceptions,
    experiment_api,
    experiment_info,
    llm_result,
)
from ..types import JSONEncodable
from . import chain, state


@exceptions.filter(allow_raising=config.raising_enabled(), summary=app.SUMMARY)
def start_chain(
    inputs: Dict[str, JSONEncodable],
    api_key: Optional[str] = None,
    workspace: Optional[str] = None,
    project: Optional[str] = None,
    metadata: Optional[Dict[str, Dict[str, JSONEncodable]]] = None,
    tags: Optional[List[str]] = None,
) -> None:
    """
    Creates global Chain object that tracks created Spans.
    Args:
        inputs: Dict[str, JSONEncodable] (required) chain inputs.
        workspace: str (optional) comet workspace to use for logging.
        project: str (optional) project name to create in comet workspace.
        tags: List[str] (optional), user-defined tags attached to a prompt call.
        api_key: str (optional) comet API key.
        metadata: Dict[str, Dict[str, JSONEncodable]] (optional) user-defined
            dictionary with additional metadata to the call.
        tags: List[str] (optional) user-defined tags attached to the chain
    """

    MESSAGE = """
    CometLLM requires an API key. Please provide it as the
    api_key argument to comet_llm.start_chain or as an environment
    variable named COMET_API_KEY
    """

    experiment_info_ = experiment_info.get(
        api_key,
        workspace,
        project,
        api_key_not_found_message=MESSAGE,
    )
    global_chain = chain.Chain(
        inputs=inputs,
        metadata=metadata,
        experiment_info=experiment_info_,
        tags=tags,
    )
    state.set_global_chain(global_chain)


def end_chain(
    outputs: Dict[str, JSONEncodable],
    metadata: Optional[Dict[str, JSONEncodable]] = None,
) -> llm_result.LLMResult:
    """
    Commits global chain and logs the result to Comet.
    Args:
        outputs: Dict[str, JSONEncodable] (required) chain outputs.
        metadata: Dict[str, Dict[str, JSONEncodable]] (optional) user-defined
            dictionary with additional metadata to the call. This metadata
            will be deep merged with the metadata passed to start_chain if
            it was provided.
        tags: List[str] (optional) user-defined tags attached to the chain

    Returns: LLMResult
    """
    global_chain = state.get_global_chain()
    global_chain.set_outputs(outputs=outputs, metadata=metadata)
    return log_chain(global_chain)


def log_chain(chain: chain.Chain) -> llm_result.LLMResult:
    chain_data = chain.as_dict()

    experiment_info_ = chain.experiment_info
    experiment_api_ = experiment_api.ExperimentAPI.create_new(
        api_key=experiment_info_.api_key,
        workspace=experiment_info_.workspace,
        project_name=experiment_info_.project_name,
    )

    if chain.tags is not None:
        experiment_api_.log_tags(chain.tags)

    experiment_api_.log_asset_with_io(
        name="comet_llm_data.json",
        file=io.StringIO(json.dumps(chain_data)),
        asset_type="llm_data",
    )

    experiment_api_.log_metric(
        name="chain_duration", value=chain_data["chain_duration"]
    )

    parameters = convert.chain_metadata_to_flat_parameters(chain_data["metadata"])
    for name, value in parameters.items():
        experiment_api_.log_parameter(name, value)

    for name, value in chain.others.items():
        experiment_api_.log_other(name, value)

    app.SUMMARY.add_log(experiment_api_.project_url, "chain")

    return llm_result.LLMResult(
        id=experiment_api_.id, project_url=experiment_api_.project_url
    )
