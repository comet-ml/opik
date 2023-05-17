import functools
import requests

from typing import Any, Callable
from .. import exceptions

def wrap(func: Callable):
    @functools.wraps(func)
    def wrapper(*args, **kwargs) -> Any:  # type: ignore
        try:
            return func(*args, **kwargs)
        except requests.RequestException as exception:
            raise exceptions.CometLLMException() from exception

    return wrapper