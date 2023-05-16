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
from typing import Any, Callable, Type


class CometLLMException(Exception):
    pass


class CometLLMRestApiException(CometLLMException):
    pass


class CometAPIKeyIsMissing(CometLLMException):
    pass


def reraiser(to_raise: Type[Exception], to_catch: Type[Exception]) -> Callable:
    def outer_wrapper(func: Callable) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            try:
                return func(*args, **kwargs)
            except to_catch as exception:
                raise to_raise from exception

        return wrapper

    return outer_wrapper
