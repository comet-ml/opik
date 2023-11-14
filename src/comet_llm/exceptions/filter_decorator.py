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
from typing import TYPE_CHECKING, Any, Callable, Optional

from comet_llm import logging as comet_logging

if TYPE_CHECKING:
    from comet_llm import summary

LOGGER = logging.getLogger(__name__)


def filter(
    allow_raising: bool, summary: Optional["summary.Summary"] = None
) -> Callable:
    def decorator(function: Callable) -> Callable:
        @functools.wraps(function)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            try:
                return function(*args, **kwargs)
            except Exception as exception:
                if summary is not None:
                    summary.increment_failed()

                if allow_raising:
                    raise

                if getattr(exception, "log_message_once", False):
                    comet_logging.log_once_at_level(
                        LOGGER,
                        logging.ERROR,
                        str(exception),
                        exc_info=True,
                        extra={"show_traceback": True},
                    )
                else:
                    LOGGER.error(
                        str(exception),
                        exc_info=True,
                        extra={"show_traceback": True},
                    )

        return wrapper

    return decorator
