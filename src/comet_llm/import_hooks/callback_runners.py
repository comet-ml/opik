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

import logging
from typing import Any, Callable, Dict, List, Tuple, Union

from . import validate
from .types import AfterCallback, AfterExceptionCallback, BeforeCallback

LOGGER = logging.getLogger(__name__)

Args = Union[Tuple[Any, ...], List[Any]]
ArgsKwargs = Tuple[Args, Dict[str, Any]]


def run_before(  # type: ignore
    callbacks: List[BeforeCallback], original: Callable, *args, **kwargs
) -> ArgsKwargs:
    for callback in callbacks:
        try:
            callback_return = callback(original, *args, **kwargs)

            if validate.args_kwargs(callback_return):
                LOGGER.debug("New args %r", callback_return)
                args, kwargs = callback_return
        except Exception:
            LOGGER.debug(
                "Exception calling before callback %r", callback, exc_info=True
            )

    return args, kwargs


def run_after(  # type: ignore
    callbacks: List[AfterCallback],
    original: Callable,
    return_value: Any,
    *args,
    **kwargs
) -> Any:
    for callback in callbacks:
        try:
            new_return_value = callback(original, return_value, *args, **kwargs)
            if new_return_value is not None:
                return_value = new_return_value
        except Exception:
            LOGGER.debug("Exception calling after callback %r", callback, exc_info=True)

    return return_value


def run_after_exception(  # type: ignore
    callbacks: List[AfterExceptionCallback],
    original: Callable,
    exception: Exception,
    *args,
    **kwargs
) -> None:
    for callback in callbacks:
        try:
            callback(original, exception, *args, **kwargs)
        except Exception:
            LOGGER.debug(
                "Exception calling after-exception callback %r", callback, exc_info=True
            )
