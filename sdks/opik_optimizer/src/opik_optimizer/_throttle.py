from functools import wraps
from pyrate_limiter import Duration, Rate, Limiter
import time

class RateLimiter:
    """
    Rate limiter that enforces a maximum number of calls across all threads using pyrate_limiter.
    """
    def __init__(self, max_calls_per_second):
        self.max_calls_per_second = max_calls_per_second
        rate = Rate(max_calls_per_second, Duration.SECOND)
        # Use a single bucket for all calls, with a specific identifier
        self.limiter = Limiter(rate, raise_when_fail=False)
        self.bucket_key = "global_rate_limit"  # Shared bucket key for all calls
        
    def acquire(self):
        """
        Wait until a call is allowed according to the global rate limit.
        Returns immediately if the call is allowed, otherwise blocks until it's time.
        """
        # Block until we can acquire a token
        
        while not self.limiter.try_acquire(self.bucket_key):
            # Sleep a small amount before retrying
            # This is more efficient than busy-waiting
            time.sleep(0.01)

def rate_limited(limiter: RateLimiter):
    """Decorator to rate limit a function using the provided limiter"""
    def decorator(func):
        @wraps(func)
        def wrapper(*args, **kwargs):
            limiter.acquire()
            return func(*args, **kwargs)
        return wrapper
    return decorator

