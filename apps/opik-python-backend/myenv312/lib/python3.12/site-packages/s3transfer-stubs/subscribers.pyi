"""
Type annotations for s3transfer.subscribers module.

Copyright 2025 Vlad Emelianov
"""

from typing import Any, TypeVar

from s3transfer.compat import accepts_kwargs as accepts_kwargs
from s3transfer.exceptions import InvalidSubscriberMethodError as InvalidSubscriberMethodError
from s3transfer.futures import TransferFuture

_R = TypeVar("_R", bound=BaseSubscriber)

class BaseSubscriber:
    VALID_SUBSCRIBER_TYPES: list[str]
    def __new__(cls: type[_R], *args: Any, **kwargs: Any) -> _R: ...
    def on_queued(self, future: TransferFuture, **kwargs: Any) -> None: ...
    def on_progress(
        self, future: TransferFuture, bytes_transferred: int, **kwargs: Any
    ) -> None: ...
    def on_done(self, future: TransferFuture, **kwargs: Any) -> None: ...
