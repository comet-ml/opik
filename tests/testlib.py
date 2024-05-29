import contextlib
import logging
import os
import time
from typing import Callable, Dict


@contextlib.contextmanager
def environ(env: Dict[str, str]):
    """Temporarily set environment variables inside the context manager and
    fully restore previous environment afterwards
    """
    original_env = {key: os.getenv(key) for key in env}
    os.environ.update(env)
    try:
        yield

    finally:
        for key, value in original_env.items():
            if value is None:
                del os.environ[key]
            else:
                os.environ[key] = value


def until(function: Callable, sleep: float = 0.5, max_try_seconds: int = 20) -> bool:
    """
    Try assert function(). 20 seconds max
    """
    start_time = time.time()
    while not function():
        if (time.time() - start_time) > max_try_seconds:
            return False
        time.sleep(sleep)
    return True