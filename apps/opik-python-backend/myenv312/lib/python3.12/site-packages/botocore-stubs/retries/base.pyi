"""
Type annotations for botocore.retries.base module.

Copyright 2025 Vlad Emelianov
"""

from botocore.retries.standard import RetryContext

class BaseRetryBackoff:
    def delay_amount(self, context: RetryContext) -> float: ...

class BaseRetryableChecker:
    def is_retryable(self, context: RetryContext) -> bool: ...
