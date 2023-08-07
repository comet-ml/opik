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


import functools
from typing import Any, Callable

import requests  # type: ignore

from .. import exceptions, config

def wrap(check_on_prem=False) -> Callable:
    def inner_wrap(func: Callable) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            try:
                return func(*args, **kwargs)
            except requests.RequestException as exception:
                args = []

                if check_on_prem and config.comet_url() != "https://www.comet.com/clientlib/":
                    args = ["Failed to send prompt to your Comet installation at https://comet.example.com/. Check that your Comet installation is up-to-date and check the traceback for more details."]

                raise exceptions.CometLLMException(*args) from exception

        return wrapper
    
    return inner_wrap
