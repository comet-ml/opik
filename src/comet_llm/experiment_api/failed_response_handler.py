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

import collections
import json
from typing import NoReturn

import requests  # type: ignore

from .. import backend_error_codes, exceptions, logging_messages

_SDK_ERROR_CODES_LOGGING_MESSAGE = collections.defaultdict(
    lambda: logging_messages.FAILED_TO_SEND_DATA_TO_SERVER,
    {
        backend_error_codes.UNABLE_TO_LOG_TO_NON_LLM_PROJECT: logging_messages.UNABLE_TO_LOG_TO_NON_LLM_PROJECT
    },
)


def handle(exception: requests.RequestException) -> NoReturn:
    response = exception.response
    sdk_error_code = json.loads(response.text)["sdk_error_code"]
    error_message = _SDK_ERROR_CODES_LOGGING_MESSAGE[sdk_error_code]

    raise exceptions.CometLLMException(error_message) from exception
