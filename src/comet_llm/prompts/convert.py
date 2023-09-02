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

from typing import Any, Dict, Optional

from ..types import JSONEncodable


def call_data_to_dict(
    prompt: JSONEncodable,
    outputs: JSONEncodable,
    metadata: Optional[Dict[str, Any]] = None,
    prompt_template: Optional[JSONEncodable] = None,
    prompt_template_variables: Optional[JSONEncodable] = None,
    start_timestamp: Optional[float] = None,
    end_timestamp: Optional[float] = None,
    duration: Optional[float] = None,
) -> Dict[str, Any]:
    return {
        "id": 1,
        "category": "llm-call",
        "name": "llm-call-1",
        "inputs": {
            "final_prompt": prompt,
            "prompt_template": prompt_template,
            "prompt_template_variables": prompt_template_variables,
        },
        "outputs": {"output": outputs},
        "duration": duration,
        "start_timestamp": start_timestamp,
        "end_timestamp": end_timestamp,
        "parent_ids": [],
        "metadata": metadata,
    }
