import contextlib
import os
from typing import Any, Dict, List


@contextlib.contextmanager
def patch_environ(
    add_keys: Dict[str, Any],
    remove_keys: List[str] = None,
):
    """
    Temporarily set environment variables inside the context manager and
    fully restore the previous environment afterward
    """
    original_env = {key: os.getenv(key) for key in add_keys}

    for key in remove_keys or []:
        if key in os.environ:
            original_env[key] = os.getenv(key)
            del os.environ[key]

    os.environ.update(add_keys)

    try:
        yield
    finally:
        for key, value in original_env.items():
            if value is None:
                del os.environ[key]
            else:
                os.environ[key] = value
