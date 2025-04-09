"""
Type annotations for botocore.retries.special module.

Copyright 2025 Vlad Emelianov
"""

from logging import Logger
from typing import Any

from botocore.retries.base import BaseRetryableChecker as BaseRetryableChecker
from botocore.retries.standard import RetryContext

logger: Logger = ...

class RetryIDPCommunicationError(BaseRetryableChecker):
    def is_retryable(self, context: RetryContext) -> Any: ...

class RetryDDBChecksumError(BaseRetryableChecker):
    def is_retryable(self, context: RetryContext) -> Any: ...
