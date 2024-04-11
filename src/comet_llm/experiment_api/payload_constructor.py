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

from typing import Dict, List, Optional

from ..types import JSONEncodable


def chain_parameters_payload(
    parameters: Optional[Dict[str, JSONEncodable]]
) -> List[Dict[str, JSONEncodable]]:
    return _dict_to_payload_format(parameters, "parameterName", "parameterValue")


def chain_metrics_payload(
    metrics: Optional[Dict[str, JSONEncodable]]
) -> List[Dict[str, JSONEncodable]]:
    return _dict_to_payload_format(metrics, "metricName", "metricValue")


def chain_others_payload(
    others: Optional[Dict[str, JSONEncodable]]
) -> List[Dict[str, JSONEncodable]]:
    return _dict_to_payload_format(others, "key", "value")


def _dict_to_payload_format(
    source: Optional[Dict[str, JSONEncodable]], key_name: str, value_name: str
) -> List[Dict[str, JSONEncodable]]:
    if source is None:
        return []

    result = [{key_name: key, value_name: value} for key, value in source.items()]

    return result
