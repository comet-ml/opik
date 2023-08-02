import functools
from typing import Callable

from . import callable_extenders, callback_runners


def wrap(original: Callable, callbacks: callable_extenders.CallableExtenders):
    def wrapped(*args, **kwargs):
        args, kwargs = callback_runners.run_before(callbacks.before, original)
        try:
            result = original(*args, **kwargs)
        except Exception as exception:
            callback_runners.run_after_exception(
                callbacks.after_exception, original, exception, *args, **kwargs
            )
            raise exception

        callback_runners.run_after(callbacks.after, original, result, *args, **kwargs)

    # Simulate functools.wraps behavior but make it working with mocks
    for attr in functools.WRAPPER_ASSIGNMENTS:
        if hasattr(original, attr):
            setattr(wrapped, attr, getattr(original, attr))

    return wrapped
