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
import logging
import sys
from typing import Any, Callable

from . import config

_LOG_ONCE_CACHE = set()


def setup() -> None:
    root = logging.getLogger("comet_llm")
    root.setLevel(config.logging_level())

    console_handler = logging.StreamHandler(sys.stdout)
    root.addHandler(console_handler)


def log_once_at_level(  # type: ignore
    logger: logging.Logger, logging_level: int, message: str, *args, **kwargs
) -> None:
    """
    Log the given message once at the given level then at the DEBUG
    level on further calls.

    This is a global log-once-per-session, as opposed to the
    log-once-per-experiment.
    """
    global _LOG_ONCE_CACHE

    if message not in _LOG_ONCE_CACHE:
        _LOG_ONCE_CACHE.add(message)
        logger.log(logging_level, message, *args, **kwargs)
    else:
        logger.debug(message, *args, **kwargs)


def log_message_on_error(  # type: ignore
    logger: logging.Logger,
    logging_level: int,
    message: str,
    *log_args,
    log_once=False,
    **log_kwargs
) -> Callable:
    def decorator(function: Callable) -> Callable:
        @functools.wraps(function)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            try:
                return function(*args, **kwargs)
            except Exception:
                if log_once:
                    log_once_at_level(
                        logger, logging_level, message, *log_args, **log_kwargs
                    )
                else:
                    logger.log(logging_level, message, *log_args, **log_kwargs)

                raise

        return wrapper

    return decorator
