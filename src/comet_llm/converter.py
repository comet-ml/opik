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

from .types import JSONEncodable, Timestamp


def call_data_to_dict(
    prompt: JSONEncodable,
    output: JSONEncodable,
    metadata: Optional[Dict[str, Any]],
    prompt_template: Optional[JSONEncodable],
    prompt_variables: Optional[JSONEncodable],
    timestamp: Timestamp,
):
    pass
