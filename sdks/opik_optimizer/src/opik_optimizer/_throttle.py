import functools
import pyrate_limiter
import time
import opik.config

from typing import Callable, Any


class RateLimiter:
    """
    Rate limiter that enforces a maximum number of calls across all threads using pyrate_limiter.
    """
    def __init__(self, max_calls_per_second: int):
        self.max_calls_per_second = max_calls_per_second
        rate = pyrate_limiter.Rate(max_calls_per_second, pyrate_limiter.Duration.SECOND)

        self.limiter = pyrate_limiter.Limiter(rate, raise_when_fail=False)
        self.bucket_key = "global_rate_limit"
        
    def acquire(self) -> None:
        while not self.limiter.try_acquire(self.bucket_key):
            time.sleep(0.01)

def rate_limited(limiter: RateLimiter) -> Callable[[Callable], Callable]:
    """Decorator to rate limit a function using the provided limiter"""

    def decorator(func: Callable) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:
            limiter.acquire()
            return func(*args, **kwargs)
        return wrapper
    return decorator


def get_rate_limiter_for_current_opik_installation() -> RateLimiter:
    opik_config = opik.config.OpikConfig()
    max_calls_per_second = (
        10
        if opik_config.is_cloud_installation
        else 50
    )
    return RateLimiter(max_calls_per_second=max_calls_per_second)