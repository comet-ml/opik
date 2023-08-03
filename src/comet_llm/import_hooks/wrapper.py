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
from typing import Callable

from . import callable_extenders, callback_runners


def wrap(
    original: Callable, callbacks: callable_extenders.CallableExtenders
) -> Callable:
    def wrapped(*args, **kwargs):  # type: ignore
        args, kwargs = callback_runners.run_before(
            callbacks.before, original, *args, **kwargs
        )
        try:
            result = original(*args, **kwargs)
        except Exception as exception:
            callback_runners.run_after_exception(
                callbacks.after_exception, original, exception, *args, **kwargs
            )
            raise exception

        result = callback_runners.run_after(
            callbacks.after, original, result, *args, **kwargs
        )

        return result

    # Simulate functools.wraps behavior but make it working with mocks
    for attr in functools.WRAPPER_ASSIGNMENTS:
        if hasattr(original, attr):
            setattr(wrapped, attr, getattr(original, attr))

    return wrapped
