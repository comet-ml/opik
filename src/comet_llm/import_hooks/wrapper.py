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
import inspect
from typing import Callable

from . import callable_extenders, callback_runners


def wrap(
    original: Callable, callbacks: callable_extenders.CallableExtenders
) -> Callable:
    original = _unbound_if_classmethod(original)

    @functools.wraps(original)
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

    return wrapped


def _unbound_if_classmethod(original: Callable) -> Callable:
    if hasattr(original, "__self__") and inspect.isclass(original.__self__):
        # when original is classmethod, mypy doesn't consider it as a callable.
        original = original.__func__  # type: ignore

    return original
