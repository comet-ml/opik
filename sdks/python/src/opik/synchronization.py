import time
import logging
from typing import Callable, Optional, Any


LOGGER = logging.getLogger(__name__)


def wait_for_done(
    check_function: Callable[[], bool],
    timeout: Optional[float],
    progress_callback: Optional[Callable] = None,
    sleep_time: float = 1,
) -> None:
    """Wait up to TIMEOUT seconds for the check function to return True"""
    end_time = time.time() + timeout if timeout else float("inf")
    while check_function() is False and time.time() < end_time:
        if progress_callback is not None:
            progress_callback()
        # Wait a max of sleep_time, but keep checking to see if
        # check_function is done. Allows wait_for_empty to end
        # before sleep_time has elapsed:
        end_sleep_time = time.time() + sleep_time
        while check_function() is False and time.time() < end_sleep_time:
            time.sleep(sleep_time / 20.0)


def until(
    function: Callable[[], bool],
    sleep: float = 0.5,
    max_try_seconds: float = 10,
    allow_errors: bool = False,
) -> bool:
    start_time = time.time()
    while True:
        try:
            if function():
                break
        except Exception:
            LOGGER.debug(
                f"{function.__name__} raised error in 'until' function.", exc_info=True
            )
            if not allow_errors:
                raise
        finally:
            if (time.time() - start_time) > max_try_seconds:
                return False
            time.sleep(sleep)
    return True


def try_get_until(
    function: Callable[[], Any], sleep: float = 0.5, max_try_seconds: float = 10
) -> Any:
    """
    As soon
    """
    start_time = time.time()
    while True:
        try:
            return function()
        except Exception:
            if (time.time() - start_time) > max_try_seconds:
                raise
            time.sleep(sleep)
    return True
