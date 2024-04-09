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

import functools
import json
import logging
import urllib.parse
from pprint import pformat
from typing import Any, Callable, List, NoReturn

import requests  # type: ignore

from .. import config, exceptions, logging_messages
from . import error_codes_mapping

LOGGER = logging.getLogger(__name__)


def wrap(check_on_prem: bool = False) -> Callable:
    def inner_wrap(func: Callable) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            try:
                return func(*args, **kwargs)
            except requests.RequestException as exception:
                _debug_log(exception)

                if check_on_prem:
                    comet_url = config.comet_url()
                    if _is_on_prem(comet_url):
                        raise exceptions.CometLLMException(
                            f"Failed to send prompt to your Comet installation at "
                            f"{comet_url}. Check that your Comet "
                            f"installation is up-to-date and check the traceback for more details."
                        ) from exception

                if exception.response is None:
                    raise exceptions.CometLLMException(
                        logging_messages.FAILED_TO_SEND_DATA_TO_SERVER
                    ) from exception

                _handle_request_exception(exception)

        return wrapper

    return inner_wrap


def _is_on_prem(url: str) -> bool:
    parsed = urllib.parse.urlparse(url)
    root = f"{parsed.scheme}://{parsed.hostname}/"
    return root != "https://www.comet.com/"


def _debug_log(exception: requests.RequestException) -> None:
    try:
        if exception.request is not None:
            LOGGER.debug(f"Request:\n{pformat(vars(exception.request))}")

        if exception.response is not None:
            LOGGER.debug(f"Response:\n{pformat(vars(exception.response))}")
    except Exception:
        # Make sure we won't fail on attempt to debug.
        # It's mainly for tests when response object can be mocked
        pass


def _handle_request_exception(exception: requests.RequestException) -> NoReturn:
    response = exception.response
    sdk_error_code = json.loads(response.text)["sdk_error_code"]
    error_message = error_codes_mapping.MESSAGES[sdk_error_code]

    raise exceptions.CometLLMException(error_message) from exception
