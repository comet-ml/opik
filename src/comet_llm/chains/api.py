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
    logging_messages,
)
from ..message_processing import api as message_processing_api, messages
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
        inputs: Chain inputs.
        workspace: Comet workspace to use for logging.
        project: Project name to create in comet workspace.
        tags: User-defined tags attached to a prompt call.
        api_key: Comet API key.
        metadata: User-defined
            dictionary with additional metadata to the call.
        tags: User-defined tags attached to the chain
    """

    MESSAGE = (
        None
        if config.offline_enabled()
        else (logging_messages.API_KEY_NOT_FOUND_MESSAGE % "comet_llm.start_chain")
    )

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


@exceptions.filter(allow_raising=config.raising_enabled(), summary=app.SUMMARY)
def end_chain(
    outputs: Dict[str, JSONEncodable],
    metadata: Optional[Dict[str, JSONEncodable]] = None,
) -> Optional[llm_result.LLMResult]:
    """
    Commits global chain and logs the result to Comet.

    Args:
        outputs: Chain outputs.
        metadata: User-defined
            dictionary with additional metadata to the call. This metadata
            will be deep merged with the metadata passed to start_chain if
            it was provided.
        tags:User-defined tags attached to the chain

    Returns: LLMResult
    """
    global_chain = state.get_global_chain()
    if global_chain is None:
        raise exceptions.CometLLMException(
            logging_messages.GLOBAL_CHAIN_NOT_INITIALIZED % "`end_chain`"
        )

    global_chain.set_outputs(outputs=outputs, metadata=metadata)
    return log_chain(global_chain)


def log_chain(chain: chain.Chain) -> Optional[llm_result.LLMResult]:
    chain_data = chain.as_dict()

    message = messages.ChainMessage(
        id=messages.generate_id(),
        experiment_info_=chain.experiment_info,
        tags=chain.tags,
        chain_data=chain_data,
        duration=chain_data["chain_duration"],
        metadata=chain_data["metadata"],
        others=chain.others,
    )

    result = message_processing_api.MESSAGE_PROCESSOR.process(message)

    if result is not None:
        app.SUMMARY.add_log(result.project_url, "chain")

    return result
