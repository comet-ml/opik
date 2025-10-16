class NoOpDeathPenalty:
    """
    No-op death penalty to avoid using signals from a non-main thread.
    Used to disable RQ's UnixSignalDeathPenalty when running worker in a background thread.
    """

    def __init__(self, timeout, exception_class, **kwargs):
        # Accept arbitrary kwargs like job_id to match RQ's signature
        self.timeout = timeout
        self.exception_class = exception_class

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        # Do not suppress exceptions
        return False

    def setup_death_penalty(self):
        pass

    def cancel_death_penalty(self):
        pass


