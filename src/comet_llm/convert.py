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

from typing import Any, Dict, Optional

from .types import JSONEncodable

CALL_DICT_VERSION = 1


def call_data_to_dict(
    prompt: JSONEncodable,
    outputs: JSONEncodable,
    id: int,
    metadata: Optional[Dict[str, Any]] = None,
    prompt_template: Optional[JSONEncodable] = None,
    prompt_template_variables: Optional[JSONEncodable] = None,
    start_timestamp: Optional[float] = None,
    end_timestamp: Optional[float] = None,
    duration: Optional[float] = None,
) -> Dict[str, Any]:

    result = {
        "_id": id,
        "_type": "llm_call",
        "inputs": {
            "final_prompt": prompt,
            "prompt_template": prompt_template,
            "prompt_template_variables": prompt_template_variables,
        },
        "outputs": outputs,
        "duration": duration,
        "start_timestamp": start_timestamp,
        "end_timestamp": end_timestamp,
        "context": [],
        "metadata": metadata,
    }

    return result
