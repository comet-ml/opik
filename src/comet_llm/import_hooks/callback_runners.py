import logging
from typing import Any, Callable, Dict, List, Tuple, Union

from .types import AfterCallback, AfterExceptionCallback, BeforeCallback

LOGGER = logging.getLogger(__name__)

Args = Union[Tuple[Any, ...], List[Any]]
ArgsKwargs = Tuple[Args, Dict[str, Any]]


def run_before(
    callbacks: List[BeforeCallback], original: Callable, *args, **kwargs
) -> ArgsKwargs:
    for callback in callbacks:
        try:
            callback_return = callback(original, *args, **kwargs)

            if _valid_new_args_kwargs(callback_return):
                LOGGER.debug("New args %r", callback_return)
                args, kwargs = callback_return
        except Exception:
            LOGGER.debug(
                "Exception calling before callback %r", callback, exc_info=True
            )

    return args, kwargs


def run_after(
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


def run_after_exception(
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


def _valid_new_args_kwargs(callback_return: Any) -> bool:
    if callback_return is None:
        return False

    try:
        args, kwargs = callback_return
    except (ValueError, TypeError):
        return False

    if not isinstance(args, (list, tuple)):
        return False

    if not isinstance(kwargs, dict):
        return False

    return True
