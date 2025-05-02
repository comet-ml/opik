import threading
import time
import queue
from functools import wraps

class RateLimiter:
    """
    Rate limiter that enforces a maximum number of calls across all threads.
    """
    def __init__(self, max_calls_per_second):
        self.max_calls_per_second = max_calls_per_second
        self.interval = 1.0 / max_calls_per_second  # Time between allowed calls
        self.last_call_time = 0
        self.lock = threading.Lock()
        
    def acquire(self):
        """
        Wait until a call is allowed according to the global rate limit.
        Returns immediately if the call is allowed, otherwise blocks until it's time.
        """
        with self.lock:
            current_time = time.time()
            time_since_last = current_time - self.last_call_time
            
            # If we haven't waited long enough since the last call
            if time_since_last < self.interval:
                # Calculate how much longer we need to wait
                sleep_time = self.interval - time_since_last
                time.sleep(sleep_time)
                
            # Update the last call time (after potential sleep)
            self.last_call_time = time.time()

def rate_limited(limiter):
    """Decorator to rate limit a function using the provided limiter"""
    def decorator(func):
        @wraps(func)
        def wrapper(*args, **kwargs):
            limiter.acquire()
            return func(*args, **kwargs)
        return wrapper
    return decorator

