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
from typing import Dict, Optional

from .. import convert, experiment_api, experiment_info
from ..types import JSONEncodable
from . import chain, state


def start_chain(
    inputs: Dict[str, JSONEncodable],
    api_key: Optional[str] = None,
    workspace: Optional[str] = None,
    project_name: Optional[str] = None,
    metadata: Optional[Dict[str, Dict[str, JSONEncodable]]] = None,
) -> None:

    MESSAGE = """
    CometLLM requires an API key. Please provide it as the
    api_key argument to comet_llm.start_chain or as an environment
    variable named COMET_API_KEY
    """

    experiment_info_ = experiment_info.get(
        api_key,
        workspace,
        project_name,
        api_key_not_found_message=MESSAGE,
    )
    global_chain = chain.Chain(
        inputs=inputs, metadata=metadata, experiment_info=experiment_info_
    )
    state.set_global_chain(global_chain)


def end_chain(
    outputs: Dict[str, JSONEncodable],
    metadata: Optional[Dict[str, JSONEncodable]] = None,
) -> None:
    global_chain = state.get_global_chain()
    global_chain.set_outputs(outputs=outputs, metadata=metadata)
    global_chain_data = global_chain.as_dict()

    experiment_info_ = global_chain.experiment_info
    experiment_api_ = experiment_api.ExperimentAPI(
        api_key=experiment_info_.api_key,
        workspace=experiment_info_.workspace,
        project_name=experiment_info_.project_name,
    )

    experiment_api_.log_asset_with_io(
        name="comet_llm_data.json",
        file=io.StringIO(json.dumps(global_chain_data)),
        asset_type="llm_data",
    )
    parameters = convert.chain_metadata_to_flat_parameters(
        global_chain_data["metadata"]
    )

    for name, value in parameters.items():
        experiment_api_.log_parameter(name, value)
